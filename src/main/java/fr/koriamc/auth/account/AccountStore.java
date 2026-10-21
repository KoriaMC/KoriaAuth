package fr.koriamc.auth.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.koriamc.auth.KoriaAuth;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Comptes enregistrés (plugins/KoriaAuth/comptes.json), indexés par pseudo en minuscules. L'écriture se fait
 * hors des threads réseau, dans un fichier temporaire remplacé d'un coup (jamais de fichier à moitié écrit).
 */
public final class AccountStore {

    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Account>>() {
    }.getType();

    private final KoriaAuth plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final File file;
    private final Map<String, Account> accounts = new ConcurrentHashMap<String, Account>();
    private final Object writeLock = new Object();
    /** Le fichier existant est illisible : il n'est jamais écrasé. */
    private boolean readOnly;

    public AccountStore(KoriaAuth plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "comptes.json");
    }

    /** @return un message d'erreur, ou null */
    public String load() {
        accounts.clear();
        readOnly = false;
        if (!file.exists()) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Account> read = gson.fromJson(json, TYPE);
            if (read != null) {
                for (Map.Entry<String, Account> entry : read.entrySet()) {
                    if (entry.getValue() != null) {
                        accounts.put(key(entry.getKey()), entry.getValue());
                    }
                }
            }
            return null;
        } catch (Exception e) {
            readOnly = true;
            plugin.getLogger().log(Level.SEVERE, "comptes.json illisible : aucun compte chargé et le fichier ne sera pas"
                    + " modifié tant qu'il n'est pas réparé", e);
            return "comptes.json illisible : " + e.getMessage();
        }
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Account get(String name) {
        return accounts.get(key(name));
    }

    public boolean exists(String name) {
        return accounts.containsKey(key(name));
    }

    public int count() {
        return accounts.size();
    }

    /** Comptes inscrits depuis cette adresse IP. */
    public int countForIp(String ip) {
        int n = 0;
        for (Account account : accounts.values()) {
            if (account.getRegisteredIp().equals(ip)) {
                n++;
            }
        }
        return n;
    }

    public void put(Account account) {
        accounts.put(key(account.getName()), account);
        save();
    }

    public boolean remove(String name) {
        boolean removed = accounts.remove(key(name)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** Sauvegarde en tâche de fond. */
    public void save() {
        if (readOnly) {
            plugin.getLogger().warning("comptes.json n'est pas enregistré : le fichier était illisible au chargement.");
            return;
        }
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                saveNow();
            }
        });
    }

    /** Sauvegarde immédiate (arrêt du proxy). */
    public void saveNow() {
        if (readOnly) {
            return;
        }
        synchronized (writeLock) {
            Map<String, Account> snapshot = new LinkedHashMap<String, Account>(new java.util.TreeMap<String, Account>(accounts));
            String json = gson.toJson(snapshot, TYPE);
            try {
                File folder = file.getParentFile();
                if (!folder.isDirectory() && !folder.mkdirs()) {
                    throw new IOException("dossier " + folder + " impossible à créer");
                }
                File temp = new File(folder, file.getName() + ".tmp");
                Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer comptes.json", e);
            }
        }
    }
}
