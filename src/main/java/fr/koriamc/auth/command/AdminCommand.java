package fr.koriamc.auth.command;

import fr.koriamc.auth.KoriaAuth;
import fr.koriamc.auth.account.Account;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * /koriaauth [help | info &lt;joueur&gt; | unregister &lt;joueur&gt; | forcelogin &lt;joueur&gt; | reload]
 * (permission koriaauth.admin).
 */
public final class AdminCommand extends Command implements TabExecutor {

    public static final String PERMISSION = "koriaauth.admin";
    private static final List<String> ACTIONS = Arrays.asList("help", "info", "unregister", "forcelogin", "reload");

    private final KoriaAuth plugin;

    public AdminCommand(KoriaAuth plugin, String name, String[] aliases) {
        super(name, PERMISSION, aliases);
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String target = args.length > 1 ? args[1] : null;
        if ((action.equals("info") || action.equals("unregister") || action.equals("forcelogin")) && target == null) {
            plugin.msg().send(sender, "admin.usage", "commande", getName(), "action", action);
            return;
        }
        if (action.equals("info")) {
            Account account = plugin.accounts().get(target);
            if (account == null) {
                plugin.msg().send(sender, "admin.unknown", "joueur", target);
                return;
            }
            SimpleDateFormat date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
            ProxiedPlayer online = plugin.getProxy().getPlayer(target);
            plugin.msg().send(sender, "admin.info", "joueur", account.getName(),
                    "inscription", date.format(new Date(account.getRegisteredAt())), "ip-inscription", account.getRegisteredIp(),
                    "derniere", account.getLastLogin() > 0 ? date.format(new Date(account.getLastLogin())) : "?",
                    "ip", account.getLastIp(),
                    "etat", plugin.msg().text(online == null ? "values.offline"
                            : plugin.auth().isLogged(online) ? "values.logged" : "values.pending"));
        } else if (action.equals("unregister")) {
            if (plugin.auth().unregister(target)) {
                plugin.msg().send(sender, "admin.unregistered", "joueur", target);
                plugin.getLogger().info(sender.getName() + " a supprimé le compte de " + target + ".");
            } else {
                plugin.msg().send(sender, "admin.unknown", "joueur", target);
            }
        } else if (action.equals("forcelogin")) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(target);
            if (player == null) {
                plugin.msg().send(sender, "admin.offline", "joueur", target);
            } else if (plugin.auth().forceLogin(player)) {
                plugin.msg().send(sender, "admin.forced", "joueur", player.getName());
                plugin.getLogger().info(sender.getName() + " a connecté " + player.getName() + " sans mot de passe.");
            } else {
                plugin.msg().send(sender, "admin.already-logged", "joueur", player.getName());
            }
        } else if (action.equals("reload") || action.equals("rl")) {
            List<String> problems = plugin.reload();
            plugin.msg().send(sender, "admin.reloaded", "comptes", plugin.accounts().count());
            for (String problem : problems) {
                plugin.msg().send(sender, "admin.reload-problem", "probleme", problem);
            }
        } else {
            plugin.msg().send(sender, "admin.help", "commande", getName());
        }
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String action : ACTIONS) {
                if (action.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(action);
                }
            }
        } else if (args.length == 2) {
            for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(player.getName());
                }
            }
        }
        return out;
    }
}
