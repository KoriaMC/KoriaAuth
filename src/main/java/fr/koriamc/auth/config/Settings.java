package fr.koriamc.auth.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Réglages typés de config.json, relus à chaque rechargement.
 */
public final class Settings {

    // Serveurs
    /** Serveur (lobby) où le joueur attend tant qu'il n'est pas connecté. */
    public String authServer;
    /** Serveur où il est envoyé une fois connecté (vide = il reste sur le lobby). */
    public String afterLoginServer;

    // Mots de passe
    public int minLength;
    public int maxLength;
    public List<String> forbidden = new ArrayList<String>();
    public int hashIterations;

    // Connexion
    public int timeoutSeconds;
    public int maxAttempts;
    public int sessionMinutes;
    public int maxAccountsPerIp;
    public int chatReminderSeconds;
    /** Délai sans nouvelles du lobby (KoriaAuthLobby) avant de passer aux rappels dans le chat. */
    public int lobbyWaitSeconds;

    // Mot de passe oublié (code par mail, e-mail lu dans la base Azuriom)
    public boolean forgotEnabled;
    public int codeMinutes;
    public int codeAttempts;
    public int mailCooldownSeconds;
    /** Temps laissé au joueur sur le lobby après l'envoi du code. */
    public int forgotDelayMinutes;
    public String dbHost;
    public int dbPort;
    public String dbName;
    public String dbUser;
    public String dbPassword;
    public String dbTable;
    public String dbNameColumn;
    public String dbEmailColumn;
    public boolean dbSsl;
    public String mailHost;
    public int mailPort;
    public String mailSecurity;
    public String mailUser;
    public String mailPassword;
    public String mailFrom;
    public String mailFromName;

    // Commandes (noms et alias, minuscules)
    public Set<String> loginCommands = new LinkedHashSet<String>();
    public Set<String> registerCommands = new LinkedHashSet<String>();
    public Set<String> changeCommands = new LinkedHashSet<String>();
    public Set<String> forgotCommands = new LinkedHashSet<String>();
    public String adminCommand;
    public List<String> adminAliases = new ArrayList<String>();

    // Titres (ticks)
    public int titleFadeIn;
    public int titleStay;
    public int titleFadeOut;

    public void load(JsonConfig c) {
        authServer = c.getString("serveurs.auth", "lobby").trim();
        afterLoginServer = c.getString("serveurs.apres-connexion", "").trim();

        minLength = Math.max(1, c.getInt("mot-de-passe.longueur-min", 6));
        maxLength = Math.max(minLength, Math.min(64, c.getInt("mot-de-passe.longueur-max", 32)));
        forbidden = new ArrayList<String>();
        for (String word : c.getStringList("mot-de-passe.interdits")) {
            forbidden.add(word.toLowerCase(Locale.ROOT));
        }
        hashIterations = Math.max(10000, c.getInt("mot-de-passe.iterations", 60000));

        timeoutSeconds = Math.max(10, c.getInt("connexion.delai-secondes", 90));
        maxAttempts = Math.max(1, c.getInt("connexion.essais-max", 5));
        sessionMinutes = Math.max(0, c.getInt("connexion.session-minutes", 0));
        maxAccountsPerIp = Math.max(0, c.getInt("connexion.comptes-max-par-ip", 0));
        chatReminderSeconds = Math.max(3, c.getInt("connexion.rappel-chat-secondes", 10));
        lobbyWaitSeconds = Math.max(1, c.getInt("connexion.attente-lobby-secondes", 6));

        forgotEnabled = c.getBoolean("oubli.actif", false);
        codeMinutes = Math.max(1, c.getInt("oubli.code-minutes", 15));
        codeAttempts = Math.max(1, c.getInt("oubli.essais-code", 5));
        mailCooldownSeconds = Math.max(0, c.getInt("oubli.attente-entre-mails-secondes", 120));
        forgotDelayMinutes = Math.max(1, c.getInt("oubli.temps-sur-le-lobby-minutes", 10));
        dbHost = c.getString("oubli.base-azuriom.hote", "localhost").trim();
        dbPort = c.getInt("oubli.base-azuriom.port", 3306);
        dbName = c.getString("oubli.base-azuriom.base", "azuriom").trim();
        dbUser = c.getString("oubli.base-azuriom.utilisateur", "").trim();
        dbPassword = c.getString("oubli.base-azuriom.mot-de-passe", "");
        dbTable = c.getString("oubli.base-azuriom.table", "users").trim();
        dbNameColumn = c.getString("oubli.base-azuriom.colonne-pseudo", "name").trim();
        dbEmailColumn = c.getString("oubli.base-azuriom.colonne-email", "email").trim();
        dbSsl = c.getBoolean("oubli.base-azuriom.ssl", false);
        mailHost = c.getString("oubli.mail.hote", "").trim();
        mailPort = c.getInt("oubli.mail.port", 587);
        mailSecurity = c.getString("oubli.mail.securite", "starttls");
        mailUser = c.getString("oubli.mail.utilisateur", "").trim();
        mailPassword = c.getString("oubli.mail.mot-de-passe", "");
        mailFrom = c.getString("oubli.mail.expediteur", "").trim();
        mailFromName = c.getString("oubli.mail.nom-expediteur", "KoriaMC").trim();

        loginCommands = names(c, "commandes.login", "login");
        registerCommands = names(c, "commandes.register", "register");
        changeCommands = names(c, "commandes.changepassword", "changepassword");
        forgotCommands = names(c, "commandes.oubli", "motdepasseoublie");
        adminCommand = c.getString("commandes.admin.nom", "koriaauth").trim().toLowerCase(Locale.ROOT);
        if (adminCommand.isEmpty()) {
            adminCommand = "koriaauth";
        }
        adminAliases = new ArrayList<String>();
        for (String alias : c.getStringList("commandes.admin.alias")) {
            if (!alias.trim().isEmpty()) {
                adminAliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }

        titleFadeIn = c.getInt("titres.apparition", 5);
        titleStay = c.getInt("titres.duree", 40);
        titleFadeOut = c.getInt("titres.disparition", 10);
    }

    /** Liste "commandes.x" (nom puis alias), jamais vide. */
    private static Set<String> names(JsonConfig c, String path, String def) {
        Set<String> set = new LinkedHashSet<String>();
        for (String name : c.getStringList(path)) {
            String clean = name.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("/")) {
                clean = clean.substring(1);
            }
            if (!clean.isEmpty()) {
                set.add(clean);
            }
        }
        if (set.isEmpty()) {
            set.add(def);
        }
        return set;
    }

    /** Nom principal d'une commande (premier de la liste). */
    public static String first(Set<String> names) {
        return names.iterator().next();
    }
}
