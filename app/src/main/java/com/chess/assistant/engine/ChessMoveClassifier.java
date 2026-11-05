package com.chess.assistant.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phân loại nước đi (Move Classification) kiểu Chess.com.
 *
 * Ý tưởng: dựa trên Expected Points (EP) — xác suất thắng của bên đi.
 * EP được tính từ centipawn eval bằng công thức sigmoid (tương tự Lichess):
 *
 * EP(cp) = 0.5 + 0.5 * (2 / (1 + e^(-0.00368208 * cp)) - 1) ∈ [0, 1]
 *
 * Sau đó với bên vừa đi (giả sử TRẮNG):
 * drop = EP(before) - EP(after)
 *
 * Nếu drop > 0 → bên đi mất lợi thế → nước dở.
 * Nếu drop < 0 → nước hoá ra tốt hơn dự đoán.
 *
 * Ngưỡng (giống Chess.com / Lichess):
 * drop < -0.03 → BRILLIANT (!!) — hiếm, khi engine depth khác biệt
 * drop ≤ 0.02 → BEST (★)
 * drop ≤ 0.05 → EXCELLENT (✓)
 * drop ≤ 0.10 → GOOD (·)
 * drop ≤ 0.20 → INACCURACY(?!)
 * drop ≤ 0.35 → MISTAKE (?)
 * drop > 0.35 → BLUNDER (??)
 *
 * QUAN TRỌNG: Depth của classification ĐỘC LẬP với depth phân tích của user.
 * Xem ChessFloatingService.CLASSIFICATION_DEPTH (cố định = 12).
 */
public class ChessMoveClassifier {

    public enum Classification {
        BRILLIANT("!!", "Thiên tài", 0xFF26C2A3),
        BEST("★", "Tốt nhất", 0xFF22C55E),
        EXCELLENT("✓", "Xuất sắc", 0xFF84CC16),
        GOOD("·", "Tốt", 0xFFA3E635),
        INACCURACY("?!", "Không chính xác", 0xFFEAB308),
        MISTAKE("?", "Sai lầm", 0xFFF97316),
        BLUNDER("??", "Sai nghiêm trọng", 0xFFDC2626),
        UNKNOWN("?", "Không xác định", 0xFF64748B);

        public final String icon;
        public final String name;
        public final int color;

        Classification(String icon, String name, int color) {
            this.icon = icon;
            this.name = name;
            this.color = color;
        }
    }

    public static class Result {
        public final Classification classification;
        public final String uciMove;
        public final String fromSq;
        public final String toSq;
        public final boolean whiteMoved;
        public final int evalBeforeCp;
        public final int evalAfterCp;
        public final double epBefore;
        public final double epAfter;
        public final double drop;

        public Result(Classification c, String uci, String from, String to, boolean wm,
                int eb, int ea, double epb, double epa, double d) {
            this.classification = c;
            this.uciMove = uci;
            this.fromSq = from;
            this.toSq = to;
            this.whiteMoved = wm;
            this.evalBeforeCp = eb;
            this.evalAfterCp = ea;
            this.epBefore = epb;
            this.epAfter = epa;
            this.drop = d;
        }

        @Override
        public String toString() {
            return (uciMove == null ? "?" : uciMove)
                    + " " + (whiteMoved ? "W" : "B")
                    + " → " + classification.icon + " " + classification.name
                    + String.format(java.util.Locale.US, " (drop %.1f%%)", drop * 100);
        }
    }

    /**
     * Expected Points (EP) từ centipawns (White's perspective).
     * Trả về ∈ [0, 1], càng gần 1 = White càng thắng chắc.
     */
    public static double expectedPoints(int cp) {
        if (cp > 2000)
            cp = 2000;
        if (cp < -2000)
            cp = -2000;
        return 0.5 + 0.5 * (2.0 / (1.0 + Math.exp(-0.00368208 * cp)) - 1.0);
    }

    /**
     * Phân loại nước đi từ fenBefore → fenAfter (uciMove đã có sẵn).
     * evalBefore, evalAfter: centipawns từ White's perspective.
     */
    public static Result classify(String fenBefore, String fenAfter, String uciMove,
            int evalBefore, int evalAfter) {
        if (uciMove == null || uciMove.length() < 4) {
            return new Result(Classification.UNKNOWN, null, null, null, true,
                    evalBefore, evalAfter, 0, 0, 0);
        }

        String fromSq = uciMove.substring(0, 2);
        String toSq = uciMove.substring(2, 4);

        char turn = 'w';
        String[] parts = fenBefore.split(" ");
        if (parts.length >= 2)
            turn = parts[1].charAt(0);
        boolean whiteMoved = (turn == 'w');

        double epBefore = expectedPoints(evalBefore);
        double epAfter = expectedPoints(evalAfter);

        // Drop từ góc nhìn của bên vừa đi
        double drop;
        if (whiteMoved) {
            drop = epBefore - epAfter; // White đi, EP giảm = nước dở
        } else {
            drop = epAfter - epBefore; // Black đi, EP tăng (tốt cho White) = Black đi dở
        }

        Classification cls;
        if (drop < -0.03) {
            cls = Classification.BRILLIANT;
        } else if (drop <= 0.02) {
            cls = Classification.BEST;
        } else if (drop <= 0.05) {
            cls = Classification.EXCELLENT;
        } else if (drop <= 0.10) {
            cls = Classification.GOOD;
        } else if (drop <= 0.20) {
            cls = Classification.INACCURACY;
        } else if (drop <= 0.35) {
            cls = Classification.MISTAKE;
        } else {
            cls = Classification.BLUNDER;
        }

        return new Result(cls, uciMove, fromSq, toSq, whiteMoved,
                evalBefore, evalAfter, epBefore, epAfter, drop);
    }

    /**
     * Tìm UCI move bằng cách diff 2 FEN liên tiếp.
     * Xử lý: move thường, capture, promotion, castling, en passant.
     * Trả null nếu không xác định được.
     */
    public static String diffToUci(String fenBefore, String fenAfter) {
        if (fenBefore == null || fenAfter == null)
            return null;
        char[][] before = parseFenBoard(fenBefore);
        char[][] after = parseFenBoard(fenAfter);
        if (before == null || after == null)
            return null;

        List<int[]> lostFrom = new ArrayList<>(); // [row, col, pieceChar]
        List<int[]> gainedAt = new ArrayList<>(); // [row, col, pieceChar]

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char b = before[r][f];
                char a = after[r][f];
                if (b == a)
                    continue;
                if (b != '.' && a == '.')
                    lostFrom.add(new int[] { r, f, b });
                if (a != '.')
                    gainedAt.add(new int[] { r, f, a });
            }
        }

        // Case 1: 1-from, 1-to → move thường hoặc capture
        if (lostFrom.size() == 1 && gainedAt.size() == 1) {
            int[] from = lostFrom.get(0);
            int[] to = gainedAt.get(0);
            String uci = sq(from[0], from[1]) + sq(to[0], to[1]);
            // Promotion?
            // [FIX] to[2] là int → cần cast (char) trước khi gọi Character.toLowerCase
            if (Character.toLowerCase((char) from[2]) == 'p' && (to[0] == 0 || to[0] == 7)) {
                char promoted = Character.toLowerCase((char) to[2]);
                if (promoted == 'q' || promoted == 'r' || promoted == 'b' || promoted == 'n')
                    uci += promoted;
            }
            return uci;
        }

        // Case 2: 2-from, 2-to → castling (King + Rook move)
        if (lostFrom.size() == 2 && gainedAt.size() == 2) {
            int[] kingFrom = null, kingTo = null;
            for (int[] lf : lostFrom)
                if (Character.toLowerCase((char) lf[2]) == 'k') {
                    kingFrom = lf;
                    break;
                }
            for (int[] gt : gainedAt)
                if (Character.toLowerCase((char) gt[2]) == 'k') {
                    kingTo = gt;
                    break;
                }
            if (kingFrom != null && kingTo != null)
                return sq(kingFrom[0], kingFrom[1]) + sq(kingTo[0], kingTo[1]);
        }

        // Case 3: 2-from, 1-to → en passant
        if (lostFrom.size() == 2 && gainedAt.size() == 1) {
            int[] to = gainedAt.get(0);
            for (int[] lf : lostFrom) {
                if (lf[2] == to[2]
                        && Math.abs(lf[1] - to[1]) == 1
                        && Math.abs(lf[0] - to[0]) == 1) {
                    return sq(lf[0], lf[1]) + sq(to[0], to[1]);
                }
            }
        }

        return null;
    }

    private static String sq(int r, int f) {
        return "" + (char) ('a' + f) + (char) ('8' - r);
    }

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
                } else if (Character.isDigit(c))
                    file += (c - '0');
                else {
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
}