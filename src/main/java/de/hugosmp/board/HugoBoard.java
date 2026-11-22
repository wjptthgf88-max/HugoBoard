package de.hugosmp.board;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HugoBoard extends JavaPlugin implements CommandExecutor, Listener {
    private Economy econ;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Set<UUID> boardDisabled = new HashSet<>();
    private File dataFile;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault oder ein Economy-Plugin fehlt!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadData();
        getCommand("board").setExecutor(this);
        getCommand("bounty").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                updateBoard(p);
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    public String formatMoney(double amount) {
        if (amount >= 1_000_000_000_000.0) return new DecimalFormat("#.##").format(amount / 1_000_000_000_000.0) + "T $";
        if (amount >= 1_000_000_000.0) return new DecimalFormat("#.##").format(amount / 1_000_000_000.0) + "B $";
        if (amount >= 1_000_000.0) return new DecimalFormat("#.##").format(amount / 1_000_000.0) + "M $";
        if (amount >= 1_000.0) return new DecimalFormat("#.##").format(amount / 1_000.0) + "K $";
        return new DecimalFormat("#.##").format(amount) + " $";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (cmd.getName().equalsIgnoreCase("board")) {
            if (args.length == 0) {
                p.sendMessage(mm.deserialize("<red>Nutze: /board <on|off>"));
                return true;
            }
            if (args.equalsIgnoreCase("off")) {
                boardDisabled.add(p.getUniqueId());
                Scoreboard sb = p.getScoreboard();
                Objective obj = sb.getObjective("hugoboard");
                if (obj != null) obj.unregister();
                saveData();
                p.sendMessage(mm.deserialize("<yellow>Scoreboard wurde <red>ausgeschaltet<yellow>."));
            } else if (args.equalsIgnoreCase("on")) {
                boardDisabled.remove(p.getUniqueId());
                saveData();
                updateBoard(p);
                p.sendMessage(mm.deserialize("<yellow>Scoreboard wurde <green>angeschaltet<yellow>."));
            } else {
                p.sendMessage(mm.deserialize("<red>Nutze: /board <on|off>"));
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("bounty")) {
            if (args.length == 0) {
                p.sendMessage(mm.deserialize("<red>Nutze: /bounty <set <Spieler> <Betrag> | check <Spieler> | top>"));
                return true;
            }

            switch (args.toLowerCase()) {
                case "set" -> {
                    if (args.length < 3) {
                        p.sendMessage(mm.deserialize("<red>Nutze: /bounty set <Spieler> <Betrag>"));
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args);
                    if (target == null) {
                        p.sendMessage(mm.deserialize("<red>Dieser Spieler ist nicht online!"));
                        return true;
                    }
                    if (target.equals(p)) {
                        p.sendMessage(mm.deserialize("<red>Du kannst kein Kopfgeld auf dich selbst setzen!"));
                        return true;
                    }
                    double amount;
                    try {
                        amount = Double.parseDouble(args);
                    } catch (NumberFormatException ex) {
                        p.sendMessage(mm.deserialize("<red>Bitte gib eine gueltige Zahl ein!"));
                        return true;
                    }
                    if (amount < 100) {
                        p.sendMessage(mm.deserialize("<red>Das Mindest-Kopfgeld betraegt 100 $!"));
                        return true;
                    }
                    if (!econ.has(p, amount)) {
                        p.sendMessage(mm.deserialize("<red>Du hast nicht genug Geld (<gold>" + formatMoney(amount) + "<red>)!"));
                        return true;
                    }

                    econ.withdrawPlayer(p, amount);
                    double newBounty = bounties.getOrDefault(target.getUniqueId(), 0.0) + amount;
                    bounties.put(target.getUniqueId(), newBounty);
                    saveData();
                    updateBoard(target);

                    Bukkit.broadcast(mm.deserialize(
                        "<red><bold>[BOUNTY]</bold> <gold>" + p.getName() + " <white>hat <gold>" + formatMoney(amount) +
                        " <white>Kopfgeld auf <red>" + target.getName() + " <white>ausgesetzt! <gray>(Gesamt: <gold>" + formatMoney(newBounty) + "<gray>)"
                    ));
                }
                case "check" -> {
                    if (args.length < 2) {
                        p.sendMessage(mm.deserialize("<red>Nutze: /bounty check <Spieler>"));
                        return true;
                    }
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args);
                    double b = bounties.getOrDefault(target.getUniqueId(), 0.0);
                    p.sendMessage(mm.deserialize("<aqua>[Bounty] <white>Kopfgeld auf <gold>" + args + "<white>: <green>" + formatMoney(b)));
                }
                case "top" -> {
                    List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(bounties.entrySet());
                    sorted.removeIf(e -> e.getValue() <= 0);
                    sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                    if (sorted.isEmpty()) {
                        p.sendMessage(mm.deserialize("<gray>Aktuell sind keine Kopfgelder ausgesetzt."));
                        return true;
                    }
                    p.sendMessage(mm.deserialize("<gold><bold>Top Kopfgelder:</bold>"));
                    for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                        Map.Entry<UUID, Double> entry = sorted.get(i);
                        String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                        p.sendMessage(mm.deserialize("<gray>#" + (i + 1) + " <white>" + (name != null ? name : "Unbekannt") + " <gray>- <gold>" + formatMoney(entry.getValue())));
                    }
                }
                default -> p.sendMessage(mm.deserialize("<red>Nutze: /bounty <set <Spieler> <Betrag> | check <Spieler> | top>"));
            }
        }
        return true;
    }
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            double killReward = 25000.0;
            econ.depositPlayer(killer, killReward);
            killer.sendMessage(mm.deserialize("<green>+ " + formatMoney(killReward) + " <gray>(Spieler-Kill)"));

            double bounty = bounties.getOrDefault(victim.getUniqueId(), 0.0);
            if (bounty > 0) {
                bounties.remove(victim.getUniqueId());
                econ.depositPlayer(killer, bounty);
                saveData();

                Bukkit.broadcast(mm.deserialize(
                    "<red><bold>[BOUNTY]</bold> <gold>" + killer.getName() + " <white>hat <red>" + victim.getName() +
                    " <white>getoetet und <green>" + formatMoney(bounty) + " <white>Kopfgeld kassiert!"
                ));
            }
        }
        updateBoard(victim);
        if (killer != null) updateBoard(killer);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> updateBoard(e.getPlayer()), 15L);
    }

    @SuppressWarnings("deprecation")
    public void updateBoard(Player p) {
        Scoreboard sb = p.getScoreboard();
        if (sb == null || sb.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
        }

        if (boardDisabled.contains(p.getUniqueId())) {
            Objective old = sb.getObjective("hugoboard");
            if (old != null) old.unregister();
            return;
        }

        Objective obj = sb.getObjective("hugoboard");
        if (obj == null) {
            obj = sb.registerNewObjective("hugoboard", Criteria.DUMMY, mm.deserialize("<gradient:#00d2ff:#3a7bd5><bold>HUGOSMP</bold></gradient>"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        for (String entry : new HashSet<>(sb.getEntries())) {
            if (entry.startsWith("§")) {
                sb.resetScores(entry);
            }
        }

        double money = econ.getBalance(p);
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        double bounty = bounties.getOrDefault(p.getUniqueId(), 0.0);

        setLine(obj, "§1", 11);
        setLine(obj, "§8▪ §7Geld: §a" + formatMoney(money), 10);
        setLine(obj, "§2", 9);
        setLine(obj, "§8▪ §7Kills: §c" + kills, 8);
        setLine(obj, "§8▪ §7Tode: §e" + deaths, 7);
        setLine(obj, "§3", 6);
        setLine(obj, "§8▪ §7Bounty: §6" + formatMoney(bounty), 5);
        setLine(obj, "§4", 4);
        setLine(obj, "§7/board off", 3);
    }

    private void setLine(Objective obj, String text, int score) {
        obj.getScore(text).setScore(score);
    }

    private void saveData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<UUID, Double> entry : bounties.entrySet()) {
            if (entry.getValue() > 0) {
                cfg.set("bounties." + entry.getKey().toString(), entry.getValue());
            }
        }
        List<String> disabledList = new ArrayList<>();
        for (UUID id : boardDisabled) disabledList.add(id.toString());
        cfg.set("disabled", disabledList);

        try { cfg.save(dataFile); } catch (IOException ex) { ex.printStackTrace(); }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection sec = cfg.getConfigurationSection("bounties");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                bounties.put(UUID.fromString(key), sec.getDouble(key));
            }
        }
        for (String id : cfg.getStringList("disabled")) {
            boardDisabled.add(UUID.fromString(id));
        }
    }
}
