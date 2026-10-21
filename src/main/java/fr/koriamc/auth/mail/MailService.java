package fr.koriamc.auth.mail;

import fr.koriamc.auth.KoriaAuth;
import fr.koriamc.auth.config.Settings;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.SQLException;

/**
 * Mot de passe oublié : e-mail du joueur lu dans la base Azuriom, code envoyé par SMTP (textes : messages.json,
 * section mail). Recréé à chaque rechargement de la configuration.
 */
public final class MailService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KoriaAuth plugin;
    private final AzuriomDatabase database;
    private final SmtpMailer mailer;

    public MailService(KoriaAuth plugin) {
        this.plugin = plugin;
        Settings s = plugin.settings();
        AzuriomDatabase db = null;
        SmtpMailer smtp = null;
        if (s.forgotEnabled) {
            try {
                db = new AzuriomDatabase(s.dbHost, s.dbPort, s.dbName, s.dbUser, s.dbPassword, s.dbTable, s.dbNameColumn,
                        s.dbEmailColumn, s.dbSsl, 5000);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("config.json, oubli.base-azuriom : " + e.getMessage());
            }
            if (s.mailHost.isEmpty() || s.mailFrom.isEmpty()) {
                plugin.getLogger().severe("config.json : oubli.mail.hote et oubli.mail.expediteur sont obligatoires pour envoyer"
                        + " les codes de mot de passe oublié.");
            } else {
                smtp = new SmtpMailer(s.mailHost, s.mailPort, SmtpMailer.Security.parse(s.mailSecurity), s.mailUser, s.mailPassword,
                        s.mailFrom, s.mailFromName, 10000);
            }
        }
        this.database = db;
        this.mailer = smtp;
    }

    /** Fonction utilisable (activée et configurée) ? */
    public boolean isReady() {
        return database != null && mailer != null;
    }

    public String findEmail(String name) throws SQLException {
        if (database == null) {
            throw new SQLException("base Azuriom non configurée");
        }
        return database.findEmail(name);
    }

    /** Code à 6 chiffres. */
    public String newCode() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }

    public void sendCode(String email, String name, String code, int minutes) throws IOException {
        if (mailer == null) {
            throw new IOException("serveur de mail non configuré");
        }
        String subject = plugin.msg().rawText("mail.sujet", "joueur", name, "code", code, "minutes", minutes);
        String body = plugin.msg().rawText("mail.contenu", "joueur", escape(name), "code", code, "minutes", minutes);
        mailer.send(email, subject, body);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** "jean.dupont@gmail.com" -&gt; "je*******@g****.com" (affiché au joueur). */
    public static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String host = dot > 0 ? domain.substring(0, dot) : domain;
        String tld = dot > 0 ? domain.substring(dot) : "";
        return keep(local, 2) + "@" + keep(host, 1) + tld;
    }

    private static String keep(String text, int visible) {
        if (text.length() <= visible) {
            return text.substring(0, Math.min(1, text.length())) + "***";
        }
        StringBuilder sb = new StringBuilder(text.substring(0, visible));
        for (int i = visible; i < Math.min(text.length(), 10); i++) {
            sb.append('*');
        }
        return sb.toString();
    }
}
