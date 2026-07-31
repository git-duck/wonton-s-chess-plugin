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
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        PIECE_KEY = new NamespacedKey(this, "chess_piece");
        this.challengeManager = new ChallengeManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(this.getCommand("chess")).setExecutor(new ChessCommand());
        Objects.requireNonNull(this.getCommand("chessaccept")).setExecutor(new ChessAcceptCommand());
        Objects.requireNonNull(this.getCommand("chessdeny")).setExecutor(new ChessDenyCommand());
        getLogger().info("ChessPlugin enabled");
    }

    @Override
    public void onDisable() {
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
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player accepter = (Player) sender;
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

    // Events ------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g == null) return;
        // delegate to game; always cancel so no items are moved in board area
        e.setCancelled(true);
        g.onInventoryClick(p, e);
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
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            g.onPlayerQuit(p);
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

        void createChallenge(Player challenger, Player target) {
            pending.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), System.currentTimeMillis() + 30_000L));
        }

        UUID acceptChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            if (c == null) return null;
            return c.challenger;
        }

        UUID denyChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            if (c == null) return null;
            return c.challenger;
        }

        static class Challenge {
            final UUID challenger;
            final long expiresAt;
            Challenge(UUID challenger, long expiresAt) { this.challenger = challenger; this.expiresAt = expiresAt; }
        }
    }

    // Chess game (engine + combined inventory GUI) ----------------------------

    static class ChessGame {
        final ChessPlugin plugin;
        final Player white;
        final Player black;
        final ChessBoard board;
        UUID selectedPlayer = null;
        int selectedX = -1, selectedY = -1;
        // track whether player's inventory has been replaced (so we know to restore)
        final Map<UUID, SavedInventory> saved = new HashMap<>();
        // timers
        int whiteTime = 5 * 60;
        int blackTime = 5 * 60;
        BukkitTask timerTask;
        boolean running = false;
        // en passant: last double pawn end coordinate or null
        int[] lastDoublePawn = null;

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
            if (!running) return;
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

        void onPlayerQuit(Player p) {
            if (!running) return;
            Player other = (p.getUniqueId().equals(white.getUniqueId())) ? black : white;
            endGame(other.getName() + " wins (opponent disconnected)");
        }

        void onClose(Player p) {
            // restore player's inventory when they close the board; game continues
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

        // Build and open combined view for player
        void openFor(Player p) {
            if (!p.isOnline()) return;
            // Save current inventory if we haven't already
            if (!saved.containsKey(p.getUniqueId())) {
                saved.put(p.getUniqueId(), SavedInventory.save(p));
            }
            // Build top inventory (54)
            Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "Chess (8x8)");
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
            // bottom chest row: controls
            inv.setItem(45, createButton(Material.ARROW, ChatColor.GREEN + "Flip"));
            inv.setItem(46, createButton(Material.BARRIER, ChatColor.RED + "Resign"));
            inv.setItem(47, createButton(Material.CLOCK, ChatColor.GOLD + "Time"));
            inv.setItem(51, createButton(Material.PAPER, ChatColor.AQUA + "Info"));
            inv.setItem(53, createButton(Material.OAK_SIGN, ChatColor.GRAY + "Close"));

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

        private ItemStack createButton(Material mat, String name) {
            ItemStack it = new ItemStack(mat);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(name);
            it.setItemMeta(m);
            return it;
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
                        case "Resign":
                            endGame((p.getName()) + " resigned. " + ((p.getUniqueId().equals(white.getUniqueId())) ? black.getName() : white.getName()) + " wins.");
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
                // player inventory region: rawSlot >= 54
                int bottomIndex = raw - 54;
                int brow = bottomIndex / 9;
                int bcol = bottomIndex % 9;
                // only accept brow 0..3 and bcol 0..7 as our board area
                if (brow >= 0 && brow < 4 && bcol >= 0 && bcol < 8) {
                    int boardRow = 4 + brow;
                    int boardCol = bcol;
                    handleBoardClick(p, boardRow, boardCol);
                    return;
                } else {
                    // clicks in the parts of player's inventory outside our mapped area (e.g., last column) are cancelled to avoid item movement issues
                    return;
                }
            }
        }

        private void handleBoardClick(Player player, int br, int bc) {
            if (!running) return;
            ChessPiece clicked = board.getPiece(br, bc);
            // if no selection started
            if (selectedPlayer == null) {
                if (clicked == null) return;
                if (clicked.color == Color.WHITE && !board.whiteToMove) {
                    player.sendMessage(ChatColor.RED + "It's not White's turn.");
                    return;
                }
                if (clicked.color == Color.BLACK && board.whiteToMove) {
                    player.sendMessage(ChatColor.RED + "It's not Black's turn.");
                    return;
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
                // en passant handling: ChessBoard.applyMove will remove captured pawn if needed
                if (move.isDoublePawn) lastDoublePawn = new int[]{move.toX, move.toY};
                else lastDoublePawn = null;
                board.applyMove(move);
                // promotion to queen if needed
                if (move.promotion) {
                    board.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, ChessPieceType.QUEEN, move.toX, move.toY));
                }
                board.whiteToMove = !board.whiteToMove;
                selectedPlayer = null; selectedX = -1; selectedY = -1;
                updateAllInventories();
                if (board.isInCheckmate(board.whiteToMove)) {
                    endGame((board.whiteToMove ? "White" : "Black") + " is checkmated. " + (board.whiteToMove ? black.getName() : white.getName()) + " wins.");
                    return;
                }
                if (board.isStalemate(board.whiteToMove)) {
                    endGame("Draw by stalemate.");
                    return;
                }
            }
        }

        private String squareName(int r, int c) {
            char file = (char) ('a' + c);
            int rank = 8 - r;
            return "" + file + rank;
        }

        void updateAllInventories() {
            // Refresh both players: update chest top and their player-inventory mapped slots
            for (Player p : Arrays.asList(white, black)) {
                if (!p.isOnline()) continue;
                InventoryView iv = p.getOpenInventory();
                if (iv == null) continue;
                Inventory top = iv.getTopInventory();
                if (top == null) continue;
                if (iv.getTitle().equals(ChatColor.DARK_GREEN + "Chess (8x8)")) {
                    // re-apply top area and player's mapped slots
                    // top:
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
                            String text;
                            if (guiRow == 0) text = ChatColor.AQUA + "White: " + formatTime(whiteTime);
                            else if (guiRow == 1) text = ChatColor.AQUA + "Black: " + formatTime(blackTime);
                            else if (guiRow == 2) text = ChatColor.YELLOW + "Turn: " + (board.whiteToMove ? "White" : "Black");
                            else text = ChatColor.GRAY + "8x8 Board";
                            meta.setDisplayName(text);
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

        boolean isInCheckmate(boolean whiteToMoveSide) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            // if king not in check -> false
            if (!isKingInCheck(c)) return false;
            // if no legal move for side -> checkmate
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, null).isEmpty()) return false;
                }
            }
            return true;
        }

        boolean isStalemate(boolean whiteToMoveSide) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            if (isKingInCheck(c)) return false;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, null).isEmpty()) return false;
                }
            }
            return true;
        }
    }
}
