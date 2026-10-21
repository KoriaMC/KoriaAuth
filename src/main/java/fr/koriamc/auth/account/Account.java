package fr.koriamc.auth.account;

/**
 * Compte d'un joueur (comptes.json), indexé par son pseudo en minuscules.
 */
public final class Account {

    /** Pseudo avec sa casse d'inscription (un autre joueur ne peut pas prendre "steve" si "Steve" existe). */
    private String name;
    /** Mot de passe haché (voir PasswordHasher). */
    private String hash;
    private long registeredAt;
    private String registeredIp;
    private long lastLogin;
    private String lastIp;

    public Account() {
    }

    public Account(String name, String hash, String ip) {
        this.name = name;
        this.hash = hash;
        this.registeredAt = System.currentTimeMillis();
        this.registeredIp = ip;
        this.lastLogin = registeredAt;
        this.lastIp = ip;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    public String getRegisteredIp() {
        return registeredIp == null ? "" : registeredIp;
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public String getLastIp() {
        return lastIp == null ? "" : lastIp;
    }

    public void touch(String ip) {
        this.lastLogin = System.currentTimeMillis();
        this.lastIp = ip;
    }
}
