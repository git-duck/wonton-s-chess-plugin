package com.wonton.chess;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.wonton.chess.ChessPlugin.ChessAI;
import com.wonton.chess.ChessPlugin.ChessBoard;
import com.wonton.chess.ChessPlugin.ChessMove;
import com.wonton.chess.ChessPlugin.Color;

class ChessAITest {

    @Test
    void returnsSomeLegalMoveFromInitialPosition() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        ChessAI ai = new ChessAI();
        ChessMove m = ai.findBestMove(b, null, ChessAI.Difficulty.MEDIUM);
        assertNotNull(m);
        assertNotNull(b.getPiece(m.fromX, m.fromY));
        assertTrue(b.getPiece(m.fromX, m.fromY).color == Color.WHITE);
    }

    @Test
    void returnsNullWhenSideToMoveIsCheckmated() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{
            "WKe1", "WBc4", "WQf7",
            "BKe8", "BQd8", "BPa7", "BPb7", "BPc7", "BPd7", "BPg7", "BPh7"
        }, false); // black to move, scholar's mate
        ChessAI ai = new ChessAI();
        assertTrue(b.isInCheckmate(false, null));
        assertNull(ai.findBestMove(b, null, ChessAI.Difficulty.MEDIUM));
    }

    @Test
    void findsMateInOne() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKc6", "BKa8", "WQb4", "BPa7", "BPb7"}, true);
        ChessAI ai = new ChessAI();
        ChessMove m = ai.findBestMove(b, null, ChessAI.Difficulty.MEDIUM);
        assertNotNull(m);
        assertTrue(m.toX == 6 && m.toY == 1, "expected Qb4xb7# but got " + m.fromX + "," + m.fromY + "->" + m.toX + "," + m.toY);
    }

    @Test
    void mateScoreExceedsThreshold() {
        ChessBoard b = new ChessBoard();
        b.setPosition(new String[]{"WKc6", "BKa8", "WQb4", "BPa7", "BPb7"}, true);
        ChessAI ai = new ChessAI();
        ChessMove capture = new ChessMove(3, 1, 6, 1); // Qb4xb7
        int score = ai.scoreForMove(b, null, capture, null, ChessAI.Difficulty.MEDIUM);
        assertTrue(score > 500_000, "expected mate score, got " + score);
    }

    @Test
    void easyDifficultyStillPlays() {
        ChessBoard b = new ChessBoard();
        b.resetInitialPosition();
        ChessAI ai = new ChessAI();
        ChessMove m = ai.findBestMove(b, null, ChessAI.Difficulty.EASY);
        assertNotNull(m);
    }
}
