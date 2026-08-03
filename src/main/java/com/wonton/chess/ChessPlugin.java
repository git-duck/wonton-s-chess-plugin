package com.wonton.chess;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChessPlugin: combined top+player inventory 8x8 board.
 *
 * - Top chest (rows 0..3, cols 0..7) -> board rows 0..3
 * - Player inventory visible area (main rows + hotbar; first 8 columns) -> board rows 4..7
 *
 * Safety:
 * - Player inventories are saved before showing board and restored on close/end.
 * - Clicks in board area are cancelled and handled as chess moves.
 *
 * Notes:
 * - Promotion auto-queens.
 * - Castling, en passant, and check/checkmate are supported.
 */
public class ChessPlugin extends JavaPlugin implements Listener {

    public static NamespacedKey PIECE_KEY;
    private ChallengeManager challengeManager;
    private EloManager eloManager;
    private Economy economy;
    private boolean enabled = true;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();
    // spectators: player UUID -> game they are watching
    private final Map<UUID, ChessGame> spectators = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        PIECE_KEY = new NamespacedKey(this, "chess_piece");
        this.challengeManager = new ChallengeManager(this);
        this.eloManager = new EloManager(this);
        setupEconomy();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(this.getCommand("chess")).setExecutor(new ChessCommand());
        Objects.requireNonNull(this.getCommand("chessaccept")).setExecutor(new ChessAcceptCommand());
        Objects.requireNonNull(this.getCommand("chessdeny")).setExecutor(new ChessDenyCommand());
        Objects.requireNonNull(this.getCommand("chessrating")).setExecutor(new ChessRatingCommand());
        Objects.requireNonNull(this.getCommand("chesstoggle")).setExecutor(new ChessToggleCommand());
        Objects.requireNonNull(this.getCommand("chessai")).setExecutor(new ChessAiCommand());
        Objects.requireNonNull(this.getCommand("chessspectate")).setExecutor(new ChessSpectateCommand());
        Objects.requireNonNull(this.getCommand("chesspuzzle")).setExecutor(new ChessPuzzleCommand());
        getLogger().info("ChessPlugin enabled");
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found - currency betting disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy provider registered via Vault - currency betting disabled.");
            return;
        }
        economy = rsp.getProvider();
        getLogger().info("Hooked into economy: " + economy.getName());
    }

    public Economy getEconomy() {
        return economy;
    }

    @Override
    public void onDisable() {
        if (eloManager != null) eloManager.save();
        // End games (this closes inventories -> triggers restoration on close)
        for (ChessGame g : new ArrayList<>(activeGames.values())) {
            g.endGame("Server shutting down");
        }
    }

    // Commands ----------------------------------------------------------------

    private class ChessCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (!enabled) {
                p.sendMessage(ChatColor.RED + "Chess is currently disabled.");
                return true;
            }
            if (args.length < 1 || args.length > 3) {
                p.sendMessage(ChatColor.RED + "Usage: /chess <player> [1|3|5|10] [bet]");
                return true;
            }
            int minutes = 3; // default: blitz
            if (args.length >= 2) {
                try {
                    minutes = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Invalid time control. Use 1 (bullet), 3 (blitz), 5 (blitz), or 10 (rapid).");
                    return true;
                }
                if (minutes != 1 && minutes != 3 && minutes != 5 && minutes != 10) {
                    p.sendMessage(ChatColor.RED + "Invalid time control. Use 1 (bullet), 3 (blitz), 5 (blitz), or 10 (rapid).");
                    return true;
                }
            }
            double bet = 0;
            if (args.length == 3) {
                try {
                    bet = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Invalid bet amount.");
                    return true;
                }
                if (bet <= 0) {
                    p.sendMessage(ChatColor.RED + "Bet must be a positive number.");
                    return true;
                }
                if (economy == null) {
                    p.sendMessage(ChatColor.RED + "No economy plugin is installed, so bets are unavailable.");
                    return true;
                }
                if (!economy.has(p, bet)) {
                    p.sendMessage(ChatColor.RED + "You do not have enough money for that bet. Balance: " + economy.format(economy.getBalance(p)));
                    return true;
                }
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                p.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You cannot challenge yourself.");
                return true;
            }
            if (activeGames.containsKey(p.getUniqueId()) || activeGames.containsKey(target.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "Either you or the target is already in a game.");
                return true;
            }
            challengeManager.createChallenge(p, target, minutes, bet);
            if (bet > 0) {
                p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + " (" + minutes + " min, bet " + economy.format(bet) + "). Expires in 30s.");
                target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a " + minutes + "m chess game for " + ChatColor.GOLD + economy.format(bet) + ChatColor.YELLOW + "! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
            } else {
                p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + " (" + minutes + " min). Expires in 30s.");
                target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a " + minutes + "m chess game! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 2) {
                return Arrays.asList("1", "3", "5", "10");
            }
            return Collections.emptyList();
        }
    }

    private class ChessAcceptCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player accepter = (Player) sender;
            ChallengeManager.Challenge challenge = challengeManager.acceptChallenge(accepter);
            if (challenge == null) {
                accepter.sendMessage(ChatColor.RED + "You have no pending challenges.");
                return true;
            }
            Player challenger = Bukkit.getPlayer(challenge.challenger);
            if (challenger == null || !challenger.isOnline()) {
                accepter.sendMessage(ChatColor.RED + "Challenger is no longer online.");
                return true;
            }
            double bet = challenge.bet;
            if (bet > 0) {
                if (economy == null) {
                    accepter.sendMessage(ChatColor.RED + "The economy is unavailable; the bet cannot be placed.");
                    challenger.sendMessage(ChatColor.RED + "The challenge was cancelled because the economy is unavailable.");
                    return true;
                }
                if (!economy.has(challenger, bet)) {
                    accepter.sendMessage(ChatColor.RED + "The challenger no longer has enough money to cover the bet.");
                    challenger.sendMessage(ChatColor.RED + "You cannot afford the " + economy.format(bet) + " bet.");
                    return true;
                }
                if (!economy.has(accepter, bet)) {
                    accepter.sendMessage(ChatColor.RED + "You do not have enough money to cover the " + economy.format(bet) + " bet.");
                    challenger.sendMessage(ChatColor.RED + accepter.getName() + " could not afford the bet and the challenge was cancelled.");
                    return true;
                }
                economy.withdrawPlayer(challenger, bet);
                economy.withdrawPlayer(accepter, bet);
            }
            ChessGame game = new ChessGame(ChessPlugin.this, challenger, accepter, challenge.minutes);
            game.bet = bet;
            activeGames.put(challenger.getUniqueId(), game);
            activeGames.put(accepter.getUniqueId(), game);
            game.start();
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessRatingCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            Player target;
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Players only. Usage: /chessrating <player>");
                    return true;
                }
                target = (Player) sender;
            } else if (args.length == 1) {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(ChatColor.RED + "Player not found or offline.");
                    return true;
                }
            } else {
                sender.sendMessage(ChatColor.RED + "Usage: /chessrating [player]");
                return true;
            }
            sender.sendMessage(ChatColor.AQUA + target.getName() + "'s chess ratings:");
            sender.sendMessage(ChatColor.GRAY + "Bullet (1m): " + ratingLine(target, "bullet"));
            sender.sendMessage(ChatColor.GRAY + "Blitz (3-5m): " + ratingLine(target, "blitz"));
            sender.sendMessage(ChatColor.GRAY + "Rapid (10m): " + ratingLine(target, "rapid"));
            return true;
        }

        private String ratingLine(Player p, String category) {
            return eloManager.getRating(p.getUniqueId(), category) + " (" + eloManager.getGames(p.getUniqueId(), category) + " games)";
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class ChessDenyCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player denier = (Player) sender;
            UUID challenger = challengeManager.denyChallenge(denier);
            if (challenger == null) {
                denier.sendMessage(ChatColor.RED + "You have no pending challenges.");
                return true;
            }
            Player c = Bukkit.getPlayer(challenger);
            if (c != null && c.isOnline()) {
                c.sendMessage(ChatColor.RED + denier.getName() + " denied your challenge.");
            }
            denier.sendMessage(ChatColor.YELLOW + "Challenge denied.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessToggleCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("chess.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to toggle the chess plugin.");
                return true;
            }
            enabled = !enabled;
            getServer().broadcastMessage(ChatColor.AQUA + "Chess is now " + (enabled ? "enabled" : "disabled") + ".");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessAiCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (!enabled) {
                p.sendMessage(ChatColor.RED + "Chess is currently disabled.");
                return true;
            }
            int minutes = 3;
            boolean humanWhite = true;
            ChessAI.Difficulty difficulty = ChessAI.Difficulty.MEDIUM;
            if (args.length > 3) {
                p.sendMessage(ChatColor.RED + "Usage: /chessai [easy|casual|medium|hard|extreme] [1|3|5|10] [white|black]");
                return true;
            }
            for (String a : args) {
                if (a.equalsIgnoreCase("white")) {
                    humanWhite = true;
                } else if (a.equalsIgnoreCase("black")) {
                    humanWhite = false;
                } else if (a.matches("1|3|5|10")) {
                    minutes = Integer.parseInt(a);
                } else {
                    ChessAI.Difficulty d = ChessAI.Difficulty.fromString(a);
                    if (d == null) {
                        p.sendMessage(ChatColor.RED + "Usage: /chessai [easy|casual|medium|hard|extreme] [1|3|5|10] [white|black]");
                        return true;
                    }
                    difficulty = d;
                }
            }
            if (activeGames.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are already in a game.");
                return true;
            }
            ChessGame game = new ChessGame(ChessPlugin.this, p, p, minutes);
            game.whiteIsAI = !humanWhite;
            game.blackIsAI = humanWhite;
            game.aiDifficulty = difficulty;
            activeGames.put(p.getUniqueId(), game);
            game.start();
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length <= 3) {
                String prefix = args[args.length - 1].toLowerCase();
                return Arrays.asList("easy", "casual", "medium", "hard", "extreme", "1", "3", "5", "10", "white", "black").stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class ChessSpectateCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (!enabled) {
                p.sendMessage(ChatColor.RED + "Chess is currently disabled.");
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Usage: /chessspectate <player>");
                return true;
            }
            if (activeGames.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are already in a game.");
                return true;
            }
            if (spectators.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are already spectating a game. Close its board first.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                p.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }
            ChessGame g = activeGames.get(target.getUniqueId());
            if (g == null) {
                p.sendMessage(ChatColor.RED + target.getName() + " is not in a chess game.");
                return true;
            }
            g.addSpectator(p);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class ChessPuzzleCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (!enabled) {
                p.sendMessage(ChatColor.RED + "Chess is currently disabled.");
                return true;
            }
            if (args.length > 1) {
                p.sendMessage(ChatColor.RED + "Usage: /chesspuzzle");
                return true;
            }
            if (activeGames.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are already in a game. Close your current board first.");
                return true;
            }
            Puzzle puzzle = Puzzle.today();
            ChessGame game = new ChessGame(ChessPlugin.this, p, p, 10);
            game.puzzleMode = true;
            game.humanIsWhite = puzzle.whiteToMove;
            game.whiteIsAI = !puzzle.whiteToMove;
            game.blackIsAI = puzzle.whiteToMove;
            game.puzzleDay = puzzle.day;
            game.puzzleTitle = puzzle.title;
            game.puzzleSolution = puzzle.solution;
            game.board.setPosition(puzzle.pieces, puzzle.whiteToMove);
            activeGames.put(p.getUniqueId(), game);
            game.start();
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    // Events ------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            // delegate to game; always cancel so no items are moved in board area
            e.setCancelled(true);
            g.onInventoryClick(p, e);
            return;
        }
        ChessGame sg = spectators.get(p.getUniqueId());
        if (sg != null) {
            e.setCancelled(true);
            sg.handleSpectatorClick(p, e);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        HumanEntity he = e.getPlayer();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            g.onClose(p);
        }
        ChessGame sg = spectators.get(p.getUniqueId());
        if (sg != null) {
            sg.removeSpectator(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            g.onPlayerQuit(p);
        }
        ChessGame sg = spectators.get(p.getUniqueId());
        if (sg != null) {
            sg.removeSpectator(p);
        }
    }

    void removeGame(ChessGame g) {
        activeGames.remove(g.white.getUniqueId());
        activeGames.remove(g.black.getUniqueId());
    }

    // Challenge manager -------------------------------------------------------

    static class ChallengeManager {
        private final Map<UUID, Challenge> pending = new ConcurrentHashMap<>();
        private final JavaPlugin plugin;

        ChallengeManager(JavaPlugin plugin) {
            this.plugin = plugin;
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                long now = System.currentTimeMillis();
                List<UUID> toRemove = new ArrayList<>();
                for (Map.Entry<UUID, Challenge> e : pending.entrySet()) {
                    if (e.getValue().expiresAt <= now) {
                        Player c = plugin.getServer().getPlayer(e.getValue().challenger);
                        Player t = plugin.getServer().getPlayer(e.getKey());
                        if (c != null && c.isOnline()) c.sendMessage(ChatColor.RED + "Your challenge to " + (t != null ? t.getName() : "player") + " expired.");
                        if (t != null && t.isOnline()) t.sendMessage(ChatColor.RED + "Challenge from " + (c != null ? c.getName() : "player") + " expired.");
                        toRemove.add(e.getKey());
                    }
                }
                toRemove.forEach(pending::remove);
            }, 20L, 20L);
        }

        void createChallenge(Player challenger, Player target, int minutes, double bet) {
            pending.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), System.currentTimeMillis() + 30_000L, minutes, bet));
        }

        Challenge acceptChallenge(Player target) {
            return pending.remove(target.getUniqueId());
        }

        UUID denyChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            if (c == null) return null;
            return c.challenger;
        }

        static class Challenge {
            final UUID challenger;
            final long expiresAt;
            final int minutes;
            final double bet;
            Challenge(UUID challenger, long expiresAt, int minutes, double bet) { this.challenger = challenger; this.expiresAt = expiresAt; this.minutes = minutes; this.bet = bet; }
        }
    }

    // Elo ratings (chess.com formula), persisted to ratings.yml
    static class EloManager {
        private static final int DEFAULT_RATING = 1200;
        private final JavaPlugin plugin;
        private final Map<UUID, Map<String, Rating>> ratings = new ConcurrentHashMap<>();

        EloManager(JavaPlugin plugin) {
            this.plugin = plugin;
            plugin.getDataFolder().mkdirs();
            load();
        }

        int getRating(UUID id, String category) {
            Map<String, Rating> m = ratings.get(id);
            Rating r = m == null ? null : m.get(category);
            return r == null ? DEFAULT_RATING : r.rating;
        }

        int getGames(UUID id, String category) {
            Map<String, Rating> m = ratings.get(id);
            Rating r = m == null ? null : m.get(category);
            return r == null ? 0 : r.games;
        }

        // chess.com formula: newRating = old + K * (score - expected)
        RatingChange[] applyResult(UUID a, UUID b, double scoreA, String category) {
            int ra = getRating(a, category);
            int rb = getRating(b, category);
            double expectedA = 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / 400.0));
            double expectedB = 1.0 - expectedA;
            double scoreB = 1.0 - scoreA;
            int newRa = (int) Math.round(ra + kFactor(a, category) * (scoreA - expectedA));
            int newRb = (int) Math.round(rb + kFactor(b, category) * (scoreB - expectedB));
            setRating(a, category, newRa);
            setRating(b, category, newRb);
            save();
            return new RatingChange[]{ new RatingChange(ra, newRa), new RatingChange(rb, newRb) };
        }

        // chess.com K-factors: 64 for new players (<30 games), 32 below 2400, 16 above
        int kFactor(UUID id, String category) {
            if (getGames(id, category) < 30) return 64;
            if (getRating(id, category) < 2400) return 32;
            return 16;
        }

        void setRating(UUID id, String category, int rating) {
            Map<String, Rating> m = ratings.computeIfAbsent(id, k -> new HashMap<>());
            Rating r = m.computeIfAbsent(category, k -> new Rating());
            r.rating = rating;
            r.games++;
        }

        void load() {
            File f = new File(plugin.getDataFolder(), "ratings.yml");
            if (!f.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String key : cfg.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Map<String, Rating> m = new HashMap<>();
                for (String cat : new String[]{"bullet", "blitz", "rapid"}) {
                    Rating r = new Rating();
                    r.rating = cfg.getInt(key + "." + cat + ".rating", DEFAULT_RATING);
                    r.games = cfg.getInt(key + "." + cat + ".games", 0);
                    m.put(cat, r);
                }
                ratings.put(id, m);
            }
        }

        void save() {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, Map<String, Rating>> en : ratings.entrySet()) {
                String base = en.getKey().toString();
                for (Map.Entry<String, Rating> e : en.getValue().entrySet()) {
                    cfg.set(base + "." + e.getKey() + ".rating", e.getValue().rating);
                    cfg.set(base + "." + e.getKey() + ".games", e.getValue().games);
                }
            }
            try {
                cfg.save(new File(plugin.getDataFolder(), "ratings.yml"));
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save ratings: " + ex.getMessage());
            }
        }

        static class Rating {
            int rating = DEFAULT_RATING;
            int games = 0;
        }

        static class RatingChange {
            final int oldRating, newRating, delta;
            RatingChange(int oldRating, int newRating) {
                this.oldRating = oldRating;
                this.newRating = newRating;
                this.delta = newRating - oldRating;
            }
        }
    }

    // Daily puzzles: classic mate-in-one motifs (pool rotates by calendar day)
    static class Puzzle {
        final String title;
        final boolean whiteToMove;
        final String solution;
        final String[] pieces; // entries like "WKg1" = color + type + square
        int day = 0; // index into the pool, assigned by today()

        Puzzle(String title, boolean whiteToMove, String solution, String[] pieces) {
            this.title = title;
            this.whiteToMove = whiteToMove;
            this.solution = solution;
            this.pieces = pieces;
        }

        static final List<Puzzle> PUZZLES = Arrays.asList(
            new Puzzle("Smothered Mate", true, "Nf7#", new String[]{"WKg1", "WNg5", "BKh8", "BRg8", "BPg7", "BPh7"}),
            new Puzzle("Back-Rank Queen", true, "Qd8#", new String[]{"WKg1", "WQd1", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Back-Rank Rook", true, "Re8#", new String[]{"WKg1", "WRe1", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Corridor Rook", true, "Rb8#", new String[]{"WKg1", "WRb1", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Cornered Queen", true, "Qd8#", new String[]{"WKg1", "WQd3", "BKh8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Cornered Rook", true, "Rb8#", new String[]{"WKg1", "WRb1", "BKh8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Forked Queen", true, "Qe8#", new String[]{"WKg1", "WQe2", "WBc4", "WNf3", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Knight Escort", true, "Qe8#", new String[]{"WKg1", "WQe1", "WNf3", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Long Diagonal", true, "Qa8#", new String[]{"WKg1", "WQf3", "WBc4", "BKg8", "BPf7", "BPg7", "BPh7"}),
            new Puzzle("Arabian Mate", true, "Rd8#", new String[]{"WKg1", "WRd1", "WNf6", "BKh8", "BPg7", "BPh7"}),
            new Puzzle("Smothered Mate (Black)", false, "Nf7#", new String[]{"BKg1", "BNg5", "WKh8", "WRg8", "WPg7", "WPh7"}),
            new Puzzle("Back-Rank Queen (Black)", false, "Qd1#", new String[]{"WKg1", "WPf2", "WPg2", "WPh2", "BKg8", "BQd8"})
        );

        static Puzzle today() {
            long epochDay = LocalDate.now(ZoneOffset.UTC).toEpochDay();
            int idx = (int) Math.floorMod(epochDay, PUZZLES.size());
            Puzzle p = PUZZLES.get(idx);
            p.day = idx;
            return p;
        }
    }

    // Chess game (engine + combined inventory GUI) ----------------------------

    static class ChessGame {
        enum GameResult { WHITE_WIN, BLACK_WIN, DRAW, ABANDONED }

        final ChessPlugin plugin;
        final Player white;
        final Player black;
        final ChessBoard board;
        final int minutes;
        final String title;
        UUID selectedPlayer = null;
        int selectedX = -1, selectedY = -1;
        // track whether player's inventory has been replaced (so we know to restore)
        final Map<UUID, SavedInventory> saved = new HashMap<>();
        // timers
        int whiteTime;
        int blackTime;
        BukkitTask timerTask;
        boolean running = false;
        // en passant: last double pawn end coordinate or null
        int[] lastDoublePawn = null;
        // pending pawn promotion (move awaiting piece choice)
        ChessMove pendingPromotion = null;
        UUID promotionPlayer = null;
        // AI sides: in AI games the human's Player object is reused for the AI slot
        boolean whiteIsAI = false;
        boolean blackIsAI = false;
        boolean aiThinking = false;
        ChessAI.Difficulty aiDifficulty = ChessAI.Difficulty.MEDIUM;
        // currency bet (each player's stake; winner takes both) - 0 = no bet
        double bet = 0;
        // spectators watching this game (player UUID -> player)
        final Map<UUID, Player> spectators = new ConcurrentHashMap<>();
        String spectatorTitle = null;
        // daily puzzle mode (mate in one)
        boolean puzzleMode = false;
        boolean humanIsWhite = true;
        int puzzleDay = 0;
        String puzzleTitle = "";
        String puzzleSolution = "";
        int puzzleAttempts = 0;

        ChessGame(ChessPlugin plugin, Player white, Player black, int minutes) {
            this.plugin = plugin;
            this.white = white;
            this.black = black;
            this.board = new ChessBoard();
            this.minutes = minutes;
            this.whiteTime = minutes * 60;
            this.blackTime = minutes * 60;
            this.title = ChatColor.DARK_GREEN + "Chess (" + minutes + "m " + timeControlName().toLowerCase() + ")";
        }

        String timeControlName() {
            if (minutes <= 1) return "Bullet";
            if (minutes <= 5) return "Blitz";
            return "Rapid";
        }

        static String categoryFor(int minutes) {
            if (minutes <= 1) return "bullet";
            if (minutes <= 5) return "blitz";
            return "rapid";
        }

        void start() {
            if (puzzleMode) {
                // board already set by the puzzle command; no clock, ratings, or AI
                running = true;
                openFor(white);
                sendBoth(ChatColor.LIGHT_PURPLE + "Daily Puzzle #" + (puzzleDay + 1) + ": " + puzzleTitle + " - find mate in one!");
                sendBoth(ChatColor.GRAY + "You play " + (humanIsWhite ? "White" : "Black") + ". Wrong moves are not played. 'Hint' reveals the solution.");
                updateAllInventories();
                return;
            }
            board.resetInitialPosition();
            running = true;
            if (!whiteIsAI) openFor(white);
            if (!blackIsAI) openFor(black);
            timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            String cat = categoryFor(minutes);
            sendBoth(ChatColor.GREEN + "Game started! " + timeControlName() + " (" + minutes + "m).");
            if (bet > 0 && plugin.getEconomy() != null) {
                sendBoth(ChatColor.GOLD + "Bet: " + plugin.getEconomy().format(bet) + " each. Winner takes all.");
            }
            if (whiteIsAI || blackIsAI) {
                sendBoth(ChatColor.GRAY + "You are " + (whiteIsAI ? "Black" : "White") + " vs AI (" + aiDifficulty.label + "). Ratings are not affected.");
            } else {
                sendBoth(ChatColor.GRAY + "White " + white.getName() + " [" + plugin.eloManager.getRating(white.getUniqueId(), cat) + "] vs Black " + black.getName() + " [" + plugin.eloManager.getRating(black.getUniqueId(), cat) + "]");
            }
            updateAllInventories();
            maybeScheduleAI();
        }

        void tick() {
            if (!running) return;
            if (puzzleMode) return;
            if (board.whiteToMove) {
                whiteTime--;
                if (whiteTime <= 0) endGame(sideName(false) + " wins on time", GameResult.BLACK_WIN);
            } else {
                blackTime--;
                if (blackTime <= 0) endGame(sideName(true) + " wins on time", GameResult.WHITE_WIN);
            }
            if (running) maybeScheduleAI();
            updateAllInventories();
        }

        void endGame(String reason) {
            endGame(reason, GameResult.ABANDONED);
        }

        void endGame(String reason, GameResult result) {
            if (!running) return;
            running = false;
            if (timerTask != null) timerTask.cancel();
            pendingPromotion = null;
            promotionPlayer = null;
            sendBoth(ChatColor.YELLOW + "Game ended: " + reason);
            if (result != GameResult.ABANDONED) {
                applyElo(result);
            }
            resolveBet(result);
            // kick spectators out (closes their boards -> triggers removeSpectator via close event)
            for (Player sp : new ArrayList<>(spectators.values())) {
                if (sp.isOnline()) sp.closeInventory();
                removeSpectator(sp);
            }
            // close GUIs (will trigger onClose -> restore)
            for (Player p : Arrays.asList(white, black)) {
                if (p.isOnline()) p.closeInventory();
            }
            // ensure saved inventories restored (in case close events weren't processed)
            for (UUID id : new ArrayList<>(saved.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) saved.get(id).restore(p);
                saved.remove(id);
            }
            plugin.removeGame(this);
        }

        void resolveBet(GameResult result) {
            if (bet <= 0) return;
            Economy econ = plugin.getEconomy();
            if (econ == null) {
                plugin.getLogger().warning("Cannot settle chess bet of " + bet + ": economy unavailable.");
                return;
            }
            OfflinePlayer ow = Bukkit.getOfflinePlayer(white.getUniqueId());
            OfflinePlayer ob = Bukkit.getOfflinePlayer(black.getUniqueId());
            if (result == GameResult.WHITE_WIN) {
                econ.depositPlayer(ow, bet * 2);
                sendBoth(ChatColor.GOLD + "Bet settled: " + econ.format(bet * 2) + " paid to " + sideName(true) + ".");
            } else if (result == GameResult.BLACK_WIN) {
                econ.depositPlayer(ob, bet * 2);
                sendBoth(ChatColor.GOLD + "Bet settled: " + econ.format(bet * 2) + " paid to " + sideName(false) + ".");
            } else {
                econ.depositPlayer(ow, bet);
                econ.depositPlayer(ob, bet);
                sendBoth(ChatColor.GRAY + "Bet refunded: " + econ.format(bet) + " each.");
            }
        }

        void applyElo(GameResult result) {
            if (whiteIsAI || blackIsAI) return;
            String cat = categoryFor(minutes);
            if (result == GameResult.DRAW) {
                EloManager.RatingChange[] ch = plugin.eloManager.applyResult(white.getUniqueId(), black.getUniqueId(), 0.5, cat);
                sendRatingChange(white, ch[0]);
                sendRatingChange(black, ch[1]);
            } else if (result == GameResult.WHITE_WIN) {
                EloManager.RatingChange[] ch = plugin.eloManager.applyResult(white.getUniqueId(), black.getUniqueId(), 1.0, cat);
                sendRatingChange(white, ch[0]);
                sendRatingChange(black, ch[1]);
            } else if (result == GameResult.BLACK_WIN) {
                EloManager.RatingChange[] ch = plugin.eloManager.applyResult(black.getUniqueId(), white.getUniqueId(), 1.0, cat);
                sendRatingChange(black, ch[0]);
                sendRatingChange(white, ch[1]);
            }
        }

        void sendRatingChange(Player p, EloManager.RatingChange c) {
            if (p == null || !p.isOnline() || c == null) return;
            String sign = c.delta >= 0 ? "+" : "";
            p.sendMessage(ChatColor.GRAY + "Rating (" + timeControlName().toLowerCase() + "): " + c.oldRating + " -> " + c.newRating + " (" + sign + c.delta + ")");
        }

        void onPlayerQuit(Player p) {
            if (!running) return;
            if (puzzleMode) {
                endGame("You left the daily puzzle.", GameResult.ABANDONED);
                return;
            }
            if (p.getUniqueId().equals(white.getUniqueId())) {
                endGame(sideName(false) + " wins (opponent disconnected)", GameResult.BLACK_WIN);
            } else {
                endGame(sideName(true) + " wins (opponent disconnected)", GameResult.WHITE_WIN);
            }
        }

        void onClose(Player p) {
            // closing a puzzle board counts as giving up (prevents orphaned games)
            if (puzzleMode && running) {
                giveUpPuzzle(p);
                return;
            }
            // discard any pending promotion if the promoting player leaves the view
            if (pendingPromotion != null && promotionPlayer != null && promotionPlayer.equals(p.getUniqueId())) {
                pendingPromotion = null;
                promotionPlayer = null;
            }
            // restore player's inventory when they close the board; game continues
            SavedInventory s = saved.remove(p.getUniqueId());
            if (s != null) {
                s.restore(p);
                p.sendMessage(ChatColor.GRAY + "Your inventory has been restored. Reopen the board to continue.");
            }
        }

        void sendBoth(String s) {
            if (!whiteIsAI && white.isOnline()) white.sendMessage(s);
            if (!blackIsAI && black.isOnline()) black.sendMessage(s);
        }

        String sideName(boolean isWhite) {
            boolean ai = isWhite ? whiteIsAI : blackIsAI;
            Player p = isWhite ? white : black;
            return ai ? "AI" : p.getName();
        }

        boolean aiToMove() {
            return (board.whiteToMove && whiteIsAI) || (!board.whiteToMove && blackIsAI);
        }

        void maybeScheduleAI() {
            if (!running) return;
            if (aiThinking) return;
            if (!aiToMove()) return;
            aiThinking = true;
            plugin.getServer().getScheduler().runTaskLater(plugin, this::doAIMove, 5L);
        }

        void doAIMove() {
            if (!running) return;
            if (!aiToMove()) return;
            // aiThinking stays true until the move is applied, so tick() cannot re-schedule
            final ChessBoard snapshot = board.copy();
            final int[] ldp = lastDoublePawn == null ? null : lastDoublePawn.clone();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                ChessMove best = new ChessAI().findBestMove(snapshot, ldp, aiDifficulty);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    aiThinking = false;
                    if (!running) return;
                    if (!aiToMove()) return;
                    if (best == null) {
                        sendBoth(ChatColor.RED + "AI could not find a move.");
                        return;
                    }
                    sendBoth(ChatColor.GRAY + "AI played " + squareName(best.fromX, best.fromY) + "->" + squareName(best.toX, best.toY)
                            + (best.promotion ? " (promotes to " + best.promotionTo.name().toLowerCase() + ")" : "") + ".");
                    completeMove(best, best.promotionTo);
                });
            });
        }

        // Build and open combined view for player
        void openFor(Player p) {
            openView(p, viewTitle(), false);
        }

        // GUI title for the current view (puzzles use their own header)
        String viewTitle() {
            if (puzzleMode) return ChatColor.LIGHT_PURPLE + "Daily Puzzle #" + (puzzleDay + 1);
            return title;
        }

        // Build and open combined view for a player or spectator (read-only board, own controls)
        void openView(Player p, String viewTitle, boolean spectator) {
            if (!p.isOnline()) return;
            // Save current inventory if we haven't already
            if (!saved.containsKey(p.getUniqueId())) {
                saved.put(p.getUniqueId(), SavedInventory.save(p));
            }
            // Build top inventory (54)
            Inventory inv = Bukkit.createInventory(null, 54, viewTitle);
            // fill top 4 ranks into chest top:
            // top area: guiRow 0..3, col 0..7 -> boardRow = guiRow, boardCol = col
            for (int guiRow = 0; guiRow < 4; guiRow++) {
                for (int guiCol = 0; guiCol < 8; guiCol++) {
                    int slot = guiRow * 9 + guiCol; // col 8 reserved for controls
                    ChessPiece piece = board.getPiece(guiRow, guiCol);
                    inv.setItem(slot, toItemFor(piece));
                }
                // control column at col 8
                int ctrlSlot = guiRow * 9 + 8;
                inv.setItem(ctrlSlot, createInfo(infoText(guiRow, spectator)));
            }
            // bottom chest row: controls
            inv.setItem(45, createButton(Material.ARROW, ChatColor.GREEN + "Flip"));
            if (spectator) {
                inv.setItem(51, createButton(Material.PAPER, ChatColor.AQUA + "Info"));
                inv.setItem(53, createButton(Material.OAK_SIGN, ChatColor.GRAY + "Close"));
            } else if (puzzleMode) {
                inv.setItem(46, createButton(Material.BARRIER, ChatColor.RED + "Give Up"));
                inv.setItem(47, createButton(Material.CLOCK, ChatColor.GOLD + "Hint"));
                inv.setItem(51, createButton(Material.PAPER, ChatColor.AQUA + "Info"));
                inv.setItem(53, createButton(Material.OAK_SIGN, ChatColor.GRAY + "Close"));
            } else {
                inv.setItem(46, createButton(Material.BARRIER, ChatColor.RED + "Resign"));
                inv.setItem(47, createButton(Material.CLOCK, ChatColor.GOLD + "Time"));
                inv.setItem(51, createButton(Material.PAPER, ChatColor.AQUA + "Info"));
                inv.setItem(53, createButton(Material.OAK_SIGN, ChatColor.GRAY + "Close"));
            }

            // Apply bottom 4 ranks into player's own visible inventory slots:
            // bottomRow 0..3 maps to board rows 4..7
            // PlayerInventory slot mapping:
            // hotbar: 0..8 (we'll use 0..7)
            // main rows: 9..17, 18..26, 27..35
            PlayerInventory pinv = p.getInventory();
            // prepare a clean copy of the saved inventory contents as base then overwrite the controlled slots
            // Clear the 32 slots we will write to, then set them
            // bottomRow 0 -> main row 1 (slots 9..16)
            // bottomRow 1 -> main row 2 (slots 18..25)
            // bottomRow 2 -> main row 3 (slots 27..34)
            // bottomRow 3 -> hotbar (slots 0..7)
            // For each bottomRow
            for (int br = 0; br < 4; br++) {
                int boardRow = 4 + br;
                for (int col = 0; col < 8; col++) {
                    ItemStack item = toItemFor(board.getPiece(boardRow, col));
                    int invIndex;
                    if (br == 3) { // hotbar
                        invIndex = col; // 0..7
                    } else {
                        invIndex = 9 + br * 9 + col; // br 0 -> 9..16, br1->18..25, br2->27..34 (works since 9 + br*9)
                        // Note: for br=0 -> 9 + 0 + col = 9..16 ; br=1 -> 18..25 ; br=2 -> 27..34
                    }
                    pinv.setItem(invIndex, item);
                }
            }

            // Finally open the chest for the player
            p.openInventory(inv);
        }

        // Spectators ----------------------------------------------------------

        void addSpectator(Player p) {
            if (!running) return;
            if (spectators.containsKey(p.getUniqueId())) return;
            String base = "Spectating: " + sideName(true) + " vs " + sideName(false);
            if (base.length() > 30) base = base.substring(0, 30);
            spectatorTitle = ChatColor.DARK_AQUA + base;
            spectators.put(p.getUniqueId(), p);
            plugin.spectators.put(p.getUniqueId(), this);
            openView(p, spectatorTitle, true);
            p.sendMessage(ChatColor.AQUA + "You are now spectating " + sideName(true) + " vs " + sideName(false)
                    + (bet > 0 && plugin.getEconomy() != null ? " (bet " + plugin.getEconomy().format(bet) + ")" : "") + ".");
            p.sendMessage(ChatColor.GRAY + "Close the board to stop spectating.");
        }

        void removeSpectator(Player p) {
            if (!spectators.containsKey(p.getUniqueId()) && !plugin.spectators.containsKey(p.getUniqueId())) return;
            spectators.remove(p.getUniqueId());
            plugin.spectators.remove(p.getUniqueId());
            SavedInventory s = saved.remove(p.getUniqueId());
            if (s != null && p.isOnline()) s.restore(p);
            p.sendMessage(ChatColor.GRAY + "You are no longer spectating.");
        }

        void handleSpectatorClick(Player p, InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw >= 45 && raw <= 53) {
                ItemStack cur = e.getCurrentItem();
                if (cur == null || !cur.hasItemMeta()) return;
                String name = ChatColor.stripColor(cur.getItemMeta().getDisplayName());
                switch (name) {
                    case "Flip":
                        openView(p, spectatorTitle, true);
                        return;
                    case "Info":
                        p.sendMessage(ChatColor.GRAY + "Spectating " + sideName(true) + " vs " + sideName(false)
                                + " | " + formatTime(whiteTime) + " - " + formatTime(blackTime)
                                + (bet > 0 && plugin.getEconomy() != null ? " | Bet: " + plugin.getEconomy().format(bet) : ""));
                        return;
                    case "Close":
                        p.closeInventory();
                        return;
                }
            }
            // all other clicks on the spectator board are ignored (event already cancelled)
        }

        private ItemStack createButton(Material mat, String name) {
            ItemStack it = new ItemStack(mat);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(name);
            it.setItemMeta(m);
            return it;
        }

        private ItemStack createInfo(String text) {
            ItemStack info = new ItemStack(Material.PAPER);
            ItemMeta meta = info.getItemMeta();
            meta.setDisplayName(text);
            info.setItemMeta(meta);
            return info;
        }

        private String infoText(int guiRow, boolean spectator) {
            if (puzzleMode) {
                if (guiRow == 0) return ChatColor.LIGHT_PURPLE + "Daily Puzzle #" + (puzzleDay + 1);
                if (guiRow == 1) return ChatColor.AQUA + puzzleTitle;
                if (guiRow == 2) return ChatColor.YELLOW + "Turn: " + (board.whiteToMove ? "White" : "Black");
                return ChatColor.GRAY + "Mate in 1 | Tries: " + puzzleAttempts;
            }
            if (guiRow == 0) return ChatColor.AQUA + "White: " + (spectator ? sideName(true) : formatTime(whiteTime));
            if (guiRow == 1) return ChatColor.AQUA + "Black: " + (spectator ? sideName(false) : formatTime(blackTime));
            if (guiRow == 2) return ChatColor.YELLOW + "Turn: " + (board.whiteToMove ? "White" : "Black");
            return spectator ? ChatColor.GRAY + "Spectating" : ChatColor.GRAY + "" + minutes + "m " + timeControlName();
        }

        private String formatTime(int secs) {
            int m = secs / 60;
            int s = secs % 60;
            return String.format("%d:%02d", m, s);
        }

        private ItemStack toItemFor(ChessPiece p) {
            if (p == null) return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            Material mat = materialFor(p);
            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName((p.color == Color.WHITE ? ChatColor.WHITE : ChatColor.DARK_GRAY) + p.type.name());
            meta.setLore(Collections.singletonList("x" + p.x + " y" + p.y));
            meta.getPersistentDataContainer().set(ChessPlugin.PIECE_KEY, PersistentDataType.STRING, p.toShortString());
            it.setItemMeta(meta);
            return it;
        }

        private Material materialFor(ChessPiece p) {
            switch (p.type) {
                case PAWN: return p.color == Color.WHITE ? Material.OAK_FENCE : Material.DARK_OAK_FENCE;
                case ROOK: return p.color == Color.WHITE ? Material.IRON_BLOCK : Material.BLACKSTONE;
                case KNIGHT: return p.color == Color.WHITE ? Material.SADDLE : Material.LEATHER_HORSE_ARMOR;
                case BISHOP: return p.color == Color.WHITE ? Material.BRICK : Material.NETHER_BRICKS;
                case QUEEN: return p.color == Color.WHITE ? Material.GOLD_BLOCK : Material.CRYING_OBSIDIAN;
                case KING: return p.color == Color.WHITE ? Material.DIAMOND_BLOCK : Material.OBSIDIAN;
                default: return Material.PAPER;
            }
        }

        // Handle clicks in combined view
        void onInventoryClick(Player p, InventoryClickEvent e) {
            if (!running) return;
            // while a promotion is pending for this player, all their clicks go to the promotion picker
            if (pendingPromotion != null && promotionPlayer != null && promotionPlayer.equals(p.getUniqueId())) {
                handlePromotionClick(p, e);
                return;
            }
            // Identify rawSlot and whether it's a top chest board square or player inventory board square
            int raw = e.getRawSlot();
            // First handle chest control buttons (they are in top chest area)
            if (raw < 54) {
                int r = raw / 9;
                int c = raw % 9;
                // control column at c==8 (info/time)
                if (c == 8 && r >= 0 && r < 4) {
                    ItemStack current = e.getCurrentItem();
                    if (current == null || !current.hasItemMeta()) return;
                    String name = ChatColor.stripColor(current.getItemMeta().getDisplayName());
                    // nothing special for these info slots
                    return;
                }
                // bottom control slots row (45..53)
                if (raw >= 45 && raw <= 53) {
                    ItemStack cur = e.getCurrentItem();
                    if (cur == null || !cur.hasItemMeta()) return;
                    String name = ChatColor.stripColor(cur.getItemMeta().getDisplayName());
                    switch (name) {
                        case "Flip":
                            // flip view by restoring and reopening (simpler: just reopen)
                            openFor(p);
                            return;
                        case "Give Up":
                            giveUpPuzzle(p);
                            return;
                        case "Hint":
                            p.sendMessage(ChatColor.AQUA + "Hint: play " + puzzleSolution + ".");
                            return;
                        case "Resign":
                            boolean whiteSide = p.getUniqueId().equals(white.getUniqueId());
                            endGame(p.getName() + " resigned. " + (whiteSide ? sideName(false) : sideName(true)) + " wins.",
                                    whiteSide ? GameResult.BLACK_WIN : GameResult.WHITE_WIN);
                            return;
                        case "Time":
                            p.sendMessage(ChatColor.AQUA + "White: " + formatTime(whiteTime) + " | Black: " + formatTime(blackTime));
                            return;
                        case "Info":
                            p.sendMessage(ChatColor.GRAY + "8x8 combined board. Close to restore inventory.");
                            return;
                        case "Close":
                            p.closeInventory();
                            return;
                    }
                }
                // chest board region: rows 0..3, cols 0..7
                int guiRow = raw / 9;
                int guiCol = raw % 9;
                if (guiRow >= 0 && guiRow < 4 && guiCol >= 0 && guiCol < 8) {
                    int boardRow = guiRow;
                    int boardCol = guiCol;
                    handleBoardClick(p, boardRow, boardCol);
                    return;
                } else {
                    // clicking non-board chest slot -> ignore (cancelled already)
                    return;
                }
            } else {
                // player inventory region: rawSlot >= 54 maps to player inventory slots 0..35
                // This must mirror the layout used in openFor():
                //   hotbar slots 0..7            -> board row 7
                //   main row 1 slots 9..16       -> board row 4
                //   main row 2 slots 18..25      -> board row 5
                //   main row 3 slots 27..34      -> board row 6
                int invIndex = raw - 54;
                int brow, bcol;
                if (invIndex >= 0 && invIndex <= 7) {
                    brow = 3;
                    bcol = invIndex;
                } else if (invIndex >= 9 && invIndex <= 34) {
                    int t = invIndex - 9;
                    brow = t / 9;
                    bcol = t % 9;
                } else {
                    // slot 8, 17, 26, 35 and anything beyond are not part of the board area
                    return;
                }
                if (brow < 0 || brow > 3 || bcol < 0 || bcol > 7) return;
                int boardRow = 4 + brow;
                int boardCol = bcol;
                handleBoardClick(p, boardRow, boardCol);
                return;
            }
        }

        private void handleBoardClick(Player player, int br, int bc) {
            if (!running) return;
            if (aiToMove()) {
                player.sendMessage(ChatColor.RED + "The AI is thinking...");
                return;
            }
            ChessPiece clicked = board.getPiece(br, bc);
            // if no selection started
            if (selectedPlayer == null) {
                if (clicked == null) return;
                boolean whitesTurn = board.whiteToMove;
                if (whitesTurn) {
                    if (!player.getUniqueId().equals(white.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "It's not your turn. White is to move.");
                        return;
                    }
                    if (clicked.color != Color.WHITE) {
                        player.sendMessage(ChatColor.RED + "You can only move White pieces.");
                        return;
                    }
                } else {
                    if (!player.getUniqueId().equals(black.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "It's not your turn. Black is to move.");
                        return;
                    }
                    if (clicked.color != Color.BLACK) {
                        player.sendMessage(ChatColor.RED + "You can only move Black pieces.");
                        return;
                    }
                }
                selectedPlayer = player.getUniqueId();
                selectedX = br; selectedY = bc;
                player.sendMessage(ChatColor.YELLOW + "Selected " + clicked.type.name() + " at " + squareName(br, bc));
            } else {
                if (!selectedPlayer.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Another player is mid-selection.");
                    return;
                }
                int fromX = selectedX, fromY = selectedY;
                int toX = br, toY = bc;
                List<ChessMove> legal = board.legalMoves(fromX, fromY, lastDoublePawn);
                Optional<ChessMove> chosen = legal.stream().filter(m -> m.toX == toX && m.toY == toY).findFirst();
                if (chosen.isEmpty()) {
                    ChessPiece cc = board.getPiece(br, bc);
                    ChessPiece fromPiece = board.getPiece(fromX, fromY);
                    if (cc != null && fromPiece != null && cc.color == fromPiece.color) {
                        selectedX = br; selectedY = bc;
                        player.sendMessage(ChatColor.YELLOW + "Reselected " + cc.type.name() + " at " + squareName(br, bc));
                        return;
                    }
                    player.sendMessage(ChatColor.RED + "Illegal move.");
                    selectedPlayer = null; selectedX = -1; selectedY = -1;
                    return;
                }
                ChessMove move = chosen.get();
                if (move.promotion) {
                    // pause: ask the player which piece to promote to
                    selectedPlayer = null; selectedX = -1; selectedY = -1;
                    pendingPromotion = move;
                    promotionPlayer = player.getUniqueId();
                    showPromotionChoices(player);
                    return;
                }
                if (puzzleMode) {
                    handlePuzzleMove(player, move, null);
                    return;
                }
                completeMove(move, null);
            }
        }

        // Promotion picker -----------------------------------------------------

        void handlePromotionClick(Player p, InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= 54) return; // choices live in the top chest
            switch (raw) {
                case 0: finishPromotion(p, ChessPieceType.QUEEN); break;
                case 1: finishPromotion(p, ChessPieceType.ROOK); break;
                case 2: finishPromotion(p, ChessPieceType.BISHOP); break;
                case 3: finishPromotion(p, ChessPieceType.KNIGHT); break;
                case 4: cancelPromotion(p); break;
                default: break;
            }
        }

        void showPromotionChoices(Player p) {
            InventoryView view = p.getOpenInventory();
            if (view == null) return;
            Inventory top = view.getTopInventory();
            if (top == null) return;
            Color c = pendingPromotion.promotionColor;
            top.setItem(0, toItemFor(new ChessPiece(c, ChessPieceType.QUEEN, 0, 0)));
            top.setItem(1, toItemFor(new ChessPiece(c, ChessPieceType.ROOK, 0, 0)));
            top.setItem(2, toItemFor(new ChessPiece(c, ChessPieceType.BISHOP, 0, 0)));
            top.setItem(3, toItemFor(new ChessPiece(c, ChessPieceType.KNIGHT, 0, 0)));
            top.setItem(4, createButton(Material.BARRIER, ChatColor.RED + "Cancel"));
            p.sendMessage(ChatColor.YELLOW + "Promote your pawn! Click Queen, Rook, Bishop, or Knight in the top-left of the board.");
        }

        void cancelPromotion(Player p) {
            pendingPromotion = null;
            promotionPlayer = null;
            updateAllInventories();
            p.sendMessage(ChatColor.GRAY + "Promotion cancelled.");
        }

        void finishPromotion(Player p, ChessPieceType type) {
            ChessMove move = pendingPromotion;
            if (move == null) return;
            pendingPromotion = null;
            promotionPlayer = null;
            if (puzzleMode) {
                handlePuzzleMove(p, move, type);
                return;
            }
            completeMove(move, type);
        }

        // Daily puzzle: only a move that checkmates the opponent is accepted.
        void handlePuzzleMove(Player p, ChessMove move, ChessPieceType promo) {
            ChessBoard copy = board.copy();
            copy.applyMove(move);
            if (move.promotion && promo != null) {
                copy.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, promo, move.toX, move.toY));
            }
            copy.whiteToMove = !copy.whiteToMove;
            int[] nextLdp = move.isDoublePawn ? new int[]{move.toX, move.toY} : null;
            if (!copy.isInCheckmate(copy.whiteToMove, nextLdp)) {
                puzzleAttempts++;
                selectedPlayer = null; selectedX = -1; selectedY = -1;
                p.sendMessage(ChatColor.RED + "That is not mate in one! Try again. (Attempt " + puzzleAttempts + ")");
                updateAllInventories();
                return;
            }
            p.sendMessage(ChatColor.GREEN + "Checkmate! You solved today's puzzle"
                    + (puzzleAttempts == 0 ? " on the first try" : " in " + (puzzleAttempts + 1) + " attempts") + "!");
            p.sendMessage(ChatColor.GRAY + "Solution: " + squareName(move.fromX, move.fromY) + "->" + squareName(move.toX, move.toY)
                    + (move.promotion ? " (" + promo.name().toLowerCase() + ")" : "") + ".");
            completeMove(move, promo);
        }

        void giveUpPuzzle(Player p) {
            if (!running) return;
            sendBoth(ChatColor.GRAY + "Solution: " + puzzleSolution + ". Come back tomorrow for a new one!");
            endGame("You gave up on today's puzzle.", GameResult.ABANDONED);
        }

        void completeMove(ChessMove move, ChessPieceType promotionTo) {
            if (move.isDoublePawn) lastDoublePawn = new int[]{move.toX, move.toY};
            else lastDoublePawn = null;
            board.applyMove(move);
            if (promotionTo != null) {
                board.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, promotionTo, move.toX, move.toY));
            }
            board.whiteToMove = !board.whiteToMove;
            selectedPlayer = null; selectedX = -1; selectedY = -1;
            updateAllInventories();
            if (board.isInCheckmate(board.whiteToMove, lastDoublePawn)) {
                if (puzzleMode) {
                    endGame("You solved today's puzzle!", humanIsWhite ? GameResult.WHITE_WIN : GameResult.BLACK_WIN);
                    return;
                }
                Color winner = board.whiteToMove ? Color.BLACK : Color.WHITE;
                endGame((board.whiteToMove ? "White" : "Black") + " is checkmated. " + sideName(!board.whiteToMove) + " wins.",
                        winner == Color.WHITE ? GameResult.WHITE_WIN : GameResult.BLACK_WIN);
                return;
            }
            if (board.isStalemate(board.whiteToMove, lastDoublePawn)) {
                endGame("Draw by stalemate.", GameResult.DRAW);
                return;
            }
            maybeScheduleAI();
        }

        private String squareName(int r, int c) {
            char file = (char) ('a' + c);
            int rank = 8 - r;
            return "" + file + rank;
        }

        void updateAllInventories() {
            // Refresh all human players
            if (!whiteIsAI) refreshPlayer(white);
            if (!blackIsAI) refreshPlayer(black);
            // Refresh spectators
            for (Player sp : spectators.values()) {
                refreshSpectator(sp);
            }
        }

        private void refreshPlayer(Player p) {
            if (p == null || !p.isOnline()) return;
            // while this player is choosing a promotion piece, keep the picker visible
            if (pendingPromotion != null && promotionPlayer != null && promotionPlayer.equals(p.getUniqueId())) return;
            InventoryView iv = p.getOpenInventory();
            if (iv == null) return;
            Inventory top = iv.getTopInventory();
            if (top == null) return;
            if (iv.getTitle().equals(viewTitle())) {
                renderBoardInto(top, p, false);
            }
        }

        private void refreshSpectator(Player p) {
            if (p == null || !p.isOnline()) return;
            InventoryView iv = p.getOpenInventory();
            if (iv == null) return;
            Inventory top = iv.getTopInventory();
            if (top == null) return;
            if (spectatorTitle != null && iv.getTitle().equals(spectatorTitle)) {
                renderBoardInto(top, p, true);
            }
        }

        // Re-apply the board to the top chest and the player's mapped inventory slots
        private void renderBoardInto(Inventory top, Player p, boolean spectator) {
            // top area
            for (int guiRow = 0; guiRow < 4; guiRow++) {
                for (int guiCol = 0; guiCol < 8; guiCol++) {
                    int slot = guiRow * 9 + guiCol;
                    ChessPiece piece = board.getPiece(guiRow, guiCol);
                    top.setItem(slot, toItemFor(piece));
                }
                int ctrlSlot = guiRow * 9 + 8;
                ItemStack info = top.getItem(ctrlSlot);
                if (info != null && info.hasItemMeta()) {
                    // update time/turn text
                    ItemMeta meta = info.getItemMeta();
                    meta.setDisplayName(infoText(guiRow, spectator));
                    info.setItemMeta(meta);
                    top.setItem(ctrlSlot, info);
                }
            }
            // bottom mapped slots in player's inventory:
            PlayerInventory pinv = p.getInventory();
            for (int br = 0; br < 4; br++) {
                int boardRow = 4 + br;
                for (int col = 0; col < 8; col++) {
                    ItemStack item = toItemFor(board.getPiece(boardRow, col));
                    int invIndex;
                    if (br == 3) invIndex = col; // hotbar 0..7
                    else invIndex = 9 + br * 9 + col; // br 0->9..16, br1->18..25, br2->27..34
                    pinv.setItem(invIndex, item);
                }
            }
        }
    }

    // Inventory snapshot to restore later
    static class SavedInventory {
        final ItemStack[] contents;
        final ItemStack[] armor;
        final ItemStack offhand;

        private SavedInventory(ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
            this.contents = contents;
            this.armor = armor;
            this.offhand = offhand;
        }

        static SavedInventory save(Player p) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] contentsCopy = Arrays.stream(inv.getContents())
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
            ItemStack[] armorCopy = Arrays.stream(inv.getArmorContents())
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
            ItemStack off = inv.getItemInOffHand() == null ? null : inv.getItemInOffHand().clone();
            return new SavedInventory(contentsCopy, armorCopy, off);
        }

        void restore(Player p) {
            PlayerInventory inv = p.getInventory();
            inv.setContents(Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new));
            inv.setArmorContents(Arrays.stream(armor).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new));
            inv.setItemInOffHand(offhand == null ? null : offhand.clone());
        }
    }

    // ----------------- Chess AI (alpha-beta minimax with piece-square tables) -----------------
    static class ChessAI {
        private static final int INF = 1_000_000_000;
        private static final int MATE = 1_000_000;
        private static final int[] PV = {100, 500, 320, 330, 900, 20000};
        // Piece-square tables (white's perspective; row 0 = furthest rank from white)
        private static final int[][] PST = {
            { 0,  0,  0,  0,  0,  0,  0,  0,
             50, 50, 50, 50, 50, 50, 50, 50,
             10, 10, 20, 30, 30, 20, 10, 10,
              5,  5, 10, 25, 25, 10,  5,  5,
              0,  0,  0, 20, 20,  0,  0,  0,
              5, -5,-10,  0,  0,-10, -5,  5,
              5, 10, 10,-20,-20, 10, 10,  5,
              0,  0,  0,  0,  0,  0,  0,  0},
            { 0,  0,  0,  0,  0,  0,  0,  0,
              5, 10, 10, 10, 10, 10, 10,  5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             -5,  0,  0,  0,  0,  0,  0, -5,
              0,  0,  0,  5,  5,  0,  0,  0},
            { -50,-40,-30,-30,-30,-30,-40,-50,
              -40,-20,  0,  0,  0,  0,-20,-40,
              -30,  0, 10, 15, 15, 10,  0,-30,
              -30,  5, 15, 20, 20, 15,  5,-30,
              -30,  0, 15, 20, 20, 15,  0,-30,
              -30,  5, 10, 15, 15, 10,  5,-30,
              -40,-20,  0,  5,  5,  0,-20,-40,
              -50,-40,-30,-30,-30,-30,-40,-50},
            { -20,-10,-10,-10,-10,-10,-10,-20,
              -10,  0,  0,  0,  0,  0,  0,-10,
              -10,  0,  5, 10, 10,  5,  0,-10,
              -10,  5,  5, 10, 10,  5,  5,-10,
              -10,  0, 10, 10, 10, 10,  0,-10,
              -10, 10, 10, 10, 10, 10, 10,-10,
              -10,  5,  0,  0,  0,  0,  5,-10,
              -20,-10,-10,-10,-10,-10,-10,-20},
            { -20,-10,-10, -5, -5,-10,-10,-20,
              -10,  0,  0,  0,  0,  0,  0,-10,
              -10,  0,  5,  5,  5,  5,  0,-10,
               -5,  0,  5,  5,  5,  5,  0, -5,
                0,  0,  5,  5,  5,  5,  0, -5,
              -10,  5,  5,  5,  5,  5,  0,-10,
              -10,  0,  5,  0,  0,  0,  0,-10,
              -20,-10,-10, -5, -5,-10,-10,-20},
            { -30,-40,-40,-50,-50,-40,-40,-30,
              -30,-40,-40,-50,-50,-40,-40,-30,
              -30,-40,-40,-50,-50,-40,-40,-30,
              -30,-40,-40,-50,-50,-40,-40,-30,
              -20,-30,-30,-40,-40,-30,-30,-20,
              -10,-20,-20,-20,-20,-20,-20,-10,
               20, 20,  0,  0,  0,  0, 20, 20,
               20, 30, 10,  0,  0, 10, 30, 20}
        };
        private long nodes;
        private long nodeLimit = 250_000;
        // score of the best root move from the last findBestMove call (used for puzzle move validation)
        public int lastBestScore = 0;
        private static final Random RANDOM = new Random();

        enum Difficulty {
            EASY("Easy", 1, 0.4, 250, 25_000),
            CASUAL("Casual", 2, 0.1, 100, 50_000),
            MEDIUM("Medium", 3, 0.0, 0, 60_000),
            HARD("Hard", 4, 0.0, 0, 100_000),
            EXTREME("Extreme", 5, 0.0, 0, 65_000);
            final String label;
            final int depth;
            final double randomness; // chance to play a random legal move
            final int noise;         // centipawn noise added to each root move score
            final long nodeLimit;
            Difficulty(String label, int depth, double randomness, int noise, long nodeLimit) {
                this.label = label;
                this.depth = depth;
                this.randomness = randomness;
                this.noise = noise;
                this.nodeLimit = nodeLimit;
            }
            static Difficulty fromString(String s) {
                for (Difficulty d : values()) {
                    if (d.name().equalsIgnoreCase(s)) return d;
                }
                return null;
            }
        }

        ChessMove findBestMove(ChessBoard b, int[] lastDoublePawn, Difficulty d) {
            int depth = d.depth;
            nodeLimit = d.nodeLimit;
            nodes = 0;
            List<ChessMove> moves = allLegalMoves(b, lastDoublePawn);
            if (moves.isEmpty()) return null;
            if (d.randomness > 0 && RANDOM.nextDouble() < d.randomness) {
                lastBestScore = 0;
                return moves.get(RANDOM.nextInt(moves.size()));
            }
            orderMoves(moves, b);
            int bestScore = -INF;
            List<ChessMove> best = new ArrayList<>();
            for (ChessMove m : moves) {
                ChessBoard copy = b.copy();
                applyMove(copy, m);
                int[] nextLdp = m.isDoublePawn ? new int[]{m.toX, m.toY} : null;
                int score = -alphabeta(copy, depth - 1, -INF, INF, 1, nextLdp);
                if (d.noise > 0) score += RANDOM.nextInt(2 * d.noise + 1) - d.noise;
                if (score > bestScore) {
                    bestScore = score;
                    best.clear();
                    best.add(m);
                } else if (score == bestScore) {
                    best.add(m);
                }
            }
            if (best.isEmpty()) return null;
            lastBestScore = bestScore;
            return best.get(RANDOM.nextInt(best.size()));
        }

        // Score a specific root move from the current side's perspective (deterministic, no noise).
        int scoreForMove(ChessBoard b, int[] lastDoublePawn, ChessMove m, ChessPieceType promo, Difficulty d) {
            nodeLimit = d.nodeLimit;
            nodes = 0;
            ChessBoard copy = b.copy();
            applyMove(copy, m);
            if (m.promotion && promo != null) {
                copy.setPiece(m.toX, m.toY, new ChessPiece(m.promotionColor, promo, m.toX, m.toY));
            }
            int[] nextLdp = m.isDoublePawn ? new int[]{m.toX, m.toY} : null;
            return -alphabeta(copy, d.depth - 1, -INF, INF, 1, nextLdp);
        }

        private int alphabeta(ChessBoard b, int depth, int alpha, int beta, int ply, int[] lastDoublePawn) {
            nodes++;
            List<ChessMove> moves = allLegalMoves(b, lastDoublePawn);
            if (moves.isEmpty()) {
                // side to move is mated or stalemated
                if (b.isKingInCheck(b.whiteToMove ? Color.WHITE : Color.BLACK)) return -(MATE - ply);
                return 0;
            }
            if (depth <= 0 || nodes > nodeLimit) return evalFromSide(b);
            orderMoves(moves, b);
            int best = -INF;
            for (ChessMove m : moves) {
                ChessBoard copy = b.copy();
                applyMove(copy, m);
                int[] nextLdp = m.isDoublePawn ? new int[]{m.toX, m.toY} : null;
                int score = -alphabeta(copy, depth - 1, -beta, -alpha, ply + 1, nextLdp);
                if (score > best) best = score;
                if (best > alpha) alpha = best;
                if (alpha >= beta) break;
            }
            return best;
        }

        private List<ChessMove> allLegalMoves(ChessBoard b, int[] lastDoublePawn) {
            List<ChessMove> out = new ArrayList<>();
            Color side = b.whiteToMove ? Color.WHITE : Color.BLACK;
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) {
                ChessPiece p = b.getPiece(x, y);
                if (p == null || p.color != side) continue;
                for (ChessMove m : b.legalMoves(x, y, lastDoublePawn)) {
                    if (m.promotion) {
                        for (ChessPieceType t : new ChessPieceType[]{ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT}) {
                            ChessMove c = new ChessMove(m.fromX, m.fromY, m.toX, m.toY);
                            c.promotion = true;
                            c.promotionColor = m.promotionColor;
                            c.promotionTo = t;
                            out.add(c);
                        }
                    } else {
                        out.add(m);
                    }
                }
            }
            return out;
        }

        private void orderMoves(List<ChessMove> moves, ChessBoard b) {
            moves.sort((a, c) -> Integer.compare(captureValue(c, b), captureValue(a, b)));
        }

        private int captureValue(ChessMove m, ChessBoard b) {
            ChessPiece t = b.getPiece(m.toX, m.toY);
            if (t == null) return m.promotion ? PV[ChessPieceType.QUEEN.ordinal()] : 0;
            return PV[t.type.ordinal()];
        }

        private void applyMove(ChessBoard b, ChessMove m) {
            b.applyMove(m);
            if (m.promotion && m.promotionTo != null) {
                b.setPiece(m.toX, m.toY, new ChessPiece(m.promotionColor, m.promotionTo, m.toX, m.toY));
            }
            b.whiteToMove = !b.whiteToMove;
        }

        private int evalFromSide(ChessBoard b) {
            int e = evaluate(b);
            return b.whiteToMove ? e : -e;
        }

        private int evaluate(ChessBoard b) {
            int score = 0;
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) {
                ChessPiece p = b.getPiece(x, y);
                if (p == null) continue;
                int v = PV[p.type.ordinal()] + pst(p.type, x, y, p.color);
                score += (p.color == Color.WHITE) ? v : -v;
            }
            return score;
        }

        private int pst(ChessPieceType t, int x, int y, Color c) {
            int row = (c == Color.WHITE) ? (7 - x) : x;
            return PST[t.ordinal()][row * 8 + y];
        }
    }

    // ----------------- Chess engine simplified (en passant, castling, check/checkmate) -----------------
    enum Color { WHITE, BLACK }
    enum ChessPieceType { PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING }

    static class ChessPiece {
        final Color color;
        final ChessPieceType type;
        int x, y;
        boolean hasMoved = false;
        ChessPiece(Color color, ChessPieceType type, int x, int y) { this.color = color; this.type = type; this.x = x; this.y = y; }
        String toShortString() { return color.name().charAt(0) + "-" + type.name().charAt(0); }
    }

    static class ChessMove {
        int fromX, fromY, toX, toY;
        ChessPieceType promotionTo = null;
        boolean isDoublePawn = false;
        boolean promotion = false;
        Color promotionColor = null;
        boolean isCastling = false;
        ChessMove(int fx,int fy,int tx,int ty){fromX=fx;fromY=fy;toX=tx;toY=ty;}
    }

    static class ChessBoard {
        private final ChessPiece[][] b = new ChessPiece[8][8];
        boolean whiteToMove = true;

        ChessPiece getPiece(int x, int y) {
            if (x<0||x>7||y<0||y>7) return null;
            return b[x][y];
        }
        void setPiece(int x, int y, ChessPiece p) {
            if (x<0||x>7||y<0||y>7) return;
            b[x][y] = p;
            if (p!=null){p.x=x;p.y=y;}
        }
        void resetInitialPosition() {
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) b[x][y]=null;
            for (int y=0;y<8;y++) setPiece(1,y,new ChessPiece(Color.WHITE, ChessPieceType.PAWN,1,y));
            for (int y=0;y<8;y++) setPiece(6,y,new ChessPiece(Color.BLACK, ChessPieceType.PAWN,6,y));
            setPiece(0,0,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,0));
            setPiece(0,7,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,7));
            setPiece(7,0,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,0));
            setPiece(7,7,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,7));
            setPiece(0,1,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,1));
            setPiece(0,6,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,6));
            setPiece(7,1,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,1));
            setPiece(7,6,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,6));
            setPiece(0,2,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,2));
            setPiece(0,5,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,5));
            setPiece(7,2,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,2));
            setPiece(7,5,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,5));
            setPiece(0,3,new ChessPiece(Color.WHITE,ChessPieceType.QUEEN,0,3));
            setPiece(7,3,new ChessPiece(Color.BLACK,ChessPieceType.QUEEN,7,3));
            setPiece(0,4,new ChessPiece(Color.WHITE,ChessPieceType.KING,0,4));
            setPiece(7,4,new ChessPiece(Color.BLACK,ChessPieceType.KING,7,4));
            whiteToMove = true;
        }

        // Load a position from definitions like "WKg1" (color + type + square).
        // Kings and rooks are marked as moved so castling is never available.
        void setPosition(String[] defs, boolean whiteToMove) {
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) b[x][y] = null;
            for (String d : defs) {
                char col = d.charAt(0);
                char typ = d.charAt(1);
                int file = d.charAt(2) - 'a';
                int rank = d.charAt(3) - '1';
                ChessPieceType t;
                switch (typ) {
                    case 'P': t = ChessPieceType.PAWN; break;
                    case 'R': t = ChessPieceType.ROOK; break;
                    case 'N': t = ChessPieceType.KNIGHT; break;
                    case 'B': t = ChessPieceType.BISHOP; break;
                    case 'Q': t = ChessPieceType.QUEEN; break;
                    default:  t = ChessPieceType.KING; break;
                }
                ChessPiece p = new ChessPiece(col == 'W' ? Color.WHITE : Color.BLACK, t, rank, file);
                if (t == ChessPieceType.KING || t == ChessPieceType.ROOK) p.hasMoved = true;
                setPiece(rank, file, p);
            }
            this.whiteToMove = whiteToMove;
        }

        List<ChessMove> legalMoves(int x, int y, int[] lastDoublePawn) {
            ChessPiece p = getPiece(x,y);
            if (p == null) return Collections.emptyList();
            List<ChessMove> moves = pseudoLegalMoves(x,y,lastDoublePawn);
            List<ChessMove> legal = new ArrayList<>();
            for (ChessMove m : moves) {
                ChessBoard copy = copy();
                copy.applyMove(m);
                if (!copy.isKingInCheck(p.color)) {
                    // Check castling constraints
                    if (m.isCastling) {
                        if (isKingInCheck(p.color)) continue; // Cannot castle out of check
                        
                        // Check if the passing square is under attack
                        int passY = m.fromY + (m.toY > m.fromY ? 1 : -1);
                        ChessBoard passCopy = copy();
                        // Simulate King moving just one square to check if it's attacked
                        passCopy.applyMove(new ChessMove(m.fromX, m.fromY, m.fromX, passY));
                        if (passCopy.isKingInCheck(p.color)) continue; // Cannot castle through check
                    }
                    legal.add(m);
                }
            }
            return legal;
        }

        List<ChessMove> pseudoLegalMoves(int x, int y, int[] lastDoublePawn) {
            ChessPiece p = getPiece(x,y);
            if (p == null) return Collections.emptyList();
            List<ChessMove> out = new ArrayList<>();
            int dir = (p.color == Color.WHITE) ? 1 : -1;
            switch (p.type) {
                case PAWN:
                    int nx = x + dir;
                    if (onBoard(nx,y) && getPiece(nx,y)==null) {
                        ChessMove m = new ChessMove(x,y,nx,y);
                        if (nx==7 || nx==0) { m.promotion = true; m.promotionColor = p.color; m.promotionTo = ChessPieceType.QUEEN; }
                        out.add(m);
                        if ((p.color==Color.WHITE && x==1) || (p.color==Color.BLACK && x==6)) {
                            int nx2 = x + 2*dir;
                            if (onBoard(nx2,y) && getPiece(nx2,y)==null) {
                                ChessMove m2 = new ChessMove(x,y,nx2,y); m2.isDoublePawn = true;
                                out.add(m2);
                            }
                        }
                    }
                    for (int dy : new int[]{-1,1}) {
                        int cx = x + dir, cy = y + dy;
                        if (onBoard(cx,cy)) {
                            ChessPiece targ = getPiece(cx,cy);
                            if (targ != null && targ.color != p.color) {
                                ChessMove m = new ChessMove(x,y,cx,cy);
                                if (cx==7 || cx==0) { m.promotion=true; m.promotionTo=ChessPieceType.QUEEN; m.promotionColor=p.color;}
                                out.add(m);
                            }
                        }
                    }
                    if (lastDoublePawn != null) {
                        int ldX = lastDoublePawn[0], ldY = lastDoublePawn[1];
                        if (x == (p.color==Color.WHITE ? 4 : 3)) {
                            if (Math.abs(ldY - y) == 1 && ldX == x) {
                                int capToX = x + dir;
                                int capToY = ldY;
                                ChessMove m = new ChessMove(x,y,capToX,capToY);
                                out.add(m);
                            }
                        }
                    }
                    break;
                case KNIGHT:
                    int[][] ksteps = {{1,2},{2,1},{-1,2},{-2,1},{1,-2},{2,-1},{-1,-2},{-2,-1}};
                    for (int[] s: ksteps) {
                        int tx=x+s[0], ty=y+s[1];
                        if (!onBoard(tx,ty)) continue;
                        ChessPiece t = getPiece(tx,ty);
                        if (t==null || t.color!=p.color) out.add(new ChessMove(x,y,tx,ty));
                    }
                    break;
                case BISHOP:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}});
                    break;
                case ROOK:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,0},{-1,0},{0,1},{0,-1}});
                    break;
                case QUEEN:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,1},{1,-1},{-1,1},{-1,-1},{1,0},{-1,0},{0,1},{0,-1}});
                    break;
                case KING:
                    for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++){
                        if (dx==0&&dy==0) continue;
                        int tx=x+dx, ty=y+dy;
                        if (!onBoard(tx,ty)) continue;
                        ChessPiece t = getPiece(tx,ty);
                        if (t==null || t.color!=p.color) out.add(new ChessMove(x,y,tx,ty));
                    }
                    
                    // Castling Logic
                    if (!p.hasMoved) {
                        // Kingside (short) -> check right rook at y = 7
                        ChessPiece hRook = getPiece(x, 7);
                        if (hRook != null && hRook.type == ChessPieceType.ROOK && !hRook.hasMoved) {
                            if (getPiece(x, 5) == null && getPiece(x, 6) == null) {
                                ChessMove m = new ChessMove(x, y, x, y + 2);
                                m.isCastling = true;
                                out.add(m);
                            }
                        }
                        // Queenside (long) -> check left rook at y = 0
                        ChessPiece aRook = getPiece(x, 0);
                        if (aRook != null && aRook.type == ChessPieceType.ROOK && !aRook.hasMoved) {
                            if (getPiece(x, 1) == null && getPiece(x, 2) == null && getPiece(x, 3) == null) {
                                ChessMove m = new ChessMove(x, y, x, y - 2);
                                m.isCastling = true;
                                out.add(m);
                            }
                        }
                    }
                    break;
            }
            return out;
        }

        private void addSlidingMoves(List<ChessMove> out, int x, int y, ChessPiece p, int[][] dirs) {
            for (int[] d : dirs) {
                int tx = x + d[0], ty = y + d[1];
                while (onBoard(tx,ty)) {
                    ChessPiece t = getPiece(tx,ty);
                    if (t == null) out.add(new ChessMove(x,y,tx,ty));
                    else { if (t.color != p.color) out.add(new ChessMove(x,y,tx,ty)); break; }
                    tx += d[0]; ty += d[1];
                }
            }
        }

        boolean onBoard(int x,int y){return x>=0&&x<8&&y>=0&&y<8;}

        void applyMove(ChessMove m) {
            ChessPiece p = getPiece(m.fromX,m.fromY);
            if (p==null) return;
            // en passant capture detection: if pawn moves diagonally into empty square -> en passant
            if (p.type == ChessPieceType.PAWN && Math.abs(m.toY - m.fromY) == 1 && getPiece(m.toX, m.toY) == null && m.toX != m.fromX) {
                // capture pawn behind
                int capX = m.fromX;
                int capY = m.toY;
                setPiece(capX, capY, null);
            }
            
            // Castling Execution
            if (m.isCastling) {
                if (m.toY > m.fromY) { // Kingside
                    ChessPiece rook = getPiece(m.fromX, 7);
                    setPiece(m.fromX, 5, rook); // Move rook next to king
                    setPiece(m.fromX, 7, null);
                    if (rook != null) rook.hasMoved = true;
                } else { // Queenside
                    ChessPiece rook = getPiece(m.fromX, 0);
                    setPiece(m.fromX, 3, rook); // Move rook next to king
                    setPiece(m.fromX, 0, null);
                    if (rook != null) rook.hasMoved = true;
                }
            }
            
            p.hasMoved = true; // Mark piece as moved
            setPiece(m.toX,m.toY,p);
            setPiece(m.fromX,m.fromY,null);
        }

        ChessBoard copy() {
            ChessBoard c = new ChessBoard();
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = b[x][y];
                if (p != null) {
                    c.b[x][y] = new ChessPiece(p.color, p.type, x, y);
                    c.b[x][y].hasMoved = p.hasMoved; // Preserve movement state
                }
            }
            c.whiteToMove = whiteToMove;
            return c;
        }

        boolean isKingInCheck(Color kingColor) {
            // find king
            int kx=-1, ky=-1;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.type==ChessPieceType.KING && p.color==kingColor) { kx=x; ky=y; }
            }
            if (kx==-1) return true; // no king?
            // scan for enemy attacks (simple: generate pseudo-legal enemy moves and see if any hits king square)
            Color enemy = kingColor == Color.WHITE ? Color.BLACK : Color.WHITE;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == enemy) {
                    for (ChessMove m : pseudoLegalMoves(x,y, null)) {
                        if (m.toX == kx && m.toY == ky) return true;
                    }
                }
            }
            return false;
        }

        boolean isInCheckmate(boolean whiteToMoveSide, int[] lastDoublePawn) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            // if king not in check -> false
            if (!isKingInCheck(c)) return false;
            // if no legal move for side -> checkmate
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, lastDoublePawn).isEmpty()) return false;
                }
            }
            return true;
        }

        boolean isStalemate(boolean whiteToMoveSide, int[] lastDoublePawn) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            if (isKingInCheck(c)) return false;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, lastDoublePawn).isEmpty()) return false;
                }
            }
            return true;
        }
    }
}