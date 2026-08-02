package com.wonton.chess;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChessPlugin: combined top+player inventory 8x8 board.
 *
 * Safety:
 * - Player inventories are saved before showing board and restored on close/end.
 * - Clicks in board area are cancelled and handled as chess moves.
 *
 * Notes:
 * - Promotion opens a GUI for choice (Queen, Rook, Bishop, Knight).
 * - Castling, en passant, and check/checkmate are supported.
 */
public class ChessPlugin extends JavaPlugin implements Listener {

    public static NamespacedKey PIECE_KEY;
    private ChallengeManager challengeManager;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();
    
    // NEW: Plugin toggle state
    public boolean pluginEnabled = true;

    @Override
    public void onEnable() {
        PIECE_KEY = new NamespacedKey(this, "chess_piece");
        this.challengeManager = new ChallengeManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        Objects.requireNonNull(this.getCommand("chess")).setExecutor(new ChessCommand());
        Objects.requireNonNull(this.getCommand("chessaccept")).setExecutor(new ChessAcceptCommand());
        Objects.requireNonNull(this.getCommand("chessdeny")).setExecutor(new ChessDenyCommand());
        // NEW: Register toggle command
        Objects.requireNonNull(this.getCommand("chesstoggle")).setExecutor(new ChessToggleCommand());
        
        getLogger().info("ChessPlugin enabled");
    }

    @Override
    public void onDisable() {
        for (ChessGame g : new ArrayList<>(activeGames.values())) {
            g.endGame("Server shutting down");
        }
    }

    // Commands ----------------------------------------------------------------

    // NEW: Toggle Command
    private class ChessToggleCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player && !sender.isOp() && !sender.hasPermission("chess.toggle")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to toggle the chess plugin.");
                return true;
            }
            pluginEnabled = !pluginEnabled;
            sender.sendMessage(ChatColor.YELLOW + "Chess plugin is now " + (pluginEnabled ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED") + ".");
            
            if (!pluginEnabled) {
                for (ChessGame g : new ArrayList<>(activeGames.values())) {
                    g.endGame("An admin disabled the chess plugin.");
                }
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            
            // NEW: Check if plugin is enabled
            if (!pluginEnabled) {
                p.sendMessage(ChatColor.RED + "The chess plugin is currently disabled.");
                return true;
            }

            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Usage: /chess <player>");
                return true;
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
            challengeManager.createChallenge(p, target);
            p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + ". Expires in 30s.");
            target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a chess game! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
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

    private class ChessAcceptCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player accepter = (Player) sender;
            if (!pluginEnabled) {
                accepter.sendMessage(ChatColor.RED + "The chess plugin is currently disabled.");
                return true;
            }
            UUID challengerId = challengeManager.acceptChallenge(accepter);
            if (challengerId == null) {
                accepter.sendMessage(ChatColor.RED + "You have no pending challenges.");
                return true;
            }
            Player challenger = Bukkit.getPlayer(challengerId);
            if (challenger == null || !challenger.isOnline()) {
                accepter.sendMessage(ChatColor.RED + "Challenger is no longer online.");
                return true;
            }
            ChessGame game = new ChessGame(ChessPlugin.this, challenger, accepter);
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

    private class ChessDenyCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
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

    // Events ------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g == null) return;
        
        // NEW: Handle clicks in the Promotion Menu
        if (e.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Promote Pawn")) {
            e.setCancelled(true);
            if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;
            String name = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
            try {
                ChessPieceType type = ChessPieceType.valueOf(name);
                g.executePromotion(p, type);
            } catch (IllegalArgumentException ex) {
                // Not a valid piece, do nothing
            }
            return;
        }

        // Lock main board clicks if waiting for someone to promote
        if (g.pendingPromotion) {
            e.setCancelled(true);
            return;
        }

        e.setCancelled(true);
        if (e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Chess (8x8)")) {
            g.onInventoryClick(p, e);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        HumanEntity he = e.getPlayer();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            // NEW: Handle cases where they close the GUI without picking a promotion
            if (e.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Promote Pawn")) {
                if (g.pendingPromotion && p.getUniqueId().equals(g.pendingPromotionPlayer) && !g.transitioning) {
                    // Default to Queen if they attempt to escape the menu
                    g.executePromotion(p, ChessPieceType.QUEEN);
                }
            } else if (e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Chess (8x8)")) {
                g.onClose(p);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) g.onPlayerQuit(p);
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
                        if (c != null && c.isOnline()) c.sendMessage(ChatColor.RED + "Your challenge expired.");
                        if (t != null && t.isOnline()) t.sendMessage(ChatColor.RED + "Challenge expired.");
                        toRemove.add(e.getKey());
                    }
                }
                toRemove.forEach(pending::remove);
            }, 20L, 20L);
        }
        void createChallenge(Player challenger, Player target) {
            pending.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), System.currentTimeMillis() + 30_000L));
        }
        UUID acceptChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            return (c == null) ? null : c.challenger;
        }
        UUID denyChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            return (c == null) ? null : c.challenger;
        }
        static class Challenge {
            final UUID challenger;
            final long expiresAt;
            Challenge(UUID challenger, long expiresAt) { this.challenger = challenger; this.expiresAt = expiresAt; }
        }
    }

    // Chess game --------------------------------------------------------------
    static class ChessGame {
        final ChessPlugin plugin;
        final Player white;
        final Player black;
        final ChessBoard board;
        UUID selectedPlayer = null;
        int selectedX = -1, selectedY = -1;
        final Map<UUID, SavedInventory> saved = new HashMap<>();
        
        int whiteTime = 5 * 60;
        int blackTime = 5 * 60;
        BukkitTask timerTask;
        boolean running = false;
        int[] lastDoublePawn = null;
        
        // NEW: Promotion state variables
        boolean pendingPromotion = false;
        UUID pendingPromotionPlayer = null;
        int promotionX = -1, promotionY = -1;
        Color promotionColor = null;
        boolean transitioning = false; // Flag to prevent inv restoration during GUI swaps

        ChessGame(ChessPlugin plugin, Player white, Player black) {
            this.plugin = plugin;
            this.white = white;
            this.black = black;
            this.board = new ChessBoard();
        }

        void start() {
            board.resetInitialPosition();
            running = true;
            openFor(white);
            openFor(black);
            timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            sendBoth(ChatColor.GREEN + "Game started! White to move. 5-minute blitz.");
            updateAllInventories();
        }

        void tick()
