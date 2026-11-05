package com.chess.assistant.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.chess.assistant.engine.ChessEngineClient;
import com.chess.assistant.engine.ChessMoveClassifier;
import com.chess.assistant.engine.ChessTacticalAnalyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lớp phủ vẽ mũi tên gợi ý + icon phân loại nước đi + icon phong cấp tối ưu.
 *
 * UPDATE (2026-11-05 — PROMOTION ICON):
 * - [NEW] Khi best move là phong cấp (UCI 5 ký tự, vd e7e8q), vẽ badge
 * tròn ở góc trên-trái ô đích, hiển thị chữ cái quân tối ưu
 * (Q/R/B/N). Nền = màu quân, viền = xanh lá (match best arrow).
 * Hoạt động cả khi board lật (isFlipped).
 *
 * UPDATE (2026-11-05 — TOGGLE STATE + ICON NHỎ HƠN):
 * - [NEW] updateToggleStates(): áp dụng ngay khi user đổi toggle.
 * - [CHANGE] Icon classification nhỏ hơn + sát góc hơn (0.38 offset).
 *
 * UPDATE (2026-11-05 — FIX ROTATION):
 * - [FIX] onSizeChanged: clear state khi view resize lớn (xoay màn hình).
 */
public class ChessOverlayView extends View {

    private static final String TAG = "ChessOverlay";

    private static final int COLOR_BEST = Color.argb(240, 34, 197, 94);
    private static final int COLOR_PREVIEW = Color.argb(140, 34, 211, 238);
    private static final int COLOR_CLASSIFICATION_BG = Color.argb(200, 15, 23, 42);

    private static final float STROKE_BEST = 0.160f;

    // Classification icon (góc trên-phải ô đích)
    private static final float CLASSIFY_RADIUS_FACTOR = 0.14f;
    private static final float CLASSIFY_OFFSET_FACTOR = 0.38f;
    private static final float CLASSIFY_RING_FACTOR = 0.025f;
    private static final float CLASSIFY_TEXT_FACTOR_1 = 0.20f;
    private static final float CLASSIFY_TEXT_FACTOR_2 = 0.14f;

    /**
     * [NEW] Promotion icon (góc trên-trái ô đích) — đối xứng với classification.
     * Nền: trắng nếu phong cấp thành quân Trắng, đen nếu quân Đen.
     * Viền: xanh lá (COLOR_BEST) — match best move arrow.
     */
    private static final float PROMO_RADIUS_FACTOR = 0.16f;
    private static final float PROMO_OFFSET_FACTOR = 0.38f;
    private static final float PROMO_RING_FACTOR = 0.030f;
    private static final float PROMO_TEXT_FACTOR = 0.22f;

    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint classifyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint classifyRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint promoBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint promoRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint promoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    private Rect boardRect;
    private boolean isFlipped = false;

    private List<ChessEngineClient.EngineLine> allBestMoves = new ArrayList<>();
    private List<ChessTacticalAnalyzer.TacticalArrow> tacticalNow = new ArrayList<>();
    private List<ChessTacticalAnalyzer.TacticalArrow> tacticalPreview = new ArrayList<>();
    private boolean showTactical = true;
    private boolean showPreview = true;
    private boolean showClassification = true;

    private ChessMoveClassifier.Result classification = null;

    private int lastWidth = 0, lastHeight = 0;

    public ChessOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        circlePaint.setStyle(Paint.Style.FILL);
        headPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        classifyBgPaint.setStyle(Paint.Style.FILL);
        classifyBgPaint.setColor(COLOR_CLASSIFICATION_BG);
        classifyRingPaint.setStyle(Paint.Style.STROKE);
        promoBgPaint.setStyle(Paint.Style.FILL);
        promoRingPaint.setStyle(Paint.Style.STROKE);
        promoTextPaint.setTextAlign(Paint.Align.CENTER);
        promoTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (lastWidth > 0 && lastHeight > 0) {
            if (Math.abs(w - lastWidth) > 20 || Math.abs(h - lastHeight) > 20) {
                Log.d(TAG, "onSizeChanged: " + lastWidth + "x" + lastHeight
                        + " → " + w + "x" + h + " — clearing state (rotation detected)");
                boardRect = null;
                allBestMoves.clear();
                tacticalNow.clear();
                tacticalPreview.clear();
                classification = null;
                postInvalidate();
            }
        }
        lastWidth = w;
        lastHeight = h;
    }

    public void updateToggleStates(boolean showTactical, boolean showPreview, boolean showClassification) {
        this.showTactical = showTactical;
        this.showPreview = showPreview;
        this.showClassification = showClassification;
        if (!showClassification) {
            this.classification = null;
        }
        Log.d(TAG, "updateToggleStates: tactical=" + showTactical
                + ", preview=" + showPreview + ", classification=" + showClassification);
        postInvalidate();
    }

    public void updateOverlay(Rect boardRect,
            List<ChessEngineClient.EngineLine> allBestMoves,
            List<ChessTacticalAnalyzer.TacticalArrow> tacticalNow,
            List<ChessTacticalAnalyzer.TacticalArrow> tacticalPreview,
            boolean showTactical,
            boolean showPreview,
            boolean isFlipped) {
        Log.d(TAG, "updateOverlay: best=" + (allBestMoves == null ? 0 : allBestMoves.size())
                + ", tactNow=" + (tacticalNow == null ? 0 : tacticalNow.size())
                + ", tactPreview=" + (tacticalPreview == null ? 0 : tacticalPreview.size()));
        this.boardRect = boardRect;
        this.allBestMoves = allBestMoves == null ? new ArrayList<>() : new ArrayList<>(allBestMoves);
        this.tacticalNow = tacticalNow == null ? new ArrayList<>() : new ArrayList<>(tacticalNow);
        this.tacticalPreview = tacticalPreview == null ? new ArrayList<>() : new ArrayList<>(tacticalPreview);
        this.showTactical = showTactical;
        this.showPreview = showPreview;
        this.isFlipped = isFlipped;
        postInvalidate();
    }

    public void updateClassification(ChessMoveClassifier.Result result) {
        this.classification = result;
        postInvalidate();
    }

    public void clearMoves() {
        this.allBestMoves = new ArrayList<>();
        this.tacticalNow = new ArrayList<>();
        this.tacticalPreview = new ArrayList<>();
        postInvalidate();
    }

    public void clearClassification() {
        this.classification = null;
        postInvalidate();
    }

    public void clearAll() {
        boardRect = null;
        allBestMoves.clear();
        tacticalNow.clear();
        tacticalPreview.clear();
        classification = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boardRect == null)
            return;
        float sqSize = boardRect.width() / 8.0f;

        // ── 1) PREVIEW (dưới cùng) ─────────────────────────────────────
        if (showPreview && !tacticalPreview.isEmpty()) {
            for (ChessTacticalAnalyzer.TacticalArrow a : tacticalPreview) {
                if (ChessTacticalAnalyzer.TYPE_ATTACK.equals(a.type)) {
                    drawArrowGeneric(canvas, a.fromSquare, a.toSquare, sqSize,
                            COLOR_PREVIEW, 0.09f, 0.10f, 0.16f);
                }
            }
        }

        // ── 2) TACTICAL (giữa) ─────────────────────────────────────────
        if (showTactical && !tacticalNow.isEmpty()) {
            for (ChessTacticalAnalyzer.TacticalArrow a : tacticalNow) {
                if (ChessTacticalAnalyzer.TYPE_ENEMY_DEFENSE.equals(a.type)) {
                    drawArrowGeneric(canvas, a.fromSquare, a.toSquare, sqSize,
                            a.color, 0.55f * 0.16f, 0.55f * 0.20f, 0.55f * 0.30f);
                }
            }
            for (ChessTacticalAnalyzer.TacticalArrow a : tacticalNow) {
                if (ChessTacticalAnalyzer.TYPE_DEFENSE.equals(a.type)) {
                    drawArrowGeneric(canvas, a.fromSquare, a.toSquare, sqSize,
                            a.color, 0.55f * 0.16f, 0.55f * 0.20f, 0.55f * 0.30f);
                }
            }
            for (ChessTacticalAnalyzer.TacticalArrow a : tacticalNow) {
                if (ChessTacticalAnalyzer.TYPE_ATTACK.equals(a.type)) {
                    drawArrowGeneric(canvas, a.fromSquare, a.toSquare, sqSize,
                            a.color, 0.90f * 0.16f, 0.90f * 0.20f, 0.90f * 0.30f);
                }
            }
        }

        // ── 3) BEST MOVE (+ PROMOTION BADGE nếu là phong cấp) ──────────
        if (!allBestMoves.isEmpty()) {
            ChessEngineClient.EngineLine best = allBestMoves.get(0);
            if (best != null && best.bestMove != null && best.bestMove.length() >= 4) {
                String uci = best.bestMove;
                drawArrowGeneric(canvas, uci.substring(0, 2), uci.substring(2, 4), sqSize,
                        COLOR_BEST, STROKE_BEST, STROKE_BEST * 1.25f, STROKE_BEST * 1.8f);

                // ═══ [NEW] Phong cấp? ═══
                if (uci.length() >= 5) {
                    char promoChar = Character.toLowerCase(uci.charAt(4));
                    if (promoChar == 'q' || promoChar == 'r' || promoChar == 'b' || promoChar == 'n') {
                        // Màu quân phong cấp = màu của bên đang đi
                        // Player đang đi, isFlipped = player chơi ĐEN
                        boolean whitePromo = !isFlipped;
                        drawPromotionIcon(canvas, uci.substring(2, 4), promoChar, whitePromo, sqSize);
                    }
                }
            }
        }

        // ── 4) CLASSIFICATION ICON (trên cùng) ─────────────────────────
        if (showClassification && classification != null && classification.toSq != null) {
            drawClassificationIcon(canvas, classification.toSq,
                    classification.classification.icon,
                    classification.classification.color, sqSize);
        }
    }

    /**
     * [NEW] Badge tròn ở góc trên-trái ô đích — hiển thị quân phong cấp
     * tối ưu (Q/R/B/N) do engine đề xuất.
     *
     * Nền: trắng nếu quân phong cấp là Trắng, đen nếu quân Đen.
     * Viền: xanh lá (COLOR_BEST) — đồng bộ với mũi tên best move.
     * Text: chữ cái Q/R/B/N màu tương phản.
     */
    private void drawPromotionIcon(Canvas canvas, String square, char promoChar,
            boolean whitePromo, float sqSize) {
        PointF center = uciToCoordinates(square);
        if (center == null)
            return;

        // Góc trên-trái (đối xứng với classification ở trên-phải)
        float cx = center.x - sqSize * PROMO_OFFSET_FACTOR;
        float cy = center.y - sqSize * PROMO_OFFSET_FACTOR;

        float radius = sqSize * PROMO_RADIUS_FACTOR;

        // Nền theo màu quân
        promoBgPaint.setColor(whitePromo ? 0xFFF8FAFC : 0xFF0F172A);
        canvas.drawCircle(cx, cy, radius, promoBgPaint);

        // Viền xanh lá — match best move indicator
        promoRingPaint.setColor(COLOR_BEST);
        promoRingPaint.setStrokeWidth(sqSize * PROMO_RING_FACTOR);
        canvas.drawCircle(cx, cy, radius, promoRingPaint);

        // Text: Q/R/B/N
        promoTextPaint.setColor(whitePromo ? 0xFF0F172A : 0xFFF8FAFC);
        promoTextPaint.setTextSize(sqSize * PROMO_TEXT_FACTOR);
        String label = String.valueOf(Character.toUpperCase(promoChar));

        Paint.FontMetrics fm = promoTextPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2.0f;
        canvas.drawText(label, cx, textY, promoTextPaint);
    }

    /**
     * Icon classification — góc trên-phải ô đích.
     */
    private void drawClassificationIcon(Canvas canvas, String square, String icon,
            int color, float sqSize) {
        PointF center = uciToCoordinates(square);
        if (center == null)
            return;

        float cx = center.x + sqSize * CLASSIFY_OFFSET_FACTOR;
        float cy = center.y - sqSize * CLASSIFY_OFFSET_FACTOR;

        float radius = sqSize * CLASSIFY_RADIUS_FACTOR;

        canvas.drawCircle(cx, cy, radius, classifyBgPaint);

        classifyRingPaint.setColor(color);
        classifyRingPaint.setStrokeWidth(sqSize * CLASSIFY_RING_FACTOR);
        canvas.drawCircle(cx, cy, radius, classifyRingPaint);

        textPaint.setColor(color);
        float textSize = (icon.length() >= 2)
                ? sqSize * CLASSIFY_TEXT_FACTOR_2
                : sqSize * CLASSIFY_TEXT_FACTOR_1;
        textPaint.setTextSize(textSize);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2.0f;
        canvas.drawText(icon, cx, textY, textPaint);
    }

    private void drawArrowGeneric(Canvas canvas, String fromSq, String toSq, float sqSize,
            int color, float strokeFactor, float circleFactor, float headFactor) {
        PointF from = uciToCoordinates(fromSq);
        PointF to = uciToCoordinates(toSq);
        if (from == null || to == null)
            return;

        arrowPaint.setColor(color);
        arrowPaint.setStrokeWidth(sqSize * strokeFactor);
        circlePaint.setColor(color);
        headPaint.setColor(color);

        canvas.drawCircle(from.x, from.y, sqSize * circleFactor, circlePaint);
        drawArrow(canvas, from.x, from.y, to.x, to.y, sqSize * headFactor);
    }

    private void drawArrow(Canvas canvas, float fromX, float fromY, float toX, float toY, float headSize) {
        float dx = toX - fromX;
        float dy = toY - fromY;
        double angle = Math.atan2(dy, dx);

        canvas.drawLine(fromX, fromY, toX, toY, arrowPaint);

        arrowPath.reset();
        arrowPath.moveTo(toX, toY);
        arrowPath.lineTo(
                (float) (toX - headSize * Math.cos(angle - Math.PI / 6)),
                (float) (toY - headSize * Math.sin(angle - Math.PI / 6)));
        arrowPath.lineTo(
                (float) (toX - headSize * Math.cos(angle + Math.PI / 6)),
                (float) (toY - headSize * Math.sin(angle + Math.PI / 6)));
        arrowPath.close();
        canvas.drawPath(arrowPath, headPaint);
    }

    private PointF uciToCoordinates(String square) {
        if (square == null || square.length() < 2)
            return null;
        int col = square.charAt(0) - 'a';
        int row = 8 - (square.charAt(1) - '0');
        if (col < 0 || col > 7 || row < 0 || row > 7)
            return null;
        if (isFlipped) {
            col = 7 - col;
            row = 7 - row;
        }

        float sqW = boardRect.width() / 8.0f;
        float sqH = boardRect.height() / 8.0f;

        int[] screenPos = new int[2];
        getLocationOnScreen(screenPos);
        float viewLeft = screenPos[0];
        float viewTop = screenPos[1];

        float cx = (boardRect.left - viewLeft) + col * sqW + sqW / 2.0f;
        float cy = (boardRect.top - viewTop) + row * sqH + sqH / 2.0f;
        return new PointF(cx, cy);
    }

    public static WindowManager.LayoutParams createOverlayParams() {
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }
}
