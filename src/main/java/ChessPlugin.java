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

import java.io.File;
import java.nio.file.Files;
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
 * - Includes status.txt kill switch.
 */
public class ChessPlugin extends JavaPlugin implements Listener {

    public static NamespacedKey PIECE_KEY;
    private ChallengeManager challengeManager;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Kill Switch Check
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File statusFile = new File(getDataFolder(), "status.txt");
        if (!statusFile.exists()) {
            try {
                Files.writeString(statusFile.toPath(), "true");
            } catch (Exception e) {
                getLogger().warning("Could not create status.txt");
            }
        } else {
            try {
                String content = Files.readString(statusFile.toPath()).trim();
                if (content.equalsIgnoreCase("false")) {
                    getLogger().warning("status.txt is set to false! Disabling ChessPlugin...");
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
            } catch (Exception e) {
                getLogger().warning("Could not read status.txt");
            }
        }

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
    // Clone inventory contents to avoid shared references
    ItemStack[] contents = Arrays.stream(p.getInventory().getContents())
            .map(it -> it == null ? null : it.clone())
            .toArray(ItemStack[]::new);
    ItemStack[] armor = Arrays.stream(p.getInventory().getArmorContents())
            .map(it -> it == null ? null : it.clone())
            .toArray(ItemStack[]::new);
    ItemStack off = p.getInventory().getItemInOffHand();
    off = (off == null ? null : off.clone());
    return new SavedInventory(contents, armor, off);
}

void restore(Player p) {
    PlayerInventory inv = p.getInventory();
    inv.setContents(Arrays.copyOf(contents, contents.length));
    inv.setArmorContents(Arrays.copyOf(armor, armor.length));
    inv.setItemInOffHand(offhand == null ? null : offhand.clone());
    // Ensure client sees the restored inventory
    p.updateInventory();
}
} // end of SavedInventory

} // end of ChessPlugin
