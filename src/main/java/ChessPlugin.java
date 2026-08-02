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
                    ChessPiece piece = board.getPiece(guiRow, guiCol);
                    inv.setItem(slot, toItemFor(piece));
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
                    int invIndex;
                    if (br == 3) {
                        invIndex = col;
                    } else {
                        invIndex = 9 + br * 9 + col;
                    }
                    pinv.setItem(invIndex, item);
                }
            }
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
                int guiRow = raw / 9;
                int guiCol = raw % 9;
                if (guiRow >= 0 && guiRow < 4 && guiCol >= 0 && guiCol < 8) {
                    handleBoardClick(p, guiRow, guiCol);
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
                if (move.isDoublePawn) lastDoublePawn = new int[]{move.toX, move.toY};
                else lastDoublePawn = null;
                board.applyMove(move);
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
            for (Player p : Arrays.asList(white, black)) {
                if (!p.isOnline()) continue;
                InventoryView iv = p.getOpenInventory();
                if (iv == null) continue;
                Inventory top = iv.getTopInventory();
                if (top == null) continue;
                if (iv.getTitle().equals(ChatColor.DARK_GREEN + "Chess (8x8)")) {
                    for (int guiRow = 0; guiRow < 4; guiRow++) {
                        for (int guiCol = 0; guiCol < 8; guiCol++) {
                            int slot = guiRow * 9 + guiCol;
                            ChessPiece piece = board.getPiece(guiRow, guiCol);
                            top.setItem(slot, toItemFor(piece));
                        }
                        int ctrlSlot = guiRow * 9 + 8;
                        ItemStack info = top.getItem(ctrlSlot);
                        if (info != null && info.hasItemMeta()) {
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
                    PlayerInventory pinv = p.getInventory();
                    for (int br = 0; br < 4; br++) {
                        int boardRow = 4 + br;
                        for (int col = 0; col < 8; col++) {
                            ItemStack item = toItemFor(board.getPiece(boardRow, col));
                            int invIndex;
                            if (br == 3) invIndex = col;
                            else invIndex = 9 + br * 9 + col;
                            pinv.setItem(invIndex, item);
                        }
                    }
                }
            }
        }
    }

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
                    if (!p.hasMoved) {
                        ChessPiece hRook = getPiece(x, 7);
                        if (hRook != null && hRook.type == ChessPieceType.ROOK && !hRook.hasMoved) {
                            if (getPiece(x, 5) == null && getPiece(x, 6) == null) {
                                ChessMove m = new ChessMove(x, y, x, y + 2);
                                m.isCastling = true;
                                out.add(m);
                            }
                        }
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
            if (p.type == ChessPieceType.PAWN && Math.abs(m.toY - m.fromY) == 1 && getPiece(m.toX, m.toY) == null && m.toX != m.fromX) {
                int capX = m.fromX;
                int capY = m.toY;
                setPiece(capX, capY, null);
            }
            if (m.isCastling) {
                if (m.toY > m.fromY) {
                    ChessPiece rook = getPiece(m.fromX, 7);
                    setPiece(m.fromX, 5, rook);
                    setPiece(m.fromX, 7, null);
                    if (rook != null) rook.hasMoved = true;
                } else {
                    ChessPiece rook = getPiece(m.fromX, 0);
                    setPiece(m.fromX, 3, rook);
                    setPiece(m.fromX, 0, null);
                    if (rook != null) rook.hasMoved = true;
                }
            }
            p.hasMoved = true;
            setPiece(m.toX,m.toY,p);
            setPiece(m.fromX,m.fromY,null);
        }

        ChessBoard copy() {
            ChessBoard c = new ChessBoard();
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = b[x][y];
                if (p != null) {
                    c.b[x][y] = new ChessPiece(p.color, p.type, x, y);
                    c.b[x][y].hasMoved = p.hasMoved;
                }
            }
            c.whiteToMove = whiteToMove;
            return c;
        }

        boolean isKingInCheck(Color kingColor) {
            int kx=-1, ky=-1;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.type==ChessPieceType.KING && p.color==kingColor) { kx=x; ky=y; }
            }
            if (kx==-1) return true;
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
            if (!isKingInCheck(c)) return false;
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
