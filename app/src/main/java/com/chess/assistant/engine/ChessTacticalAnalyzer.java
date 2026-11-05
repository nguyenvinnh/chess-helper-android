package com.chess.assistant.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phân tích chiến thuật cục bộ từ FEN — KHÔNG phụ thuộc engine online / cache.
 *
 * Trả về danh sách mũi tên:
 * 🔴 ĐỎ = quân ĐỐI THỦ tấn công quân TA
 * 🟡 VÀNG = quân TA tấn công quân ĐỊCH (TYPE_ATTACK)
 * hoặc quân TA bảo vệ quân TA đang bị tấn công (TYPE_DEFENSE)
 * 🟣 TÍM = quân ĐỊCH bảo vệ quân ĐỊCH — CHỈ vẽ khi quân bị bảo vệ đang
 * bị TA tấn công. Giúp phán đoán: "ăn quân này có bị địch
 * đáp trả không?"
 *
 * UPDATE (2026-11-03):
 * - Thêm analyzeAfterMove(fen, uciMove, playerIsWhite) — apply UCI move
 * (castling + promotion) rồi chạy lại analyze.
 * - Thêm helper applyUciMove() và buildFenFromBoard().
 * - Thêm filterNewArrows() để loại mũi tên preview trùng với tactical hiện tại.
 * - [RESTORE] Thêm Pass 3 phát hiện ĐỊCH BẢO VỆ ĐỊCH (TYPE_ENEMY_DEFENSE,
 * màu tím) — chỉ cho các quân địch đang bị ta tấn công, để người chơi
 * biết ô ăn quân có hộ vệ hay không.
 */
public class ChessTacticalAnalyzer {

    public static final int COLOR_RED = 0xE6EF4444; // 🔴
    public static final int COLOR_YELLOW = 0xE6FBBF24; // 🟡
    public static final int COLOR_ENEMY_DEFENSE = 0xE6E879F9; // 🟣 (tím/fuchsia)

    public static final String TYPE_ATTACK = "attack";
    public static final String TYPE_DEFENSE = "defense";
    public static final String TYPE_ENEMY_DEFENSE = "enemy_defense";

    public static class TacticalArrow {
        public final String fromSquare;
        public final String toSquare;
        public final int color;
        public final String type;
        public final String pieceChar;

        public TacticalArrow(String fromSquare, String toSquare, int color, String type, String pieceChar) {
            this.fromSquare = fromSquare;
            this.toSquare = toSquare;
            this.color = color;
            this.type = type;
            this.pieceChar = pieceChar;
        }

        @Override
        public String toString() {
            String icon;
            if (TYPE_ENEMY_DEFENSE.equals(type)) {
                icon = "🟣";
            } else if (color == COLOR_RED) {
                icon = "🔴";
            } else {
                icon = "🟡";
            }
            return icon + " " + fromSquare + "→" + toSquare + " [" + type + "]";
        }
    }

    // ── API công khai ─────────────────────────────────────────────────────

    public static List<TacticalArrow> analyze(String fen, boolean playerIsWhite) {
        List<TacticalArrow> arrows = new ArrayList<>();
        if (fen == null || fen.isEmpty())
            return arrows;

        char[][] board = parseFenBoard(fen);
        if (board == null)
            return arrows;

        boolean[][] ourPieceUnderAttack = new boolean[8][8];
        boolean[][] enemyPieceUnderOurAttack = new boolean[8][8];

        // ── Pass 1: ĐỊCH tấn công quân TA → RED ─────────────────────────────
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char attacker = board[r][f];
                if (attacker == '.')
                    continue;

                boolean attackerIsWhite = Character.isUpperCase(attacker);
                boolean attackerIsOpponent = (attackerIsWhite != playerIsWhite);
                if (!attackerIsOpponent)
                    continue;

                for (int[] t : attackedSquares(board, r, f, attacker)) {
                    char target = board[t[0]][t[1]];
                    if (target == '.')
                        continue;

                    boolean targetIsWhite = Character.isUpperCase(target);
                    boolean targetIsOurs = (targetIsWhite == playerIsWhite);
                    if (targetIsOurs) {
                        ourPieceUnderAttack[t[0]][t[1]] = true;
                        arrows.add(new TacticalArrow(
                                squareName(r, f), squareName(t[0], t[1]),
                                COLOR_RED, TYPE_ATTACK, String.valueOf(attacker)));
                    }
                }
            }
        }

        // ── Pass 2: TA tấn công ĐỊCH (YELLOW attack) + TA bảo vệ quân TA (YELLOW
        // defense)
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char attacker = board[r][f];
                if (attacker == '.')
                    continue;

                boolean attackerIsWhite = Character.isUpperCase(attacker);
                boolean attackerIsOurs = (attackerIsWhite == playerIsWhite);
                if (!attackerIsOurs)
                    continue;

                for (int[] t : attackedSquares(board, r, f, attacker)) {
                    char target = board[t[0]][t[1]];
                    if (target == '.')
                        continue;

                    boolean targetIsWhite = Character.isUpperCase(target);
                    boolean targetIsOurs = (targetIsWhite == playerIsWhite);

                    if (!targetIsOurs) {
                        enemyPieceUnderOurAttack[t[0]][t[1]] = true;
                        arrows.add(new TacticalArrow(
                                squareName(r, f), squareName(t[0], t[1]),
                                COLOR_YELLOW, TYPE_ATTACK, String.valueOf(attacker)));
                    } else if (ourPieceUnderAttack[t[0]][t[1]]) {
                        arrows.add(new TacticalArrow(
                                squareName(r, f), squareName(t[0], t[1]),
                                COLOR_YELLOW, TYPE_DEFENSE, String.valueOf(attacker)));
                    }
                }
            }
        }

        // ── Pass 3: ĐỊCH bảo vệ ĐỊCH ────────────────────────────────────────
        // CHỈ vẽ cho các quân địch đang bị TA tấn công — giúp người chơi
        // biết "ô ăn quân này có hộ vệ không" trước khi quyết định ăn.
        for (int dr = 0; dr < 8; dr++) {
            for (int df = 0; df < 8; df++) {
                char defender = board[dr][df];
                if (defender == '.')
                    continue;

                boolean defenderIsWhite = Character.isUpperCase(defender);
                boolean defenderIsEnemy = (defenderIsWhite != playerIsWhite);
                if (!defenderIsEnemy)
                    continue;

                for (int[] t : attackedSquares(board, dr, df, defender)) {
                    if (!enemyPieceUnderOurAttack[t[0]][t[1]])
                        continue;

                    char target = board[t[0]][t[1]];
                    if (target == '.')
                        continue;

                    boolean targetIsWhite = Character.isUpperCase(target);
                    boolean targetIsEnemy = (targetIsWhite != playerIsWhite);
                    if (!targetIsEnemy)
                        continue; // Chỉ quan tâm địch bảo vệ địch

                    arrows.add(new TacticalArrow(
                            squareName(dr, df), squareName(t[0], t[1]),
                            COLOR_ENEMY_DEFENSE, TYPE_ENEMY_DEFENSE,
                            String.valueOf(defender)));
                }
            }
        }

        return arrows;
    }

    /**
     * Áp dụng UCI move lên FEN rồi phân tích lại.
     * 
     * @return danh sách mũi tên chiến thuật sau khi đã đi nước đó.
     *         Nếu không apply được move → trả list rỗng.
     */
    public static List<TacticalArrow> analyzeAfterMove(String fen, String uciMove, boolean playerIsWhite) {
        String newFen = applyUciMove(fen, uciMove);
        if (newFen == null)
            return new ArrayList<>();
        return analyze(newFen, playerIsWhite);
    }

    /**
     * Loại khỏi `preview` những mũi tên có cùng (from,to) với `current`
     * → chỉ giữ các hướng tấn công MỚI xuất hiện sau khi đi nước đó.
     */
    public static List<TacticalArrow> filterNewArrows(List<TacticalArrow> preview,
            List<TacticalArrow> current) {
        if (preview == null)
            return new ArrayList<>();
        if (current == null || current.isEmpty())
            return preview;
        Set<String> seen = new HashSet<>();
        for (TacticalArrow a : current) {
            seen.add(a.fromSquare + a.toSquare);
        }
        List<TacticalArrow> result = new ArrayList<>();
        for (TacticalArrow a : preview) {
            if (!seen.contains(a.fromSquare + a.toSquare)) {
                result.add(a);
            }
        }
        return result;
    }

    // ── Apply UCI move ────────────────────────────────────────────────────

    private static String applyUciMove(String fen, String uci) {
        if (fen == null || uci == null || uci.length() < 4)
            return null;
        try {
            String[] parts = fen.split(" ");
            if (parts.length < 2)
                return null;

            char[][] board = parseFenBoard(parts[0]);
            if (board == null)
                return null;
            char turn = parts[1].charAt(0);

            int fromCol = uci.charAt(0) - 'a';
            int fromRow = 8 - (uci.charAt(1) - '0');
            int toCol = uci.charAt(2) - 'a';
            int toRow = 8 - (uci.charAt(3) - '0');

            if (!inBounds(fromRow, fromCol) || !inBounds(toRow, toCol))
                return null;
            char piece = board[fromRow][fromCol];
            if (piece == '.')
                return null;

            char pieceLower = Character.toLowerCase(piece);
            boolean white = Character.isUpperCase(piece);

            // Castling: king moves 2 files
            if (pieceLower == 'k' && Math.abs(toCol - fromCol) == 2) {
                board[fromRow][fromCol] = '.';
                board[toRow][toCol] = piece;
                if (toCol > fromCol) {
                    char rook = board[fromRow][7];
                    board[fromRow][7] = '.';
                    board[fromRow][5] = rook;
                } else {
                    char rook = board[fromRow][0];
                    board[fromRow][0] = '.';
                    board[fromRow][3] = rook;
                }
            } else {
                board[fromRow][fromCol] = '.';
                if (pieceLower == 'p' && (toRow == 0 || toRow == 7)) {
                    char promo = 'q';
                    if (uci.length() >= 5) {
                        char p = Character.toLowerCase(uci.charAt(4));
                        if (p == 'n' || p == 'b' || p == 'r' || p == 'q')
                            promo = p;
                    }
                    board[toRow][toCol] = white ? Character.toUpperCase(promo) : promo;
                } else {
                    board[toRow][toCol] = piece;
                }
            }

            char newTurn = (turn == 'w') ? 'b' : 'w';
            return buildFenFromBoard(board, newTurn);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildFenFromBoard(char[][] board, char turn) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                char c = board[r][f];
                if (c == '.') {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(c);
                }
            }
            if (empty > 0)
                sb.append(empty);
            if (r < 7)
                sb.append('/');
        }
        StringBuilder castling = new StringBuilder();
        if (board[7][4] == 'K') {
            if (board[7][7] == 'R')
                castling.append('K');
            if (board[7][0] == 'R')
                castling.append('Q');
        }
        if (board[0][4] == 'k') {
            if (board[0][7] == 'r')
                castling.append('k');
            if (board[0][0] == 'r')
                castling.append('q');
        }
        sb.append(' ').append(turn).append(' ')
                .append(castling.length() > 0 ? castling : "-")
                .append(" - 0 1");
        return sb.toString();
    }

    // ── FEN parsing ────────────────────────────────────────────────────────

    private static char[][] parseFenBoard(String fen) {
        try {
            char[][] board = new char[8][8];
            for (char[] row : board)
                Arrays.fill(row, '.');

            String boardPart = fen.split(" ")[0];
            int rank = 0, file = 0;
            for (char c : boardPart.toCharArray()) {
                if (c == '/') {
                    rank++;
                    file = 0;
                } else if (Character.isDigit(c)) {
                    file += (c - '0');
                } else {
                    if (rank < 8 && file < 8)
                        board[rank][file] = c;
                    file++;
                }
            }
            return (rank == 7) ? board : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String squareName(int rank, int file) {
        return "" + (char) ('a' + file) + (char) ('8' - rank);
    }

    private static boolean inBounds(int r, int f) {
        return r >= 0 && r < 8 && f >= 0 && f < 8;
    }

    // ── Attack generation ─────────────────────────────────────────────────

    private static List<int[]> attackedSquares(char[][] board, int r, int f, char piece) {
        List<int[]> result = new ArrayList<>();
        boolean white = Character.isUpperCase(piece);
        char p = Character.toLowerCase(piece);

        if (p == 'p') {
            int dir = white ? -1 : 1;
            if (inBounds(r + dir, f - 1))
                result.add(new int[] { r + dir, f - 1 });
            if (inBounds(r + dir, f + 1))
                result.add(new int[] { r + dir, f + 1 });
        } else if (p == 'n') {
            int[][] off = { { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 } };
            for (int[] o : off) {
                int nr = r + o[0], nf = f + o[1];
                if (inBounds(nr, nf))
                    result.add(new int[] { nr, nf });
            }
        } else if (p == 'k') {
            for (int dr = -1; dr <= 1; dr++)
                for (int df = -1; df <= 1; df++) {
                    if (dr == 0 && df == 0)
                        continue;
                    if (inBounds(r + dr, f + df))
                        result.add(new int[] { r + dr, f + df });
                }
        } else if (p == 'b' || p == 'r' || p == 'q') {
            int[][] dirs;
            if (p == 'b')
                dirs = new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
            else if (p == 'r')
                dirs = new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
            else
                dirs = new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }, { -1, 0 }, { 1, 0 }, { 0, -1 },
                        { 0, 1 } };

            for (int[] d : dirs) {
                int nr = r + d[0], nf = f + d[1];
                while (inBounds(nr, nf)) {
                    result.add(new int[] { nr, nf });
                    if (board[nr][nf] != '.')
                        break;
                    nr += d[0];
                    nf += d[1];
                }
            }
        }
        return result;
    }
}