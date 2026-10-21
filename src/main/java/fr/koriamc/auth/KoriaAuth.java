package fr.koriamc.auth;

import fr.koriamc.auth.account.AccountStore;
import fr.koriamc.auth.account.AuthManager;
import fr.koriamc.auth.channel.AuthChannel;
import fr.koriamc.auth.command.AdminCommand;
import fr.koriamc.auth.config.JsonConfig;
import fr.koriamc.auth.config.Messages;
import fr.koriamc.auth.config.Settings;
import fr.koriamc.auth.listener.ProxyListener;
import fr.koriamc.auth.mail.MailService;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * KoriaAuth : comptes et connexion des joueurs sur le proxy KoriaMC (BungeeCord, Waterfall, FlameCord).
 *
 * À la première venue le joueur choisit un mot de passe (deux fois), ensuite il le donne à chaque connexion.
 * Les écrans du client KoriaMC sont affichés par le plugin KoriaAuthLobby installé sur le serveur lobby
 * (canal koriamc:auth) ; sans le client, /register et /login dans le chat.
 */
public final class KoriaAuth extends Plugin {

    private JsonConfig configFile;
    private JsonConfig messagesFile;
    private final Settings settings = new Settings();
    private final Messages messages = new Messages();
    private AccountStore accounts;
    private AuthManager auth;
    private AdminCommand adminCommand;
    private MailService mail;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        configFile = new JsonConfig(this, "config.json");
        messagesFile = new JsonConfig(this, "messages.json");
        accounts = new AccountStore(this);
        auth = new AuthManager(this);

        for (String problem : loadAll()) {
            getLogger().warning(problem);
        }
        getProxy().registerChannel(AuthChannel.NAME);
        getProxy().getPluginManager().registerListener(this, new ProxyListener(this));
        registerAdminCommand();
        auth.start();

        if (getProxy().getServerInfo(settings.authServer) == null) {
            getLogger().severe("config.json : serveurs.auth \"" + settings.authServer + "\" n'existe pas dans la config du proxy.");
        }
        getLogger().info("KoriaAuth activé en " + (System.currentTimeMillis() - start) + " ms (" + accounts.count()
                + " compte(s)). Installez KoriaAuthLobby sur le serveur \"" + settings.authServer + "\".");
    }

    @Override
    public void onDisable() {
        if (auth != null) {
            auth.stop();
        }
        if (accounts != null) {
            accounts.saveNow();
        }
        getProxy().unregisterChannel(AuthChannel.NAME);
    }

    private List<String> loadAll() {
        List<String> problems = new ArrayList<String>();
        for (JsonConfig file : new JsonConfig[] {configFile, messagesFile}) {
            String error = file.load();
            if (error != null) {
                problems.add(error);
            }
        }
        settings.load(configFile);
        messages.load(messagesFile);
        mail = new MailService(this);
        String error = accounts.load();
        if (error != null) {
            problems.add(error);
        }
        return problems;
    }

    private void registerAdminCommand() {
        if (adminCommand != null) {
            getProxy().getPluginManager().unregisterCommand(adminCommand);
        }
        adminCommand = new AdminCommand(this, settings.adminCommand, settings.adminAliases.toArray(new String[0]));
        getProxy().getPluginManager().registerCommand(this, adminCommand);
    }

    /** /koriaauth reload. @return les problèmes rencontrés */
    public List<String> reload() {
        accounts.saveNow();
        List<String> problems = loadAll();
        registerAdminCommand();
        auth.refreshPending();
        return problems;
    }

    public Settings settings() {
        return settings;
    }

    public Messages msg() {
        return messages;
    }

    public AccountStore accounts() {
        return accounts;
    }

    public AuthManager auth() {
        return auth;
    }

    /** Mot de passe oublié : base Azuriom et envoi des codes par mail. */
    public MailService mail() {
        return mail;
    }
}
