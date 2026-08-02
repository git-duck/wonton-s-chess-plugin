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

import java.net.URL;
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
 * - Includes an in-game toggle and a remote GitHub kill switch.
 */
public class ChessPlugin extends JavaPlugin implements Listener {

    public static NamespacedKey PIECE_KEY;
    private ChallengeManager challengeManager;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();
    
    // Plugin toggle state
    public boolean pluginEnabled = true;

    @Override
    public void onEnable() {
        // Run the remote kill switch check as soon as the plugin starts
        checkRemoteGitHubStatus();

        PIECE_KEY = new NamespacedKey(this, "chess_piece");
        this.challengeManager = new ChallengeManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        Objects.requireNonNull(this.getCommand("chess")).setExecutor(new ChessCommand());
        Objects.requireNonNull(this.getCommand("chessaccept")).setExecutor(new ChessAcceptCommand());
        Objects.requireNonNull(this.getCommand("chessdeny")).setExecutor(new ChessDenyCommand());
        Objects.requireNonNull(this.getCommand("chesstoggle")).setExecutor(new ChessToggleCommand());
        
        getLogger().info("ChessPlugin enabled");
    }

    @Override
    public void onDisable() {
        for (ChessGame g : new ArrayList<>(activeGames.values())) {
            g.endGame("Server shutting down");
        }
    }

    // --- GitHub Remote Kill Switch ---
    private void checkRemoteGitHubStatus() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // IMPORTANT: Replace this URL with the RAW URL of your text file on GitHub
                URL url = new URL("https://raw.githubusercontent.com/YourUsername/YourRepo/main/status.txt");
                Scanner scanner = new Scanner(url.openStream());
                
                if (scanner.hasNextLine()) {
                    String status = scanner.nextLine().trim();
                    
                    if (status.equalsIgnoreCase("false") || status.equalsIgnoreCase("off")) {
                        getLogger().warning("GitHub kill switch activated! Disabling ChessPlugin...");
                        pluginEnabled = false;
                        
                        // Bukkit requires plugins to be disabled on the main thread
                        Bukkit.getScheduler().runTask(this, () -> {
                            getServer().getPluginManager().disablePlugin(this);
                        });
                    } else {
                        getLogger().info("GitHub remote status is clear. Plugin allowed to run.");
                    }
                }
                scanner.close();
            } catch (Exception e) {
                getLogger().warning("Could not reach GitHub to check status. Defaulting to enabled. Error: " + e.getMessage());
            }
        });
    }

    // Commands ----------------------------------------------------------------

    private class ChessToggleCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player && !sender.isOp() && !sender.hasPermission("chess.admin")) {
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
        
        // Handle clicks in the Promotion Menu
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
            // Handle cases where they close the GUI without picking a promotion
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
        
        // Promotion state variables
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

        void tick() {
            if (!running || pendingPromotion) return; // Pause timer during promotion
            if (board.whiteToMove) {
                whiteTime--;
                if (whiteTime <= 0) endGame("Black wins on time");
            } else {
                blackTime--;
                if (blackTime <= 0) endGame("White wins on time");
            }
            updateAllInventories();
        }

        void endGame(String reason) {
            if (!running) return;
            running = false;
            if (timerTask != null) timerTask.cancel();
            sendBoth(ChatColor.YELLOW + "Game ended: " + reason);
            for (Player p : Arrays.asList(white, black)) {
                if (p.isOnline()) p.closeInventory();
            }
            for (UUID id : new ArrayList<>(saved.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) saved.get(id).restore(p);
                saved.remove(id);
            }
            plugin.removeGame(this);
        }

        void onPlayerQuit(Player p) {
            if (!running) return;
            Player other = (p.getUniqueId().equals(white.getUniqueId())) ? black : white;
            endGame(other.getName() + " wins (opponent disconnected)");
        }

        void onClose(Player p) {
            if (transitioning) return; // Don't restore inventory if just switching to/from promo menu
            SavedInventory s = saved.remove(p.getUniqueId());
            if (s != null) {
                s.restore(p);
                p.sendMessage(ChatColor.GRAY + "Your inventory has been restored. Reopen the board to continue.");
            }
        }

        void sendBoth(String s) {
            if (white.isOnline()) white.sendMessage(s);
            if (black.isOnline()) black.sendMessage(s);
        }

        void openFor(Player p) {
            if (!p.isOnline()) return;
            if (!saved.containsKey(p.getUniqueId())) {
                saved.put(p.getUniqueId(), SavedInventory.save(p));
            }
            Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "Chess (8x8)");
            for (int guiRow = 0; guiRow < 4; guiRow++) {
                for (int guiCol = 0; guiCol < 8; guiCol++) {
                    int slot = guiRow * 9 + guiCol;
                    inv.setItem(slot, toItemFor(board.getPiece(guiRow, guiCol)));
                }
                int ctrlSlot = guiRow * 9 + 8;
                String text;
                if (guiRow == 0) text = ChatColor.AQUA + "White: " + formatTime(whiteTime);
                else if (guiRow == 1) text = ChatColor.AQUA + "Black: " + formatTime(blackTime);
                else if (guiRow == 2) text = ChatColor.YELLOW + "Turn: " + (board.whiteToMove ? "White" : "Black");
                else text = ChatColor.GRAY + "8x8 Board";
                ItemStack info = new ItemStack(Material.PAPER);
                ItemMeta meta = info.getItemMeta();
                meta.setDisplayName(text);
                info.setItemMeta(meta);
                inv.setItem(ctrlSlot, info);
            }
            inv.setItem(45, createButton(Material.ARROW, ChatColor.GREEN + "Flip"));
            inv.setItem(46, createButton(Material.BARRIER, ChatColor.RED + "Resign"));
            inv.setItem(47, createButton(Material.CLOCK, ChatColor.GOLD + "Time"));
            inv.setItem(51, createButton(Material.PAPER, ChatColor.AQUA + "Info"));
            inv.setItem(53, createButton(Material.OAK_SIGN, ChatColor.GRAY + "Close"));

            PlayerInventory pinv = p.getInventory();
            for (int br = 0; br < 4; br++) {
                int boardRow = 4 + br;
                for (int col = 0; col < 8; col++) {
                    ItemStack item = toItemFor(board.getPiece(boardRow, col));
                    int invIndex = (br == 3) ? col : 9 + br * 9 + col;
                    pinv.setItem(invIndex, item);
                }
            }
            p.openInventory(inv);
        }

        // Promotion Menu Builder
        void openPromotionMenu(Player p) {
            Inventory inv = Bukkit.createInventory(null, 9, ChatColor.DARK_PURPLE + "Promote Pawn");
            inv.setItem(1, createPromoItem(Material.GOLD_BLOCK, ChessPieceType.QUEEN));
            inv.setItem(3, createPromoItem(Material.IRON_BLOCK, ChessPieceType.ROOK));
            inv.setItem(5, createPromoItem(Material.BRICK, ChessPieceType.BISHOP));
            inv.setItem(7, createPromoItem(Material.SADDLE, ChessPieceType.KNIGHT));
            p.openInventory(inv);
        }

        private ItemStack createPromoItem(Material mat, ChessPieceType type) {
            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + type.name());
            it.setItemMeta(meta);
            return it;
        }

        // Execute chosen promotion and continue the game loop
        void executePromotion(Player p, ChessPieceType type) {
            if (!pendingPromotion) return;
            board.setPiece(promotionX, promotionY, new ChessPiece(promotionColor, type, promotionX, promotionY));
            
            pendingPromotion = false;
            pendingPromotionPlayer = null;
            
            // Reopen board
            transitioning = true;
            openFor(p);
            transitioning = false;
            
            finishTurn();
        }

        private ItemStack createButton(Material mat, String name) {
            ItemStack it = new ItemStack(mat);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(name);
            it.setItemMeta(m);
            return it;
        }

        private String formatTime(int secs) {
            return String.format("%d:%02d", secs / 60, secs % 60);
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

        void onInventoryClick(Player p, InventoryClickEvent e) {
            if (!running) return;
            int raw = e.getRawSlot();
            if (raw < 54) {
                int r = raw / 9;
                int c = raw % 9;
                if (c == 8 && r >= 0 && r < 4) return;
                if (raw >= 45 && raw <= 53) {
                    ItemStack cur = e.getCurrentItem();
                    if (cur == null || !cur.hasItemMeta()) return;
                    String name = ChatColor.stripColor(cur.getItemMeta().getDisplayName());
                    switch (name) {
                        case "Flip": openFor(p); return;
                        case "Resign": endGame((p.getName()) + " resigned. " + ((p.getUniqueId().equals(white.getUniqueId())) ? black.getName() : white.getName()) + " wins."); return;
                        case "Time": p.sendMessage(ChatColor.AQUA + "White: " + formatTime(whiteTime) + " | Black: " + formatTime(blackTime)); return;
                        case "Info": p.sendMessage(ChatColor.GRAY + "8x8 combined board. Close to restore inventory."); return;
                        case "Close": p.closeInventory(); return;
                    }
                }
                if (r >= 0 && r < 4 && c >= 0 && c < 8) {
                    handleBoardClick(p, r, c);
                }
            } else {
                int bottomIndex = raw - 54;
                int brow = bottomIndex / 9;
                int bcol = bottomIndex % 9;
                if (brow >= 0 && brow < 4 && bcol >= 0 && bcol < 8) {
                    handleBoardClick(p, 4 + brow, bcol);
                }
            }
        }

        private void handleBoardClick(Player player, int br, int bc) {
            if (!running) return;
            ChessPiece clicked = board.getPiece(br, bc);
            
            if (selectedPlayer == null) {
                if (clicked == null) return;
                if (clicked.color == Color.WHITE && !board.whiteToMove) { player.sendMessage(ChatColor.RED + "It's not White's turn."); return; }
                if (clicked.color == Color.BLACK && board.whiteToMove) { player.sendMessage(ChatColor.RED + "It's not Black's turn."); return; }
                
                selectedPlayer = player.getUniqueId();
                selectedX = br; selectedY = bc;
                player.sendMessage(ChatColor.YELLOW + "Selected " + clicked.type.name() + " at " + squareName(br, bc));
            } else {
                if (!selectedPlayer.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Another player is mid-selection.");
                    return;
                }
                int fromX = selectedX, fromY = selectedY;
                List<ChessMove> legal = board.legalMoves(fromX, fromY, lastDoublePawn);
                Optional<ChessMove> chosen = legal.stream().filter(m -> m.toX == br && m.toY == bc).findFirst();
                
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
                lastDoublePawn = move.isDoublePawn ? new int[]{move.toX, move.toY} : null;
                
                board.applyMove(move);
                
                // Halt turn to open promotion menu
                if (move.promotion) {
                    pendingPromotion = true;
                    pendingPromotionPlayer = player.getUniqueId();
                    promotionX = move.toX;
                    promotionY = move.toY;
                    promotionColor = move.promotionColor;
                    
                    transitioning = true;
                    openPromotionMenu(player);
                    transitioning = false;
                    return; 
                }
                
                finishTurn();
            }
        }

        // Abstracted Turn Finisher logic
        void finishTurn() {
            board.whiteToMove = !board.whiteToMove;
            selectedPlayer = null; selectedX = -1; selectedY = -1;
            updateAllInventories();
            
            if (board.isInCheckmate(board.whiteToMove)) {
                endGame((board.whiteToMove ? "White" : "Black") + " is checkmated. " + (board.whiteToMove ? black.getName() : white.getName()) + " wins.");
            } else if (board.isStalemate(board.whiteToMove)) {
                endGame("Draw by stalemate.");
            }
        }

        private String squareName(int r, int c) {
            return "" + (char) ('a' + c) + (8 - r);
        }

        void updateAllInventories() {
            for (Player p : Arrays.asList(white, black)) {
                if (!p.isOnline()) continue;
                InventoryView iv = p.getOpenInventory();
                if (iv != null && iv.getTitle().equals(ChatColor.DARK_GREEN + "Chess (8x8)")) {
                    Inventory top = iv.getTopInventory();
                    for (int guiRow = 0; guiRow < 4; guiRow++) {
                        for (int guiCol = 0; guiCol < 8; guiCol++) {
                            top.setItem(guiRow * 9 + guiCol, toItemFor(board.getPiece(guiRow, guiCol)));
                        }
                        int ctrlSlot = guiRow * 9 + 8;
                        ItemStack info = top.getItem(ctrlSlot);
                        if (info != null && info.hasItemMeta()) {
                            ItemMeta meta = info.getItemMeta();
                            String text = (guiRow == 0) ? ChatColor.AQUA + "White: " + formatTime(whiteTime) :
                                          (guiRow == 1) ? ChatColor.AQUA + "Black: " + formatTime(blackTime) :
                                          (guiRow == 2) ? ChatColor.YELLOW + "Turn: " + (board.whiteToMove ? "White" : "Black") :
                                          ChatColor.GRAY + "8x8 Board";
                            meta.setDisplayName(text);
                            info.setItemMeta(meta);
                            top.setItem(ctrlSlot, info);
                        }
                    }
                    PlayerInventory pinv = p.getInventory();
                    for (int br = 0; br < 4; br++) {
                        for (int col = 0; col < 8; col++) {
                            int invIndex = (br == 3) ? col : 9 + br * 9 + col;
                            pinv.setItem(invIndex, toItemFor(board.getPiece(4 + br, col)));
                        }
                    }
                }
            }
        }
    }

    static class SavedInventory {
        final ItemStack[] contents, armor;
        final ItemStack offhand;
        private SavedInventory(ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
            this.contents = contents; this.armor = armor; this.offhand = offhand;
        }
        static SavedInventory save(Player p) {
            PlayerInventory inv = p.getInventory();
            return new SavedInventory(
                Arrays.stream(inv.getContents()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new),
                Arrays.stream(inv.getArmorContents()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new),
                inv.getItemInOffHand() == null ? null : inv.getItemInOffHand().clone()
            );
        }
        void restore(Player p) {
            PlayerInventory inv = p.getInventory();
            inv.setContents(Arrays.stream(contents).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
            inv.setArmorContents(Arrays.stream(armor).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
            inv.setItemInOffHand(offhand == null ? null : offhand.clone());
        }
    }

    // Engine ------------------------------------------------------------------
    enum Color { WHITE, BLACK }
    enum ChessPieceType { PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING }

    static class ChessPiece {
        final Color color; final ChessPieceType type; int x, y; boolean hasMoved = false;
        ChessPiece(Color color, ChessPieceType type, int x, int y) { this.color = color; this.type = type; this.x = x; this.y = y; }
        String toShortString() { return color.name().charAt(0) + "-" + type.name().charAt(0); }
    }

    static class ChessMove {
        int fromX, fromY, toX, toY;
        boolean isDoublePawn = false, promotion = false, isCastling = false;
        Color promotionColor = null;
        ChessMove(int fx,int fy,int tx,int ty){fromX=fx;fromY=fy;toX=tx;toY=ty;}
    }

    static class ChessBoard {
        private final ChessPiece[][] b = new ChessPiece[8][8];
        boolean whiteToMove = true;

        ChessPiece getPiece(int x, int y) { return (x<0||x>7||y<0||y>7) ? null : b[x][y]; }
        void setPiece(int x, int y, ChessPiece p) { if (x>=0&&x<=7&&y>=0&&y<=7) { b[x][y] = p; if (p!=null){p.x=x;p.y=y;} } }
        
        void resetInitialPosition() {
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) b[x][y]=null;
            for (int y=0;y<8;y++) {
                setPiece(1,y,new ChessPiece(Color.WHITE, ChessPieceType.PAWN,1,y));
                setPiece(6,y,new ChessPiece(Color.BLACK, ChessPieceType.PAWN,6,y));
            }
            setPiece(0,0,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,0)); setPiece(0,7,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,7));
            setPiece(7,0,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,0)); setPiece(7,7,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,7));
            setPiece(0,1,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,1)); setPiece(0,6,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,6));
            setPiece(7,1,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,1)); setPiece(7,6,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,6));
            setPiece(0,2,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,2)); setPiece(0,5,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,5));
            setPiece(7,2,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,2)); setPiece(7,5,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,5));
            setPiece(0,3,new ChessPiece(Color.WHITE,ChessPieceType.QUEEN,0,3)); setPiece(7,3,new ChessPiece(Color.BLACK,ChessPieceType.QUEEN,7,3));
            setPiece(0,4,new ChessPiece(Color.WHITE,ChessPieceType.KING,0,4)); setPiece(7,4,new ChessPiece(Color.BLACK,ChessPieceType.KING,7,4));
            whiteToMove = true;
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
                    if (m.isCastling) {
                        if (isKingInCheck(p.color)) continue;
                        int passY = m.fromY + (m.toY > m.fromY ? 1 : -1);
                        ChessBoard passCopy = copy();
                        passCopy.applyMove(new ChessMove(m.fromX, m.fromY, m.fromX, passY));
                        if (passCopy.isKingInCheck(p.color)) continue;
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
                        if (nx==7 || nx==0) { m.promotion = true; m.promotionColor = p.color; }
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
                                if (cx==7 || cx==0) { m.promotion=true; m.promotionColor=p.color;}
                                out.add(m);
                            }
                        }
                    }
                    if (lastDoublePawn != null) {
                        int ldX = lastDoublePawn[0], ldY = lastDoublePawn[1];
                        if (x == (p.color==Color.WHITE ? 4 : 3)) {
                            if (Math.abs(ldY - y) == 1 && ldX == x) {
                                out.add(new ChessMove(x,y,x+dir,ldY));
                            }
                        }
                    }
                    break;
                case KNIGHT:
                    for (int[] s: new int[][]{{1,2},{2,1},{-1,2},{-2,1},{1,-2},{2,-1},{-1,-2},{-2,-1}}) {
                        int tx=x+s[0], ty=
