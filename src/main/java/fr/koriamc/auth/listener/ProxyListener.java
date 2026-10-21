package fr.koriamc.auth.listener;

import fr.koriamc.auth.KoriaAuth;
import fr.koriamc.auth.account.AuthManager;
import fr.koriamc.auth.channel.AuthChannel;
import fr.koriamc.auth.config.Messages;
import fr.koriamc.auth.config.Settings;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Évènements du proxy : arrivée et départ, chat et commandes (/login, /register et /changepassword sont traitées
 * ici et jamais transmises aux serveurs ni écrites dans les logs), changements de serveur, messages du lobby.
 */
public final class ProxyListener implements Listener {

    private final KoriaAuth plugin;

    public ProxyListener(KoriaAuth plugin) {
        this.plugin = plugin;
    }

    private AuthManager auth() {
        return plugin.auth();
    }

    // ============================================================ Arrivée / départ

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(LoginEvent event) {
        if (event.isCancelled()) {
            return;
        }
        String name = event.getConnection().getName();
        if (name == null) {
            return;
        }
        ProxiedPlayer online = plugin.getProxy().getPlayer(name);
        if (online != null) {
            // un deuxième "Steve" ne peut pas éjecter le Steve déjà connecté
            event.setCancelled(true);
            event.setCancelReason(Messages.components(plugin.msg().get("kick.already-online")));
            return;
        }
        String refusal = auth().checkName(name);
        if (refusal != null) {
            event.setCancelled(true);
            event.setCancelReason(Messages.components(refusal));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPostLogin(PostLoginEvent event) {
        auth().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        auth().onQuit(event.getPlayer());
    }

    // ============================================================ Serveurs

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerConnect(ServerConnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!auth().isPending(player) || event.isCancelled()) {
            return;
        }
        ServerInfo lobby = plugin.getProxy().getServerInfo(plugin.settings().authServer);
        if (lobby == null) {
            plugin.getLogger().severe("config.json : serveurs.auth \"" + plugin.settings().authServer
                    + "\" n'existe pas dans le proxy : les joueurs non connectés ne peuvent aller nulle part.");
            event.setCancelled(true);
            player.disconnect(Messages.components(plugin.msg().get("kick.no-auth-server")));
            return;
        }
        if (event.getTarget().getName().equals(lobby.getName())) {
            return;
        }
        if (player.getServer() == null) {
            // première connexion : direction le lobby
            event.setTarget(lobby);
        } else {
            event.setCancelled(true);
            plugin.msg().send(player, "general.login-first");
        }
    }

    // ============================================================ Chat et commandes

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String message = event.getMessage();
        if (event.isCommand()) {
            String[] parts = message.substring(1).trim().split(" +");
            String root = parts[0].toLowerCase(Locale.ROOT);
            int colon = root.indexOf(':');
            if (colon >= 0) {
                root = root.substring(colon + 1);
            }
            Settings s = plugin.settings();
            if (s.loginCommands.contains(root) || s.registerCommands.contains(root) || s.changeCommands.contains(root)
                    || s.forgotCommands.contains(root)) {
                // jamais transmise au serveur ni journalisée
                event.setCancelled(true);
                handleAuthCommand(player, root, parts);
                return;
            }
            if (auth().isPending(player)) {
                event.setCancelled(true);
                plugin.msg().send(player, "general.login-first");
            }
            return;
        }
        if (auth().isPending(player)) {
            event.setCancelled(true);
            plugin.msg().send(player, "general.login-first");
        }
    }

    private void handleAuthCommand(ProxiedPlayer player, String root, String[] parts) {
        Settings s = plugin.settings();
        String a = parts.length > 1 ? parts[1] : "";
        String b = parts.length > 2 ? parts[2] : "";
        if (s.loginCommands.contains(root)) {
            // plusieurs mots : un mot de passe n'a pas d'espace, il sera refusé comme incorrect
            auth().login(player, parts.length > 2 ? join(parts) : a);
        } else if (s.registerCommands.contains(root)) {
            auth().register(player, a, b);
        } else if (s.forgotCommands.contains(root)) {
            // sans argument : envoi du code ; un argument : le code ; deux : le nouveau mot de passe (deux fois)
            if (parts.length <= 1) {
                auth().forgot(player);
            } else if (parts.length == 2) {
                auth().checkCode(player, a);
            } else {
                auth().resetPassword(player, a, b);
            }
        } else {
            auth().changePassword(player, a, b);
        }
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTab(TabCompleteEvent event) {
        if (event.getSender() instanceof ProxiedPlayer && auth().isPending((ProxiedPlayer) event.getSender())) {
            event.setCancelled(true);
        }
    }

    // ============================================================ Lobby

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!AuthChannel.NAME.equals(event.getTag())) {
            return;
        }
        // jamais relayé ; seul un serveur du réseau peut parler sur ce canal (pas un client)
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server) || !(event.getReceiver() instanceof ProxiedPlayer)) {
            return;
        }
        Server server = (Server) event.getSender();
        ProxiedPlayer carrier = (ProxiedPlayer) event.getReceiver();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String type = in.readUTF();
            UUID id = UUID.fromString(in.readUTF());
            ProxiedPlayer player = plugin.getProxy().getPlayer(id);
            if (player == null || player != carrier || player.getServer() == null
                    || !player.getServer().getInfo().getName().equals(server.getInfo().getName())) {
                return;
            }
            if (AuthChannel.READY.equals(type)) {
                auth().onReady(player, in.readBoolean());
            } else if (AuthChannel.LOGIN.equals(type)) {
                auth().login(player, in.readUTF());
            } else if (AuthChannel.REGISTER.equals(type)) {
                String password = in.readUTF();
                auth().register(player, password, in.readUTF());
            } else if (AuthChannel.FORGOT.equals(type)) {
                auth().forgot(player);
            } else if (AuthChannel.CODE.equals(type)) {
                auth().checkCode(player, in.readUTF());
            } else if (AuthChannel.RESET.equals(type)) {
                String password = in.readUTF();
                auth().resetPassword(player, password, in.readUTF());
            } else if (AuthChannel.BACK.equals(type)) {
                auth().back(player);
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Message illisible sur " + AuthChannel.NAME + " depuis " + server.getInfo().getName());
        }
    }
}
