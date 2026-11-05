package com.chess.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.chess.assistant.engine.ChessEngineClient;
import com.chess.assistant.engine.ChessModel;
import com.chess.assistant.engine.ChessMoveClassifier;
import com.chess.assistant.engine.ChessTacticalAnalyzer;

import java.util.List;
import java.util.Locale;

/**
 * MainActivity — điều khiển & hiển thị log/lines.
 *
 * UPDATE (2026-11-05 — SLIDER MODE theo MODEL):
 * - [NEW] Slider 3 chế độ:
 * • Depth mode: max = maxDepth-minDepth, label = "N (min-max)"
 * • Tier mode: max = tierCount-1, label = tên tier
 * • Fixed: disabled, label = "auto"
 * - [NEW] Khi đổi model tự remap slider về pref đúng (KEY_DEPTH /
 * KEY_TIER_INDEX).
 * - [NEW] Note giải thích range thực tế từng model.
 */
public class MainActivity extends Activity implements ChessFloatingService.ServiceCallback {

    private static final int OVERLAY_PERMISSION_REQ_CODE = 1234;

    private static final int COLOR_ONLINE = 0xFF38BDF8;
    private static final int COLOR_OFFLINE = 0xFF4ADE80;
    private static final int COLOR_CLOUD = 0xFFE879F9;
    private static final int COLOR_WARN = 0xFFFCA5A5;

    private TextView tvStatus;
    private TextView tvFen;
    private TextView tvBestMove;
    private TextView tvClassification;
    private TextView tvMoveHistory;
    private TextView tvLogArea;
    private ScrollView scrollLog;
    private Button btnToggleService;
    private Button btnAnalyzeNow;
    private Button btnClearOverlay;

    private TextView tvDepthValue;
    private SeekBar seekDepth;
    private Switch switchTactical;
    private Switch switchPreview;
    private Switch switchClassification;
    private Spinner spinnerModel;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ChessFloatingService.PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F172A);
        root.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        TextView title = new TextView(this);
        title.setText("♟️ Chess Assistant Pro");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF38BDF8);
        root.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("🟢 Best • 🔴/🟡/🟣 chiến thuật • 🔵 preview • 🏷️ classification");
        subTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subTitle.setTextColor(0xFF94A3B8);
        subTitle.setPadding(0, dpToPx(4), 0, dpToPx(12));
        root.addView(subTitle);

        // ── Info Card ─────────────────────────────────────────────────
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackgroundColor(0xFF1E293B);
        infoCard.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        tvStatus = makeLabel("⏳ Trạng thái: Chờ mở Chess.com...", 0xFFFCD34D, 13, true);
        infoCard.addView(tvStatus);

        tvBestMove = makeLabel("💡 Nước đi tốt nhất: Chưa có", 0xFF4ADE80, 13, true);
        tvBestMove.setPadding(0, dpToPx(6), 0, dpToPx(6));
        infoCard.addView(tvBestMove);

        tvClassification = makeLabel("🏷️ Phân loại nước đi: (chưa có)", 0xFF94A3B8, 12, true);
        tvClassification.setPadding(0, 0, 0, dpToPx(6));
        infoCard.addView(tvClassification);

        tvMoveHistory = makeLabel("📋 Nước đi đã đi: (trống)", 0xFFCBD5E1, 12, false);
        infoCard.addView(tvMoveHistory);

        tvFen = makeLabel("♟️ FEN: (chờ bàn cờ)", 0xFF94A3B8, 11, false);
        tvFen.setPadding(0, dpToPx(4), 0, 0);
        infoCard.addView(tvFen);

        root.addView(infoCard);

        // ── Settings Card ─────────────────────────────────────────────
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setBackgroundColor(0xFF1E293B);
        settingsCard.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setLp.setMargins(0, dpToPx(10), 0, 0);
        settingsCard.setLayoutParams(setLp);

        TextView tvSettingTitle = makeLabel("⚙️ Cài đặt", 0xFF38BDF8, 13, true);
        settingsCard.addView(tvSettingTitle);

        // ═══ Model selector ═══
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setPadding(0, dpToPx(8), 0, 0);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvModelLabel = makeLabel("🤖 Model:", 0xFFE2E8F0, 12, true);
        LinearLayout.LayoutParams lblModelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f);
        tvModelLabel.setLayoutParams(lblModelLp);
        modelRow.addView(tvModelLabel);

        spinnerModel = new Spinner(this);
        ChessModel[] allModels = ChessModel.values();
        String[] modelNames = new String[allModels.length];
        for (int i = 0; i < allModels.length; i++)
            modelNames[i] = allModels[i].displayName;
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, modelNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(spinnerAdapter);

        ChessModel selected = readModel();
        int selectedIdx = 0;
        for (int i = 0; i < allModels.length; i++) {
            if (allModels[i] == selected) {
                selectedIdx = i;
                break;
            }
        }
        spinnerModel.setSelection(selectedIdx);

        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ChessModel picked = allModels[position];
                prefs.edit().putString(ChessFloatingService.KEY_MODEL, picked.prefValue).apply();
                Toast.makeText(MainActivity.this,
                        "Model: " + picked.displayName
                                + " • " + picked.getRangeLabel()
                                + " • " + picked.strengthLabel,
                        Toast.LENGTH_LONG).show();

                applyModelToSlider(picked);
                updateDepthLabel();
                ChessFloatingService s = ChessFloatingService.getInstance();
                if (s != null)
                    s.refreshModelSelection();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f);
        spinnerModel.setLayoutParams(spinnerLp);
        modelRow.addView(spinnerModel);
        settingsCard.addView(modelRow);

        // ── Toggles ──────────────────────────────────────────────────
        switchTactical = new Switch(this);
        switchTactical.setText("Mũi tên chiến thuật (🔴/🟡/🟣)");
        switchTactical.setTextColor(0xFFE2E8F0);
        switchTactical.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        switchTactical.setPadding(0, dpToPx(8), 0, 0);
        switchTactical.setChecked(readShowTactical());
        switchTactical.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(ChessFloatingService.KEY_SHOW_TACTICAL, isChecked).apply();
            Toast.makeText(this, isChecked ? "Tactical: BẬT" : "Tactical: TẮT",
                    Toast.LENGTH_SHORT).show();
            ChessFloatingService s = ChessFloatingService.getInstance();
            if (s != null)
                s.refreshToggleStates();
        });
        settingsCard.addView(switchTactical);

        switchPreview = new Switch(this);
        switchPreview.setText("Preview hướng tấn công (🔵)");
        switchPreview.setTextColor(0xFFE2E8F0);
        switchPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        switchPreview.setPadding(0, dpToPx(4), 0, 0);
        switchPreview.setChecked(readShowPreview());
        switchPreview.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(ChessFloatingService.KEY_SHOW_PREVIEW, isChecked).apply();
            Toast.makeText(this, isChecked ? "Preview: BẬT" : "Preview: TẮT",
                    Toast.LENGTH_SHORT).show();
            ChessFloatingService s = ChessFloatingService.getInstance();
            if (s != null)
                s.refreshToggleStates();
        });
        settingsCard.addView(switchPreview);

        switchClassification = new Switch(this);
        switchClassification.setText("Phân loại nước đi (🏷️ !! ★ ? ??)");
        switchClassification.setTextColor(0xFFE2E8F0);
        switchClassification.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        switchClassification.setPadding(0, dpToPx(4), 0, 0);
        switchClassification.setChecked(readShowClassification());
        switchClassification.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(ChessFloatingService.KEY_SHOW_CLASSIFICATION, isChecked).apply();
            Toast.makeText(this, isChecked
                    ? "Classification: BẬT (depth cố định " + ChessFloatingService.CLASSIFICATION_DEPTH + ")"
                    : "Classification: TẮT",
                    Toast.LENGTH_SHORT).show();
            ChessFloatingService s = ChessFloatingService.getInstance();
            if (s != null)
                s.refreshToggleStates();
            if (!isChecked)
                tvClassification.setText("🏷️ Phân loại nước đi: (đã tắt)");
        });
        settingsCard.addView(switchClassification);

        // ── Level slider (depth OR tier OR fixed) ─────────────────────
        LinearLayout depthRow = new LinearLayout(this);
        depthRow.setOrientation(LinearLayout.HORIZONTAL);
        depthRow.setPadding(0, dpToPx(10), 0, 0);
        depthRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDepthLabel = makeLabel("Độ mạnh:", 0xFFE2E8F0, 12, false);
        LinearLayout.LayoutParams lbl1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvDepthLabel.setLayoutParams(lbl1);
        depthRow.addView(tvDepthLabel);

        tvDepthValue = makeLabel("", COLOR_OFFLINE, 13, true);
        depthRow.addView(tvDepthValue);
        settingsCard.addView(depthRow);

        seekDepth = new SeekBar(this);
        seekDepth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                ChessModel m = readModel();
                if (m.isTierBased()) {
                    prefs.edit().putInt(ChessFloatingService.KEY_TIER_INDEX, p).apply();
                } else if (!m.isFixed()) {
                    int depth = m.clampDepth(p + m.minDepth);
                    prefs.edit().putInt(ChessFloatingService.KEY_DEPTH, depth).apply();
                }
                updateDepthLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                ChessModel m = readModel();
                String desc;
                if (m.isTierBased()) {
                    int tier = readTierIndex();
                    desc = m.getTierName(tier) + " (tier #" + tier + ")";
                } else if (m.isFixed()) {
                    desc = "auto — không điều chỉnh được";
                } else {
                    desc = "depth=" + readDepth() + " (range " + m.minDepth + "-" + m.maxDepth + ")";
                }
                Toast.makeText(MainActivity.this,
                        m.shortName + " • " + desc, Toast.LENGTH_SHORT).show();
                ChessFloatingService s = ChessFloatingService.getInstance();
                if (s != null)
                    s.requestManualAnalysis();
            }
        });
        settingsCard.addView(seekDepth);

        applyModelToSlider(readModel());
        updateDepthLabel();

        TextView note = makeLabel(
                "📊 Range thực tế từng model:\n"
                        + "• 📴 Offline HCE: depth 1-4 (yếu nhất)\n"
                        + "• 🎭 Lichess Opening: 5 tiers (1100/1500/1900/2200/2500) — không dùng depth\n"
                        + "• 🌐 Stockfish Online: depth 5-15 (min 5 — API từ chối <5)\n"
                        + "• 🚀 Chess-API.com: depth 1-18 (Stockfish 17)\n"
                        + "• ☁️ Lichess Cloud: auto (cached depth 20-50, không điều chỉnh)\n"
                        + "• 🤖 AUTO: d1-4→Offline, d5-15→SF-Online, d16-18→Chess-API\n"
                        + "• ⚠️ Slider tự đổi chế độ: depth (số) / tier (tên) / disabled.\n"
                        + "• 🎲 Random: max 2 nước liên tiếp, cooldown 2 nước.\n"
                        + "• 🏷️ Classification luôn dùng AUTO depth "
                        + ChessFloatingService.CLASSIFICATION_DEPTH + ".",
                0xFF94A3B8, 10, false);
        note.setPadding(0, dpToPx(8), 0, 0);
        settingsCard.addView(note);

        root.addView(settingsCard);

        // ── Action Buttons ───────────────────────────────────────────
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setPadding(0, dpToPx(12), 0, dpToPx(6));

        btnAnalyzeNow = makeButton("⚡ Gợi ý ngay", 0xFF0284C7);
        btnAnalyzeNow.setOnClickListener(v -> {
            ChessFloatingService s = ChessFloatingService.getInstance();
            if (s != null)
                s.requestManualAnalysis();
            else
                Toast.makeText(this, "Hãy kích hoạt Service trước!", Toast.LENGTH_SHORT).show();
        });
        btnRow1.addView(btnAnalyzeNow);

        btnClearOverlay = makeButton("🧹 Xóa mũi tên", 0xFF475569);
        btnClearOverlay.setOnClickListener(v -> {
            ChessFloatingService s = ChessFloatingService.getInstance();
            if (s != null)
                s.clearOverlay();
            tvBestMove.setText("💡 Nước đi tốt nhất: Đã xóa");
            tvClassification.setText("🏷️ Phân loại nước đi: (đã xóa)");
        });
        btnRow1.addView(btnClearOverlay);
        root.addView(btnRow1);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setPadding(0, 0, 0, dpToPx(12));

        btnToggleService = makeButton("▶ Kích hoạt Lớp phủ vẽ", 0xFF16A34A);
        btnToggleService.setOnClickListener(v -> toggleService());
        btnRow2.addView(btnToggleService);

        Button btnAccessSetting = makeButton("⚙ Cài đặt Trợ năng", 0xFF6366F1);
        btnAccessSetting.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Bật 'Chess Assistant' trong mục Ứng dụng đã cài đặt!",
                    Toast.LENGTH_LONG).show();
        });
        btnRow2.addView(btnAccessSetting);
        root.addView(btnRow2);

        // ── Log Header ────────────────────────────────────────────────
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setPadding(0, dpToPx(4), 0, dpToPx(6));

        TextView tvLogTitle = new TextView(this);
        tvLogTitle.setText("📜 Nhật ký hoạt động (Real-time)");
        tvLogTitle.setTextColor(0xFFE2E8F0);
        tvLogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLogTitle.setTypeface(null, Typeface.BOLD);
        tvLogTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logHeader.addView(tvLogTitle);

        Button btnClearLog = new Button(this);
        btnClearLog.setText("Xóa Log");
        btnClearLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnClearLog.setBackgroundColor(0xFF334155);
        btnClearLog.setTextColor(0xFFF1F5F9);
        btnClearLog.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        btnClearLog.setOnClickListener(v -> tvLogArea.setText(""));
        logHeader.addView(btnClearLog);
        root.addView(logHeader);

        // ── Scrollable Log ────────────────────────────────────────────
        scrollLog = new ScrollView(this);
        scrollLog.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scrollLog.setBackgroundColor(0xFF020617);
        scrollLog.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));

        tvLogArea = new TextView(this);
        tvLogArea.setTextColor(0xFF34D399);
        tvLogArea.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvLogArea.setTypeface(Typeface.MONOSPACE);
        tvLogArea.setText("ℹ️ Chào mừng!\n"
                + "• Offline d1-4 • Lichess Opening 5 tiers • SF-Online d5-15 • Chess-API d1-18.\n"
                + "• ☁️ Lichless Cloud: cached, không điều chỉnh depth.\n"
                + "• Slider đổi chế độ theo model (depth/tier/fixed).\n"
                + "• 🎲 Random: max 2 nước, cooldown 2.\n"
                + "• Mở Chess.com — gợi ý sẽ vẽ lên bàn cờ.\n");
        scrollLog.addView(tvLogArea);
        root.addView(scrollLog);

        setContentView(root);
    }

    // ═══════════════════ SLIDER LOGIC ═════════════════════════════════

    private void applyModelToSlider(ChessModel m) {
        if (seekDepth == null)
            return;

        if (m.isFixed()) {
            seekDepth.setEnabled(false);
            seekDepth.setAlpha(0.4f);
            seekDepth.setMax(0);
            seekDepth.setProgress(0);
        } else if (m.isTierBased()) {
            seekDepth.setEnabled(true);
            seekDepth.setAlpha(1.0f);
            int tierCount = m.getTierCount();
            seekDepth.setMax(Math.max(0, tierCount - 1));
            int tier = readTierIndex();
            if (tier >= tierCount)
                tier = tierCount - 1;
            if (tier < 0)
                tier = 0;
            seekDepth.setProgress(tier);
        } else {
            seekDepth.setEnabled(true);
            seekDepth.setAlpha(1.0f);
            seekDepth.setMax(m.maxDepth - m.minDepth);
            int d = readDepth();
            if (d < m.minDepth)
                d = m.minDepth;
            if (d > m.maxDepth)
                d = m.maxDepth;
            seekDepth.setProgress(d - m.minDepth);
        }
    }

    private int readDepth() {
        int d = prefs.getInt(ChessFloatingService.KEY_DEPTH, ChessFloatingService.DEFAULT_DEPTH);
        if (d < ChessEngineClient.DEPTH_MIN)
            d = ChessEngineClient.DEPTH_MIN;
        if (d > ChessEngineClient.DEPTH_MAX)
            d = ChessEngineClient.DEPTH_MAX;
        return d;
    }

    private int readTierIndex() {
        return prefs.getInt(ChessFloatingService.KEY_TIER_INDEX,
                ChessFloatingService.DEFAULT_TIER_INDEX);
    }

    private boolean readShowTactical() {
        return prefs.getBoolean(ChessFloatingService.KEY_SHOW_TACTICAL,
                ChessFloatingService.DEFAULT_SHOW_TACTICAL);
    }

    private boolean readShowPreview() {
        return prefs.getBoolean(ChessFloatingService.KEY_SHOW_PREVIEW,
                ChessFloatingService.DEFAULT_SHOW_PREVIEW);
    }

    private boolean readShowClassification() {
        return prefs.getBoolean(ChessFloatingService.KEY_SHOW_CLASSIFICATION,
                ChessFloatingService.DEFAULT_SHOW_CLASSIFICATION);
    }

    private ChessModel readModel() {
        return ChessModel.fromPref(prefs.getString(
                ChessFloatingService.KEY_MODEL,
                ChessFloatingService.DEFAULT_MODEL.prefValue));
    }

    /**
     * Label hiển thị tuỳ model:
     * - Fixed: "auto ☁️"
     * - Tier: "tier#N: tên-tier 🎭"
     * - Depth: "depth (min-max) 🌐"
     */
    private void updateDepthLabel() {
        if (tvDepthValue == null)
            return;
        ChessModel model = readModel();

        String text;
        int color;

        if (model.isTierBased()) {
            int tier = readTierIndex();
            tier = model.clampTierIndex(tier);
            text = model.getTierName(tier) + "  🎭";
            color = COLOR_CLOUD;
        } else if (model.isFixed()) {
            text = "auto (cached)  ☁️";
            color = COLOR_CLOUD;
        } else {
            int depth = readDepth();
            int clamped = model.clampDepth(depth);
            boolean outOfRange = (clamped != depth);

            switch (model) {
                case OFFLINE_HCE:
                    color = COLOR_OFFLINE;
                    break;
                case STOCKFISH_ONLINE:
                    color = COLOR_ONLINE;
                    break;
                case CHESS_API_COM:
                    color = COLOR_ONLINE;
                    break;
                case RANDOM:
                    color = COLOR_OFFLINE;
                    break;
                case AUTO:
                default:
                    if (depth < ChessEngineClient.ONLINE_THRESHOLD)
                        color = COLOR_OFFLINE;
                    else
                        color = COLOR_ONLINE;
                    break;
            }

            String rangeLabel = "";
            if (model != ChessModel.AUTO && model != ChessModel.RANDOM) {
                rangeLabel = " (" + model.minDepth + "-" + model.maxDepth + ")";
                if (outOfRange)
                    color = COLOR_WARN;
            }

            text = (outOfRange ? "⚠️ " : "") + "depth=" + clamped + rangeLabel;
        }

        tvDepthValue.setText(text);
        tvDepthValue.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChessFloatingService.setActivityCallback(this);
        updateServiceButtonState();
        if (switchTactical != null)
            switchTactical.setChecked(readShowTactical());
        if (switchPreview != null)
            switchPreview.setChecked(readShowPreview());
        if (switchClassification != null)
            switchClassification.setChecked(readShowClassification());
        if (spinnerModel != null) {
            ChessModel m = readModel();
            ChessModel[] all = ChessModel.values();
            for (int i = 0; i < all.length; i++) {
                if (all[i] == m) {
                    spinnerModel.setSelection(i);
                    break;
                }
            }
        }
        applyModelToSlider(readModel());
        updateDepthLabel();
    }

    private void toggleService() {
        if (ChessFloatingService.getInstance() != null) {
            stopService(new Intent(this, ChessFloatingService.class));
            Toast.makeText(this, "Đã tắt lớp phủ vẽ cờ!", Toast.LENGTH_SHORT).show();
            btnToggleService.setText("▶ Kích hoạt Lớp phủ vẽ");
            btnToggleService.setBackgroundColor(0xFF16A34A);
            tvStatus.setText("Trạng thái: Đã tắt lớp phủ");
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền Vẽ trên ứng dụng khác!",
                        Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION_REQ_CODE);
            } else {
                startService(new Intent(this, ChessFloatingService.class));
                Toast.makeText(this, "Đã bật lớp phủ trong suốt!", Toast.LENGTH_SHORT).show();
                btnToggleService.setText("⏹ Tắt Lớp phủ");
                btnToggleService.setBackgroundColor(0xFFDC2626);
                tvStatus.setText("Trạng thái: Đang chạy • Chờ mở Chess.com");
            }
        }
    }

    private void updateServiceButtonState() {
        if (ChessFloatingService.getInstance() != null) {
            btnToggleService.setText("⏹ Tắt Lớp phủ");
            btnToggleService.setBackgroundColor(0xFFDC2626);
        } else {
            btnToggleService.setText("▶ Kích hoạt Lớp phủ vẽ");
            btnToggleService.setBackgroundColor(0xFF16A34A);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService(new Intent(this, ChessFloatingService.class));
                Toast.makeText(this, "Đã khởi chạy lớp phủ!", Toast.LENGTH_SHORT).show();
                updateServiceButtonState();
            }
        }
    }

    @Override
    public void onLog(String logLine) {
        runOnUiThread(() -> {
            if (tvLogArea != null) {
                tvLogArea.append(logLine + "\n");
                if (scrollLog != null)
                    scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    public void onBoardUpdated(String fen, Rect boardRect, List<String> moves, boolean isFlipped) {
        runOnUiThread(() -> {
            String side = isFlipped ? "ĐEN (dưới)" : "TRẮNG (dưới)";
            tvStatus.setText("Trạng thái: ✅ Đang theo dõi bàn cờ (" + side + ")");
            tvFen.setText("♟️ FEN: " + fen);
            tvMoveHistory.setText("📋 Nước đi (" + moves.size() + "): "
                    + (moves.isEmpty() ? "Bắt đầu ván" : moves.toString()));
        });
    }

    @Override
    public void onAnalysisComplete(List<ChessEngineClient.EngineLine> lines,
            String engineName, int depth,
            List<ChessTacticalAnalyzer.TacticalArrow> tacticalNow,
            List<ChessTacticalAnalyzer.TacticalArrow> tacticalPreview,
            boolean tacticalOn, boolean previewOn) {
        runOnUiThread(() -> {
            if (lines == null || lines.isEmpty()) {
                tvBestMove.setText("💡 Nước đi tốt nhất: (không có)");
                return;
            }
            ChessEngineClient.EngineLine best = lines.get(0);

            StringBuilder sb = new StringBuilder();
            sb.append("💡 Best: ").append(best.bestMove.toUpperCase())
                    .append("  (").append(best.getEvalString())
                    .append(" • ").append(engineName)
                    .append(" • depth=").append(depth).append(")");

            int reds = 0, yellows = 0, purples = 0;
            if (tacticalOn && tacticalNow != null) {
                for (ChessTacticalAnalyzer.TacticalArrow a : tacticalNow) {
                    if (ChessTacticalAnalyzer.TYPE_ENEMY_DEFENSE.equals(a.type))
                        purples++;
                    else if (a.color == ChessTacticalAnalyzer.COLOR_RED)
                        reds++;
                    else
                        yellows++;
                }
                sb.append("\n🎯 Tactical: 🔴 ").append(reds)
                        .append(" • 🟡 ").append(yellows)
                        .append(" • 🟣 ").append(purples);
            }

            if (previewOn && tacticalPreview != null) {
                sb.append("\n🔮 Preview: ").append(tacticalPreview.size())
                        .append(" hướng tấn công mới sau ").append(best.bestMove);
            }

            if (lines.size() > 1) {
                sb.append("\n📊 Gợi ý phụ:");
                for (int i = 1; i < lines.size(); i++) {
                    ChessEngineClient.EngineLine l = lines.get(i);
                    sb.append("\n   #").append(l.rank).append("  ")
                            .append(l.bestMove.toUpperCase())
                            .append("  (").append(l.getEvalString()).append(")");
                }
            }

            tvBestMove.setText(sb.toString());
        });
    }

    @Override
    public void onMoveClassified(ChessMoveClassifier.Result result) {
        runOnUiThread(() -> {
            if (!readShowClassification()) {
                tvClassification.setText("🏷️ Phân loại nước đi: (đã tắt)");
                return;
            }
            if (result == null || result.classification == null) {
                tvClassification.setText("🏷️ Phân loại nước đi: (không xác định)");
                return;
            }
            String sideStr = result.whiteMoved ? "⚪ TRẮNG" : "⚫ ĐEN";
            tvClassification.setText(
                    "🏷️ Nước " + result.uciMove + " (" + sideStr + "): "
                            + result.classification.icon + " " + result.classification.name
                            + String.format(Locale.US,
                                    "\n   EP %.1f%% → %.1f%%  (drop %.1f%%)",
                                    result.epBefore * 100, result.epAfter * 100, result.drop * 100));
            tvClassification.setTextColor(result.classification.color);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> tvStatus.setText("Trạng thái: ⚠️ " + message));
    }

    private TextView makeLabel(String text, int color, int sizeSp, boolean isBold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (isBold)
            tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private Button makeButton(String text, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        btn.setLayoutParams(lp);
        btn.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        return btn;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
