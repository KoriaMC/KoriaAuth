package fr.koriamc.auth.channel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Canal de messages entre le proxy (KoriaAuth) et le lobby (KoriaAuthLobby). Chaque message commence par son type
 * puis l'UUID du joueur concerné (writeUTF).
 *
 * Lobby -&gt; proxy :
 *   READY    uuid, client (boolean)          le joueur est sur le lobby (client KoriaMC ou non)
 *   LOGIN    uuid, mot de passe              bouton Se connecter
 *   REGISTER uuid, mot de passe, confirmation  bouton S'enregistrer
 *   FORGOT   uuid                            lien Mot de passe oublié : envoi d'un code par mail
 *   CODE     uuid, code                      code reçu par mail
 *   RESET    uuid, mot de passe, confirmation  nouveau mot de passe (après le code)
 *   BACK     uuid                            retour à l'écran de connexion
 * Proxy -&gt; lobby :
 *   SHOW     uuid, écran (login|register|reset_code|reset_password), erreur, essais restants (int),
 *            secondes restantes (int), longueur min (int), information (e-mail masqué)
 *   DONE     uuid                            le joueur est connecté : écran fermé, joueur libéré
 */
public final class AuthChannel {

    public static final String NAME = "koriamc:auth";

    public static final String READY = "READY";
    public static final String LOGIN = "LOGIN";
    public static final String REGISTER = "REGISTER";
    public static final String FORGOT = "FORGOT";
    public static final String CODE = "CODE";
    public static final String RESET = "RESET";
    public static final String BACK = "BACK";
    public static final String SHOW = "SHOW";
    public static final String DONE = "DONE";

    private AuthChannel() {
    }

    /** Message : chaînes (writeUTF), entiers (writeInt) et booléens (writeBoolean). */
    public static byte[] encode(Object... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            for (Object value : values) {
                if (value instanceof Integer) {
                    out.writeInt((Integer) value);
                } else if (value instanceof Boolean) {
                    out.writeBoolean((Boolean) value);
                } else {
                    out.writeUTF(String.valueOf(value));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
