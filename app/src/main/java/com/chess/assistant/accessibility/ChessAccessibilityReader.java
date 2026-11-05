package com.chess.assistant.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AccessibilityService tối ưu cho Chess.com Android (com.chess).
 *
 * UPDATE (2026-11-05 — FIX ROTATION LỆCH MŨI TÊN):
 * - [FIX] scanChessComBoard() fire callback khi fenChanged HOẶC rectChanged
 * (trước đây chỉ khi fenChanged → xoay màn hình làm boardRect đổi
 * nhưng FEN giữ nguyên → overlay vẽ sai vị trí).
 * - [NEW] rectDiffers() với dung sai 5px chống nhiễu.
 */
public class ChessAccessibilityReader extends AccessibilityService {

    private static final String TAG = "ChessAccessibility";
    private static final String CHESS_COM_PACKAGE = "com.chess";

    /** Dung sai pixel — chống fire callback do nhiễu toạ độ nhỏ. */
    private static final int RECT_TOLERANCE_PX = 5;

    private static final Map<String, Character> WHITE_PIECES = new HashMap<String, Character>() {
        {
            put("vua", 'K');
            put("hậu", 'Q');
            put("xe", 'R');
            put("tượng", 'B');
            put("ngựa", 'N');
            put("mã", 'N');
            put("tốt", 'P');
            put("king", 'K');
            put("queen", 'Q');
            put("rook", 'R');
            put("bishop", 'B');
            put("knight", 'N');
            put("pawn", 'P');
        }
    };

    private static final Map<String, Character> BLACK_PIECES = new HashMap<String, Character>() {
        {
            put("vua", 'k');
            put("hậu", 'q');
            put("xe", 'r');
            put("tượng", 'b');
            put("ngựa", 'n');
            put("mã", 'n');
            put("tốt", 'p');
            put("king", 'k');
            put("queen", 'q');
            put("rook", 'r');
            put("bishop", 'b');
            put("knight", 'n');
            put("pawn", 'p');
        }
    };

    private static final Pattern VI_WHITE_PATTERN = Pattern.compile(
            "(?:quân\\s+)?trắng\\s+(\\S+)\\s+trên\\s+([a-h][1-8])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VI_BLACK_PATTERN = Pattern.compile(
            "(?:quân\\s+)?đen\\s+(\\S+)\\s+trên\\s+([a-h][1-8])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VI_EMPTY_PATTERN = Pattern.compile(
            "([a-h][1-8]),\\s*trống", Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_WHITE_PATTERN = Pattern.compile(
            "white\\s+(\\S+)\\s+(?:on\\s+)?([a-h][1-8])", Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_BLACK_PATTERN = Pattern.compile(
            "black\\s+(\\S+)\\s+(?:on\\s+)?([a-h][1-8])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAN_PATTERN = Pattern.compile(
            "^[KQRBNkqrbn]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?[+#]?$|^O-O(-O)?[+#]?$");
    private static final Pattern BARE_SQUARE_PATTERN = Pattern.compile("^[a-h][1-8]$");
    private static final Pattern COMBINED_PATTERN = Pattern.compile(
            "^(\\d+)\\.\\s*([KQRBNkqrbn]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?[+#]?|O-O(-O)?[+#]?)$");
    private static final Pattern MOVE_NUM_PATTERN = Pattern.compile("^\\d+\\.$");

    public interface BoardUpdateListener {
        void onBoardDetected(String fen, Rect boardRect, List<String> moves, boolean isFlipped);

        void onLog(String message);
    }

    private static BoardUpdateListener listener;
    private static ChessAccessibilityReader instance;

    public static String sLastFen = "";
    public static Rect sLastBoardRect = null;
    public static List<String> sLastMoves = new ArrayList<>();
    public static boolean sLastIsFlipped = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final char[][] previousBoard = new char[8][8];
    private final char[][] currentBoard = new char[8][8];
    private char currentTurn = 'w';
    private boolean isFlipped = false;
    private boolean hasPreviousBoard = false;
    private long lastScanTime = 0;

    public static void setListener(BoardUpdateListener l) {
        listener = l;
        if (listener != null && !sLastFen.isEmpty() && sLastBoardRect != null) {
            listener.onBoardDetected(sLastFen, sLastBoardRect, sLastMoves, sLastIsFlipped);
        }
    }

    public static ChessAccessibilityReader getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        log("✅ Accessibility Service đã kích hoạt & sẵn sàng nhận diện Chess.com");
        initEmptyBoard(previousBoard);
        initEmptyBoard(currentBoard);
    }

    private void initEmptyBoard(char[][] b) {
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++)
                b[r][f] = '.';
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !CHESS_COM_PACKAGE.equals(pkg.toString()))
            return;
        long now = System.currentTimeMillis();
        if (now - lastScanTime < 150)
            return;
        lastScanTime = now;
        scanChessComBoard();
    }

    public void forceScan() {
        scanChessComBoard();
    }

    private static class TextNodeEntry {
        final int y, x;
        final String text, resId;

        TextNodeEntry(int y, int x, String text, String resId) {
            this.y = y;
            this.x = x;
            this.text = text;
            this.resId = resId;
        }
    }

    private void scanChessComBoard() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null)
            return;
        try {
            initEmptyBoard(currentBoard);
            Rect computedBoardRect = new Rect(99999, 99999, 0, 0);
            List<Integer> rank1YList = new ArrayList<>();
            List<Integer> rank8YList = new ArrayList<>();
            List<TextNodeEntry> allTextNodes = new ArrayList<>();

            collectNodes(root, currentBoard, computedBoardRect, allTextNodes, rank1YList, rank8YList);

            int pieceCount = 0;
            for (int r = 0; r < 8; r++)
                for (int f = 0; f < 8; f++)
                    if (currentBoard[r][f] != '.')
                        pieceCount++;

            if (pieceCount >= 2 && computedBoardRect.width() > 100 && computedBoardRect.height() > 100) {
                if (!rank1YList.isEmpty() && !rank8YList.isEmpty()) {
                    isFlipped = (average(rank8YList) > average(rank1YList));
                }

                List<TextNodeEntry> candidateTextNodes = filterMoveHistoryNodes(allTextNodes, computedBoardRect);

                List<String> matchNotation = new ArrayList<>();
                determineTurnFromMoveHistory(candidateTextNodes, currentBoard, previousBoard, hasPreviousBoard,
                        matchNotation);

                copyBoard(currentBoard, previousBoard);
                hasPreviousBoard = true;

                String fen = buildFenWithTurn(currentBoard, currentTurn);

                // ═══ [FIX ROTATION] Fire callback khi FEN HOẶC boardRect thay đổi ═══
                boolean fenChanged = !fen.equals(sLastFen);
                boolean rectChanged = rectDiffers(sLastBoardRect, computedBoardRect, RECT_TOLERANCE_PX);

                if (fenChanged || rectChanged) {
                    sLastFen = fen;
                    sLastBoardRect = new Rect(computedBoardRect);
                    sLastMoves = new ArrayList<>(matchNotation);
                    sLastIsFlipped = isFlipped;

                    // Log chỉ khi FEN thật sự đổi (tránh spam khi xoay)
                    if (fenChanged) {
                        String side = isFlipped ? "Bạn chơi ĐEN (Bàn lật)" : "Bạn chơi TRẮNG (Chuẩn)";
                        String turnName = (currentTurn == 'w') ? "Trắng đi" : "Đen đi";
                        log("♟️ " + pieceCount + " quân cờ (" + turnName + " • " + side + ")");
                        log("📜 Biên bản (" + matchNotation.size() + " nước): "
                                + (matchNotation.isEmpty() ? "(đầu ván)" : matchNotation));
                        log("📋 FEN: " + fen);
                    } else {
                        log("🔄 Board rect thay đổi (xoay màn hình?) — cập nhật lại toạ độ");
                    }

                    final String finalFen = fen;
                    final Rect finalRect = new Rect(computedBoardRect);
                    final List<String> finalMoves = new ArrayList<>(matchNotation);
                    final boolean finalFlipped = isFlipped;
                    mainHandler.post(() -> {
                        if (listener != null)
                            listener.onBoardDetected(finalFen, finalRect, finalMoves, finalFlipped);
                    });
                }
            }
        } catch (Exception e) {
            log("❌ Lỗi khi quét: " + e.getMessage());
        } finally {
            root.recycle();
        }
    }

    /** Trả true nếu 2 Rect khác nhau vượt dung sai pixel. */
    private static boolean rectDiffers(Rect a, Rect b, int tolerance) {
        if (a == null || b == null)
            return true;
        return Math.abs(a.left - b.left) > tolerance
                || Math.abs(a.top - b.top) > tolerance
                || Math.abs(a.right - b.right) > tolerance
                || Math.abs(a.bottom - b.bottom) > tolerance;
    }

    private List<TextNodeEntry> filterMoveHistoryNodes(List<TextNodeEntry> allNodes, Rect boardRect) {
        int boardHeight = boardRect.height();
        int margin = (int) (boardHeight * 0.75);

        int topZoneTop = boardRect.top - margin;
        int topZoneBottom = boardRect.top;
        int botZoneTop = boardRect.bottom;
        int botZoneBottom = boardRect.bottom + margin;

        List<TextNodeEntry> filtered = new ArrayList<>();
        for (TextNodeEntry e : allNodes) {
            boolean inTop = e.y >= topZoneTop && e.y <= topZoneBottom;
            boolean inBot = e.y >= botZoneTop && e.y <= botZoneBottom;
            if (inTop || inBot)
                filtered.add(e);
        }

        if (filtered.isEmpty()) {
            for (TextNodeEntry e : allNodes) {
                boolean inTop = e.y >= (boardRect.top - boardHeight) && e.y < boardRect.top;
                boolean inBot = e.y > boardRect.bottom && e.y <= (boardRect.bottom + boardHeight);
                if (inTop || inBot)
                    filtered.add(e);
            }
        }

        log("🔎 MoveHistoryZone boardY=[" + boardRect.top + "," + boardRect.bottom
                + "] filtered=" + filtered.size() + "/" + allNodes.size());
        return filtered;
    }

    private void determineTurnFromMoveHistory(List<TextNodeEntry> candidateNodes,
            char[][] current, char[][] prev, boolean hasPrev,
            List<String> outMatchNotation) {
        for (TextNodeEntry entry : candidateNodes) {
            String clean = entry.text.replaceAll("[\\u200a\\u200b\\u00a0\\t\\r\\n]+", "").trim();
            if (clean.isEmpty())
                continue;
            Matcher m = COMBINED_PATTERN.matcher(clean);
            if (m.matches()) {
                outMatchNotation.add(m.group(2));
            } else if (SAN_PATTERN.matcher(clean).matches()) {
                outMatchNotation.add(clean);
            }
        }

        int wCnt = 0, bCnt = 0;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                char c = current[r][f];
                if (Character.isUpperCase(c))
                    wCnt++;
                else if (Character.isLowerCase(c))
                    bCnt++;
            }
        }
        if (wCnt == 16 && bCnt == 16 && outMatchNotation.isEmpty()) {
            currentTurn = 'w';
            log("🏁 Đầu ván 32 quân (chưa đi) → TRẮNG đi trước");
            return;
        }

        if (hasPrev) {
            List<Character> movedFrom = new ArrayList<>();
            List<Character> movedTo = new ArrayList<>();

            for (int r = 0; r < 8; r++) {
                for (int f = 0; f < 8; f++) {
                    char oldC = prev[r][f], newC = current[r][f];
                    if (oldC == newC)
                        continue;
                    if (oldC != '.' && newC == '.')
                        movedFrom.add(oldC);
                    else if (newC != '.')
                        movedTo.add(newC);
                }
            }

            if (!movedTo.isEmpty() || !movedFrom.isEmpty()) {
                log("🔄 Bàn cờ thay đổi: movedFrom=" + movedFrom + " movedTo=" + movedTo);

                int whiteArrived = 0, blackArrived = 0;
                for (char c : movedTo) {
                    if (Character.isUpperCase(c))
                        whiteArrived++;
                    else
                        blackArrived++;
                }

                if (whiteArrived > blackArrived) {
                    currentTurn = 'b';
                    log("♟️ Quân TRẮNG vừa di chuyển → Lượt của ĐEN");
                    return;
                } else if (blackArrived > whiteArrived) {
                    currentTurn = 'w';
                    log("♟️ Quân ĐEN vừa di chuyển → Lượt của TRẮNG");
                    return;
                } else {
                    int whiteLeft = 0, blackLeft = 0;
                    for (char c : movedFrom) {
                        if (Character.isUpperCase(c))
                            whiteLeft++;
                        else
                            blackLeft++;
                    }
                    if (whiteLeft > blackLeft) {
                        currentTurn = 'b';
                        log("♟️ Quân TRẮNG rời ô → Lượt của ĐEN");
                        return;
                    } else if (blackLeft > whiteLeft) {
                        currentTurn = 'w';
                        log("♟️ Quân ĐEN rời ô → Lượt của TRẮNG");
                        return;
                    }
                }
            } else {
                return;
            }
        }

        List<TextNodeEntry> whiteMoveNodes = new ArrayList<>();
        List<TextNodeEntry> blackMoveNodes = new ArrayList<>();
        List<TextNodeEntry> combinedNodes = new ArrayList<>();
        List<TextNodeEntry> genericSanNodes = new ArrayList<>();

        for (TextNodeEntry entry : candidateNodes) {
            String clean = entry.text.replaceAll("[\\u200a\\u200b\\u00a0\\t\\r\\n]+", "").trim();
            if (clean.isEmpty())
                continue;
            String resId = entry.resId != null ? entry.resId : "";

            if (COMBINED_PATTERN.matcher(clean).matches()) {
                combinedNodes.add(new TextNodeEntry(entry.y, entry.x, clean, resId));
            } else if (MOVE_NUM_PATTERN.matcher(clean).matches()) {
                // bỏ qua
            } else if (SAN_PATTERN.matcher(clean).matches()) {
                if (resId.contains("whiteMoveText"))
                    whiteMoveNodes.add(new TextNodeEntry(entry.y, entry.x, clean, resId));
                else if (resId.contains("blackMoveText"))
                    blackMoveNodes.add(new TextNodeEntry(entry.y, entry.x, clean, resId));
                else
                    genericSanNodes.add(new TextNodeEntry(entry.y, entry.x, clean, resId));
            }
        }

        if (!whiteMoveNodes.isEmpty() || !blackMoveNodes.isEmpty()) {
            List<TextNodeEntry> all = new ArrayList<>();
            all.addAll(whiteMoveNodes);
            all.addAll(blackMoveNodes);
            TextNodeEntry rightMost = findRightMost(all);
            if (rightMost != null) {
                if (rightMost.resId.contains("whiteMoveText")) {
                    currentTurn = 'b';
                    log("⚫ [resId] Nước cuối TRẮNG → ĐEN đến lượt");
                } else {
                    currentTurn = 'w';
                    log("⚪ [resId] Nước cuối ĐEN → TRẮNG đến lượt");
                }
                return;
            }
        }

        if (!combinedNodes.isEmpty() || !genericSanNodes.isEmpty()) {
            List<TextNodeEntry> work = new ArrayList<>();
            for (TextNodeEntry cn : combinedNodes) {
                Matcher m = COMBINED_PATTERN.matcher(cn.text);
                if (m.matches())
                    work.add(new TextNodeEntry(cn.y, cn.x, m.group(2), "whiteMoveText"));
            }
            work.addAll(genericSanNodes);
            sortByPosition(work);
            TextNodeEntry rightMost = findRightMost(work);
            if (rightMost != null) {
                int idx = work.indexOf(rightMost);
                if (idx >= 0) {
                    currentTurn = (idx % 2 == 0) ? 'b' : 'w';
                } else {
                    currentTurn = (work.size() % 2 != 0) ? 'b' : 'w';
                }
                log((currentTurn == 'b' ? "⚫" : "⚪") + " [History Fallback] Lượt: "
                        + (currentTurn == 'w' ? "TRẮNG" : "ĐEN"));
                return;
            }
        }

        log("🔄 Giữ nguyên lượt đi hiện tại: " + (currentTurn == 'w' ? "TRẮNG" : "ĐEN"));
    }

    private void sortByPosition(List<TextNodeEntry> list) {
        Collections.sort(list, (a, b) -> {
            if (Math.abs(a.y - b.y) > 30)
                return Integer.compare(a.y, b.y);
            return Integer.compare(a.x, b.x);
        });
    }

    private TextNodeEntry findRightMost(List<TextNodeEntry> list) {
        TextNodeEntry result = null;
        int maxX = Integer.MIN_VALUE;
        for (TextNodeEntry n : list) {
            if (n.x > maxX) {
                maxX = n.x;
                result = n;
            }
        }
        return result;
    }

    private void collectNodes(AccessibilityNodeInfo node, char[][] board, Rect boardRect,
            List<TextNodeEntry> allTextNodes, List<Integer> rank1YList, List<Integer> rank8YList) {
        if (node == null)
            return;

        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        CharSequence resIdCs = node.getViewIdResourceName();
        String resId = resIdCs != null ? resIdCs.toString() : "";

        if (desc != null && desc.length() > 0) {
            String d = desc.toString().trim();
            boolean isSquare = parseSquareNode(node, d, board, boardRect, rank1YList, rank8YList);

            if (!isSquare && (text == null || text.length() == 0)) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0) {
                    String cleaned = d.replaceAll("[\\u200a\\u200b\\u00a0\\t\\r\\n]+", " ").trim();
                    tryAddSanFromDescription(cleaned, resId, b, allTextNodes);
                }
            }
        }

        if (text != null && text.length() > 0) {
            String t = text.toString().trim();
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                allTextNodes.add(new TextNodeEntry(b.top, b.left, t, resId));
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, board, boardRect, allTextNodes, rank1YList, rank8YList);
                child.recycle();
            }
        }
    }

    private void tryAddSanFromDescription(String desc, String resId, Rect bounds, List<TextNodeEntry> out) {
        if (SAN_PATTERN.matcher(desc).matches()) {
            if (!isBareSquareName(desc)) {
                out.add(new TextNodeEntry(bounds.top, bounds.left, desc, resId));
            }
            return;
        }
        if (COMBINED_PATTERN.matcher(desc).matches()) {
            out.add(new TextNodeEntry(bounds.top, bounds.left, desc, resId));
            return;
        }
        String[] tokens = desc.split("\\s+");
        if (tokens.length > 1) {
            String last = tokens[tokens.length - 1];
            if (SAN_PATTERN.matcher(last).matches() && !isBareSquareName(last)) {
                out.add(new TextNodeEntry(bounds.top, bounds.left, last, resId));
            }
        }
    }

    private boolean isBareSquareName(String san) {
        if (BARE_SQUARE_PATTERN.matcher(san).matches())
            return true;
        return false;
    }

    private boolean parseSquareNode(AccessibilityNodeInfo node, String desc, char[][] board,
            Rect boardRect, List<Integer> rank1YList, List<Integer> rank8YList) {
        String lower = desc.toLowerCase().trim();
        String square = null;
        char piece = '.';

        Matcher wm = VI_WHITE_PATTERN.matcher(lower);
        if (wm.find()) {
            Character p = WHITE_PIECES.get(wm.group(1).toLowerCase());
            if (p != null) {
                piece = p;
                square = wm.group(2).toLowerCase();
            }
        }
        if (square == null) {
            Matcher bm = VI_BLACK_PATTERN.matcher(lower);
            if (bm.find()) {
                Character p = BLACK_PIECES.get(bm.group(1).toLowerCase());
                if (p != null) {
                    piece = p;
                    square = bm.group(2).toLowerCase();
                }
            }
        }
        if (square == null) {
            Matcher em = VI_EMPTY_PATTERN.matcher(lower);
            if (em.find())
                square = em.group(1).toLowerCase();
        }
        if (square == null) {
            Matcher ewm = EN_WHITE_PATTERN.matcher(lower);
            if (ewm.find()) {
                Character p = WHITE_PIECES.get(ewm.group(1).toLowerCase());
                if (p != null) {
                    piece = p;
                    square = ewm.group(2).toLowerCase();
                }
            }
        }
        if (square == null) {
            Matcher ebm = EN_BLACK_PATTERN.matcher(lower);
            if (ebm.find()) {
                Character p = BLACK_PIECES.get(ebm.group(1).toLowerCase());
                if (p != null) {
                    piece = p;
                    square = ebm.group(2).toLowerCase();
                }
            }
        }

        if (square != null && square.length() >= 2) {
            int file = square.charAt(0) - 'a';
            char rankChar = square.charAt(1);
            int rank = 8 - (rankChar - '0');
            if (file >= 0 && file < 8 && rank >= 0 && rank < 8) {
                if (piece != '.')
                    board[rank][file] = piece;
                Rect nr = new Rect();
                node.getBoundsInScreen(nr);
                if (nr.width() > 0 && nr.height() > 0) {
                    boardRect.left = Math.min(boardRect.left, nr.left);
                    boardRect.top = Math.min(boardRect.top, nr.top);
                    boardRect.right = Math.max(boardRect.right, nr.right);
                    boardRect.bottom = Math.max(boardRect.bottom, nr.bottom);
                    int cy = (nr.top + nr.bottom) / 2;
                    if (rankChar == '1')
                        rank1YList.add(cy);
                    else if (rankChar == '8')
                        rank8YList.add(cy);
                }
                return true;
            }
        }
        return false;
    }

    private String buildFenWithTurn(char[][] board, char turn) {
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
        sb.append(' ').append(turn).append(' ').append(castling.length() > 0 ? castling : "-").append(" - 0 1");
        return sb.toString();
    }

    private int average(List<Integer> list) {
        int s = 0;
        for (int v : list)
            s += v;
        return s / list.size();
    }

    private void copyBoard(char[][] src, char[][] dest) {
        for (int r = 0; r < 8; r++)
            System.arraycopy(src[r], 0, dest[r], 0, 8);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (listener != null)
            mainHandler.post(() -> listener.onLog(msg));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onInterrupt() {
        log("Accessibility bị gián đoạn");
    }
}
