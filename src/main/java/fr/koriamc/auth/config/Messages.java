package fr.koriamc.auth.config;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Textes de messages.json. Les variables sont passées par paires : get("cle", "joueur", nom).
 */
public final class Messages {

    private JsonConfig cfg;
    private String prefix = "";

    public void load(JsonConfig cfg) {
        this.cfg = cfg;
        this.prefix = cfg.getString("prefix", "");
    }

    /** Lignes brutes d'un message (une clé absente renvoie son nom). */
    public List<String> raw(String key) {
        if (cfg == null) {
            return Collections.singletonList("<" + key + ">");
        }
        List<String> lines = cfg.getStringList(key);
        return lines.isEmpty() ? Collections.singletonList("<" + key + ">") : lines;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Remplace {prefix} et les variables puis colore. */
    public String format(String template, Object... kv) {
        String s = template.replace("{prefix}", prefix);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return color(s);
    }

    public List<String> lines(String key, Object... kv) {
        List<String> out = new ArrayList<String>();
        for (String line : raw(key)) {
            out.add(format(line, kv));
        }
        return out;
    }

    /** Message sur une seule chaîne (lignes jointes par un retour à la ligne). */
    public String get(String key, Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines(key, kv)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** Message sans le préfixe, sur une ligne (affichage dans un écran du client). */
    public String plain(String key, Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw(key)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(format(line.replace("{prefix}", ""), kv));
        }
        return sb.toString().trim();
    }

    /** Lignes jointes par un retour à la ligne, variables remplacées, sans couleurs (contenu d'un mail). */
    public String rawText(String key, Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw(key)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String s = line;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Valeur simple (fragment), chaîne vide si absente. */
    public String text(String key, Object... kv) {
        String value = cfg == null ? null : cfg.getString(key, null);
        return value == null ? "" : format(value, kv);
    }

    public void send(CommandSender to, String key, Object... kv) {
        if (to == null) {
            return;
        }
        for (String line : lines(key, kv)) {
            if (!line.isEmpty()) {
                to.sendMessage(components(line));
            }
        }
    }

    public static BaseComponent[] components(String text) {
        return TextComponent.fromLegacyText(text);
    }
}
