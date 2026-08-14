package com.wonton.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wonton.chess.ChessPlugin.ChessBoard;
import com.wonton.chess.ChessPlugin.ChessMove;
import com.wonton.chess.ChessPlugin.ChessPiece;
import com.wonton.chess.ChessPlugin.ChessPieceType;
import com.wonton.chess.ChessPlugin.Color;

class ChessEngineTest {

    // ---------- helpers ----------

    private static ChessPieceType typeOf(char c) {
        switch (c) {
            case 'P': return ChessPieceType.PAWN;
            case 'R': return ChessPieceType.ROOK;
            case 'N': return ChessPieceType.KNIGHT;
            case 'B': return ChessPieceType.BISHOP;
            case 'Q': return ChessPieceType.QUEEN;
            default:  return ChessPieceType.KING;
        }
    }

    // def like "WKg1", "BPd5"; moved=false -> castling/pawn-double availability
    private static void place(ChessBoard b, String def, boolean moved) {
        Color color = def.charAt(0) == 'W' ? Color.WHITE : Color.BLACK;
        int y = def.charAt(2) - 'a';
        int x = def.charAt(3) - '1';
        ChessPiece p = new ChessPiece(color, typeOf(def.charAt(1)), x, y);
        p.hasMoved = moved;
        b.setPiece(x, y, p);
    }

    private static ChessPiece piece(ChessBoard b, String sq) {
        int y = sq.charAt(0) - 'a';
        int x = sq.charAt(1) - '1';
        return b.getPiece(x, y);
    }

    private static boolean hasMoveTo(List<ChessMove> moves, int tx, int ty) {
        for (ChessMove m : moves) {
            if (m.toX == tx && m.toY == ty) return true;
        }
        return false;
    }

    private static boolean hasCastle(List<ChessMove> moves) {
        for (ChessMove m : moves) {
            if (m.isCastling) return true;
        }
        return false;
    }

    private static int countPieces(ChessBoard b) {
        int n = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                if (b.getPiece(x, y) != null) n++;
            }
        }
        return n;
    }

    // ---------- tests ----------

    @Test
    void initialPositionHasAllPieces() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        assertEquals(32, countPieces(b));
        assertEquals(Color.WHITE, piece(b, "e1").color);
        assertEquals(ChessPieceType.KING, piece(b, "e1").type);
        assertEquals(Color.BLACK, piece(b, "d8").color);
        assertEquals(ChessPieceType.QUEEN, piece(b, "d8").type);
        assertEquals(Color.WHITE, piece(b, "a1").color);
        assertEquals(Color.BLACK, piece(b, "h8").color);
        assertTrue(b.whiteToMove);
    }

    @Test
    void initialPawnAndKnightMoves() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        assertEquals(2, b.legalMoves(1, 4, null).size()); // e2 -> e3/e4
        assertTrue(hasMoveTo(b.legalMoves(1, 4, null), 2, 4));
        assertTrue(hasMoveTo(b.legalMoves(1, 4, null), 3, 4));
        assertEquals(2, b.legalMoves(0, 1, null).size()); // Nb1 -> a3/c3
        assertEquals(0, b.legalMoves(0, 0, null).size()); // Ra1 blocked by a2
    }

    @Test
    void pawnCapturesDiagonally() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "BKe8", "WPe4", "BPd5"}, true);
        List<ChessMove> moves = b.legalMoves(3, 4, null);
        assertTrue(hasMoveTo(moves, 4, 3)); // captures the d5 pawn
        assertTrue(hasMoveTo(moves, 4, 4)); // straight push to e5 also legal
    }

    @Test
    void blockedPawnCannotMove() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "BKe8", "WPe2", "BPe3"}, true);
        assertEquals(0, b.legalMoves(1, 4, null).size());
    }

    @Test
    void enPassantCaptureIsLegal() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "BKe8", "WPe5", "BPd5"}, true);
        int[] lastDoublePawn = {4, 3};
        assertTrue(hasMoveTo(b.legalMoves(4, 4, lastDoublePawn), 5, 3));
        assertFalse(hasMoveTo(b.legalMoves(4, 4, null), 5, 3));
    }

    @Test
    void enPassantRemovesPawnBehind() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "BKe8", "WPe5", "BPd5"}, true);
        ChessMove ep = new ChessMove(4, 4, 5, 3);
        b.applyMove(ep);
        assertNull(b.getPiece(4, 3));          // black pawn removed
        assertNotNull(b.getPiece(5, 3));       // white pawn on e6
        assertEquals(Color.WHITE, b.getPiece(5, 3).color);
    }

    @Test
    void kingsideCastleIsLegalAndMovesRook() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        b.setPiece(0, 5, null); // clear f1 bishop
        b.setPiece(0, 6, null); // clear g1 knight
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertTrue(hasMoveTo(kingMoves, 0, 6));
        ChessMove castle = null;
        for (ChessMove m : kingMoves) {
            if (m.isCastling) castle = m;
        }
        assertNotNull(castle);
        b.applyMove(castle);
        assertEquals(Color.WHITE, b.getPiece(0, 6).color);
        assertEquals(ChessPieceType.KING, b.getPiece(0, 6).type);
        assertEquals(ChessPieceType.ROOK, b.getPiece(0, 5).type);
        assertNull(b.getPiece(0, 7));
    }

    @Test
    void castlingBlockedWhenPiecesInTheWay() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertFalse(hasMoveTo(kingMoves, 0, 6)); // f1/g1 still occupied
        assertFalse(hasCastle(kingMoves));
    }

    @Test
    void cannotCastleThroughCheck() {
        ChessBoard b = new ChessBoard();
        place(b, "WKe1", false);
        place(b, "WRa1", false);
        place(b, "WRh1", false);
        place(b, "BKe8", true);
        place(b, "BRf8", true); // attacks f1, the kingside passing square
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertFalse(hasMoveTo(kingMoves, 0, 6)); // kingside through f1 illegal
        assertTrue(hasMoveTo(kingMoves, 0, 2));  // queenside still fine
    }

    @Test
    void cannotCastleOutOfCheck() {
        ChessBoard b = new ChessBoard();
        place(b, "WKe1", false);
        place(b, "WRa1", false);
        place(b, "WRh1", false);
        place(b, "BKa8", true);
        place(b, "BRe8", true); // checks e1 along the e-file
        assertTrue(b.isKingInCheck(Color.WHITE));
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertFalse(hasCastle(kingMoves));
    }

    @Test
    void castlingUnavailableFromSetPosition() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "WRh1", "BKe8"}, true);
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertFalse(hasMoveTo(kingMoves, 0, 6)); // kings marked as moved by setPosition
    }

    @Test
    void pinnedRookCanOnlyStayOnPinningLine() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "WRe2", "BKa8", "BRe8"}, true);
        List<ChessMove> rookMoves = b.legalMoves(1, 4, null);
        assertTrue(hasMoveTo(rookMoves, 2, 4));   // e3 still blocks
        assertTrue(hasMoveTo(rookMoves, 7, 4));   // e8 capture keeps the block
        assertFalse(hasMoveTo(rookMoves, 1, 0));  // a2 exposes the king
        assertFalse(hasMoveTo(rookMoves, 1, 7));  // h2 exposes the king
    }

    @Test
    void kingCannotMoveIntoCheck() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKe1", "BKe8", "BRf8"}, true);
        List<ChessMove> kingMoves = b.legalMoves(0, 4, null);
        assertFalse(hasMoveTo(kingMoves, 0, 5));  // f1 attacked by rook f8
        assertFalse(hasMoveTo(kingMoves, 1, 5));  // f2 attacked by rook f8
        assertTrue(hasMoveTo(kingMoves, 1, 4));   // e2 is safe
    }

    @Test
    void checkmateDetection() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{
            "WKe1", "WBc4", "WQf7",
            "BKe8", "BQd8", "BPa7", "BPb7", "BPc7", "BPd7", "BPg7", "BPh7"
        }, false); // black to move
        assertTrue(b.isKingInCheck(Color.BLACK));
        assertTrue(b.isInCheckmate(false, null));
        assertFalse(b.isStalemate(false, null));
    }

    @Test
    void stalemateDetection() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKc7", "WQb6", "BKa8"}, false); // black to move
        assertFalse(b.isKingInCheck(Color.BLACK));
        assertTrue(b.isStalemate(false, null));
        assertFalse(b.isInCheckmate(false, null));
    }

    @Test
    void applyMoveDoesNotToggleSideToMove() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        assertTrue(b.whiteToMove);
        b.applyMove(new ChessMove(1, 4, 2, 4));
        assertTrue(b.whiteToMove); // caller (TournamentManager) manages the toggle
        assertNotNull(b.getPiece(2, 4));
        assertNull(b.getPiece(1, 4));
    }

    @Test
    void copyIsIndependent() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        ChessBoard c = b.copy();
        c.applyMove(new ChessMove(1, 4, 2, 4));
        assertNull(b.getPiece(2, 4));
        assertNotNull(b.getPiece(1, 4));
        assertNotNull(c.getPiece(2, 4));
        assertEquals(32, countPieces(b));
        assertTrue(c.whiteToMove);
    }

    @Test
    void copyPreservesHasMovedFlags() {
        ChessBoard b = new ChessBoard();
        place(b, "WKe1", false);
        place(b, "WRh1", false);
        place(b, "BKe8", true);
        ChessBoard c = b.copy();
        assertFalse(c.getPiece(0, 4).hasMoved);
        assertFalse(c.getPiece(0, 7).hasMoved);
        assertTrue(c.getPiece(7, 4).hasMoved);
    }
}
