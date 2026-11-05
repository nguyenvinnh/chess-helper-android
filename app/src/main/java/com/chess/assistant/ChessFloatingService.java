package com.chess.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.chess.assistant.accessibility.ChessAccessibilityReader;
import com.chess.assistant.engine.ChessEngineClient;
import com.chess.assistant.engine.ChessModel;
import com.chess.assistant.engine.ChessMoveClassifier;
import com.chess.assistant.engine.ChessTacticalAnalyzer;
import com.chess.assistant.engine.OfflineStockfishEngine;
import com.chess.assistant.engine.RandomModelSelector;
import com.chess.assistant.overlay.ChessOverlayView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service chạy nền quản lý lớp phủ vẽ mũi tên + phân loại nước đi.
 *
 * UPDATE (2026-11-05 — TIER + FALLBACK CHAIN):
 * - [NEW] KEY_TIER_INDEX: tier pref riêng cho model tier-based
 * (LICHESS_OPENING).
 * - [NEW] requestAnalysis() truyền "levelOrTier" cho engine:
 * depth cho depth-based, tier index cho tier-based, 0 cho fixed.
 * - [NEW] RANDOM trúng tier-based → pick tier ngẫu nhiên qua selector.
 * - [KEEP] Toàn bộ fallback chain + repetition guard + classification.
 */
public class ChessFloatingService extends Service implements ChessAccessibilityReader.BoardUpdateListener {

    private static final String TAG = "ChessService";

    public static final String PREFS_NAME = "chess_assistant_prefs";

    public static final String KEY_DEPTH = "analysis_depth";
    public static final String KEY_TIER_INDEX = "analysis_tier_index";
    public static final String KEY_SHOW_TACTICAL = "show_tactical";
    public static final String KEY_SHOW_PREVIEW = "show_preview";
    public static final String KEY_SHOW_CLASSIFICATION = "show_classification";
    public static final String KEY_MODEL = "engine_model";

    public static final int DEFAULT_DEPTH = ChessEngineClient.DEFAULT_DEPTH;
    public static final int DEFAULT_TIER_INDEX = 2; // 1900 mặc định
    public static final boolean DEFAULT_SHOW_TACTICAL = true;
    public static final boolean DEFAULT_SHOW_PREVIEW = true;
    public static final boolean DEFAULT_SHOW_CLASSIFICATION = true;
    public static final ChessModel DEFAULT_MODEL = ChessModel.AUTO;

    public static final int CLASSIFICATION_DEPTH = 12;

    private static final long ANALYSIS_SAFETY_TIMEOUT_MS = 30_000;

    private static final String START_POSITION_KEY = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w";

    public interface ServiceCallback {
        void onLog(String logLine);

        void onBoardUpdated(String fen, Rect boardRect, List<String> moves, boolean isFlipped);

        void onAnalysisComplete(List<ChessEngineClient.EngineLine> lines,
                String engineName, int depth,
                List<ChessTacticalAnalyzer.TacticalArrow> tacticalNow,
                List<ChessTacticalAnalyzer.TacticalArrow> tacticalPreview,
                boolean tacticalOn, boolean previewOn);

        void onError(String message);

        void onMoveClassified(ChessMoveClassifier.Result result);
    }

    private static ServiceCallback activityCallback;
    private static ChessFloatingService instance;

    private WindowManager windowManager;
    private ChessOverlayView overlayView;
    private ChessEngineClient engineClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RandomModelSelector randomSelector = new RandomModelSelector();

    private String currentFen = "";
    private Rect currentBoardRect = null;
    private List<String> currentMoves = new ArrayList<>();
    private boolean isBoardFlipped = false;
    private volatile boolean isAnalyzing = false;

    private volatile long analysisSeq = 0;

    private volatile String pendingFen = null;
    private volatile Rect pendingBoardRect = null;
    private volatile boolean pendingIsFlipped = false;

    private final Map<String, Integer> positionCounts = new HashMap<>();
    private String lastRecordedPositionKey = null;
    private int repetitionBoost = 0;
    private String boostPositionKey = null;

    private String lastClassifiedFen = "";
    private final Map<String, Integer> evalCache = new HashMap<>();
    private String lastScheduledClassifiedFen = "";

    private final ComponentCallbacks configCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            Log.d(TAG, "onConfigurationChanged — rotation detected");
            onScreenRotated();
        }

        @Override
        public void onLowMemory() {
        }
    };

    public static void setActivityCallback(ServiceCallback callback) {
        activityCallback = callback;
    }

    public static ChessFloatingService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundServiceNotification();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        engineClient = new ChessEngineClient();

        initOverlayCanvas();

        ChessAccessibilityReader.setListener(this);

        if (overlayView != null) {
            overlayView.updateToggleStates(
                    readShowTactical(), readShowPreview(), readShowClassification());
        }

        ChessAccessibilityReader reader = ChessAccessibilityReader.getInstance();
        if (reader != null) {
            mainHandler.postDelayed(() -> {
                if (ChessAccessibilityReader.getInstance() == reader) {
                    Log.d(TAG, "Force scan sau khi service create");
                    reader.forceScan();
                }
            }, 250);
        }

        try {
            getApplicationContext().registerComponentCallbacks(configCallbacks);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register config callbacks: " + e.getMessage());
        }

        int depth = readDepth();
        ChessModel currentModel = readModel();
        emitLog("🚀 Chess Assistant Service đã khởi động!");
        emitLog("🤖 Model: " + currentModel.displayName);
        emitLog("📊 Range: " + currentModel.getRangeLabel() + " • " + currentModel.strengthLabel);
        emitLog("🔗 Fallback chain: model chính → online khác → OFFLINE (last resort)");
        emitLog("🎯 Depth=" + depth + " • MultiPV=" + ChessEngineClient.MULTI_PV);
        emitLog("⚙ Offline cap: depth " + OfflineStockfishEngine.MAX_DEPTH
                + " — last resort khi mọi online đều fail");
        emitLog("🏷️ Move Classification: " + (readShowClassification() ? "BẬT" : "TẮT")
                + " • depth cố định = " + CLASSIFICATION_DEPTH);
    }

    private void initOverlayCanvas() {
        if (overlayView == null) {
            overlayView = new ChessOverlayView(this);
            WindowManager.LayoutParams params = ChessOverlayView.createOverlayParams();
            try {
                windowManager.addView(overlayView, params);
                Log.d(TAG, "OverlayView đã attach thành công");
            } catch (Exception e) {
                emitLog("❌ Lỗi khi thêm OverlayView: " + e.getMessage());
                overlayView = null;
            }
        }
    }

    private void onScreenRotated() {
        if (overlayView != null) {
            overlayView.clearAll();
        }
        ChessAccessibilityReader reader = ChessAccessibilityReader.getInstance();
        if (reader != null) {
            mainHandler.postDelayed(reader::forceScan, 300);
        }
    }

    public void refreshToggleStates() {
        boolean showTac = readShowTactical();
        boolean showPrev = readShowPreview();
        boolean showClass = readShowClassification();

        if (overlayView != null) {
            overlayView.updateToggleStates(showTac, showPrev, showClass);
        }

        emitLog("🔄 Toggle states: tactical=" + (showTac ? "BẬT" : "TẮT")
                + ", preview=" + (showPrev ? "BẬT" : "TẮT")
                + ", classification=" + (showClass ? "BẬT" : "TẮT"));
    }

    public void refreshModelSelection() {
        ChessModel current = readModel();
        String chainInfo;
        switch (current) {
            case OFFLINE_HCE:
                chainInfo = "Offline → SF-Online → Chess-API → Lichless Cloud (offline ưu tiên)";
                break;
            case LICHESS_OPENING:
                chainInfo = "Lichess Opening → SF-Online → Chess-API → Lichless Cloud → Offline";
                break;
            case STOCKFISH_ONLINE:
                chainInfo = "SF-Online → Chess-API → Lichless Cloud → Offline";
                break;
            case CHESS_API_COM:
                chainInfo = "Chess-API → SF-Online → Lichless Cloud → Offline";
                break;
            case LICHESS_CLOUD_EVAL:
                chainInfo = "Lichless Cloud → SF-Online → Chess-API → Offline";
                break;
            case RANDOM:
                chainInfo = "Random pick → other online → Offline (max 2 consecutive, cooldown 2)";
                break;
            case AUTO:
            default:
                chainInfo = "Auto theo depth (1-4 Offline / 5-15 SF-Online / 16-18 Chess-API) → other → Offline";
                break;
        }
        emitLog("🤖 Đã đổi model → " + current.displayName
                + " (" + current.getRangeLabel() + ")");
        emitLog("🔗 Chain: " + chainInfo);
    }

    @Override
    public void onBoardDetected(String fen, Rect boardRect, List<String> moves, boolean isFlipped) {
        currentFen = fen;
        currentBoardRect = boardRect;
        currentMoves = moves;
        isBoardFlipped = isFlipped;

        recordPosition(fen);

        if (fen.startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")) {
            lastClassifiedFen = "";
            lastScheduledClassifiedFen = "";
            if (overlayView != null)
                overlayView.clearClassification();
        }

        String sideStr = isFlipped ? "Bàn cờ ĐEN (lật)" : "Bàn cờ TRẮNG (chuẩn)";
        emitLog("♟️ Bàn cờ cập nhật (" + sideStr + "): "
                + (moves.isEmpty() ? "Bắt đầu ván" : moves.size() + " nước: " + moves));

        if (activityCallback != null) {
            activityCallback.onBoardUpdated(fen, boardRect, moves, isFlipped);
        }

        scheduleClassification(fen);

        char fenTurn = 'w';
        String[] parts = fen.split(" ");
        if (parts.length >= 2)
            fenTurn = parts[1].charAt(0);

        boolean isPlayerTurn = (!isFlipped && fenTurn == 'w') || (isFlipped && fenTurn == 'b');
        if (!isPlayerTurn) {
            emitLog("⏳ Chờ đối thủ (turn=" + fenTurn + ", bạn=" + (isFlipped ? "ĐEN" : "TRẮNG") + ") — không gợi ý");
            mainHandler.post(() -> {
                if (overlayView != null)
                    overlayView.clearMoves();
            });
            return;
        }

        emitLog("✅ Đến lượt bạn (" + (isFlipped ? "ĐEN" : "TRẮNG") + ") — Phân tích...");
        requestAnalysis(fen, boardRect, isFlipped);
    }

    // ═══════════════════ MOVE CLASSIFICATION ═════════════════════════

    private interface EvalCallback {
        void onEval(int cp);
    }

    private void scheduleClassification(String newFen) {
        if (newFen == null || newFen.isEmpty())
            return;
        if (newFen.equals(lastScheduledClassifiedFen))
            return;
        lastScheduledClassifiedFen = newFen;

        final String beforeFen = lastClassifiedFen;
        lastClassifiedFen = newFen;

        if (!readShowClassification())
            return;

        if (beforeFen.isEmpty())
            return;

        final String uciMove = ChessMoveClassifier.diffToUci(beforeFen, newFen);
        if (uciMove == null) {
            Log.d(TAG, "Classification: không xác định được move từ 2 FEN");
            return;
        }

        Log.d(TAG, "Classification: move=" + uciMove + " (before→after)");

        final int[] evals = new int[2];
        final boolean[] ready = new boolean[2];
        final boolean[] fired = new boolean[1];

        final Runnable onBothReady = () -> {
            if (fired[0])
                return;
            fired[0] = true;
            ChessMoveClassifier.Result result = ChessMoveClassifier.classify(
                    beforeFen, newFen, uciMove, evals[0], evals[1]);
            emitLog("🏷️ Nước " + uciMove + " (" + (result.whiteMoved ? "TRẮNG" : "ĐEN") + "): "
                    + result.classification.icon + " " + result.classification.name
                    + String.format(Locale.US, " (drop=%.2f%%)", result.drop * 100));
            mainHandler.post(() -> {
                if (readShowClassification() && overlayView != null) {
                    overlayView.updateClassification(result);
                }
                if (activityCallback != null)
                    activityCallback.onMoveClassified(result);
            });
        };

        resolveEval(beforeFen, cp -> {
            evals[0] = cp;
            ready[0] = true;
            if (ready[0] && ready[1])
                onBothReady.run();
        });
        resolveEval(newFen, cp -> {
            evals[1] = cp;
            ready[1] = true;
            if (ready[0] && ready[1])
                onBothReady.run();
        });
    }

    private void resolveEval(String fen, EvalCallback cb) {
        String key = positionKey(fen);
        if (key == null) {
            cb.onEval(0);
            return;
        }

        Integer cached = evalCache.get(key);
        if (cached != null) {
            cb.onEval(cached);
            return;
        }

        // Classification luôn dùng AUTO — chain tự fallback nếu online fail.
        engineClient.evaluateAsync(fen, CLASSIFICATION_DEPTH, 1, ChessModel.AUTO,
                new ChessEngineClient.EngineCallback() {
                    @Override
                    public void onSuccess(ChessEngineClient.EngineResult result) {
                        int cp = (result.getBest() != null) ? result.getBest().centipawns : 0;
                        if (evalCache.size() > 500)
                            evalCache.clear();
                        evalCache.put(key, cp);
                        cb.onEval(cp);
                    }

                    @Override
                    public void onError(String message) {
                        Log.w(TAG, "Classification eval fail: " + message);
                        cb.onEval(0);
                    }
                });
    }

    // ═══════════════════ ANALYSIS ═════════════════════════════════════

    @Override
    public void onLog(String message) {
        emitLog(message);
    }

    public void requestManualAnalysis() {
        if (currentFen.isEmpty() || currentBoardRect == null) {
            emitLog("⚠️ Chưa có dữ liệu bàn cờ. Đang force scan...");
            ChessAccessibilityReader reader = ChessAccessibilityReader.getInstance();
            if (reader != null) {
                reader.forceScan();
                mainHandler.postDelayed(() -> {
                    if (!currentFen.isEmpty() && currentBoardRect != null) {
                        emitLog("⚡ Phân tích thủ công (sau force scan)...");
                        requestAnalysis(currentFen, currentBoardRect, isBoardFlipped);
                    } else {
                        emitLog("⚠️ Vẫn chưa tìm thấy bàn cờ. Hãy mở ván cờ Chess.com!");
                    }
                }, 500);
            }
            return;
        }
        emitLog("⚡ Phân tích thủ công thế cờ hiện tại...");
        requestAnalysis(currentFen, currentBoardRect, isBoardFlipped);
    }

    public void clearOverlay() {
        if (overlayView != null) {
            overlayView.clearMoves();
            overlayView.clearClassification();
        }
        emitLog("🧹 Đã xóa các mũi tên trên màn hình.");
    }

    private int readDepth() {
        int d = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DEPTH, DEFAULT_DEPTH);
        if (d < ChessEngineClient.DEPTH_MIN)
            d = ChessEngineClient.DEPTH_MIN;
        if (d > ChessEngineClient.DEPTH_MAX)
            d = ChessEngineClient.DEPTH_MAX;
        return d;
    }

    private int readTierIndex() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TIER_INDEX, DEFAULT_TIER_INDEX);
    }

    private boolean readShowTactical() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_TACTICAL, DEFAULT_SHOW_TACTICAL);
    }

    private boolean readShowPreview() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_PREVIEW, DEFAULT_SHOW_PREVIEW);
    }

    private boolean readShowClassification() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_CLASSIFICATION, DEFAULT_SHOW_CLASSIFICATION);
    }

    private ChessModel readModel() {
        String v = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, DEFAULT_MODEL.prefValue);
        return ChessModel.fromPref(v);
    }

    private static String positionKey(String fen) {
        if (fen == null)
            return null;
        String[] p = fen.split(" ");
        if (p.length < 2)
            return null;
        return p[0] + " " + p[1];
    }

    private void recordPosition(String fen) {
        String key = positionKey(fen);
        if (key == null)
            return;
        if (key.equals(lastRecordedPositionKey))
            return;
        lastRecordedPositionKey = key;

        if (key.equals(START_POSITION_KEY)) {
            if (!positionCounts.isEmpty())
                emitLog("🆕 Phát hiện ván mới — xóa lịch sử thế cờ + reset random selector.");
            positionCounts.clear();
            repetitionBoost = 0;
            boostPositionKey = null;
            randomSelector.reset();
        }

        Integer c = positionCounts.get(key);
        positionCounts.put(key, c == null ? 1 : c + 1);
    }

    private static String keyAfterMove(String fen, String uci) {
        if (fen == null || uci == null || uci.length() < 4)
            return null;
        try {
            String[] parts = fen.split(" ");
            if (parts.length < 2)
                return null;

            char[][] b = new char[8][8];
            String[] rows = parts[0].split("/");
            if (rows.length != 8)
                return null;
            for (int r = 0; r < 8; r++) {
                int f = 0;
                for (char ch : rows[r].toCharArray()) {
                    if (Character.isDigit(ch)) {
                        for (int k = 0; k < ch - '0'; k++)
                            b[r][f++] = '.';
                    } else {
                        b[r][f++] = ch;
                    }
                }
            }

            int fc = uci.charAt(0) - 'a';
            int fr = 8 - (uci.charAt(1) - '0');
            int tc = uci.charAt(2) - 'a';
            int tr = 8 - (uci.charAt(3) - '0');
            if (fc < 0 || fc > 7 || tc < 0 || tc > 7 || fr < 0 || fr > 7 || tr < 0 || tr > 7)
                return null;

            char piece = b[fr][fc];
            if (piece == '.')
                return null;
            char lower = Character.toLowerCase(piece);
            boolean white = Character.isUpperCase(piece);

            if (lower == 'k' && Math.abs(tc - fc) == 2) {
                b[fr][fc] = '.';
                b[tr][tc] = piece;
                if (tc > fc) {
                    b[fr][5] = b[fr][7];
                    b[fr][7] = '.';
                } else {
                    b[fr][3] = b[fr][0];
                    b[fr][0] = '.';
                }
            } else {
                if (lower == 'p' && fc != tc && b[tr][tc] == '.')
                    b[fr][tc] = '.';
                b[fr][fc] = '.';
                if (lower == 'p' && (tr == 0 || tr == 7)) {
                    char promo = 'q';
                    if (uci.length() >= 5) {
                        char p = Character.toLowerCase(uci.charAt(4));
                        if (p == 'n' || p == 'b' || p == 'r' || p == 'q')
                            promo = p;
                    }
                    b[tr][tc] = white ? Character.toUpperCase(promo) : promo;
                } else {
                    b[tr][tc] = piece;
                }
            }

            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                int empty = 0;
                for (int f = 0; f < 8; f++) {
                    char c = b[r][f];
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
            char newTurn = parts[1].charAt(0) == 'w' ? 'b' : 'w';
            sb.append(' ').append(newTurn);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void requestAnalysis(String fen, Rect boardRect, boolean isFlipped) {
        if (fen == null || fen.isEmpty()) {
            emitLog("⚠️ requestAnalysis: fen rỗng — bỏ qua.");
            return;
        }
        if (boardRect == null) {
            emitLog("⚠️ requestAnalysis: boardRect null — bỏ qua.");
            return;
        }

        if (isAnalyzing) {
            pendingFen = fen;
            pendingBoardRect = boardRect;
            pendingIsFlipped = isFlipped;
            emitLog("⏸ Đang phân tích thế cờ trước. Đã ghi nhớ thế cờ mới, sẽ phân tích ngay khi xong...");
            return;
        }

        final String curKey = positionKey(fen);
        if (repetitionBoost > 0 && (curKey == null || !curKey.equals(boostPositionKey))) {
            emitLog("↩️ Thế cờ đã thay đổi — trả depth về " + readDepth());
            repetitionBoost = 0;
            boostPositionKey = null;
        }

        isAnalyzing = true;
        final long seq = ++analysisSeq;

        mainHandler.postDelayed(() -> {
            if (seq == analysisSeq && isAnalyzing) {
                emitLog("⚠️ Analysis timeout " + (ANALYSIS_SAFETY_TIMEOUT_MS / 1000)
                        + "s — reset state, vô hiệu callback cũ.");
                analysisSeq++;
                isAnalyzing = false;
                schedulePendingIfAny();
            }
        }, ANALYSIS_SAFETY_TIMEOUT_MS);

        final int baseDepth = readDepth();
        final int baseTier = readTierIndex();
        final int multiPV = ChessEngineClient.MULTI_PV;
        final boolean showTactical = readShowTactical();
        final boolean showPreview = readShowPreview();
        final boolean playerIsWhite = !isFlipped;

        // Resolve RANDOM → concrete; nếu tier-based thì chọn tier ngẫu nhiên.
        final ChessModel userModel = readModel();
        final ChessModel effectiveModel;
        final int levelOrTier;

        if (userModel == ChessModel.RANDOM) {
            effectiveModel = randomSelector.pick(baseDepth);
            if (effectiveModel.isTierBased()) {
                levelOrTier = randomSelector.pickTierFor(effectiveModel);
                emitLog("🎲 Random chọn: " + effectiveModel.displayName
                        + " (tier #" + levelOrTier + " = " + effectiveModel.getTierName(levelOrTier) + ")");
            } else if (effectiveModel.isFixed()) {
                levelOrTier = 0;
                emitLog("🎲 Random chọn: " + effectiveModel.displayName + " (fixed, no depth)");
            } else {
                levelOrTier = baseDepth;
                emitLog("🎲 Random chọn: " + effectiveModel.displayName
                        + " (depth " + effectiveModel.minDepth + "-" + effectiveModel.maxDepth + ")");
            }
        } else {
            effectiveModel = userModel;
            if (effectiveModel.isTierBased()) {
                levelOrTier = effectiveModel.clampTierIndex(baseTier);
            } else if (effectiveModel.isFixed()) {
                levelOrTier = 0;
            } else {
                levelOrTier = effectiveModel.clampDepth(baseDepth);
            }
        }

        // Repetition boost chỉ áp dụng cho depth-based
        int effectiveLevelOrTier = levelOrTier;
        if (!effectiveModel.isTierBased() && !effectiveModel.isFixed() && repetitionBoost > 0) {
            effectiveLevelOrTier = Math.min(effectiveModel.maxDepth, levelOrTier + repetitionBoost);
        }

        final String levelDesc;
        if (effectiveModel.isTierBased()) {
            levelDesc = "tier #" + effectiveLevelOrTier
                    + " (" + effectiveModel.getTierName(effectiveLevelOrTier) + ")";
        } else if (effectiveModel.isFixed()) {
            levelDesc = "auto (cached)";
        } else {
            levelDesc = "depth=" + effectiveLevelOrTier
                    + " (range " + effectiveModel.minDepth + "-" + effectiveModel.maxDepth + ")";
        }

        emitLog("🧠 [" + effectiveModel.displayName + "] " + levelDesc
                + ", PV=" + multiPV
                + " • tactical=" + (showTactical ? "BẬT" : "TẮT")
                + ", preview=" + (showPreview ? "BẬT" : "TẮT")
                + " • chain=primary→online→offline");

        final int finalLevelOrTier = effectiveLevelOrTier;

        ChessEngineClient.EngineCallback callback = new ChessEngineClient.EngineCallback() {
            @Override
            public void onSuccess(ChessEngineClient.EngineResult result) {
                if (seq != analysisSeq) {
                    Log.d(TAG, "Bỏ qua callback cũ (seq=" + seq + " != " + analysisSeq + ")");
                    return;
                }
                isAnalyzing = false;
                ChessEngineClient.EngineLine best = result.getBest();
                if (best == null) {
                    emitLog("⚠️ Engine không trả về nước đi hợp lệ.");
                    mainHandler.post(() -> {
                        if (overlayView != null)
                            overlayView.clearMoves();
                    });
                    schedulePendingIfAny();
                    return;
                }

                String afterKey = keyAfterMove(fen, best.bestMove);
                Integer seenCount = afterKey == null ? null : positionCounts.get(afterKey);
                boolean repeating = seenCount != null && seenCount >= 1;

                if (repeating && !effectiveModel.isTierBased() && !effectiveModel.isFixed()) {
                    if (baseDepth + repetitionBoost < ChessEngineClient.DEPTH_MAX && pendingFen == null) {
                        repetitionBoost++;
                        boostPositionKey = curKey;
                        emitLog("🔁 Nước " + best.bestMove + " dẫn tới thế đã lặp (" + seenCount
                                + " lần) → tăng depth tạm lên "
                                + Math.min(ChessEngineClient.DEPTH_MAX, baseDepth + repetitionBoost)
                                + " và tính lại...");
                        mainHandler.post(() -> requestAnalysis(fen, boardRect, isFlipped));
                        return;
                    }
                } else if (repetitionBoost > 0) {
                    emitLog("✅ Đã tìm nước phá thế lặp (" + best.bestMove + ") — trả depth về " + baseDepth);
                }
                repetitionBoost = 0;
                boostPositionKey = null;

                if (result.lines.size() > 1) {
                    emitLog("💡 Top " + result.lines.size() + " gợi ý:");
                    for (ChessEngineClient.EngineLine l : result.lines) {
                        emitLog("   #" + l.rank + " " + l.bestMove + " (" + l.getEvalString() + ")");
                    }
                    emitLog("📊 " + result.source);
                } else {
                    emitLog("💡 Best: " + best.bestMove + "  (" + best.getEvalString()
                            + " • " + result.source + ")");
                }

                List<ChessTacticalAnalyzer.TacticalArrow> tacticalNow = null;
                if (showTactical) {
                    try {
                        tacticalNow = ChessTacticalAnalyzer.analyze(fen, playerIsWhite);
                        emitLog("🎯 Tactical hiện tại: " + tacticalNow.size() + " mũi tên");
                    } catch (Exception e) {
                        emitLog("⚠️ Lỗi tactical: " + e.getMessage());
                    }
                }

                List<ChessTacticalAnalyzer.TacticalArrow> tacticalPreview = null;
                if (showPreview) {
                    try {
                        List<ChessTacticalAnalyzer.TacticalArrow> after = ChessTacticalAnalyzer.analyzeAfterMove(fen,
                                best.bestMove, playerIsWhite);
                        tacticalPreview = ChessTacticalAnalyzer.filterNewArrows(after, tacticalNow);
                        emitLog("🔮 Preview sau " + best.bestMove + ": " + tacticalPreview.size()
                                + " hướng tấn công mới");
                    } catch (Exception e) {
                        emitLog("⚠️ Lỗi preview: " + e.getMessage());
                    }
                }

                final List<ChessEngineClient.EngineLine> finalLines = new ArrayList<>(result.lines);
                final List<ChessTacticalAnalyzer.TacticalArrow> finalTacticalNow = tacticalNow;
                final List<ChessTacticalAnalyzer.TacticalArrow> finalTacticalPreview = tacticalPreview;

                mainHandler.post(() -> {
                    if (overlayView == null) {
                        emitLog("⚠️ overlayView null — không vẽ được mũi tên");
                        return;
                    }
                    if (boardRect == null) {
                        emitLog("⚠️ boardRect null trong callback — không vẽ");
                        return;
                    }
                    overlayView.updateOverlay(boardRect, finalLines,
                            finalTacticalNow, finalTacticalPreview,
                            readShowTactical(), readShowPreview(), isFlipped);
                    if (activityCallback != null) {
                        activityCallback.onAnalysisComplete(finalLines, result.source, result.depth,
                                finalTacticalNow, finalTacticalPreview,
                                showTactical, showPreview);
                    }
                    schedulePendingIfAny();
                });
            }

            @Override
            public void onError(String message) {
                if (seq != analysisSeq)
                    return;
                isAnalyzing = false;
                emitLog("❌ " + message);
                mainHandler.post(() -> {
                    if (overlayView != null)
                        overlayView.clearMoves();
                    if (activityCallback != null)
                        activityCallback.onError(message);
                    schedulePendingIfAny();
                });
            }
        };

        try {
            engineClient.evaluateAsync(fen, finalLevelOrTier, multiPV, effectiveModel, callback);
        } catch (Throwable t) {
            isAnalyzing = false;
            emitLog("❌ Không start được engine: " + t.getMessage());
        }
    }

    private void schedulePendingIfAny() {
        final String f = pendingFen;
        final Rect r = pendingBoardRect;
        final boolean flip = pendingIsFlipped;
        pendingFen = null;
        pendingBoardRect = null;
        pendingIsFlipped = false;

        if (f != null && r != null) {
            emitLog("🔁 Chạy lại phân tích cho thế cờ mới (đã hàng đợi)...");
            mainHandler.post(() -> requestAnalysis(f, r, flip));
        }
    }

    private void emitLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg;
        Log.d(TAG, line);
        mainHandler.post(() -> {
            if (activityCallback != null)
                activityCallback.onLog(line);
        });
    }

    private void startForegroundServiceNotification() {
        String channelId = "chess_assistant_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "Chess Assistant Overlay", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null)
                nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Chess Assistant")
                .setContentText("Đang chạy lớp phủ vẽ mũi tên gợi ý...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(1001, n);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        ChessAccessibilityReader.setListener(null);
        try {
            getApplicationContext().unregisterComponentCallbacks(configCallbacks);
        } catch (Exception ignored) {
        }
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
