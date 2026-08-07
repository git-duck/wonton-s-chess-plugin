package com.wonton.chess;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
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
    private StatsManager statsManager;
    private Economy economy;
    private boolean enabled = true;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();
    // spectators: player UUID -> game they are watching
    private final Map<UUID, ChessGame> spectators = new ConcurrentHashMap<>();
    // matchmaking queue, replay archive, and tournament manager
    private MatchmakingManager matchmaking;
    private ReplayManager replayManager;
    private final TournamentManager tournament = new TournamentManager();

    @Override
    public void onEnable() {
        PIECE_KEY = new NamespacedKey(this, "chess_piece");
        saveDefaultConfig();
        saveResource("puzzles.yml", false);
        this.challengeManager = new ChallengeManager(this);
        this.eloManager = new EloManager(this);
        this.statsManager = new StatsManager(this);
        this.replayManager = new ReplayManager(this);
        this.matchmaking = new MatchmakingManager(this);
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
        Objects.requireNonNull(this.getCommand("chessboard")).setExecutor(new ChessBoardCommand());
        Objects.requireNonNull(this.getCommand("chessmatch")).setExecutor(new ChessMatchCommand());
        Objects.requireNonNull(this.getCommand("chessleaderboard")).setExecutor(new ChessLeaderboardCommand());
        Objects.requireNonNull(this.getCommand("chessreplay")).setExecutor(new ChessReplayCommand());
        Objects.requireNonNull(this.getCommand("chesstourney")).setExecutor(new ChessTourneyCommand());
        Puzzle.loadCustom(this);
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

    public StatsManager getStatsManager() {
        return statsManager;
    }

    @Override
    public void onDisable() {
        if (eloManager != null) eloManager.save();
        if (statsManager != null) statsManager.save();
        // cancel any tournament first so its onEnd hooks don't fire during shutdown
        tournament.end(this);
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
            if (args.length < 1 || args.length > 4) {
                p.sendMessage(ChatColor.RED + "Usage: /chess <player> [1|3|5|10[+inc]] [bet] [gambit]");
                return true;
            }
            int minutes = 3; // default: blitz
            int increment = 0;
            if (args.length >= 2) {
                String[] parts = args[1].split("\\+");
                try {
                    minutes = Integer.parseInt(parts[0]);
                    if (parts.length > 1) increment = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Invalid time control. Use 1 (bullet), 3 (blitz), 5 (blitz), or 10 (rapid), optionally +inc (e.g. 3+2).");
                    return true;
                }
                if (minutes != 1 && minutes != 3 && minutes != 5 && minutes != 10) {
                    p.sendMessage(ChatColor.RED + "Invalid time control. Use 1 (bullet), 3 (blitz), 5 (blitz), or 10 (rapid).");
                    return true;
                }
                if (increment < 0 || increment > 60) {
                    p.sendMessage(ChatColor.RED + "Invalid increment. Use 0-60 seconds.");
                    return true;
                }
            }
            double bet = 0;
            boolean gambit = false;
            if (args.length >= 3) {
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
            if (args.length == 4) {
                gambit = args[3].equalsIgnoreCase("gambit");
                if (!gambit) {
                    p.sendMessage(ChatColor.RED + "Invalid option '" + args[3] + "'. Use 'gambit' to enable gambit mode.");
                    return true;
                }
                if (bet <= 0) {
                    p.sendMessage(ChatColor.RED + "Gambit mode requires a bet.");
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
            challengeManager.createChallenge(p, target, minutes, bet, increment, gambit);
            String tc = minutes + (increment > 0 ? "+" + increment : "");
            if (bet > 0) {
                p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + " (" + tc + "m, bet " + economy.format(bet) + (gambit ? ", gambit" : "") + "). Expires in 30s.");
                target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a " + tc + "m chess game for " + ChatColor.GOLD + economy.format(bet)
                        + (gambit ? ChatColor.LIGHT_PURPLE + " with gambit mode" : "")
                        + ChatColor.YELLOW + "! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
            } else {
                p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + " (" + tc + " min). Expires in 30s.");
                target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a " + tc + "m chess game! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
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
                return Arrays.asList("1", "1+1", "3", "3+2", "5", "5+5", "10");
            }
            if (args.length == 3) {
                return Collections.singletonList("0");
            }
            if (args.length == 4) {
                return Collections.singletonList("gambit");
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
            ChessGame game = new ChessGame(ChessPlugin.this, challenger, accepter, challenge.minutes, challenge.increment);
            game.bet = bet;
            game.gambit = challenge.gambit;
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
            if (args.length == 0) {
                startPuzzle(p, Puzzle.today());
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                List<Puzzle> pool = Puzzle.allPuzzles();
                p.sendMessage(ChatColor.GOLD + "Puzzles (" + pool.size() + "):");
                for (int i = 0; i < pool.size(); i++) {
                    Puzzle pz = pool.get(i);
                    p.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " + pz.title + " - mate in " + pz.mateIn());
                }
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!p.hasPermission("chess.admin")) {
                    p.sendMessage(ChatColor.RED + "You don't have permission to reload puzzles.");
                    return true;
                }
                Puzzle.loadCustom(ChessPlugin.this);
                p.sendMessage(ChatColor.GREEN + "Custom puzzles reloaded (" + Puzzle.CUSTOM_PUZZLES.size() + " loaded, " + (Puzzle.allPuzzles().size() - Puzzle.PUZZLES.size()) + " total).");
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
                try {
                    Puzzle pz = Puzzle.byIndex(Integer.parseInt(args[1]));
                    if (pz == null) {
                        p.sendMessage(ChatColor.RED + "No puzzle at index " + args[1] + ".");
                        return true;
                    }
                    startPuzzle(p, pz);
                    return true;
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Usage: /chesspuzzle play <index>");
                    return true;
                }
            }
            p.sendMessage(ChatColor.RED + "Usage: /chesspuzzle [list|reload|play <index>]");
            return true;
        }

        void startPuzzle(Player p, Puzzle puzzle) {
            if (activeGames.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are already in a game. Close your current board first.");
                return;
            }
            ChessGame game = new ChessGame(ChessPlugin.this, p, p, 10);
            game.puzzleMode = true;
            game.humanIsWhite = puzzle.whiteToMove;
            game.whiteIsAI = !puzzle.whiteToMove;
            game.blackIsAI = puzzle.whiteToMove;
            game.puzzleDay = puzzle.day;
            game.puzzleTitle = puzzle.title;
            game.puzzleSolution = puzzle.solution;
            game.puzzleSolutionMoves = puzzle.solutionMoves();
            game.puzzleMateIn = puzzle.mateIn();
            game.board.setPosition(puzzle.pieces, puzzle.whiteToMove);
            activeGames.put(p.getUniqueId(), game);
            game.start();
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Arrays.asList("list", "reload", "play");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
                List<String> out = new ArrayList<>();
                for (int i = 1; i <= Puzzle.allPuzzles().size(); i++) out.add(String.valueOf(i));
                return out;
            }
            return Collections.emptyList();
        }
    }

    private class ChessBoardCommand implements TabExecutor {
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
            ChessGame game = activeGames.get(p.getUniqueId());
            if (game == null) {
                p.sendMessage(ChatColor.RED + "You are not in a chess game.");
                return true;
            }
            game.openFor(p);
            p.sendMessage(ChatColor.GREEN + "Your chess board has been reopened.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessMatchCommand implements TabExecutor {
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
            String cat = args.length >= 1 ? args[0].toLowerCase() : "blitz";
            if (!cat.equals("bullet") && !cat.equals("blitz") && !cat.equals("rapid")) {
                p.sendMessage(ChatColor.RED + "Invalid category. Use bullet, blitz, or rapid.");
                return true;
            }
            if (activeGames.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You cannot queue while in a game.");
                return true;
            }
            if (matchmaking.toggle(p, cat)) {
                p.sendMessage(ChatColor.GREEN + "You joined the " + cat + " matchmaking queue. Type /chessmatch " + cat + " again to leave.");
            } else {
                p.sendMessage(ChatColor.YELLOW + "You left the " + cat + " matchmaking queue.");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return args.length == 1 ? Arrays.asList("bullet", "blitz", "rapid") : Collections.emptyList();
        }
    }

    private class ChessLeaderboardCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String cat = args.length >= 1 ? args[0].toLowerCase() : "blitz";
            if (!cat.equals("bullet") && !cat.equals("blitz") && !cat.equals("rapid")) {
                sender.sendMessage(ChatColor.RED + "Invalid category. Use bullet, blitz, or rapid.");
                return true;
            }
            List<EloManager.LeaderEntry> top = eloManager.top(cat, 10);
            sender.sendMessage(ChatColor.GOLD + "=== Chess " + cat + " leaderboard (top " + top.size() + ") ===");
            int rank = 1;
            for (EloManager.LeaderEntry e : top) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(e.uuid);
                String name = op.getName() != null ? op.getName() : "Unknown";
                String title = eloManager.titleFor(e.rating);
                sender.sendMessage(ChatColor.GRAY + "  " + rank + ". " + ChatColor.WHITE + name
                        + ChatColor.GRAY + " - " + ChatColor.GOLD + e.rating
                        + (title != null ? ChatColor.DARK_AQUA + " [" + title + "]" : "")
                        + ChatColor.GRAY + " (" + e.games + " games)");
                rank++;
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return args.length == 1 ? Arrays.asList("bullet", "blitz", "rapid") : Collections.emptyList();
        }
    }

    private class ChessReplayCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== Recent games ===");
                int shown = 0;
                for (int i = 0; i < replayManager.games.size() && shown < 10; i++) {
                    ReplayManager.RecordedGame rg = replayManager.games.get(i);
                    sender.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " + ChatColor.WHITE + rg.white + " vs " + rg.black
                            + ChatColor.GRAY + " | " + ReplayManager.resultText(rg.result)
                            + ChatColor.GRAY + " | " + rg.minutes + "m | " + rg.moves.size() + " moves | /chessreplay " + (i + 1));
                    shown++;
                }
                if (replayManager.games.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "  No games recorded yet.");
                }
                return true;
            }
            if (args.length == 1) {
                try {
                    int idx = Integer.parseInt(args[0]);
                    if (idx < 1 || idx > replayManager.games.size()) {
                        sender.sendMessage(ChatColor.RED + "No replay at index " + idx + ".");
                        return true;
                    }
                    ReplayManager.RecordedGame rg = replayManager.games.get(idx - 1);
                    sender.sendMessage(ChatColor.GOLD + "Game #" + idx + ": " + rg.white + " vs " + rg.black
                            + " (" + rg.minutes + "m) - " + ReplayManager.resultText(rg.result));
                    StringBuilder sb = new StringBuilder();
                    int moveNo = 1;
                    for (int i = 0; i < rg.moves.size(); i += 2) {
                        if (sb.length() > 0) sb.append("  ");
                        sb.append(ChatColor.GRAY).append(moveNo).append(". ");
                        sb.append(ChatColor.WHITE).append(rg.moves.get(i));
                        if (i + 1 < rg.moves.size()) sb.append(" ").append(rg.moves.get(i + 1));
                        if (moveNo % 8 == 0) {
                            sender.sendMessage(sb.toString());
                            sb.setLength(0);
                        }
                        moveNo++;
                    }
                    if (sb.length() > 0) sender.sendMessage(sb.toString());
                    return true;
                } catch (NumberFormatException ex) {
                    // treat as player name filter
                }
                String who = args[0];
                sender.sendMessage(ChatColor.GOLD + "=== Games involving " + who + " ===");
                int shown = 0;
                for (int i = 0; i < replayManager.games.size() && shown < 10; i++) {
                    ReplayManager.RecordedGame rg = replayManager.games.get(i);
                    if (!rg.white.equalsIgnoreCase(who) && !rg.black.equalsIgnoreCase(who)) continue;
                    sender.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " + ChatColor.WHITE + rg.white + " vs " + rg.black
                            + ChatColor.GRAY + " | " + ReplayManager.resultText(rg.result)
                            + ChatColor.GRAY + " | " + rg.minutes + "m | /chessreplay " + (i + 1));
                    shown++;
                }
                if (shown == 0) {
                    sender.sendMessage(ChatColor.GRAY + "  No games found for " + who + ".");
                }
                return true;
            }
            sender.sendMessage(ChatColor.RED + "Usage: /chessreplay [index] [player]");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class ChessTourneyCommand implements TabExecutor {
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
            String sub = args.length >= 1 ? args[0].toLowerCase() : "status";
            switch (sub) {
                case "create": {
                    if (!p.hasPermission("chess.admin")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to create tournaments.");
                        return true;
                    }
                    if (tournament.state != TournamentManager.State.NONE) {
                        p.sendMessage(ChatColor.RED + "A tournament or lobby is already in progress.");
                        return true;
                    }
                    tournament.state = TournamentManager.State.LOBBY;
                    tournament.creator = p.getUniqueId();
                    p.sendMessage(ChatColor.GREEN + "Tournament lobby created! Players join with /chesstourney join. Start with /chesstourney start (needs 4 or 8).");
                    return true;
                }
                case "join": {
                    if (!tournament.lobbyOpen()) {
                        p.sendMessage(ChatColor.RED + "No tournament lobby is open.");
                        return true;
                    }
                    if (activeGames.containsKey(p.getUniqueId())) {
                        p.sendMessage(ChatColor.RED + "You cannot join while in a game.");
                        return true;
                    }
                    if (tournament.players.contains(p.getUniqueId())) {
                        p.sendMessage(ChatColor.RED + "You already joined the tournament.");
                        return true;
                    }
                    tournament.players.add(p.getUniqueId());
                    p.sendMessage(ChatColor.GREEN + "You joined the tournament (" + tournament.players.size() + " player" + (tournament.players.size() == 1 ? "" : "s") + " so far).");
                    return true;
                }
                case "leave": {
                    if (!tournament.players.remove(p.getUniqueId())) {
                        p.sendMessage(ChatColor.RED + "You are not in the tournament lobby.");
                        return true;
                    }
                    p.sendMessage(ChatColor.YELLOW + "You left the tournament lobby.");
                    return true;
                }
                case "start": {
                    if (!p.hasPermission("chess.admin")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to start tournaments.");
                        return true;
                    }
                    if (tournament.start(ChessPlugin.this)) {
                        p.sendMessage(ChatColor.GREEN + "Tournament started!");
                    } else {
                        p.sendMessage(ChatColor.RED + "Cannot start: the tournament needs exactly 4 or 8 players in the lobby.");
                    }
                    return true;
                }
                case "end": {
                    if (!p.hasPermission("chess.admin")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to end tournaments.");
                        return true;
                    }
                    if (tournament.state == TournamentManager.State.NONE) {
                        p.sendMessage(ChatColor.RED + "No tournament or lobby to end.");
                        return true;
                    }
                    tournament.end(ChessPlugin.this);
                    p.sendMessage(ChatColor.GREEN + "Tournament ended.");
                    return true;
                }
                case "status":
                default: {
                    StringBuilder sb = new StringBuilder();
                    sb.append(ChatColor.GOLD).append("=== Chess tournament ===");
                    if (tournament.state == TournamentManager.State.NONE) {
                        sb.append("\n").append(ChatColor.GRAY).append("  No tournament in progress.");
                        if (p.hasPermission("chess.admin")) {
                            sb.append("\n").append(ChatColor.GRAY).append("  Admin: /chesstourney create");
                        }
                    } else if (tournament.state == TournamentManager.State.LOBBY) {
                        sb.append("\n").append(ChatColor.GREEN).append("  Lobby open (").append(tournament.players.size()).append(" player").append(tournament.players.size() == 1 ? "" : "s").append(")");
                    } else if (tournament.state == TournamentManager.State.IN_PROGRESS) {
                        sb.append("\n").append(ChatColor.YELLOW).append("  Tournament in progress");
                    } else if (tournament.state == TournamentManager.State.FINISHED) {
                        sb.append("\n").append(ChatColor.GRAY).append("  Tournament finished");
                    }
                    p.sendMessage(sb.toString());
                    return true;
                }
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Arrays.asList("create", "join", "leave", "start", "end", "status");
            }
            return Collections.emptyList();
        }
    }
}
