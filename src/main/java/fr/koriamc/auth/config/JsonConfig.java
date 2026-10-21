package fr.koriamc.auth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fichier de configuration JSON du plugin (config.json, messages.json). Les clés absentes du fichier sont
 * complétées depuis la version du jar et le fichier est réécrit : une mise à jour n'efface jamais un réglage.
 */
public final class JsonConfig {

    private final Plugin plugin;
    private final String name;
    private JsonObject root = new JsonObject();

    public JsonConfig(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** @return un message d'erreur, ou null */
    public String load() {
        JsonObject defaults = readDefaults();
        File file = new File(plugin.getDataFolder(), name);
        try {
            if (!file.exists()) {
                root = defaults;
                write(file, root);
                return null;
            }
            JsonElement parsed = new JsonParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                root = defaults;
                return name + " : ce n'est pas un objet JSON, valeurs par défaut utilisées";
            }
            root = parsed.getAsJsonObject();
            if (merge(root, defaults)) {
                write(file, root);
            }
            return null;
        } catch (Exception e) {
            root = defaults;
            return name + " illisible (" + e.getMessage() + "), valeurs par défaut utilisées";
        }
    }

    private JsonObject readDefaults() {
        InputStream in = plugin.getResourceAsStream(name);
        if (in == null) {
            return new JsonObject();
        }
        try {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // rien à faire
            }
        }
    }

    /** Ajoute à target les clés de defaults qui lui manquent. @return true si quelque chose a été ajouté */
    private static boolean merge(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            JsonElement current = target.get(entry.getKey());
            if (current == null) {
                target.add(entry.getKey(), entry.getValue().deepCopy());
                changed = true;
            } else if (current.isJsonObject() && entry.getValue().isJsonObject()) {
                changed |= merge(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
            }
        }
        return changed;
    }

    private static void write(File file, JsonObject json) throws IOException {
        File folder = file.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("dossier " + folder + " impossible à créer");
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.write(file.toPath(), (gson.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Valeur au chemin "a.b.c", ou null. */
    public JsonElement get(String path) {
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current == null || current.isJsonNull() ? null : current;
    }

    public String getString(String path, String def) {
        JsonElement value = get(path);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : def;
    }

    public int getInt(String path, int def) {
        JsonElement value = get(path);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBoolean(String path, boolean def) {
        JsonElement value = get(path);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : def;
    }

    /** Liste de textes ; une simple chaîne donne une liste d'un élément. */
    public List<String> getStringList(String path) {
        List<String> list = new ArrayList<String>();
        JsonElement value = get(path);
        if (value == null) {
            return list;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    list.add(element.getAsString());
                }
            }
        } else if (value.isJsonPrimitive()) {
            list.add(value.getAsString());
        }
        return list;
    }
}
