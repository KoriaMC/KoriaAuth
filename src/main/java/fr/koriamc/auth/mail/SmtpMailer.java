package fr.koriamc.auth.mail;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Envoi de mails par SMTP, sans bibliothèque : connexion SSL (port 465), STARTTLS (port 587) ou en clair,
 * authentification AUTH LOGIN, message HTML en UTF-8. Le certificat et le nom du serveur sont vérifiés en SSL/TLS.
 */
public final class SmtpMailer {

    public enum Security {
        SSL, STARTTLS, NONE;

        public static Security parse(String value) {
            String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (v.equals("SSL") || v.equals("TLS")) {
                return SSL;
            }
            if (v.equals("AUCUNE") || v.equals("NONE") || v.equals("NON")) {
                return NONE;
            }
            return STARTTLS;
        }
    }

    private final String host;
    private final int port;
    private final Security security;
    private final String user;
    private final String password;
    private final String fromAddress;
    private final String fromName;
    private final int timeoutMs;

    private Socket socket;
    private BufferedReader in;
    private OutputStream out;

    public SmtpMailer(String host, int port, Security security, String user, String password, String fromAddress, String fromName,
                      int timeoutMs) {
        this.host = host;
        this.port = port;
        this.security = security;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.fromAddress = fromAddress;
        this.fromName = fromName == null ? "" : fromName;
        this.timeoutMs = timeoutMs;
    }

    /** Envoie un mail HTML (bloquant : à appeler hors des threads réseau). */
    public synchronized void send(String to, String subject, String html) throws IOException {
        if (to.indexOf('\r') >= 0 || to.indexOf('\n') >= 0 || !to.contains("@")) {
            throw new IOException("adresse invalide");
        }
        try {
            connect();
            expect(220);
            ehlo();
            if (security == Security.STARTTLS) {
                command("STARTTLS", 220);
                upgrade(socket);
                ehlo();
            }
            if (!user.isEmpty()) {
                command("AUTH LOGIN", 334);
                command(b64(user), 334);
                command(b64(password), 235);
            }
            command("MAIL FROM:<" + fromAddress + ">", 250);
            command("RCPT TO:<" + to + ">", 250, 251);
            command("DATA", 354);
            write(message(to, subject, html));
            command(".", 250);
            try {
                command("QUIT", 221);
            } catch (IOException ignored) {
                // le mail est parti
            }
        } finally {
            close();
        }
    }

    private void connect() throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        if (security == Security.SSL) {
            upgrade(raw);
        } else {
            open(raw);
        }
    }

    /** Passe la connexion en TLS en vérifiant le certificat et le nom du serveur. */
    private void upgrade(Socket raw) throws IOException {
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.setSoTimeout(timeoutMs);
        ssl.startHandshake();
        open(ssl);
    }

    private void open(Socket s) throws IOException {
        socket = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        out = s.getOutputStream();
    }

    private void ehlo() throws IOException {
        command("EHLO koriamc", 250);
    }

    private void command(String line, int... expected) throws IOException {
        write(line + "\r\n");
        expect(expected);
    }

    private void write(String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Lit une réponse (éventuellement sur plusieurs lignes "250-...") et vérifie son code. */
    private void expect(int... codes) throws IOException {
        String line;
        String last;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("connexion SMTP fermée");
            }
            last = line;
        } while (line.length() > 3 && line.charAt(3) == '-');
        int code;
        try {
            code = Integer.parseInt(last.substring(0, 3));
        } catch (RuntimeException e) {
            throw new IOException("réponse SMTP illisible : " + last);
        }
        for (int expected : codes) {
            if (code == expected) {
                return;
            }
        }
        // jamais le mot de passe dans les erreurs : seule la réponse du serveur est rapportée
        throw new IOException("le serveur SMTP a répondu " + last);
    }

    private String message(String to, String subject, String html) {
        String domain = fromAddress.contains("@") ? fromAddress.substring(fromAddress.indexOf('@') + 1) : "koriamc";
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(fromName.isEmpty() ? "" : header(fromName) + " ").append('<').append(fromAddress).append(">\r\n");
        sb.append("To: <").append(to).append(">\r\n");
        sb.append("Subject: ").append(header(subject)).append("\r\n");
        sb.append("Date: ").append(new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date())).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(domain).append(">\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/html; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String body = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < body.length(); i += 76) {
            sb.append(body, i, Math.min(body.length(), i + 76)).append("\r\n");
        }
        return sb.toString();
    }

    /** En-tête encodé en UTF-8 (accents dans le sujet et le nom de l'expéditeur). */
    private static String header(String text) {
        return "=?UTF-8?B?" + b64(text) + "?=";
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // rien à faire
            }
        }
        socket = null;
        in = null;
        out = null;
    }
}
