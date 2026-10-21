package fr.koriamc.auth.mail;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Lecture de l'e-mail d'un joueur dans la base du site Azuriom (table users : name, email). Une connexion courte
 * par recherche (les demandes de mot de passe oublié sont rares). Pilote MariaDB embarqué, compatible MySQL.
 */
public final class AzuriomDatabase {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final String url;
    private final Properties properties = new Properties();
    private final String query;

    public AzuriomDatabase(String host, int port, String database, String user, String password, String table,
                           String nameColumn, String emailColumn, boolean ssl, int timeoutMs) {
        for (String identifier : new String[] {table, nameColumn, emailColumn}) {
            if (!IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalArgumentException("nom de table ou de colonne invalide : " + identifier);
            }
        }
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", String.valueOf(timeoutMs));
        properties.setProperty("socketTimeout", String.valueOf(timeoutMs));
        properties.setProperty("useSsl", String.valueOf(ssl));
        this.query = "SELECT `" + emailColumn + "` FROM `" + table + "` WHERE `" + nameColumn + "` = ? LIMIT 1";
    }

    /** E-mail du compte du site portant ce pseudo, ou null (bloquant : hors des threads réseau). */
    public String findEmail(String name) throws SQLException {
        Driver driver = new org.mariadb.jdbc.Driver();
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("adresse de base invalide : " + url);
        }
        try {
            PreparedStatement statement = connection.prepareStatement(query);
            try {
                statement.setString(1, name);
                ResultSet result = statement.executeQuery();
                try {
                    if (!result.next()) {
                        return null;
                    }
                    String email = result.getString(1);
                    return email == null || email.trim().isEmpty() ? null : email.trim();
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }
}
