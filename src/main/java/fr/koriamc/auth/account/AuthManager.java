package fr.koriamc.auth.account;

import fr.koriamc.auth.KoriaAuth;
import fr.koriamc.auth.channel.AuthChannel;
import fr.koriamc.auth.config.Messages;
import fr.koriamc.auth.config.Settings;
import fr.koriamc.auth.mail.MailService;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Connexion des joueurs, côté proxy. Tant qu'un joueur n'est pas connecté, il reste sur le serveur d'authentification
 * (lobby), ses commandes et messages sont bloqués, et le lobby (KoriaAuthLobby) lui affiche l'écran d'inscription
 * (première venue : mot de passe deux fois) ou de connexion. Sans le client KoriaMC : /register et /login dans le chat.
 *
 * Les évènements du proxy arrivent sur plusieurs threads : l'état de chaque joueur est protégé par son objet Pending.
 * Le hachage des mots de passe se fait en tâche de fond.
 */
public final class AuthManager {

    /** Joueur pas encore connecté. */
    private static final class Pending {
        final long joinedAt = System.currentTimeMillis();
        /** Fin du temps laissé pour se connecter (prolongé quand un code est envoyé par mail). */
        long deadline;
        int attempts;
        /** Écran affiché : login, register, reset_code ou reset_password (vide = selon le compte). */
        String screen = "";
        // Mot de passe oublié
        String resetCode;
        long resetExpires;
        int resetTries;
        boolean codeOk;
        long lastMail;
        String maskedEmail = "";
        /** Un mot de passe est en cours de vérification. */
        boolean busy;
        /** Le lobby a répondu (READY). */
        boolean ready;
        /** Le joueur a le client KoriaMC : écrans plutôt que chat. */
        boolean client;
        long lastReminder;
    }

    private static final class Session {
        final String ip;
        final long quitAt;

        Session(String ip, long quitAt) {
            this.ip = ip;
            this.quitAt = quitAt;
        }
    }

    private final KoriaAuth plugin;
    private final Set<UUID> logged = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private ScheduledTask task;

    public AuthManager(KoriaAuth plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Messages msg() {
        return plugin.msg();
    }

    private AccountStore accounts() {
        return plugin.accounts();
    }

    public boolean isLogged(ProxiedPlayer player) {
        return logged.contains(player.getUniqueId());
    }

    public boolean isPending(ProxiedPlayer player) {
        return pending.containsKey(player.getUniqueId());
    }

    public boolean isRegistered(ProxiedPlayer player) {
        return accounts().exists(player.getName());
    }

    public static String ip(ProxiedPlayer player) {
        SocketAddress address = player.getSocketAddress();
        if (address instanceof InetSocketAddress && ((InetSocketAddress) address).getAddress() != null) {
            return ((InetSocketAddress) address).getAddress().getHostAddress();
        }
        return "";
    }

    // ============================================================ Arrivée / départ

    /** Avant l'arrivée : un pseudo inscrit avec une autre casse est refusé. @return message de refus, ou null */
    public String checkName(String name) {
        Account account = accounts().get(name);
        if (account != null && !account.getName().equals(name)) {
            return msg().get("kick.name-case", "pseudo", account.getName());
        }
        return null;
    }

    public void onJoin(ProxiedPlayer player) {
        UUID id = player.getUniqueId();
        logged.remove(id);
        Session session = sessions.remove(AccountStore.key(player.getName()));
        int minutes = settings().sessionMinutes;
        if (session != null && minutes > 0 && isRegistered(player) && session.ip.equals(ip(player))
                && System.currentTimeMillis() - session.quitAt <= minutes * 60000L) {
            logged.add(id);
            msg().send(player, "login.session");
            return;
        }
        Pending state = new Pending();
        state.deadline = state.joinedAt + settings().timeoutSeconds * 1000L;
        pending.put(id, state);
    }

    public void onQuit(ProxiedPlayer player) {
        UUID id = player.getUniqueId();
        if (logged.remove(id) && settings().sessionMinutes > 0) {
            sessions.put(AccountStore.key(player.getName()), new Session(ip(player), System.currentTimeMillis()));
        }
        pending.remove(id);
    }

    /** Le lobby signale que le joueur est arrivé (avec ou sans client KoriaMC). */
    public void onReady(ProxiedPlayer player, boolean client) {
        Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            // déjà connecté (session, retour au lobby) : le lobby le libère
            send(player, AuthChannel.DONE, player.getUniqueId().toString());
            return;
        }
        synchronized (state) {
            state.ready = true;
            state.client = client;
        }
        if (client) {
            show(player, state, "");
        } else {
            remind(player, state);
        }
    }

    /** Délais dépassés et rappels dans le chat (toutes les secondes). */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(entry.getKey());
            Pending state = entry.getValue();
            if (player == null) {
                pending.remove(entry.getKey());
                continue;
            }
            boolean kick;
            boolean chat;
            synchronized (state) {
                kick = now > state.deadline && !state.busy;
                chat = !state.client && (state.ready || now - state.joinedAt > settings().lobbyWaitSeconds * 1000L)
                        && now - state.lastReminder >= settings().chatReminderSeconds * 1000L;
            }
            if (kick) {
                pending.remove(entry.getKey());
                player.disconnect(Messages.components(msg().get("kick.timeout")));
            } else if (chat) {
                remind(player, state);
            }
        }
    }

    private void remind(ProxiedPlayer player, Pending state) {
        synchronized (state) {
            state.lastReminder = System.currentTimeMillis();
        }
        msg().send(player, isRegistered(player) ? "login.chat-reminder" : "register.chat-reminder",
                "login", Settings.first(settings().loginCommands), "register", Settings.first(settings().registerCommands));
    }

    // ============================================================ Lobby

    /** Demande au lobby d'afficher l'écran (connexion, inscription, code, nouveau mot de passe) avec une erreur éventuelle. */
    private void show(ProxiedPlayer player, Pending state, String error) {
        int attemptsLeft;
        long deadline;
        String screen;
        String info;
        synchronized (state) {
            boolean reset = state.screen.startsWith("reset");
            attemptsLeft = Math.max(0, reset ? settings().codeAttempts - state.resetTries : settings().maxAttempts - state.attempts);
            deadline = state.deadline;
            screen = state.screen.isEmpty() ? (isRegistered(player) ? "login" : "register") : state.screen;
            info = state.maskedEmail;
        }
        int secondsLeft = (int) Math.max(0, (deadline - System.currentTimeMillis()) / 1000L);
        send(player, AuthChannel.SHOW, player.getUniqueId().toString(), screen, error == null ? "" : error, attemptsLeft,
                secondsLeft, settings().minLength, info);
    }

    private void send(ProxiedPlayer player, Object... values) {
        Server server = player.getServer();
        if (server != null) {
            server.sendData(AuthChannel.NAME, AuthChannel.encode(values));
        }
    }

    /** Erreur : dans l'écran (client KoriaMC) ou dans le chat. */
    private void fail(ProxiedPlayer player, String key, Object... kv) {
        Pending state = pending.get(player.getUniqueId());
        boolean screen;
        if (state == null) {
            screen = false;
        } else {
            synchronized (state) {
                screen = state.client;
            }
        }
        if (screen) {
            show(player, state, msg().plain(key, kv));
        } else {
            msg().send(player, key, kv);
        }
    }

    // ============================================================ Inscription / connexion

    /** @return clé du message de refus, ou null si le mot de passe convient */
    private String validate(ProxiedPlayer player, String password) {
        if (password.isEmpty()) {
            return "password.empty";
        }
        if (password.contains(" ")) {
            return "password.spaces";
        }
        if (password.length() < settings().minLength) {
            return "password.too-short";
        }
        if (password.length() > settings().maxLength) {
            return "password.too-long";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.equals(player.getName().toLowerCase(Locale.ROOT)) || settings().forbidden.contains(lower)) {
            return "password.forbidden";
        }
        return null;
    }

    /** Marque le joueur occupé. @return l'état, ou null si rien à faire (déjà connecté ou vérification en cours) */
    private Pending begin(ProxiedPlayer player) {
        Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            msg().send(player, "general.already-logged");
            return null;
        }
        synchronized (state) {
            if (state.busy) {
                return null;
            }
            state.busy = true;
        }
        return state;
    }

    private static void end(Pending state) {
        synchronized (state) {
            state.busy = false;
        }
    }

    public void register(final ProxiedPlayer player, final String password, String confirmation) {
        final Pending state = begin(player);
        if (state == null) {
            return;
        }
        if (isRegistered(player)) {
            end(state);
            fail(player, "register.already");
            return;
        }
        String problem = validate(player, password);
        if (problem != null) {
            end(state);
            fail(player, problem, "min", settings().minLength, "max", settings().maxLength);
            return;
        }
        if (!password.equals(confirmation)) {
            end(state);
            fail(player, "register.mismatch");
            return;
        }
        final String ip = ip(player);
        if (settings().maxAccountsPerIp > 0 && accounts().countForIp(ip) >= settings().maxAccountsPerIp) {
            end(state);
            fail(player, "register.ip-limit", "max", settings().maxAccountsPerIp);
            return;
        }
        final int iterations = settings().hashIterations;
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                String hash = PasswordHasher.hash(password, iterations);
                end(state);
                if (!player.isConnected() || pending.get(player.getUniqueId()) != state) {
                    return;
                }
                synchronized (AuthManager.this) {
                    if (isRegistered(player)) {
                        fail(player, "register.already");
                        return;
                    }
                    accounts().put(new Account(player.getName(), hash, ip));
                }
                plugin.getLogger().info(player.getName() + " s'est inscrit (" + ip + ").");
                complete(player, "register.done");
            }
        });
    }

    public void login(final ProxiedPlayer player, final String password) {
        final Pending state = begin(player);
        if (state == null) {
            return;
        }
        final Account account = accounts().get(player.getName());
        if (account == null) {
            end(state);
            fail(player, "login.not-registered", "register", Settings.first(settings().registerCommands));
            return;
        }
        if (password.isEmpty()) {
            end(state);
            fail(player, "password.empty");
            return;
        }
        final String hash = account.getHash();
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                boolean ok = PasswordHasher.verify(password, hash);
                if (!player.isConnected() || pending.get(player.getUniqueId()) != state) {
                    end(state);
                    return;
                }
                if (ok) {
                    end(state);
                    account.touch(ip(player));
                    accounts().save();
                    complete(player, "login.done");
                    return;
                }
                int left;
                synchronized (state) {
                    state.attempts++;
                    state.busy = false;
                    left = settings().maxAttempts - state.attempts;
                }
                plugin.getLogger().info(player.getName() + " : mot de passe incorrect (" + ip(player) + ", essais restants : "
                        + Math.max(0, left) + ").");
                if (left <= 0) {
                    pending.remove(player.getUniqueId());
                    player.disconnect(Messages.components(msg().get("kick.too-many-attempts")));
                    return;
                }
                fail(player, "login.wrong", "essais", left);
            }
        });
    }

    /** Connexion réussie : le lobby libère le joueur, qui part éventuellement vers le serveur de jeu. */
    private void complete(ProxiedPlayer player, String key) {
        pending.remove(player.getUniqueId());
        logged.add(player.getUniqueId());
        send(player, AuthChannel.DONE, player.getUniqueId().toString());
        msg().send(player, key, "joueur", player.getName());
        String title = msg().text(key + "-title", "joueur", player.getName());
        if (!title.isEmpty()) {
            String[] parts = title.split(";;", 2);
            Title t = ProxyServer.getInstance().createTitle().title(Messages.components(parts[0]))
                    .subTitle(Messages.components(parts.length > 1 ? parts[1] : ""))
                    .fadeIn(settings().titleFadeIn).stay(settings().titleStay).fadeOut(settings().titleFadeOut);
            t.send(player);
        }
        String target = settings().afterLoginServer;
        if (!target.isEmpty()) {
            ServerInfo info = plugin.getProxy().getServerInfo(target);
            if (info == null) {
                plugin.getLogger().warning("config.json : serveurs.apres-connexion \"" + target + "\" n'existe pas dans le proxy.");
            } else if (player.getServer() == null || !player.getServer().getInfo().getName().equals(info.getName())) {
                player.connect(info);
            }
        }
    }

    /** /changepassword : le joueur connecté change son mot de passe. */
    public void changePassword(final ProxiedPlayer player, final String current, final String next) {
        final Account account = accounts().get(player.getName());
        if (account == null || !isLogged(player)) {
            msg().send(player, "change.not-logged");
            return;
        }
        if (current.isEmpty() || next.isEmpty()) {
            msg().send(player, "change.usage", "commande", Settings.first(settings().changeCommands));
            return;
        }
        String problem = validate(player, next);
        if (problem != null) {
            msg().send(player, problem, "min", settings().minLength, "max", settings().maxLength);
            return;
        }
        final String hash = account.getHash();
        final int iterations = settings().hashIterations;
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                if (!PasswordHasher.verify(current, hash)) {
                    msg().send(player, "change.wrong");
                    return;
                }
                account.setHash(PasswordHasher.hash(next, iterations));
                accounts().save();
                msg().send(player, "change.done");
            }
        });
    }

    // ============================================================ Mot de passe oublié

    /** "Mot de passe oublié ?" : un code est envoyé à l'e-mail du compte du site (base Azuriom). */
    public void forgot(final ProxiedPlayer player) {
        final Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            msg().send(player, "general.already-logged");
            return;
        }
        if (!isRegistered(player)) {
            fail(player, "login.not-registered", "register", Settings.first(settings().registerCommands));
            return;
        }
        if (!plugin.mail().isReady()) {
            fail(player, "forgot.disabled");
            return;
        }
        long wait;
        synchronized (state) {
            if (state.busy) {
                return;
            }
            wait = state.lastMail + settings().mailCooldownSeconds * 1000L - System.currentTimeMillis();
            if (wait <= 0) {
                state.busy = true;
            }
        }
        if (wait > 0) {
            fail(player, "forgot.cooldown", "secondes", (wait + 999) / 1000);
            return;
        }
        final String name = player.getName();
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                String email;
                try {
                    email = plugin.mail().findEmail(name);
                } catch (Exception e) {
                    end(state);
                    plugin.getLogger().warning("Mot de passe oublié de " + name + " : base Azuriom inaccessible (" + e.getMessage() + ").");
                    fail(player, "forgot.error");
                    return;
                }
                if (email == null) {
                    end(state);
                    fail(player, "forgot.no-email");
                    return;
                }
                String code = plugin.mail().newCode();
                try {
                    plugin.mail().sendCode(email, name, code, settings().codeMinutes);
                } catch (Exception e) {
                    end(state);
                    plugin.getLogger().warning("Mot de passe oublié de " + name + " : mail non envoyé (" + e.getMessage() + ").");
                    fail(player, "forgot.error");
                    return;
                }
                long now = System.currentTimeMillis();
                synchronized (state) {
                    state.busy = false;
                    state.resetCode = code;
                    state.resetExpires = now + settings().codeMinutes * 60000L;
                    state.resetTries = 0;
                    state.codeOk = false;
                    state.lastMail = now;
                    state.maskedEmail = MailService.mask(email);
                    state.screen = "reset_code";
                    state.deadline = Math.max(state.deadline, now + settings().forgotDelayMinutes * 60000L);
                }
                plugin.getLogger().info("Code de réinitialisation envoyé à " + name + ".");
                if (isScreen(state)) {
                    show(player, state, "");
                } else {
                    msg().send(player, "forgot.sent-chat", "email", MailService.mask(email), "minutes", settings().codeMinutes,
                            "commande", Settings.first(settings().forgotCommands));
                }
            }
        });
    }

    private static boolean isScreen(Pending state) {
        synchronized (state) {
            return state.client;
        }
    }

    /** Code reçu par mail. */
    public void checkCode(ProxiedPlayer player, String code) {
        Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            msg().send(player, "general.already-logged");
            return;
        }
        String result;
        int left = 0;
        synchronized (state) {
            if (state.resetCode == null) {
                result = "forgot.no-code";
            } else if (System.currentTimeMillis() > state.resetExpires) {
                clearReset(state);
                result = "forgot.expired";
            } else if (code.trim().equals(state.resetCode)) {
                state.codeOk = true;
                state.screen = "reset_password";
                result = null;
            } else {
                state.resetTries++;
                left = settings().codeAttempts - state.resetTries;
                if (left <= 0) {
                    clearReset(state);
                    result = "forgot.too-many";
                } else {
                    result = "forgot.wrong-code";
                }
            }
        }
        if (result == null) {
            if (isScreen(state)) {
                show(player, state, "");
            } else {
                msg().send(player, "forgot.code-ok-chat", "commande", Settings.first(settings().forgotCommands));
            }
        } else {
            fail(player, result, "essais", left, "commande", Settings.first(settings().forgotCommands));
        }
    }

    /** Nouveau mot de passe, une fois le bon code donné. */
    public void resetPassword(final ProxiedPlayer player, final String password, String confirmation) {
        final Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            msg().send(player, "general.already-logged");
            return;
        }
        synchronized (state) {
            if (!state.codeOk || System.currentTimeMillis() > state.resetExpires) {
                clearReset(state);
                state.codeOk = false;
            }
        }
        boolean allowed;
        synchronized (state) {
            allowed = state.codeOk;
        }
        if (!allowed) {
            fail(player, "forgot.no-code");
            return;
        }
        String problem = validate(player, password);
        if (problem != null) {
            fail(player, problem, "min", settings().minLength, "max", settings().maxLength);
            return;
        }
        if (!password.equals(confirmation)) {
            fail(player, "register.mismatch");
            return;
        }
        final Account account = accounts().get(player.getName());
        if (account == null) {
            fail(player, "login.not-registered", "register", Settings.first(settings().registerCommands));
            return;
        }
        if (begin(player) == null) {
            return;
        }
        final int iterations = settings().hashIterations;
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                String hash = PasswordHasher.hash(password, iterations);
                end(state);
                if (!player.isConnected() || pending.get(player.getUniqueId()) != state) {
                    return;
                }
                account.setHash(hash);
                account.touch(ip(player));
                accounts().save();
                plugin.getLogger().info(player.getName() + " a changé son mot de passe avec un code reçu par mail (" + ip(player) + ").");
                complete(player, "forgot.done");
            }
        });
    }

    /** Retour à l'écran de connexion (abandon du mot de passe oublié). */
    public void back(ProxiedPlayer player) {
        Pending state = pending.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            clearReset(state);
        }
        show(player, state, "");
    }

    /** À appeler sous le verrou de state. */
    private static void clearReset(Pending state) {
        state.resetCode = null;
        state.codeOk = false;
        state.resetTries = 0;
        state.screen = "";
        state.maskedEmail = "";
    }

    // ============================================================ Administration

    /** Supprime un compte ; le joueur en ligne est déconnecté (il se réinscrira). */
    public boolean unregister(String name) {
        boolean removed = accounts().remove(name);
        sessions.remove(AccountStore.key(name));
        ProxiedPlayer online = plugin.getProxy().getPlayer(name);
        if (removed && online != null) {
            online.disconnect(Messages.components(msg().get("kick.unregistered")));
        }
        return removed;
    }

    /** Connecte un joueur sans mot de passe. */
    public boolean forceLogin(ProxiedPlayer player) {
        if (!pending.containsKey(player.getUniqueId())) {
            return false;
        }
        complete(player, "login.forced");
        return true;
    }

    /** Rechargement : les joueurs en attente revoient leur écran (textes à jour). */
    public void refreshPending() {
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(entry.getKey());
            Pending state = entry.getValue();
            boolean client;
            synchronized (state) {
                client = state.client;
            }
            if (player != null && client) {
                show(player, state, "");
            }
        }
    }
}
