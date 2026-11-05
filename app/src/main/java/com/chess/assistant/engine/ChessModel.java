package com.chess.assistant.engine;

/**
 * Enum mô hình engine cờ vua — THỨ TỰ WEAK → STRONG.
 *
 * AUTO / RANDOM = mode (không phải model cụ thể)
 * OFFLINE_HCE = 1-4 depth (yếu nhất, nhanh)
 * LICHESS_OPENING = 5 tiers (human-like 1100-2500, không depth)
 * STOCKFISH_ONLINE = 5-15 depth (min 5 — API từ chối <5)
 * CHESS_API_COM = 1-18 depth (Stockfish 17, rất mạnh)
 * LICHESS_CLOUD_EVAL = cached (mạnh nhất, không điều chỉnh)
 *
 * UPDATE (2026-11-05 — TIER FRAMEWORK):
 * - [NEW] tierNames[]: cho API đa mức không thể chia theo depth
 * (Lichess Opening Explorer có 5 rating tiers).
 * - `tierNames == null && usesDepthControl` → slider = depth
 * - `tierNames != null && !usesDepthControl` → slider = tier name
 * - `tierNames == null && !usesDepthControl` → slider disabled
 * - [FIX] STOCKFISH_ONLINE minDepth = 5 (API thực tế từ chối 1-4).
 * - [FIX] CHESS_API_COM minDepth = 1 (khôi phục đúng docs 1.txt).
 * - [NEW] LICHESS_OPENING: 5 tiers (1100/1500/1900/2200/2500).
 * - [FIX] OFFLINE_HCE maxDepth = 4 (giữ cap theo yêu cầu).
 * - [NEW] Helper: isTierBased(), isFixed(), getTierCount(),
 * getTierName(), clampTierIndex().
 */
public enum ChessModel {

    AUTO("🤖 Tự động (theo depth)", "auto", "AUTO",
            1, 18, true, null, "Auto"),

    RANDOM("🎲 Random (luân phiên)", "random", "RANDOM",
            1, 18, true, null, "Hỗn hợp"),

    OFFLINE_HCE("📴 Offline HCE (Stockfish Classic)", "offline_hce", "OFFLINE",
            1, 4, true, null, "Yếu → TB (nhanh)"),

    LICHESS_OPENING("🎭 Lichess Opening Explorer (human-like)", "lichess_opening", "LICHESS-OPENING",
            0, 4, false,
            new String[] {
                    "1100 (Người mới)",
                    "1500 (Trung bình)",
                    "1900 (Khá)",
                    "2200 (Chuyên gia)",
                    "2500 (Master)"
            },
            "Người → Master"),

    STOCKFISH_ONLINE("🌐 Stockfish Online", "stockfish_online", "SF-ONLINE",
            5, 15, true, null, "TB → Khá"),

    CHESS_API_COM("🚀 Chess-API.com (Stockfish 17)", "chess_api_com", "CHESS-API",
            1, 18, true, null, "Khá → Rất mạnh"),

    LICHESS_CLOUD_EVAL("☁️ Lichess Cloud Eval (free, no token)", "lichess_cloud_eval", "LICHESS-CLOUD",
            0, 0, false, null, "Cực mạnh (cached)");

    public final String displayName;
    public final String prefValue;
    public final String shortName;
    public final int minDepth;
    public final int maxDepth;
    /** true nếu model nhận tham số depth (slider = depth). */
    public final boolean usesDepthControl;
    /** Nếu != null: model là tier-based (slider = tên tier). */
    public final String[] tierNames;
    /** Nhãn mức mạnh cho UI. */
    public final String strengthLabel;

    ChessModel(String displayName, String prefValue, String shortName,
            int minDepth, int maxDepth, boolean usesDepthControl,
            String[] tierNames, String strengthLabel) {
        this.displayName = displayName;
        this.prefValue = prefValue;
        this.shortName = shortName;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.usesDepthControl = usesDepthControl;
        this.tierNames = tierNames;
        this.strengthLabel = strengthLabel;
    }

    public static ChessModel fromPref(String value) {
        if (value == null)
            return AUTO;
        for (ChessModel m : values()) {
            if (m.prefValue.equals(value))
                return m;
        }
        return AUTO;
    }

    /** Model cụ thể dùng cho random selector (bỏ AUTO và RANDOM). */
    public static ChessModel[] concreteModels() {
        return new ChessModel[] {
                OFFLINE_HCE,
                STOCKFISH_ONLINE,
                CHESS_API_COM,
                LICHESS_CLOUD_EVAL,
                LICHESS_OPENING
        };
    }

    /** Model là tier-based (slider = tên tier). */
    public boolean isTierBased() {
        return tierNames != null && tierNames.length > 0;
    }

    /** Model fixed — slider bị disable. */
    public boolean isFixed() {
        return !usesDepthControl && !isTierBased();
    }

    public int getTierCount() {
        return tierNames == null ? 0 : tierNames.length;
    }

    public String getTierName(int index) {
        if (tierNames == null || tierNames.length == 0)
            return null;
        if (index < 0)
            index = 0;
        if (index >= tierNames.length)
            index = tierNames.length - 1;
        return tierNames[index];
    }

    public int clampTierIndex(int index) {
        if (!isTierBased())
            return -1;
        if (index < 0)
            return 0;
        if (index >= tierNames.length)
            return tierNames.length - 1;
        return index;
    }

    /**
     * True nếu model có thể dùng cho `depth` này.
     * Tier-based và fixed → luôn true (không phụ thuộc depth).
     */
    public boolean supportsDepth(int depth) {
        if (!usesDepthControl)
            return true;
        return depth >= minDepth && depth <= maxDepth;
    }

    /**
     * Clamp `requested` về range của model:
     * - Depth-based → depth trong [minDepth, maxDepth]
     * - Tier-based → tier index trong [0, tierCount-1]
     * - Fixed → -1 (bỏ qua)
     */
    public int clampDepth(int requested) {
        if (isTierBased())
            return clampTierIndex(requested);
        if (!usesDepthControl)
            return -1;
        return Math.max(minDepth, Math.min(maxDepth, requested));
    }

    /** Nhãn range cho UI, vd "d5-15", "5 tiers", "auto". */
    public String getRangeLabel() {
        if (isTierBased())
            return getTierCount() + " tiers";
        if (!usesDepthControl)
            return "auto";
        return "d" + minDepth + "-" + maxDepth;
    }
}