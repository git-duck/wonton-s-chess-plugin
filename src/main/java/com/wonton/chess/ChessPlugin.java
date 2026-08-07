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
                target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a " + tc + "m chess game! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or [...]
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
                        sb.append("\n").append(ChatColor.GREEN).append("  Lobby open (").append(tournament.players.size()).append(" player").append(tournament.players.size() == 1 ? "" : "s").appe[...]
                        for (UUID id : tournament.players) {
                            Player pl = Bukkit.getPlayer(id);
                            sb.append("\n").append(ChatColor.GRAY).append("    - ").append(pl != null ? pl.getName() : "?");
                        }
                    } else {
                        sb.append("\n").append(ChatColor.GREEN).append("  Running - round ").append(tournament.round);
                        for (TournamentManager.Match m : tournament.matches) {
                            Player w = Bukkit.getPlayer(m.white);
                            Player b = Bukkit.getPlayer(m.black);
                            sb.append("\n").append(ChatColor.GRAY).append("    ").append(w != null ? w.getName() : "?")
                                    .append(" vs ").append(b != null ? b.getName() : "?")
                                    .append(m.resolved ? ChatColor.GOLD + " - finished" : ChatColor.GREEN + " - playing");
                        }
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
    public void onInventoryDrag(InventoryDragEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        // Prevent dragging items while a player's chess board (or spectator view) is open.
        if (activeGames.containsKey(p.getUniqueId()) || spectators.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
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
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        // Block dropping while player has a board open (or is spectating).
        if (activeGames.containsKey(p.getUniqueId()) || spectators.containsKey(p.getUniqueId())) {
            ItemStack dropped = e.getItemDrop().getItemStack();
            if (dropped != null && dropped.hasItemMeta() && dropped.getItemMeta().getPersistentDataContainer().has(PIECE_KEY, PersistentDataType.STRING)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You can't drop chess pieces while the board is open.");
                return;
            }
            // block all drops to avoid accidental loss/duplication while board open
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't drop items while the chess board is open.");
        }
    }

    @EventHandler
    public void onInventoryCreative(InventoryCreativeEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        // Prevent creative inventory actions while board is open
        if (activeGames.containsKey(p.getUniqueId()) || spectators.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot use the creative inventory while the chess board is open.");
            return;
        }
        // Also prevent placing items that carry the PIECE_KEY via creative
        ItemStack current = e.getCursor();
        if (current != null && current.hasItemMeta() && current.getItemMeta().getPersistentDataContainer().has(PIECE_KEY, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(PIECE_KEY, PersistentDataType.STRING)) {
            // Prevent pickup of any dropped chess piece, and remove the entity to avoid world duplication
            e.setCancelled(true);
            try {
                e.getItem().remove();
            } catch (Exception ignored) {}
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

        void createChallenge(Player challenger, Player target, int minutes, double bet, int increment, boolean gambit) {
            pending.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), System.currentTimeMillis() + 30_000L, minutes, bet, increment, gambit));
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
            final int increment;
            final boolean gambit;
            Challenge(UUID challenger, long expiresAt, int minutes, double bet, int increment, boolean gambit) {
                this.challenger = challenger;
                this.expiresAt = expiresAt;
                this.minutes = minutes;
                this.bet = bet;
                this.increment = increment;
                this.gambit = gambit;
            }
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

        // Titles are awarded from config thresholds (e.g. Candidate Master @ 1600)
        List<TitleTier> titleTiers() {
            List<TitleTier> tiers = new ArrayList<>();
            for (Map<?, ?> map : plugin.getConfig().getMapList("titles")) {
                Object t = map.get("threshold");
                Object n = map.get("name");
                if (t instanceof Number && n != null) {
                    tiers.add(new TitleTier(((Number) t).intValue(), n.toString()));
                }
            }
            tiers.sort(Comparator.comparingInt(t -> t.threshold));
            return tiers;
        }

        String titleFor(int rating) {
            String title = null;
            for (TitleTier t : titleTiers()) {
                if (rating >= t.threshold) title = t.name;
            }
            return title;
        }

        String titleFor(UUID id, String category) {
            return titleFor(getRating(id, category));
        }

        List<LeaderEntry> top(String category, int limit) {
            List<LeaderEntry> all = new ArrayList<>();
            for (Map.Entry<UUID, Map<String, Rating>> e : ratings.entrySet()) {
                Rating r = e.getValue().get(category);
                if (r != null && r.games > 0) {
                    all.add(new LeaderEntry(e.getKey(), r.rating, r.games));
                }
            }
            all.sort((a, b) -> b.rating - a.rating);
            List<LeaderEntry> out = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, all.size()); i++) out.add(all.get(i));
            return out;
        }

        static class TitleTier {
            final int threshold;
            final String name;
            TitleTier(int threshold, String name) { this.threshold = threshold; this.name = name; }
        }

        static class LeaderEntry {
            final UUID uuid;
            final int rating;
            final int games;
            LeaderEntry(UUID uuid, int rating, int games) { this.uuid = uuid; this.rating = rating; this.games = games; }
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

    // Per-player puzzle stats (streak, solved-today, lichess-style rating) persisted to puzzle.yml
    static class StatsManager {
        static final int DEFAULT_PUZZLE_RATING = 1200;
        private static final int PUZZLE_OPPONENT_RATING = 1500;
        private final JavaPlugin plugin;
        private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();

        StatsManager(JavaPlugin plugin) {
            this.plugin = plugin;
            plugin.getDataFolder().mkdirs();
            load();
        }

        long todayEpochDay() {
            return LocalDate.now(ZoneOffset.UTC).toEpochDay();
        }

        Stats get(UUID id) {
            return stats.computeIfAbsent(id, k -> new Stats());
        }

        int streak(UUID id) { return get(id).streak; }
        int bestStreak(UUID id) { return get(id).bestStreak; }
        boolean isSolvedToday(UUID id, long day) { return get(id).lastSolvedDay == day; }
        int solvedTotal(UUID id) { return get(id).solvedTotal; }
        int puzzleRating(UUID id) { return get(id).puzzleRating; }
        long lastRatedDay(UUID id) { return get(id).lastRatedDay; }

        // called when the daily puzzle is solved; streak grows across consecutive UTC days
        void onPuzzleSolved(UUID id, long day) {
            Stats s = get(id);
            s.solvedTotal++;
            if (s.lastSolvedDay == day) return; // already counted today
            if (s.lastSolvedDay == day - 1) s.streak++;
            else s.streak = 1;
            s.lastSolvedDay = day;
            if (s.streak > s.bestStreak) s.bestStreak = s.streak;
            save();
        }

        // lichess-style rating: expected score vs a fixed puzzle rating, K = 32. Applied once per day.
        void applyPuzzleResult(UUID id, boolean solved, long day) {
            Stats s = get(id);
            if (s.lastRatedDay == day) return;
            s.lastRatedDay = day;
            double expected = 1.0 / (1.0 + Math.pow(10.0, (PUZZLE_OPPONENT_RATING - s.puzzleRating) / 400.0));
            s.puzzleRating = Math.max(100, (int) Math.round(s.puzzleRating + 32.0 * ((solved ? 1.0 : 0.0) - expected)));
            save();
        }

        void load() {
            File f = new File(plugin.getDataFolder(), "puzzle.yml");
            if (!f.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String key : cfg.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Stats s = new Stats();
                s.streak = cfg.getInt(key + ".streak");
                s.bestStreak = cfg.getInt(key + ".best-streak");
                s.lastSolvedDay = cfg.getLong(key + ".last-solved-day", -1);
                s.solvedTotal = cfg.getInt(key + ".solved-total");
                s.puzzleRating = cfg.getInt(key + ".puzzle-rating", DEFAULT_PUZZLE_RATING);
                s.lastRatedDay = cfg.getLong(key + ".last-rated-day", -1);
                stats.put(id, s);
            }
        }

        void save() {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, Stats> en : stats.entrySet()) {
                String base = en.getKey().toString();
                Stats s = en.getValue();
                cfg.set(base + ".streak", s.streak);
                cfg.set(base + ".best-streak", s.bestStreak);
                cfg.set(base + ".last-solved-day", s.lastSolvedDay);
                cfg.set(base + ".solved-total", s.solvedTotal);
                cfg.set(base + ".puzzle-rating", s.puzzleRating);
                cfg.set(base + ".last-rated-day", s.lastRatedDay);
            }
            try {
                cfg.save(new File(plugin.getDataFolder(), "puzzle.yml"));
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save puzzle stats: " + ex.getMessage());
            }
        }

        static class Stats {
            int streak = 0;
            int bestStreak = 0;
            long lastSolvedDay = -1;
            int solvedTotal = 0;
            int puzzleRating = DEFAULT_PUZZLE_RATING;
            long lastRatedDay = -1;
        }
    }

    // Daily puzzles: mate-in-one/two/three motifs (pool rotates by calendar day)
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

        List<String> solutionMoves() {
            return Arrays.asList(solution.split("\\s+"));
        }

        int mateIn() {
            return (solutionMoves().size() + 1) / 2;
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
            new Puzzle("Back-Rank Queen (Black)", false, "Qd1#", new String[]{"WKg1", "WPf2", "WPg2", "WPh2", "BKg8", "BQd8"}),
            new Puzzle("Queen Sacrifice Smother", true, "Qg8+ Rxg8 Nf7#", new String[]{"WKg1", "WQd5", "WNh6", "BKh8", "BRf8", "BPg7", "BPh7"}),
            new Puzzle("Knight Corridor", true, "Nf7+ Kg8 Qe8#", new String[]{"WKg1", "WQe2", "WNe5", "BKh8", "BPg7", "BPh7"}),
            new Puzzle("Quiet Knight", true, "Ne6 Kg8 Qxg7#", new String[]{"WKg1", "WQc3", "WNc5", "BKh8", "BPg7", "BPh7"}),
            new Puzzle("Knight Walk", true, "Nf7 Kg8 Ng5 Kf8 Qd8#", new String[]{"WKg1", "WQd3", "WNe5", "BKh7", "BPg6", "BPg7"}),
            new Puzzle("Corridor Queen", true, "Nf7 Kg8 Qe8+ Kh7 Qh8#", new String[]{"WKg1", "WQe4", "WNe5", "BKh7", "BPg6", "BPg7"}),
            new Puzzle("Knight Corridor (Black)", false, "Nf7+ Kg8 Qe8#", new String[]{"BKg1", "BQe2", "BNe5", "WKh8", "WPg7", "WPh7"}),
            new Puzzle("Quiet Knight (Black)", false, "Ne6 Kg8 Qxg7#", new String[]{"BKg1", "BQc3", "BNc5", "WKh8", "WPg7", "WPh7"})
        );

        // Custom puzzles appended to the daily rotation (loaded from puzzles.yml).
        static final List<Puzzle> CUSTOM_PUZZLES = new ArrayList<>();

        static List<Puzzle> allPuzzles() {
            List<Puzzle> out = new ArrayList<>(PUZZLES);
            out.addAll(CUSTOM_PUZZLES);
            return out;
        }

        static Puzzle today() {
            List<Puzzle> pool = allPuzzles();
            long epochDay = LocalDate.now(ZoneOffset.UTC).toEpochDay();
            int idx = (int) Math.floorMod(epochDay, pool.size());
            Puzzle p = pool.get(idx);
            p.day = idx;
            return p;
        }

        static Puzzle byIndex(int index) {
            List<Puzzle> pool = allPuzzles();
            if (index < 1 || index > pool.size()) return null;
            Puzzle p = pool.get(index - 1);
            p.day = index - 1;
            return p;
        }

        // Replay a puzzle's solution from its start position; true if it ends in checkmate.
        static boolean valid(Puzzle p) {
            ChessBoard b = new ChessBoard();
            try {
                b.setPosition(p.pieces, p.whiteToMove);
            } catch (RuntimeException ex) {
                return false;
            }
            if (b.isKingInCheck(Color.WHITE) || b.isKingInCheck(Color.BLACK)) return false;
            int[] ldp = null;
            for (String san : p.solutionMoves()) {
                ChessMove m = findSan(b, ldp, san);
                if (m == null) return false;
                if (m.isDoublePawn) ldp = new int[]{m.toX, m.toY};
                else ldp = null;
                b.applyMove(m);
                if (m.promotion) b.setPiece(m.toX, m.toY, new ChessPiece(m.promotionColor, ChessPieceType.QUEEN, m.toX, m.toY));
                b.whiteToMove = !b.whiteToMove;
            }
            return b.isInCheckmate(b.whiteToMove, ldp);
        }

        // Find a legal move whose SAN matches the given string (mirrors ChessGame.findSanMove).
        static ChessMove findSan(ChessBoard b, int[] ldp, String san) {
            boolean side = b.whiteToMove;
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    ChessPiece pc = b.getPiece(x, y);
                    if (pc == null || (pc.color == Color.WHITE) != side) continue;
                    for (ChessMove m : b.legalMoves(x, y, ldp)) {
                        if (m.promotion) {
                            for (ChessPieceType t : new ChessPieceType[]{ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT}) {
                                if (ChessGame.sanForOn(b, m, t, ldp).equals(san)) return m;
                            }
                        } else if (ChessGame.sanForOn(b, m, null, ldp).equals(san)) {
                            return m;
                        }
                    }
                }
            }
            return null;
        }

        static void loadCustom(JavaPlugin plugin) {
            loadCustomFrom(new File(plugin.getDataFolder(), "puzzles.yml"), plugin.getLogger());
        }

        static void loadCustomFrom(File f, Logger log) {
            CUSTOM_PUZZLES.clear();
            if (!f.exists()) {
                log.info("No puzzles.yml found; no custom puzzles loaded.");
                return;
            }
            try {
                Object root = new org.yaml.snakeyaml.Yaml().load(new FileInputStream(f));
                if (!(root instanceof Map)) return;
                Object list = ((Map<?, ?>) root).get("custom-puzzles");
                if (!(list instanceof List)) return;
                for (Object o : (List<?>) list) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> map = (Map<?, ?>) o;
                    Object title = map.get("title");
                    Object solution = map.get("solution");
                    Object wtm = map.get("white-to-move");
                    Object pieces = map.get("pieces");
                    if (!(title instanceof String) || !(solution instanceof String) || !(pieces instanceof List)) {
                        log.warning("Skipping custom puzzle with missing/invalid fields: " + title);
                        continue;
                    }
                    List<String> pcs = new ArrayList<>();
                    for (Object pc : (List<?>) pieces) {
                        if (pc instanceof String) pcs.add((String) pc);
                    }
                    boolean whiteToMove = !(wtm instanceof Boolean) || (Boolean) wtm;
                    Puzzle p = new Puzzle(title.toString(), whiteToMove, solution.toString(), pcs.toArray(new String[0]));
                    if (valid(p)) {
                        CUSTOM_PUZZLES.add(p);
                        log.info("Loaded custom puzzle: " + p.title + " (mate in " + p.mateIn() + ")");
                    } else {
                        log.warning("Skipping invalid custom puzzle (solution cannot be replayed to mate): " + p.title);
                    }
                }
            } catch (IOException ex) {
                log.warning("Failed to read puzzles.yml: " + ex.getMessage());
            }
            log.info("Custom puzzles loaded: " + CUSTOM_PUZZLES.size());
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
        // daily puzzle mode (solutions may be mate in one, two, or three)
        boolean puzzleMode = false;
        boolean humanIsWhite = true;
        int puzzleDay = 0;
        String puzzleTitle = "";
        String puzzleSolution = "";
        List<String> puzzleSolutionMoves = new ArrayList<>();
        int puzzleMateIn = 1;
        int puzzleAttempts = 0;
        int puzzleMoveIndex = 0;
        boolean puzzleMateDelivered = false;
        // move history + draw detection
        final List<MoveEntry> moveHistory = new ArrayList<>();
        int halfmoveClock = 0; // plies since last capture or pawn move (50-move rule)
        final List<String> positionKeys = new ArrayList<>();
        ChessMove lastMove = null;
        final int increment; // Fischer increment in seconds per move
        ChessBoard startPosition = null;
        // draw offer (uuid of the player who offered)
        UUID drawOfferFrom = null;
        boolean confirmingResign = false;
        // gambit mode: a check doubles the payout multiplier
        boolean gambit = false;
        double gambitMultiplier = 1.0;
        // result of the last endGame call (used by replay/tournament hooks)
        GameResult finalResult = null;
        // hook run when the game ends (used by matchmaking/tournaments)
        Runnable onEnd = null;

        ChessGame(ChessPlugin plugin, Player white, Player black, int minutes) {
            this(plugin, white, black, minutes, 0);
        }

        ChessGame(ChessPlugin plugin, Player white, Player black, int minutes, int increment) {
            this.plugin = plugin;
            this.white = white;
            this.black = black;
            this.board = new ChessBoard();
            this.minutes = minutes;
            this.increment = increment;
            this.whiteTime = minutes * 60;
            this.blackTime = minutes * 60;
            this.title = ChatColor.DARK_GREEN + "Chess (" + minutes + (increment > 0 ? "+" + increment : "") + "m " + timeControlName().toLowerCase() + ")";
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
                startPosition = board.copy();
                openFor(white);
                sendBoth(ChatColor.LIGHT_PURPLE + "Daily Puzzle #" + (puzzleDay + 1) + ": " + puzzleTitle + " - find mate in " + puzzleMateIn + "!");
                sendBoth(ChatColor.GRAY + "You play " + (humanIsWhite ? "White" : "Black") + ". Wrong moves are not played. 'Hint' reveals the solution.");
                updateAllInventories();
                return;
            }
            board.resetInitialPosition();
            running = true;
            startPosition = board.copy();
            positionKeys.add(positionKey());
            if (!whiteIsAI) openFor(white);
            if (!blackIsAI) openFor(black);
            timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            String cat = categoryFor(minutes);
            sendBoth(ChatColor.GREEN + "Game started! " + timeControlName() + " (" + minutes + (increment > 0 ? "+" + increment : "") + "m).");
            if (bet > 0 && plugin.getEconomy() != null) {
                sendBoth(ChatColor.GOLD + "Bet: " + plugin.getEconomy().format(bet) + " each. Winner takes all.");
            }
            if (gambit) {
                sendBoth(ChatColor.LIGHT_PURPLE + "Gambit mode: every check doubles the payout multiplier (win only).");
            }
            if (whiteIsAI || blackIsAI) {
                sendBoth(ChatColor.GRAY + "You are " + (whiteIsAI ? "Black" : "White") + " vs AI (" + aiDifficulty.label + "). Ratings are not affected.");
            } else {
                String whiteRating = String.valueOf(plugin.eloManager.getRating(white.getUniqueId(), cat));
                String blackRating = String.valueOf(plugin.eloManager.getRating(black.getUniqueId(), cat));
                String matchInfo = ChatColor.GRAY + "White " + white.getName() + " [" + whiteRating + "] vs Black " + black.getName() + " [" + blackRating + "].";
                sendBoth(matchInfo);
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
            finalResult = result;
            if (timerTask != null) timerTask.cancel();
            pendingPromotion = null;
            promotionPlayer = null;
            sendBoth(ChatColor.YELLOW + "Game ended: " + reason);
            playEndSounds(result);
            if (result != GameResult.ABANDONED) {
                applyElo(result);
            }
            resolveBet(result);
            giveWinReward(result);
            if (!puzzleMode && result != GameResult.ABANDONED && !white.getUniqueId().equals(black.getUniqueId())) {
                plugin.replayManager.record(this, result);
            }
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
            if (onEnd != null) {
                try {
                    onEnd.run();
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("onEnd callback failed: " + ex.getMessage());
                }
            }
        }

        void resolveBet(GameResult result) {
            if (bet <= 0) return;
            Economy econ = plugin.getEconomy();
            if (econ == null) {
                plugin.getLogger().warning("Cannot settle chess bet of " + bet + ": economy unavailable.");
                return;
            }
            double factor = Math.max(1.0, gambitMultiplier);
            OfflinePlayer ow = Bukkit.getOfflinePlayer(white.getUniqueId());
            OfflinePlayer ob = Bukkit.getOfflinePlayer(black.getUniqueId());
            if (result == GameResult.WHITE_WIN) {
                econ.depositPlayer(ow, bet * 2 * factor);
                sendBoth(ChatColor.GOLD + "Bet settled: " + econ.format(bet * 2 * factor) + " paid to " + sideName(true)
                        + (factor > 1.0 ? " (gambit x" + factor + ")" : "") + ".");
            } else if (result == GameResult.BLACK_WIN) {
                econ.depositPlayer(ob, bet * 2 * factor);
                sendBoth(ChatColor.GOLD + "Bet settled: " + econ.format(bet * 2 * factor) + " paid to " + sideName(false)
                        + (factor > 1.0 ? " (gambit x" + factor + ")" : "") + ".");
            } else {
                econ.depositPlayer(ow, bet);
                econ.depositPlayer(ob, bet);
                sendBoth(ChatColor.GRAY + "Bet refunded: " + econ.format(bet) + " each.");
            }
        }

        // ... rest of ChessGame unchanged (omitted here for brevity in this preview) ...

        private ItemStack toItemFor(ChessPiece p, boolean glow) {
            if (p == null) return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            Material mat = materialFor(p);
            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName((p.color == Color.WHITE ? ChatColor.WHITE : ChatColor.DARK_GRAY) + p.type.name());
            meta.setLore(Collections.singletonList("x" + p.x + " y" + p.y));
            meta.getPersistentDataContainer().set(ChessPlugin.PIECE_KEY, PersistentDataType.STRING, p.toShortString());
            if (glow) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
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
                            p.sendMessage(ChatColor.AQUA + "Hint: play " + (puzzleMoveIndex < puzzleSolutionMoves.size() ? puzzleSolutionMoves.get(puzzleMoveIndex) : puzzleSolution) + ".");
                            return;
                        case "Resign":
                            if (confirmingResign) {
                                boolean whiteSide = p.getUniqueId().equals(white.getUniqueId());
                                endGame(p.getName() + " resigned. " + (whiteSide ? sideName(false) : sideName(true)) + " wins.",
                                        whiteSide ? GameResult.BLACK_WIN : GameResult.WHITE_WIN);
                            } else {
                                confirmingResign = true;
                                p.sendMessage(ChatColor.YELLOW + "Click Resign again to confirm.");
                                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                    if (confirmingResign && running) confirmingResign = false;
                                }, 100L);
                            }
                            return;
                        case "Time":
                            p.sendMessage(ChatColor.AQUA + "White: " + formatTime(whiteTime) + " | Black: " + formatTime(blackTime));
                            return;
                        case "Offer Draw":
                            offerDraw(p);
                            return;
                        case "Accept Draw":
                            acceptDrawOffer(p);
                            return;
                        case "Deny Draw":
                            denyDrawOffer(p);
                            return;
                        case "Undo":
                            undoLastMove(p);
                            return;
                        case "Moves":
                            sendMoveLog(p);
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
                refreshPlayer(player);
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
                        refreshPlayer(player);
                        return;
                    }
                    player.sendMessage(ChatColor.RED + "Illegal move.");
                    selectedPlayer = null; selectedX = -1; selectedY = -1;
                    refreshPlayer(player);
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

        // Daily puzzle: each player move must match the next expected move of the solution.
        void handlePuzzleMove(Player p, ChessMove move, ChessPieceType promo) {
            if (puzzleMoveIndex >= puzzleSolutionMoves.size()) return;
            String got = sanFor(move, promo);
            String expected = puzzleSolutionMoves.get(puzzleMoveIndex);
            if (!got.equals(expected)) {
                puzzleAttempts++;
                selectedPlayer = null; selectedX = -1; selectedY = -1;
                p.sendMessage(ChatColor.RED + "That is not the solution! Try again. (Attempt " + (puzzleAttempts + 1) + ")");
                updateAllInventories();
                return;
            }
            boolean finalMove = puzzleMoveIndex == puzzleSolutionMoves.size() - 1;
            completeMove(move, promo);
            puzzleMoveIndex++;
            if (!running) return;
            if (puzzleMateDelivered || finalMove) {
                puzzleMateDelivered = false;
                onPuzzleSolved(p);
                return;
            }
            // apply the opponent's forced reply
            ChessMove reply = findSanMove(puzzleSolutionMoves.get(puzzleMoveIndex));
            if (reply == null) {
                p.sendMessage(ChatColor.RED + "The puzzle solution is invalid (could not find the reply).");
                onPuzzleSolved(p);
                return;
            }
            completeMove(reply, reply.promotion ? ChessPieceType.QUEEN : null);
            puzzleMoveIndex++;
            puzzleMateDelivered = false;
            updateAllInventories();
        }

        // Find a legal move on the current board whose SAN matches the given string (side to move).
        ChessMove findSanMove(String san) {
            boolean side = board.whiteToMove;
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    ChessPiece pc = board.getPiece(x, y);
                    if (pc == null || (pc.color == Color.WHITE) != side) continue;
                    for (ChessMove m : board.legalMoves(x, y, lastDoublePawn)) {
                        if (m.promotion) {
                            for (ChessPieceType t : new ChessPieceType[]{ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT}) {
                                if (sanForOn(board, m, t, lastDoublePawn).equals(san)) return m;
                            }
                        } else if (sanForOn(board, m, null, lastDoublePawn).equals(san)) {
                            return m;
                        }
                    }
                }
            }
            return null;
        }

        void onPuzzleSolved(Player p) {
            boolean firstTry = puzzleAttempts == 0;
            StatsManager sm = plugin.getStatsManager();
            long day = sm.todayEpochDay();
            boolean alreadySolved = sm.isSolvedToday(p.getUniqueId(), day);
            sm.onPuzzleSolved(p.getUniqueId(), day);
            sm.applyPuzzleResult(p.getUniqueId(), true, day);
            p.sendMessage(ChatColor.GREEN + "Checkmate! You solved today's puzzle"
                    + (firstTry ? " on the first try" : " in " + (puzzleAttempts + 1) + " attempts") + "!");
            p.sendMessage(ChatColor.GRAY + "Puzzle rating: " + sm.puzzleRating(p.getUniqueId())
                    + " | Streak: " + sm.streak(p.getUniqueId()) + " (best " + sm.bestStreak(p.getUniqueId()) + ")"
                    + (alreadySolved ? "" : " | Total solved: " + sm.solvedTotal(p.getUniqueId())));
            int bonusDays = plugin.getConfig().getInt("puzzle.streak-bonus-days", 7);
            if (!alreadySolved && sm.streak(p.getUniqueId()) > 0 && sm.streak(p.getUniqueId()) % bonusDays == 0) {
                Economy econ = plugin.getEconomy();
                if (econ != null) {
                    double bonus = plugin.getConfig().getDouble("puzzle.streak-bonus", 50.0);
                    econ.depositPlayer(Bukkit.getOfflinePlayer(p.getUniqueId()), bonus);
                    p.sendMessage(ChatColor.GOLD + "Streak bonus: " + econ.format(bonus) + " for a " + sm.streak(p.getUniqueId()) + "-day streak!");
                }
            }
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f);
            endGame("You solved today's puzzle!", humanIsWhite ? GameResult.WHITE_WIN : GameResult.BLACK_WIN);
        }

        void giveUpPuzzle(Player p) {
            if (!running) return;
            StatsManager sm = plugin.getStatsManager();
            sm.applyPuzzleResult(p.getUniqueId(), false, sm.todayEpochDay());
            sendBoth(ChatColor.GRAY + "Solution: " + puzzleSolution + ". Come back tomorrow for a new one!");
            endGame("You gave up on today's puzzle.", GameResult.ABANDONED);
        }

        void completeMove(ChessMove move, ChessPieceType promotionTo) {
            ChessPiece mover = board.getPiece(move.fromX, move.fromY);
            boolean capture = board.getPiece(move.toX, move.toY) != null;
            if (!capture && mover != null && mover.type == ChessPieceType.PAWN && move.toY != move.fromY) {
                capture = true; // en passant capture
            }
            String san = sanFor(move, promotionTo);
            if (move.isDoublePawn) lastDoublePawn = new int[]{move.toX, move.toY};
            else lastDoublePawn = null;
            board.applyMove(move);
            if (promotionTo != null) {
                board.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, promotionTo, move.toX, move.toY));
            }
            board.whiteToMove = !board.whiteToMove;
            moveHistory.add(new MoveEntry(move, promotionTo, san));
            lastMove = move;
            if (capture || (mover != null && mover.type == ChessPieceType.PAWN)) halfmoveClock = 0;
            else halfmoveClock++;
            if (!puzzleMode) {
                if (board.whiteToMove) blackTime += increment;
                else whiteTime += increment;
            }
            selectedPlayer = null; selectedX = -1; selectedY = -1;
            drawOfferFrom = null;
            confirmingResign = false;
            updateAllInventories();
            if (board.isInCheckmate(board.whiteToMove, lastDoublePawn)) {
                if (puzzleMode) {
                    puzzleMateDelivered = true;
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
            String key = positionKey();
            positionKeys.add(key);
            int reps = 0;
            for (String k : positionKeys) {
                if (k.equals(key)) reps++;
            }
            if (reps >= 3) {
                endGame("Draw by threefold repetition.", GameResult.DRAW);
                return;
            }
            if (halfmoveClock >= 100) {
                endGame("Draw by the fifty-move rule.", GameResult.DRAW);
                return;
            }
            if (gambit && bet > 0 && board.isKingInCheck(board.whiteToMove ? Color.WHITE : Color.BLACK)) {
                double maxMul = plugin.getConfig().getDouble("gambit.max-multiplier", 16.0);
                if (gambitMultiplier < maxMul) {
                    double mult = plugin.getConfig().getDouble("gambit.double-multiplier", 2.0);
                    gambitMultiplier = Math.min(maxMul, gambitMultiplier * mult);
                    Economy econ = plugin.getEconomy();
                    sendBoth(ChatColor.DARK_RED + "Check! Gambit bet doubled: "
                            + (econ != null ? econ.format(bet * gambitMultiplier) : String.valueOf(bet * gambitMultiplier))
                            + " each on the line.");
                }
            }
            if (!puzzleMode) sendMoveLogLine();
            playMoveSound(mover, capture);
            maybeScheduleAI();
        }

        void sendMoveLogLine() {
            if (moveHistory.isEmpty() || moveHistory.size() % 2 != 0) return;
            int moveNum = moveHistory.size() / 2;
            MoveEntry w = moveHistory.get(moveHistory.size() - 2);
            MoveEntry b = moveHistory.get(moveHistory.size() - 1);
            sendBoth(ChatColor.GRAY + "" + moveNum + ". " + ChatColor.WHITE + w.san + " " + ChatColor.DARK_GRAY + b.san);
        }

        void playMoveSound(ChessPiece mover, boolean capture) {
            Sound s = capture ? Sound.BLOCK_NOTE_BLOCK_HARP : Sound.BLOCK_NOTE_BLOCK_PLING;
            if (!whiteIsAI && white.isOnline()) white.playSound(white.getLocation(), s, 0.5f, 1.0f);
            if (!blackIsAI && black.isOnline()) black.playSound(black.getLocation(), s, 0.5f, 1.0f);
        }

        void playEndSounds(GameResult result) {
            if (result == GameResult.WHITE_WIN) {
                if (!whiteIsAI && white.isOnline()) white.playSound(white.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f);
                if (!blackIsAI && black.isOnline()) black.playSound(black.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            } else if (result == GameResult.BLACK_WIN) {
                if (!blackIsAI && black.isOnline()) black.playSound(black.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f);
                if (!whiteIsAI && white.isOnline()) white.playSound(white.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            } else if (result == GameResult.DRAW) {
                if (!whiteIsAI && white.isOnline()) white.playSound(white.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.0f);
                if (!blackIsAI && black.isOnline()) black.playSound(black.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.0f);
            }
        }

        Player opponentOf(Player p) {
            return p.getUniqueId().equals(white.getUniqueId()) ? black : white;
        }

        void offerDraw(Player p) {
            if (!running) return;
            if (puzzleMode) {
                p.sendMessage(ChatColor.RED + "You cannot offer a draw in puzzles.");
                return;
            }
            if (drawOfferFrom != null) {
                p.sendMessage(ChatColor.YELLOW + "A draw offer is already pending.");
                return;
            }
            if (whiteIsAI || blackIsAI) {
                p.sendMessage(ChatColor.RED + "You cannot offer a draw against the AI.");
                return;
            }
            drawOfferFrom = p.getUniqueId();
            sendBoth(ChatColor.YELLOW + p.getName() + " offers a draw.");
            Player other = opponentOf(p);
            if (other.isOnline()) other.sendMessage(ChatColor.YELLOW + "Click 'Accept Draw' to accept or 'Deny Draw' to decline.");
            updateAllInventories();
        }

        void acceptDrawOffer(Player p) {
            if (drawOfferFrom == null) {
                p.sendMessage(ChatColor.RED + "There is no pending draw offer.");
                return;
            }
            drawOfferFrom = null;
            endGame("Draw by agreement.", GameResult.DRAW);
        }

        void denyDrawOffer(Player p) {
            if (drawOfferFrom == null) {
                p.sendMessage(ChatColor.RED + "There is no pending draw offer.");
                return;
            }
            drawOfferFrom = null;
            sendBoth(ChatColor.GRAY + "Draw offer declined.");
            updateAllInventories();
        }

        void sendMoveLog(Player p) {
            if (moveHistory.isEmpty()) {
                p.sendMessage(ChatColor.GRAY + "No moves played yet.");
                return;
            }
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            int moveNum = 1;
            for (int i = 0; i < moveHistory.size(); i++) {
                MoveEntry e = moveHistory.get(i);
                if (i % 2 == 0) {
                    if (i > 0) {
                        lines.add(line.toString());
                        line = new StringBuilder();
                    }
                    line.append(moveNum++).append(". ").append(e.san);
                } else {
                    line.append(" ").append(e.san);
                }
            }
            lines.add(line.toString());
            p.sendMessage(ChatColor.GOLD + "Move log:");
            for (String l : lines) p.sendMessage(ChatColor.GRAY + l);
        }

        static String pieceLetter(ChessPieceType t) {
            switch (t) {
                case PAWN: return "";
                case KNIGHT: return "N";
                case BISHOP: return "B";
                case ROOK: return "R";
                case QUEEN: return "Q";
                default: return "K";
            }
        }

        // SAN for a move on the current board (before it is played)
        String sanFor(ChessMove move, ChessPieceType promotionTo) {
            return sanForOn(board, move, promotionTo, lastDoublePawn);
        }

        // SAN generator usable from a bare board (used for puzzle solution matching and verification)
        static String sanForOn(ChessBoard b, ChessMove move, ChessPieceType promotionTo, int[] lastDoublePawn) {
            ChessPiece mover = b.getPiece(move.fromX, move.fromY);
            if (mover == null) return "";
            StringBuilder sb = new StringBuilder();
            if (move.isCastling) {
                sb.append(move.toY > move.fromY ? "O-O" : "O-O-O");
            } else {
                if (mover.type != ChessPieceType.PAWN) sb.append(pieceLetter(mover.type));
                if (mover.type != ChessPieceType.PAWN) {
                    List<String> others = new ArrayList<>();
                    for (int x = 0; x < 8; x++) {
                        for (int y = 0; y < 8; y++) {
                            ChessPiece p = b.getPiece(x, y);
                            if (p == null || (x == move.fromX && y == move.fromY)) continue;
                            if (p.type != mover.type || p.color != mover.color) continue;
                            for (ChessMove m : b.legalMoves(x, y, lastDoublePawn)) {
                                if (m.toX == move.toX && m.toY == move.toY) {
                                    others.add((char) ('a' + y) + "" + (x + 1));
                                    break;
                                }
                            }
                        }
                    }
                    if (!others.isEmpty()) {
                        boolean sameFile = others.stream().anyMatch(o -> o.charAt(0) == (char) ('a' + move.fromY));
                        boolean sameRank = others.stream().anyMatch(o -> o.charAt(1) == (char) ('0' + (move.fromX + 1)));
                        if (!sameFile) sb.append((char) ('a' + move.fromY));
                        else if (!sameRank) sb.append(move.fromX + 1);
                        else sb.append((char) ('a' + move.fromY)).append(move.fromX + 1);
                    }
                }
                boolean capture = b.getPiece(move.toX, move.toY) != null;
                if (!capture && mover.type == ChessPieceType.PAWN && move.toY != move.fromY) {
                    capture = true; // en passant
                }
                if (capture) {
                    if (mover.type == ChessPieceType.PAWN) sb.append((char) ('a' + move.fromY));
                    sb.append('x');
                }
                sb.append((char) ('a' + move.toY)).append(move.toX + 1);
                if (move.promotion) sb.append('=').append(pieceLetter(promotionTo == null ? ChessPieceType.QUEEN : promotionTo));
            }
            ChessBoard copy = b.copy();
            copy.applyMove(move);
            if (move.promotion && promotionTo != null) {
                copy.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, promotionTo, move.toX, move.toY));
            }
            copy.whiteToMove = !copy.whiteToMove;
            int[] nl = move.isDoublePawn ? new int[]{move.toX, move.toY} : null;
            if (copy.isInCheckmate(copy.whiteToMove, nl)) sb.append('#');
            else if (copy.isKingInCheck(copy.whiteToMove ? Color.WHITE : Color.BLACK)) sb.append('+');
            return sb.toString();
        }

        String positionKey() {
            return keyFor(board, lastDoublePawn);
        }

        private String squareName(int r, int c) {
            char file = (char) ('a' + c);
            int rank = r + 1;
            return "" + file + rank;
        }

        // Undo the last full move pair against the AI (or the last ply if the AI has not replied).
        void undoLastMove(Player p) {
            if (!running) {
                p.sendMessage(ChatColor.RED + "The game is not running.");
                return;
            }
            if (puzzleMode) {
                p.sendMessage(ChatColor.RED + "Undo is not available in puzzles.");
                return;
            }
            if (!(whiteIsAI || blackIsAI)) {
                p.sendMessage(ChatColor.RED + "Undo is only available when playing the AI.");
                return;
            }
            if (aiThinking) {
                p.sendMessage(ChatColor.RED + "Wait for the AI to finish thinking.");
                return;
            }
            if (moveHistory.isEmpty()) {
                p.sendMessage(ChatColor.RED + "There are no moves to undo.");
                return;
            }
            int plies = Math.min(2, moveHistory.size());
            for (int i = 0; i < plies; i++) moveHistory.remove(moveHistory.size() - 1);
            rebuildFromHistory();
            selectedPlayer = null; selectedX = -1; selectedY = -1;
            drawOfferFrom = null;
            confirmingResign = false;
            sendBoth(ChatColor.YELLOW + "Move undone by " + p.getName() + ".");
            updateAllInventories();
        }

        void rebuildFromHistory() {
            board.copyFrom(startPosition);
            lastDoublePawn = null;
            positionKeys.clear();
            halfmoveClock = 0;
            ChessBoard tmp = startPosition.copy();
            int[] ldp = null;
            positionKeys.add(keyFor(tmp, ldp));
            for (MoveEntry e : moveHistory) {
                ChessPiece mover = tmp.getPiece(e.move.fromX, e.move.fromY);
                boolean capture = tmp.getPiece(e.move.toX, e.move.toY) != null;
                if (!capture && mover != null && mover.type == ChessPieceType.PAWN && e.move.toY != e.move.fromY) capture = true;
                if (e.move.isDoublePawn) ldp = new int[]{e.move.toX, e.move.toY};
                else ldp = null;
                tmp.applyMove(e.move);
                if (e.promotionTo != null) {
                    tmp.setPiece(e.move.toX, e.move.toY, new ChessPiece(e.move.promotionColor, e.promotionTo, e.move.toX, e.move.toY));
                }
                tmp.whiteToMove = !tmp.whiteToMove;
                if (capture || (mover != null && mover.type == ChessPieceType.PAWN)) halfmoveClock = 0;
                else halfmoveClock++;
                positionKeys.add(keyFor(tmp, ldp));
            }
            board.copyFrom(tmp);
            lastDoublePawn = ldp;
        }

        static String keyFor(ChessBoard b, int[] ldp) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    ChessPiece p = b.getPiece(x, y);
                    if (p == null) sb.append('.');
                    else sb.append(p.color == Color.WHITE ? 'W' : 'B').append(p.type.name().charAt(0)).append(p.hasMoved ? 'm' : 'u');
                }
            }
            sb.append(b.whiteToMove ? 'w' : 'b');
            ChessPiece wk = b.getPiece(0, 4);
            ChessPiece bk = b.getPiece(7, 4);
            if (wk != null && wk.type == ChessPieceType.KING && wk.color == Color.WHITE && !wk.hasMoved) {
                ChessPiece wkr = b.getPiece(0, 7);
                ChessPiece wqr = b.getPiece(0, 0);
                if (wkr != null && wkr.type == ChessPieceType.ROOK && !wkr.hasMoved) sb.append('K');
                if (wqr != null && wqr.type == ChessPieceType.ROOK && !wqr.hasMoved) sb.append('Q');
            }
            if (bk != null && bk.type == ChessPieceType.KING && bk.color == Color.BLACK && !bk.hasMoved) {
                ChessPiece bkr = b.getPiece(7, 7);
                ChessPiece bqr = b.getPiece(7, 0);
                if (bkr != null && bkr.type == ChessPieceType.ROOK && !bkr.hasMoved) sb.append('k');
                if (bqr != null && bqr.type == ChessPieceType.ROOK && !bqr.hasMoved) sb.append('q');
            }
            if (ldp != null) sb.append((char) ('a' + ldp[1])).append(ldp[0] + 1);
            else sb.append('-');
            return sb.toString();
        }

        static class MoveEntry {
            final ChessMove move;
            final ChessPieceType promotionTo;
            final String san;
            MoveEntry(ChessMove move, ChessPieceType promotionTo, String san) {
                this.move = move;
                this.promotionTo = promotionTo;
                this.san = san;
            }
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
            renderControls(top, p, spectator);
            boolean viewer = !spectator && selectedPlayer != null && selectedPlayer.equals(p.getUniqueId()) && selectedX >= 0;
            List<int[]> highlights = null;
            if (viewer) {
                highlights = new ArrayList<>();
                for (ChessMove m : board.legalMoves(selectedX, selectedY, lastDoublePawn)) {
                    highlights.add(new int[]{m.toX, m.toY});
                }
            }
            int[] sel = viewer ? new int[]{selectedX, selectedY} : null;
            int[] lf = null, lt = null;
            if (lastMove != null) {
                lf = new int[]{lastMove.fromX, lastMove.fromY};
                lt = new int[]{lastMove.toX, lastMove.toY};
            }
            // top area
            for (int guiRow = 0; guiRow < 4; guiRow++) {
                for (int guiCol = 0; guiCol < 8; guiCol++) {
                    int slot = guiRow * 9 + guiCol;
                    top.setItem(slot, boardItem(board.getPiece(guiRow, guiCol), sel, highlights, lf, lt, guiRow, guiCol));
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
                    int invIndex = br == 3 ? col : 9 + br * 9 + col;
                    pinv.setItem(invIndex, boardItem(board.getPiece(boardRow, col), sel, highlights, lf, lt, boardRow, col));
                }
            }
        }

        private ItemStack boardItem(ChessPiece piece, int[] sel, List<int[]> hl, int[] lf, int[] lt, int r, int c) {
            boolean isHl = false;
            if (hl != null) {
                for (int[] h : hl) {
                    if (h[0] == r && h[1] == c) {
                        isHl = true;
                        break;
                    }
                }
            }
            if (piece == null) {
                if (isHl) return new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            }
            boolean glow = isHl
                    || (sel != null && sel[0] == r && sel[1] == c)
                    || (lf != null && lf[0] == r && lf[1] == c)
                    || (lt != null && lt[0] == r && lt[1] == c);
            return toItemFor(piece, glow);
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

    // ----------------- Replay, matchmaking, and tournaments -------------------

    // Recent finished games, text-replayable via /chessreplay (persisted to replays.yml)
    static class ReplayManager {
        static class RecordedGame {
            String white = "";
            String black = "";
            String result = "DRAW";
            int minutes = 3;
            long when = 0;
            List<String> moves = new ArrayList<>();
        }

        final ChessPlugin plugin;
        final List<RecordedGame> games = new ArrayList<>();

        ReplayManager(ChessPlugin plugin) {
            this.plugin = plugin;
            load();
        }

        void record(ChessGame g, ChessGame.GameResult result) {
            RecordedGame rg = new RecordedGame();
            rg.white = g.white.getName();
            rg.black = g.black.getName();
            rg.minutes = g.minutes;
            rg.when = System.currentTimeMillis();
            rg.result = result.name();
            for (ChessGame.MoveEntry me : g.moveHistory) {
                rg.moves.add(me.san);
            }
            games.add(0, rg);
            int max = plugin.getConfig().getInt("replay.max-games", 20);
            while (games.size() > max) games.remove(games.size() - 1);
            save();
        }

        static String resultText(String name) {
            switch (name) {
                case "WHITE_WIN": return ChatColor.GREEN + "White won";
                case "BLACK_WIN": return ChatColor.GREEN + "Black won";
                default: return ChatColor.YELLOW + "Draw";
            }
        }

        void load() {
            File f = new File(plugin.getDataFolder(), "replays.yml");
            if (!f.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            int max = plugin.getConfig().getInt("replay.max-games", 20);
            List<?> list = cfg.getList("games");
            if (list == null) return;
            for (int i = 0; i < list.size() && games.size() < max; i++) {
                Object o = list.get(i);
                if (!(o instanceof Map)) continue;
                Map<?, ?> map = (Map<?, ?>) o;
                RecordedGame rg = new RecordedGame();
                if (map.get("white") != null) rg.white = map.get("white").toString();
                if (map.get("black") != null) rg.black = map.get("black").toString();
                if (map.get("result") != null) rg.result = map.get("result").toString();
                rg.minutes = toInt(map.get("minutes"), 3);
                rg.when = toLong(map.get("when"), 0);
                if (map.get("moves") instanceof List) {
                    for (Object m : (List<?>) map.get("moves")) {
                        if (m != null) rg.moves.add(m.toString());
                    }
                }
                games.add(rg);
            }
        }

        void save() {
            YamlConfiguration cfg = new YamlConfiguration();
            List<Map<String, Object>> out = new ArrayList<>();
            for (RecordedGame rg : games) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("white", rg.white);
                m.put("black", rg.black);
                m.put("result", rg.result);
                m.put("minutes", rg.minutes);
                m.put("when", rg.when);
                m.put("moves", rg.moves);
                out.add(m);
            }
            cfg.set("games", out);
            try {
                cfg.save(new File(plugin.getDataFolder(), "replays.yml"));
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save replays: " + ex.getMessage());
            }
        }

        static int toInt(Object o, int dflt) {
            return o instanceof Number ? ((Number) o).intValue() : dflt;
        }

        static long toLong(Object o, long dflt) {
            return o instanceof Number ? ((Number) o).longValue() : dflt;
        }
    }

    // Queue-based matchmaking with rating-band pairing (/chessmatch)
    static class MatchmakingManager {
        static class QueueEntry {
            final UUID id;
            QueueEntry(UUID id) { this.id = id; }
        }

        final ChessPlugin plugin;
        final Map<String, List<QueueEntry>> queue = new ConcurrentHashMap<>();

        MatchmakingManager(ChessPlugin plugin) {
            this.plugin = plugin;
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
        }

        // toggles membership; returns true if the player was added, false if removed
        boolean toggle(Player p, String category) {
            List<QueueEntry> q = queue.computeIfAbsent(category, k -> new ArrayList<>());
            synchronized (q) {
                for (Iterator<QueueEntry> it = q.iterator(); it.hasNext(); ) {
                    if (it.next().id.equals(p.getUniqueId())) {
                        it.remove();
                        return false;
                    }
                }
                q.add(new QueueEntry(p.getUniqueId()));
                return true;
            }
        }

        void remove(UUID id) {
            for (List<QueueEntry> q : queue.values()) {
                synchronized (q) {
                    q.removeIf(e -> e.id.equals(id));
                }
            }
        }

        int minutesFor(String category) {
            if (category.equals("bullet")) return 1;
            if (category.equals("rapid")) return 10;
            return plugin.getConfig().getInt("matchmaking.default-time", 3);
        }

        void tick() {
            for (String cat : new String[]{"bullet", "blitz", "rapid"}) {
                while (tryMatch(cat)) { /* keep pairing until no compatible pair remains */ }
            }
        }

        boolean tryMatch(String category) {
            List<QueueEntry> q = queue.get(category);
            if (q == null || q.isEmpty()) return false;
            int band = plugin.getConfig().getInt("matchmaking.rating-band", 200);
            synchronized (q) {
                q.removeIf(e -> {
                    Player pp = Bukkit.getPlayer(e.id);
                    return pp == null || !pp.isOnline() || plugin.activeGames.containsKey(e.id);
                });
                if (q.size() < 2) return false;
                for (int i = 0; i < q.size(); i++) {
                    UUID a = q.get(i).id;
                    for (int j = i + 1; j < q.size(); j++) {
                        UUID b = q.get(j).id;
                        int ra = plugin.eloManager.getRating(a, category);
                        int rb = plugin.eloManager.getRating(b, category);
                        if (Math.abs(ra - rb) > band) continue;
                        Player pa = Bukkit.getPlayer(a);
                        Player pb = Bukkit.getPlayer(b);
                        if (pa == null || pb == null || !pa.isOnline() || !pb.isOnline()) continue;
                        // remove j first (higher index), then i
                        q.remove(j);
                        q.remove(i);
                        ChessGame game = new ChessGame(plugin, pa, pb, minutesFor(category));
                        plugin.activeGames.put(pa.getUniqueId(), game);
                        plugin.activeGames.put(pb.getUniqueId(), game);
                        game.start();
                        pa.sendMessage(ChatColor.GREEN + "Match found! You play " + pb.getName() + " (" + category + ").");
                        pb.sendMessage(ChatColor.GREEN + "Match found! You play " + pa.getName() + " (" + category + ").");
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Single-elimination tournaments (/chesstourney). One tournament at a time.
    static class TournamentManager {
        enum State { NONE, LOBBY, RUNNING }

        static class Match {
            final UUID white;
            final UUID black;
            UUID winner = null;
            ChessGame game = null;
            int attempts = 0;
            boolean resolved = false;
            Match(UUID white, UUID black) { this.white = white; this.black = black; }
        }

        State state = State.NONE;
        UUID creator = null;
        final List<UUID> players = new ArrayList<>();
        final List<Match> matches = new ArrayList<>();
        final List<UUID> waiting = new ArrayList<>();
        int round = 0;
        UUID champion = null;

        boolean lobbyOpen() { return state == State.LOBBY; }
        boolean running() { return state == State.RUNNING; }

        void startMatch(ChessPlugin plugin, Match m) {
            Player w = Bukkit.getPlayer(m.white);
            Player b = Bukkit.getPlayer(m.black);
            boolean wOff = w == null || !w.isOnline();
            boolean bOff = b == null || !b.isOnline();
            if (wOff || bOff) {
                m.winner = bOff ? m.white : m.black;
                m.resolved = true;
                m.game = null;
                waiting.add(m.winner);
                if (allResolved()) advanceRound(plugin);
                return;
            }
            ChessGame g = new ChessGame(plugin, w, b, 5);
            g.onEnd = () -> onMatchEnd(plugin, m);
            m.game = g;
            plugin.activeGames.put(w.getUniqueId(), g);
            plugin.activeGames.put(b.getUniqueId(), g);
            g.start();
        }

        boolean allResolved() {
            for (Match m : matches) {
                if (!m.resolved) return false;
            }
            return true;
        }

        void onMatchEnd(ChessPlugin plugin, Match m) {
            if (state != State.RUNNING || m.resolved) return;
            ChessGame.GameResult r = m.game == null ? ChessGame.GameResult.ABANDONED : m.game.finalResult;
            UUID winner = null;
            if (r == ChessGame.GameResult.WHITE_WIN) winner = m.white;
            else if (r == ChessGame.GameResult.BLACK_WIN) winner = m.black;
            else if (r == ChessGame.GameResult.ABANDONED) {
                Player w = Bukkit.getPlayer(m.white);
                Player b = Bukkit.getPlayer(m.black);
                boolean wOff = w == null || !w.isOnline();
                boolean bOff = b == null || !b.isOnline();
                if (!bOff && wOff) winner = m.white;
                else winner = m.black;
            }
            if (winner == null && m.attempts < 1) {
                // draw -> one automatic rematch
                m.attempts++;
                announceMatchResult(plugin, m, "draw", "rematch scheduled");
                startMatch(plugin, m);
                return;
            }
            if (winner == null) {
                // still undecided after rematch; higher blitz rating advances (white wins ties)
                int rw = plugin.eloManager.getRating(m.white, "blitz");
                int rb = plugin.eloManager.getRating(m.black, "blitz");
                winner = rw >= rb ? m.white : m.black;
            }
            m.winner = winner;
            m.resolved = true;
            announceMatchResult(plugin, m, winner.toString(), null);
            waiting.add(winner);
            if (allResolved()) advanceRound(plugin);
        }

        void announceMatchResult(ChessPlugin plugin, Match m, String winnerUuid, String extra) {
            Player w = Bukkit.getPlayer(m.white);
            Player b = Bukkit.getPlayer(m.black);
            String wn = w != null ? w.getName() : "?";
            String bn = b != null ? b.getName() : "?";
            String msg;
            if (extra != null) {
                msg = ChatColor.GRAY + "Tournament: " + wn + " vs " + bn + " ended in a " + extra + ".";
            } else {
                Player win = Bukkit.getPlayer(UUID.fromString(winnerUuid));
                msg = ChatColor.GOLD + "Tournament: " + (win != null ? win.getName() : "?") + " advances (" + wn + " vs " + bn + ").";
            }
            plugin.getServer().broadcastMessage(msg);
        }

        void advanceRound(ChessPlugin plugin) {
            if (waiting.size() == 1) {
                champion = waiting.get(0);
                Player c = Bukkit.getPlayer(champion);
                plugin.getServer().broadcastMessage(ChatColor.GOLD + "Chess tournament champion: " + (c != null ? c.getName() : "?") + "!");
                reset();
                return;
            }
            Collections.shuffle(waiting);
            matches.clear();
            for (int i = 0; i < waiting.size(); i += 2) {
                matches.add(new Match(waiting.get(i), waiting.get(i + 1)));
            }
            waiting.clear();
            round++;
            plugin.getServer().broadcastMessage(ChatColor.GOLD + "Tournament round " + round + " starting (" + matches.size() + " match" + (matches.size() == 1 ? "" : "es") + ").");
            for (Match m : new ArrayList<>(matches)) startMatch(plugin, m);
        }

        boolean start(ChessPlugin plugin) {
            if (state != State.LOBBY) return false;
            int n = players.size();
            if (n != 4 && n != 8) return false;
            Collections.shuffle(players);
            state = State.RUNNING;
            champion = null;
            round = 1;
            matches.clear();
            for (int i = 0; i < n; i += 2) {
                matches.add(new Match(players.get(i), players.get(i + 1)));
            }
            plugin.getServer().broadcastMessage(ChatColor.GOLD + "Tournament started with " + n + " players! Round 1.");
            for (Match m : new ArrayList<>(matches)) startMatch(plugin, m);
            return true;
        }

        void end(ChessPlugin plugin) {
            for (Match m : matches) {
                if (m.game != null && m.game.running) {
                    m.game.onEnd = null;
                    m.game.endGame("Tournament cancelled.", ChessGame.GameResult.ABANDONED);
                }
            }
            reset();
        }

        void reset() {
            state = State.NONE;
            creator = null;
            players.clear();
            matches.clear();
            waiting.clear();
            round = 0;
            champion = null;
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
            for (int y=0;y<8;y++) setPiece(6,y,new ChessPiece(Color.BLACK,ChessPieceType.PAWN,6,y));
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

        void copyFrom(ChessBoard other) {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    ChessPiece p = other.b[x][y];
                    if (p == null) {
                        b[x][y] = null;
                    } else {
                        ChessPiece np = new ChessPiece(p.color, p.type, x, y);
                        np.hasMoved = p.hasMoved;
                        b[x][y] = np;
                    }
                }
            }
            whiteToMove = other.whiteToMove;
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
