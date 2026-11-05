package com.chess.assistant.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Offline chess engine (Stockfish Classic-style HCE alpha-beta).
 *
 * UPDATE (2026-11-03 — LẦN 13 — ONLINE-ONLY TỪ DEPTH 5):
 * - [FIX] MAX_DEPTH 6 → 4. Depth 6 pure-Java trên mobile có thể mất
 * 30-60s+ ở thế khai cuộc (alpha-beta không có killer/history pruning).
 * Depth 4 chạy ~1s — an toàn cho UX và đủ tốt để làm gợi ý chính
 * khi depth < 5.
 * - Offline engine CHỈ được dùng khi user chọn depth 1-4. Từ depth 5+
 * trở đi, ChessEngineClient chỉ dùng Stockfish Online (không có
 * offline backup) — xem ChessEngineClient.evaluateAsync().
 *
 * Đặc điểm:
 * • Pure Java — không cần native lib, không cần mạng.
 * • Hand-Crafted Evaluation (material + PST).
 * • Alpha-beta negamax, move ordering by captures.
 * • Multi-PV bằng cách loại dần move đã chọn (rank 1 → rank N).
 */
public class OfflineStockfishEngine {

    public static class OfflineLine {
        public final String move;
        public final int score; // centipawns from WHITE perspective
        public final int rank;

        public OfflineLine(String move, int score, int rank) {
            this.move = move;
            this.score = score;
            this.rank = rank;
        }

        public String getEvalString() {
            if (Math.abs(score) > 29000) {
                int plies = 30000 - Math.abs(score);
                int moves = (plies + 1) / 2;
                return (score > 0 ? "+M" : "-M") + moves;
            }
            return String.format(Locale.US, "%+.2f", score / 100.0);
        }
    }

    // ── Constants ─────────────────────────────────────────────────────

    private static final int MATE_VALUE = 30000;

    /** Piece values: index by piece index (1=P, 2=N, 3=B, 4=R, 5=Q, 6=K). */
    private static final int[] PVAL = { 0, 100, 320, 330, 500, 900, 20000 };

    private static final int[][] PST = buildPSTs();

    /**
     * [FIX] 4 (trước: 6) — depth 6 pure-Java trên mobile quá chậm ở
     * thế khai cuộc (30-60s). Depth 4 chạy ~1s, an toàn cho UX.
     * User depth 1-4 → dùng đúng depth họ chọn. Từ depth 5+ →
     * ChessEngineClient chuyển sang online-only, không gọi engine này.
     */
    public static final int MAX_DEPTH = 4;
    /** Cứng: PV 1..3. */
    public static final int MAX_MULTI_PV = 3;

    // ── Engine state ──────────────────────────────────────────────────

    private final char[][] board = new char[8][8];
    private boolean whiteToMove;
    private int castling; // bit 0=K, 1=Q, 2=k, 3=q
    private int epSquare = -1;
    private int halfMove = 0;
    private int fullMove = 1;

    private int rootDepth;

    // ── Public API ────────────────────────────────────────────────────

    public List<OfflineLine> analyze(String fen, int maxDepth, int multiPV) {
        List<OfflineLine> results = new ArrayList<>();
        if (fen == null || fen.isEmpty())
            return results;
        if (!parseFen(fen))
            return results;

        int depth = Math.max(1, Math.min(maxDepth, MAX_DEPTH));
        int n = Math.max(1, Math.min(multiPV, MAX_MULTI_PV));
        this.rootDepth = depth;

        boolean rootWhite = whiteToMove;
        List<String> excluded = new ArrayList<>();

        for (int rank = 1; rank <= n; rank++) {
            SearchResult best = searchRoot(depth, excluded);
            if (best == null || best.move == null)
                break;

            int whiteCp = rootWhite ? best.score : -best.score;
            results.add(new OfflineLine(best.move, whiteCp, rank));
            excluded.add(best.move);
        }

        return results;
    }

    // ── FEN parsing ───────────────────────────────────────────────────

    private boolean parseFen(String fen) {
        try {
            String[] parts = fen.split(" ");
            if (parts.length < 2)
                return false;

            for (char[] row : board)
                Arrays.fill(row, '.');

            int r = 0, f = 0;
            for (char c : parts[0].toCharArray()) {
                if (c == '/') {
                    r++;
                    f = 0;
                } else if (Character.isDigit(c)) {
                    f += (c - '0');
                } else {
                    if (r < 8 && f < 8)
                        board[r][f] = c;
                    f++;
                }
            }
            if (r != 7)
                return false;

            whiteToMove = parts.length >= 2 && parts[1].charAt(0) == 'w';

            castling = 0;
            if (parts.length >= 3) {
                if (parts[2].contains("K"))
                    castling |= 1;
                if (parts[2].contains("Q"))
                    castling |= 2;
                if (parts[2].contains("k"))
                    castling |= 4;
                if (parts[2].contains("q"))
                    castling |= 8;
            }

            epSquare = -1;
            if (parts.length >= 4 && !parts[3].equals("-") && parts[3].length() >= 2) {
                int ef = parts[3].charAt(0) - 'a';
                int er = 8 - (parts[3].charAt(1) - '0');
                if (ef >= 0 && ef <= 7 && er >= 0 && er <= 7)
                    epSquare = er * 8 + ef;
            }

            halfMove = parts.length >= 5 ? safeParseInt(parts[4], 0) : 0;
            fullMove = parts.length >= 6 ? safeParseInt(parts[5], 1) : 1;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int safeParseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ── Search ────────────────────────────────────────────────────────

    private static class SearchResult {
        final String move;
        final int score;

        SearchResult(String m, int s) {
            move = m;
            score = s;
        }
    }

    private SearchResult searchRoot(int depth, List<String> excluded) {
        List<String> moves = orderMoves(generateLegalMoves());
        SearchResult best = null;

        for (String mv : moves) {
            if (excluded.contains(mv))
                continue;

            Undo u = makeMove(mv);
            if (u == null)
                continue;

            int score = -alphaBeta(depth - 1, -MATE_VALUE - 1, MATE_VALUE + 1);
            undoMove(u);

            if (best == null || score > best.score) {
                best = new SearchResult(mv, score);
            }
        }
        return best;
    }

    private int alphaBeta(int depth, int alpha, int beta) {
        if (depth == 0)
            return evaluate();

        List<String> moves = generateLegalMoves();

        if (moves.isEmpty()) {
            if (isInCheck(whiteToMove)) {
                return -(MATE_VALUE - (rootDepth - depth));
            }
            return 0;
        }

        moves = orderMoves(moves);

        int best = -MATE_VALUE - 1;
        for (String mv : moves) {
            Undo u = makeMove(mv);
            if (u == null)
                continue;

            int score = -alphaBeta(depth - 1, -beta, -alpha);
            undoMove(u);

            if (score > best)
                best = score;
            if (best > alpha)
                alpha = best;
            if (alpha >= beta)
                break;
        }
        return best;
    }

    private List<String> orderMoves(List<String> moves) {
        if (moves.size() <= 1)
            return moves;
        List<Object[]> scored = new ArrayList<>(moves.size());
        for (String m : moves) {
            int f2 = m.charAt(2) - 'a';
            int r2 = 8 - (m.charAt(3) - '0');
            char victim = (r2 >= 0 && r2 < 8 && f2 >= 0 && f2 < 8) ? board[r2][f2] : '.';
            int score = 0;
            if (victim != '.') {
                int idx = pieceIndex(victim);
                score = 10000 + PVAL[idx] * 10;
            }
            scored.add(new Object[] { m, score });
        }
        scored.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));
        List<String> result = new ArrayList<>(moves.size());
        for (Object[] o : scored)
            result.add((String) o[0]);
        return result;
    }

    // ── Evaluation ────────────────────────────────────────────────────

    private int evaluate() {
        int score = 0;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char c = board[r][f];
                if (c == '.')
                    continue;
                int idx = pieceIndex(c);
                if (idx == 0)
                    continue;
                int pstRow = Character.isUpperCase(c) ? r : (7 - r);
                int pst = PST[idx][pstRow * 8 + f];
                if (Character.isUpperCase(c))
                    score += PVAL[idx] + pst;
                else
                    score -= PVAL[idx] + pst;
            }
        }
        return whiteToMove ? score : -score;
    }

    private static int pieceIndex(char c) {
        switch (Character.toLowerCase(c)) {
            case 'p':
                return 1;
            case 'n':
                return 2;
            case 'b':
                return 3;
            case 'r':
                return 4;
            case 'q':
                return 5;
            case 'k':
                return 6;
            default:
                return 0;
        }
    }

    // ── Move generation ───────────────────────────────────────────────

    private List<String> generateLegalMoves() {
        List<String> pseudo = generatePseudoMoves(whiteToMove);
        List<String> legal = new ArrayList<>(pseudo.size());
        for (String mv : pseudo) {
            Undo u = makeMove(mv);
            if (u == null)
                continue;
            boolean ok = !isInCheck(u.moverWasWhite);
            undoMove(u);
            if (ok)
                legal.add(mv);
        }
        return legal;
    }

    private List<String> generatePseudoMoves(boolean white) {
        List<String> moves = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char c = board[r][f];
                if (c == '.')
                    continue;
                boolean isWhite = Character.isUpperCase(c);
                if (isWhite != white)
                    continue;

                char p = Character.toLowerCase(c);
                switch (p) {
                    case 'p':
                        generatePawnMoves(r, f, isWhite, moves);
                        break;
                    case 'n':
                        generateKnightMoves(r, f, isWhite, moves);
                        break;
                    case 'b':
                        generateSlidingMoves(r, f, isWhite, moves, true, false);
                        break;
                    case 'r':
                        generateSlidingMoves(r, f, isWhite, moves, false, true);
                        break;
                    case 'q':
                        generateSlidingMoves(r, f, isWhite, moves, true, true);
                        break;
                    case 'k':
                        generateKingMoves(r, f, isWhite, moves);
                        break;
                }
            }
        }
        if (white) {
            if ((castling & 1) != 0)
                addCastle(7, 4, 7, 7, 7, 6, 7, 5, moves);
            if ((castling & 2) != 0)
                addCastle(7, 4, 7, 0, 7, 2, 7, 3, moves);
        } else {
            if ((castling & 4) != 0)
                addCastle(0, 4, 0, 7, 0, 6, 0, 5, moves);
            if ((castling & 8) != 0)
                addCastle(0, 4, 0, 0, 0, 2, 0, 3, moves);
        }
        return moves;
    }

    private void addCastle(int kfR, int kfF, int rfR, int rfF, int ktR, int ktF, int rtR, int rtF,
            List<String> moves) {
        if (Character.toLowerCase(board[kfR][kfF]) != 'k')
            return;
        if (Character.toLowerCase(board[rfR][rfF]) != 'r')
            return;

        int minF = Math.min(kfF, rfF);
        int maxF = Math.max(kfF, rfF);
        for (int f = minF + 1; f < maxF; f++) {
            if (board[kfR][f] != '.')
                return;
        }

        boolean isWhite = Character.isUpperCase(board[kfR][kfF]);
        int stepF = ktF > kfF ? 1 : -1;
        for (int f = kfF; f != ktF + stepF; f += stepF) {
            if (isSquareAttacked(kfR, f, !isWhite))
                return;
        }
        moves.add(sq(kfR, kfF) + sq(ktR, ktF));
    }

    private void generatePawnMoves(int r, int f, boolean white, List<String> moves) {
        int dir = white ? -1 : 1;
        int startRank = white ? 6 : 1;
        int promoRank = white ? 0 : 7;

        int nr = r + dir;
        if (nr >= 0 && nr < 8 && board[nr][f] == '.') {
            addPawnMove(r, f, nr, f, promoRank, moves);
            int nr2 = r + 2 * dir;
            if (r == startRank && nr2 >= 0 && nr2 < 8 && board[nr2][f] == '.') {
                moves.add(sq(r, f) + sq(nr2, f));
            }
        }
        for (int df : new int[] { -1, 1 }) {
            int nf = f + df;
            if (nf < 0 || nf > 7 || nr < 0 || nr > 7)
                continue;
            char target = board[nr][nf];
            if (target != '.' && Character.isUpperCase(target) != white) {
                addPawnMove(r, f, nr, nf, promoRank, moves);
            } else if (target == '.' && epSquare == nr * 8 + nf) {
                moves.add(sq(r, f) + sq(nr, nf));
            }
        }
    }

    private void addPawnMove(int r, int f, int nr, int nf, int promoRank, List<String> moves) {
        if (nr == promoRank) {
            String base = sq(r, f) + sq(nr, nf);
            moves.add(base + "q");
            moves.add(base + "r");
            moves.add(base + "b");
            moves.add(base + "n");
        } else {
            moves.add(sq(r, f) + sq(nr, nf));
        }
    }

    private void generateKnightMoves(int r, int f, boolean white, List<String> moves) {
        int[][] off = { { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 } };
        for (int[] o : off) {
            int nr = r + o[0], nf = f + o[1];
            if (nr < 0 || nr > 7 || nf < 0 || nf > 7)
                continue;
            char t = board[nr][nf];
            if (t == '.' || Character.isUpperCase(t) != white) {
                moves.add(sq(r, f) + sq(nr, nf));
            }
        }
    }

    private void generateSlidingMoves(int r, int f, boolean white, List<String> moves,
            boolean diag, boolean ortho) {
        int[][] dirs;
        if (diag && ortho)
            dirs = new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }, { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        else if (diag)
            dirs = new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
        else
            dirs = new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

        for (int[] d : dirs) {
            int nr = r + d[0], nf = f + d[1];
            while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                char t = board[nr][nf];
                if (t == '.') {
                    moves.add(sq(r, f) + sq(nr, nf));
                } else {
                    if (Character.isUpperCase(t) != white)
                        moves.add(sq(r, f) + sq(nr, nf));
                    break;
                }
                nr += d[0];
                nf += d[1];
            }
        }
    }

    private void generateKingMoves(int r, int f, boolean white, List<String> moves) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int df = -1; df <= 1; df++) {
                if (dr == 0 && df == 0)
                    continue;
                int nr = r + dr, nf = f + df;
                if (nr < 0 || nr > 7 || nf < 0 || nf > 7)
                    continue;
                char t = board[nr][nf];
                if (t == '.' || Character.isUpperCase(t) != white) {
                    moves.add(sq(r, f) + sq(nr, nf));
                }
            }
        }
    }

    private String sq(int r, int f) {
        return "" + (char) ('a' + f) + (char) ('8' - r);
    }

    // ── Make / Undo ───────────────────────────────────────────────────

    private static class Undo {
        boolean moverWasWhite;
        int prevCastling, prevEp, prevHalfMove;
        int[] changedSq = new int[4];
        char[] changedChar = new char[4];
        int changedCount = 0;
    }

    private void saveSquare(int r, int f, Undo u) {
        if (u.changedCount >= u.changedSq.length)
            return;
        u.changedSq[u.changedCount] = r * 8 + f;
        u.changedChar[u.changedCount] = board[r][f];
        u.changedCount++;
    }

    private Undo makeMove(String uci) {
        if (uci == null || uci.length() < 4)
            return null;
        int f1 = uci.charAt(0) - 'a';
        int r1 = 8 - (uci.charAt(1) - '0');
        int f2 = uci.charAt(2) - 'a';
        int r2 = 8 - (uci.charAt(3) - '0');
        if (f1 < 0 || f1 > 7 || r1 < 0 || r1 > 7 || f2 < 0 || f2 > 7 || r2 < 0 || r2 > 7)
            return null;
        if (r1 == r2 && f1 == f2)
            return null;

        char piece = board[r1][f1];
        if (piece == '.')
            return null;
        boolean isWhite = Character.isUpperCase(piece);
        if (isWhite != whiteToMove)
            return null;

        char lower = Character.toLowerCase(piece);

        boolean isEpCapture = (lower == 'p' && board[r2][f2] == '.'
                && epSquare == r2 * 8 + f2 && board[r1][f2] != '.');
        boolean isCastle = (lower == 'k' && Math.abs(f2 - f1) == 2);

        Undo u = new Undo();
        u.moverWasWhite = isWhite;
        u.prevCastling = castling;
        u.prevEp = epSquare;
        u.prevHalfMove = halfMove;

        saveSquare(r1, f1, u);
        saveSquare(r2, f2, u);
        if (isEpCapture)
            saveSquare(r1, f2, u);
        if (isCastle) {
            if (f2 > f1) {
                saveSquare(r1, 7, u);
                saveSquare(r1, 5, u);
            } else {
                saveSquare(r1, 0, u);
                saveSquare(r1, 3, u);
            }
        }

        if (isEpCapture)
            board[r1][f2] = '.';
        board[r2][f2] = piece;
        board[r1][f1] = '.';

        if (lower == 'p' && (r2 == 0 || r2 == 7)) {
            char promo = uci.length() >= 5 ? Character.toLowerCase(uci.charAt(4)) : 'q';
            if (promo != 'q' && promo != 'r' && promo != 'b' && promo != 'n')
                promo = 'q';
            board[r2][f2] = isWhite ? Character.toUpperCase(promo) : promo;
        }

        if (isCastle) {
            if (f2 > f1) {
                board[r1][5] = board[r1][7];
                board[r1][7] = '.';
            } else {
                board[r1][3] = board[r1][0];
                board[r1][0] = '.';
            }
        }

        if (lower == 'k') {
            if (isWhite)
                castling &= ~3;
            else
                castling &= ~12;
        }
        if (lower == 'r') {
            if (isWhite && r1 == 7 && f1 == 0)
                castling &= ~2;
            if (isWhite && r1 == 7 && f1 == 7)
                castling &= ~1;
            if (!isWhite && r1 == 0 && f1 == 0)
                castling &= ~8;
            if (!isWhite && r1 == 0 && f1 == 7)
                castling &= ~4;
        }
        if (r2 == 7 && f2 == 0)
            castling &= ~2;
        if (r2 == 7 && f2 == 7)
            castling &= ~1;
        if (r2 == 0 && f2 == 0)
            castling &= ~8;
        if (r2 == 0 && f2 == 7)
            castling &= ~4;

        epSquare = -1;
        if (lower == 'p' && Math.abs(r2 - r1) == 2) {
            epSquare = ((r1 + r2) / 2) * 8 + f1;
        }

        if (lower == 'p' || (u.changedCount >= 2 && board[r2][f2] != u.changedChar[1]))
            halfMove = 0;
        else
            halfMove++;

        whiteToMove = !whiteToMove;
        return u;
    }

    private void undoMove(Undo u) {
        for (int i = 0; i < u.changedCount; i++) {
            int sqIdx = u.changedSq[i];
            board[sqIdx / 8][sqIdx % 8] = u.changedChar[i];
        }
        castling = u.prevCastling;
        epSquare = u.prevEp;
        halfMove = u.prevHalfMove;
        whiteToMove = u.moverWasWhite;
    }

    // ── Check detection ──────────────────────────────────────────────

    private boolean isInCheck(boolean white) {
        char king = white ? 'K' : 'k';
        int kr = -1, kf = -1;
        outer: for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                if (board[r][f] == king) {
                    kr = r;
                    kf = f;
                    break outer;
                }
            }
        }
        if (kr < 0)
            return false;
        return isSquareAttacked(kr, kf, !white);
    }

    private boolean isSquareAttacked(int r, int f, boolean byWhite) {
        int[][] kOff = { { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 } };
        for (int[] o : kOff) {
            int nr = r + o[0], nf = f + o[1];
            if (nr < 0 || nr > 7 || nf < 0 || nf > 7)
                continue;
            char c = board[nr][nf];
            if (c == '.')
                continue;
            if (Character.isUpperCase(c) == byWhite && Character.toLowerCase(c) == 'n')
                return true;
        }

        int dir = byWhite ? 1 : -1;
        for (int df : new int[] { -1, 1 }) {
            int pr = r + dir, pf = f + df;
            if (pr < 0 || pr > 7 || pf < 0 || pf > 7)
                continue;
            char c = board[pr][pf];
            if (c == '.')
                continue;
            if (Character.isUpperCase(c) == byWhite && Character.toLowerCase(c) == 'p')
                return true;
        }

        int[][] allDirs = { { -1, -1 }, { -1, 0 }, { -1, 1 }, { 0, -1 }, { 0, 1 }, { 1, -1 }, { 1, 0 }, { 1, 1 } };
        for (int[] d : allDirs) {
            int nr = r + d[0], nf = f + d[1];
            int dist = 1;
            while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                char c = board[nr][nf];
                if (c != '.') {
                    if (Character.isUpperCase(c) == byWhite) {
                        char lc = Character.toLowerCase(c);
                        boolean diagonal = Math.abs(d[0]) == 1 && Math.abs(d[1]) == 1;
                        boolean orthogonal = (d[0] == 0) != (d[1] == 0);
                        if (dist == 1 && lc == 'k')
                            return true;
                        if (diagonal && (lc == 'b' || lc == 'q'))
                            return true;
                        if (orthogonal && (lc == 'r' || lc == 'q'))
                            return true;
                    }
                    break;
                }
                nr += d[0];
                nf += d[1];
                dist++;
            }
        }
        return false;
    }

    // ── PST ───────────────────────────────────────────────────────────

    private static int[][] buildPSTs() {
        int[][] pst = new int[7][64];

        pst[1] = new int[] {
                0, 0, 0, 0, 0, 0, 0, 0,
                50, 50, 50, 50, 50, 50, 50, 50,
                10, 10, 20, 30, 30, 20, 10, 10,
                5, 5, 10, 25, 25, 10, 5, 5,
                0, 0, 0, 20, 20, 0, 0, 0,
                5, -5, -10, 0, 0, -10, -5, 5,
                5, 10, 10, -20, -20, 10, 10, 5,
                0, 0, 0, 0, 0, 0, 0, 0
        };

        pst[2] = new int[] {
                -50, -40, -30, -30, -30, -30, -40, -50,
                -40, -20, 0, 0, 0, 0, -20, -40,
                -30, 0, 10, 15, 15, 10, 0, -30,
                -30, 5, 15, 20, 20, 15, 5, -30,
                -30, 0, 15, 20, 20, 15, 0, -30,
                -30, 5, 10, 15, 15, 10, 5, -30,
                -40, -20, 0, 5, 5, 0, -20, -40,
                -50, -40, -30, -30, -30, -30, -40, -50
        };

        pst[3] = new int[] {
                -20, -10, -10, -10, -10, -10, -10, -20,
                -10, 0, 0, 0, 0, 0, 0, -10,
                -10, 0, 5, 10, 10, 5, 0, -10,
                -10, 5, 5, 10, 10, 5, 5, -10,
                -10, 0, 10, 10, 10, 10, 0, -10,
                -10, 10, 10, 10, 10, 10, 10, -10,
                -10, 5, 0, 0, 0, 0, 5, -10,
                -20, -10, -10, -10, -10, -10, -10, -20
        };

        pst[4] = new int[] {
                0, 0, 0, 0, 0, 0, 0, 0,
                5, 10, 10, 10, 10, 10, 10, 5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                0, 0, 0, 5, 5, 0, 0, 0
        };

        pst[5] = new int[] {
                -20, -10, -10, -5, -5, -10, -10, -20,
                -10, 0, 0, 0, 0, 0, 0, -10,
                -10, 0, 5, 5, 5, 5, 0, -10,
                -5, 0, 5, 5, 5, 5, 0, -5,
                0, 0, 5, 5, 5, 5, 0, -5,
                -10, 5, 5, 5, 5, 5, 0, -10,
                -10, 0, 5, 0, 0, 0, 0, -10,
                -20, -10, -10, -5, -5, -10, -10, -20
        };

        pst[6] = new int[] {
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -20, -30, -30, -40, -40, -30, -30, -20,
                -10, -20, -20, -20, -20, -20, -20, -10,
                20, 20, 0, 0, 0, 0, 20, 20,
                20, 30, 10, 0, 0, 10, 30, 20
        };

        return pst;
    }
}