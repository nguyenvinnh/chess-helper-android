package com.chess.assistant.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Cổng giao tiếp Engine cờ vua.
 *
 * UPDATE (2026-11-05 — LICHESS_OPENING + TIER):
 * - [NEW] LICHESS_OPENING: GET explorer.lichess.ovh/lichess.
 * Chọn move popular nhất ở tier → eval từ win-rate.
 * - [NEW] tryModel() dispatch theo model.clampDepth():
 * - Tier-based: userDepthOrTier là tier index (0..N-1).
 * - Depth-based: userDepthOrTier là depth.
 * - Fixed: clampDepth() = -1 → bỏ qua.
 * - [FIX] Stockfish min depth = 5 (khớp ChessModel).
 * - [FIX] Chess-API min depth = 1 (khớp ChessModel).
 * - [KEEP] Fallback chain, LICHESS_CLOUD, offline fallback.
 */
public class ChessEngineClient {

    public static final int DEPTH_MIN = 1;
    public static final int DEPTH_MAX = 18;
    public static final int DEFAULT_DEPTH = 3;

    public static final int ONLINE_THRESHOLD = 5;
    public static final int ONLINE_MAX_DEPTH = 15;
    public static final int CHESS_API_MAX_DEPTH = 18;
    public static final int LICHESS_CLOUD_MAX_DEPTH = 30;

    public static final int MULTI_PV = 1;

    private static final int ONLINE_BASE_TIMEOUT_MS = 3000;
    private static final int ONLINE_PER_DEPTH_MS = 350;
    private static final int ONLINE_MAX_TIMEOUT_MS = 12000;

    private static final int FALLBACK_ATTEMPT_TIMEOUT_MS = 5000;
    private static final int LICHESS_TIMEOUT_MS = 5000;

    private static final String STOCKFISH_ONLINE_URL = "https://stockfish.online/api/s/v2.php";
    private static final String CHESS_API_URL = "https://chess-api.com/v1";
    private static final String LICHESS_CLOUD_EVAL_URL = "https://lichess.org/api/cloud-eval";
    private static final String LICHESS_OPENING_URL = "https://explorer.lichess.ovh/lichess";

    private static final Pattern UCI_MOVE_PATTERN = Pattern.compile(
            "^[a-h][1-8][a-h][1-8][qrbnQRBN]?$");

    public interface EngineCallback {
        void onSuccess(EngineResult result);

        void onError(String message);
    }

    public static class EngineLine {
        public final String bestMove;
        public final int centipawns;
        public final Integer mate;
        public final int rank;

        public EngineLine(String bestMove, int centipawns, Integer mate, int rank) {
            this.bestMove = bestMove;
            this.centipawns = centipawns;
            this.mate = mate;
            this.rank = rank;
        }

        public String getEvalString() {
            return mate != null
                    ? ("M" + mate)
                    : String.format(Locale.US, "%+.2f", centipawns / 100.0);
        }
    }

    public static class EngineResult {
        public final List<EngineLine> lines;
        public final int depth;
        public final String source;

        public EngineResult(List<EngineLine> lines, int depth, String source) {
            this.lines = lines;
            this.depth = depth;
            this.source = source;
        }

        public EngineLine getBest() {
            return (lines == null || lines.isEmpty()) ? null : lines.get(0);
        }
    }

    private static class CloudEvalResult {
        final EngineLine line;
        final int depth;

        CloudEvalResult(EngineLine line, int depth) {
            this.line = line;
            this.depth = depth;
        }
    }

    // ═══════════════════ PUBLIC API ════════════════════════════════════

    public void evaluateAsync(String fen, int depth, int multiPV, EngineCallback callback) {
        evaluateAsync(fen, depth, multiPV, ChessModel.AUTO, callback);
    }

    /**
     * userDepthOrTier:
     * - Depth-based model → depth (1..18).
     * - Tier-based model → tier index (0..N-1).
     * - Fixed model → ignored.
     */
    public void evaluateAsync(String fen, int userDepthOrTier, int multiPV,
            ChessModel model, EngineCallback callback) {
        new Thread(() -> {
            try {
                final List<ChessModel> chain = buildModelChain(model, userDepthOrTier);
                final List<String> failLogs = new ArrayList<>();

                for (int i = 0; i < chain.size(); i++) {
                    final ChessModel current = chain.get(i);
                    final boolean isFallback = (i > 0);

                    long t0 = System.currentTimeMillis();
                    EngineResult result = tryModel(fen, userDepthOrTier, current, isFallback);
                    long elapsed = System.currentTimeMillis() - t0;

                    if (result != null) {
                        if (isFallback) {
                            StringBuilder note = new StringBuilder();
                            note.append(" [FALLBACK #").append(i + 1)
                                    .append("/").append(chain.size()).append(" — ");
                            for (int k = 0; k < failLogs.size(); k++) {
                                if (k > 0)
                                    note.append(", ");
                                note.append(failLogs.get(k));
                            }
                            note.append("]");
                            result = new EngineResult(
                                    result.lines, result.depth,
                                    result.source + note);
                        }
                        callback.onSuccess(result);
                        return;
                    }

                    failLogs.add(current.shortName + "(" + elapsed + "ms)");
                }

                callback.onError("Tất cả " + chain.size() + " model đều thất bại: "
                        + String.join(" → ", failLogs));
            } catch (Throwable t) {
                callback.onError("Engine lỗi: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }, "engine-dispatcher").start();
    }

    // ═══════════════════ FALLBACK CHAIN ═════════════════════════════════

    private static List<ChessModel> buildModelChain(ChessModel requested, int userDepthOrTier) {
        ChessModel primary;

        if (requested == null) {
            requested = ChessModel.AUTO;
        }

        if (requested.isTierBased() || requested.isFixed()) {
            primary = requested;
        } else if (requested == ChessModel.AUTO) {
            // AUTO: userDepthOrTier là depth
            if (userDepthOrTier < ONLINE_THRESHOLD)
                primary = ChessModel.OFFLINE_HCE;
            else if (userDepthOrTier <= ONLINE_MAX_DEPTH)
                primary = ChessModel.STOCKFISH_ONLINE;
            else
                primary = ChessModel.CHESS_API_COM;
        } else if (requested == ChessModel.RANDOM) {
            primary = ChessModel.STOCKFISH_ONLINE;
        } else {
            primary = requested;
        }

        List<ChessModel> chain = new ArrayList<>(5);
        chain.add(primary);

        // Other online models — dùng userDepthOrTier làm hint
        // (clampDepth tự clamp cho từng model).
        if (primary != ChessModel.STOCKFISH_ONLINE && !primary.isTierBased())
            chain.add(ChessModel.STOCKFISH_ONLINE);
        if (primary != ChessModel.CHESS_API_COM && !primary.isTierBased())
            chain.add(ChessModel.CHESS_API_COM);
        if (primary != ChessModel.LICHESS_CLOUD_EVAL)
            chain.add(ChessModel.LICHESS_CLOUD_EVAL);

        // Offline LAST — unconstrained last resort
        if (primary != ChessModel.OFFLINE_HCE)
            chain.add(ChessModel.OFFLINE_HCE);

        return chain;
    }

    /**
     * Thử một model. `userDepthOrTier` được interpret:
     * - Depth-based → depth.
     * - Tier-based → tier index.
     * - Fixed → bỏ qua.
     */
    private EngineResult tryModel(String fen, int userDepthOrTier,
            ChessModel model, boolean isFallback) {
        switch (model) {
            case OFFLINE_HCE: {
                int d = model.clampDepth(userDepthOrTier);
                return tryOfflineResult(fen, d);
            }
            case STOCKFISH_ONLINE: {
                int d = model.clampDepth(userDepthOrTier);
                int t = isFallback ? FALLBACK_ATTEMPT_TIMEOUT_MS
                        : Math.min(ONLINE_MAX_TIMEOUT_MS,
                                ONLINE_BASE_TIMEOUT_MS + d * ONLINE_PER_DEPTH_MS);
                return tryStockfishOnlineResult(fen, d, t);
            }
            case CHESS_API_COM: {
                int d = model.clampDepth(userDepthOrTier);
                int t = isFallback ? FALLBACK_ATTEMPT_TIMEOUT_MS
                        : Math.min(ONLINE_MAX_TIMEOUT_MS,
                                ONLINE_BASE_TIMEOUT_MS + d * ONLINE_PER_DEPTH_MS);
                return tryChessApiResult(fen, d, t);
            }
            case LICHESS_CLOUD_EVAL: {
                int hint = Math.max(userDepthOrTier, 5);
                int t = isFallback ? FALLBACK_ATTEMPT_TIMEOUT_MS : LICHESS_TIMEOUT_MS;
                return tryLichessCloudEvalResult(fen, hint, t);
            }
            case LICHESS_OPENING: {
                int tier = model.clampTierIndex(userDepthOrTier);
                int t = isFallback ? FALLBACK_ATTEMPT_TIMEOUT_MS : LICHESS_TIMEOUT_MS;
                return tryLichessOpeningResult(fen, tier, t);
            }
            default:
                return null;
        }
    }

    // ═══════════════════ RESULT WRAPPERS ════════════════════════════════

    private EngineResult tryOfflineResult(String fen, int depth) {
        long t0 = System.currentTimeMillis();
        try {
            OfflineStockfishEngine offline = new OfflineStockfishEngine();
            List<OfflineStockfishEngine.OfflineLine> lines = offline.analyze(fen, depth, 1);
            long elapsed = System.currentTimeMillis() - t0;
            if (lines.isEmpty())
                return null;
            OfflineStockfishEngine.OfflineLine ol = lines.get(0);
            Integer mate = null;
            if (Math.abs(ol.score) > 29000) {
                int plies = 30000 - Math.abs(ol.score);
                mate = (plies + 1) / 2;
                if (ol.score < 0)
                    mate = -mate;
            }
            List<EngineLine> out = new ArrayList<>(1);
            out.add(new EngineLine(ol.move, ol.score, mate, 1));
            return new EngineResult(out, depth,
                    "📴 Stockfish HCE Offline • depth " + depth + " • " + elapsed + "ms");
        } catch (Throwable t) {
            return null;
        }
    }

    private EngineResult tryStockfishOnlineResult(String fen, int depth, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        EngineLine line = tryStockfishOnline(fen, depth, timeoutMs);
        long elapsed = System.currentTimeMillis() - t0;
        if (line == null)
            return null;
        List<EngineLine> lines = new ArrayList<>(1);
        lines.add(line);
        return new EngineResult(lines, depth,
                "🌐 Stockfish Online • depth " + depth + " • " + elapsed + "ms");
    }

    private EngineResult tryChessApiResult(String fen, int depth, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        EngineLine line = tryChessApi(fen, depth, timeoutMs);
        long elapsed = System.currentTimeMillis() - t0;
        if (line == null)
            return null;
        List<EngineLine> lines = new ArrayList<>(1);
        lines.add(line);
        return new EngineResult(lines, depth,
                "🚀 Chess-API.com • depth " + depth + " • " + elapsed + "ms");
    }

    private EngineResult tryLichessCloudEvalResult(String fen, int requestedDepth, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        CloudEvalResult r = tryLichessCloudEval(fen, timeoutMs);
        long elapsed = System.currentTimeMillis() - t0;
        if (r == null || r.line == null)
            return null;
        List<EngineLine> lines = new ArrayList<>(1);
        lines.add(r.line);
        String depthNote = (r.depth != requestedDepth)
                ? (" (yêu cầu " + requestedDepth + ", cloud có depth " + r.depth + ")")
                : "";
        return new EngineResult(lines, r.depth,
                "☁️ Lichess Cloud Eval • depth " + r.depth + depthNote
                        + " • " + elapsed + "ms");
    }

    // ── LICHESS OPENING (tier-based) ─────────────────────────────────────

    /**
     * Lấy move popular nhất ở mức rating tương ứng tier.
     * Eval được suy ra từ win-rate (không phải engine eval thật).
     *
     * Tier → ratings query:
     * 0 (1100) → 1000,1200
     * 1 (1500) → 1400,1600
     * 2 (1900) → 1800,2000
     * 3 (2200) → 2200
     * 4 (2500) → 2500
     */
    private static String tierToRatings(int tier) {
        switch (tier) {
            case 0:
                return "1000,1200";
            case 1:
                return "1400,1600";
            case 2:
                return "1800,2000";
            case 3:
                return "2200";
            case 4:
                return "2500";
            default:
                return "1800,2000";
        }
    }

    private EngineResult tryLichessOpeningResult(String fen, int tier, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        EngineLine line = tryLichessOpening(fen, tier, timeoutMs);
        long elapsed = System.currentTimeMillis() - t0;
        if (line == null)
            return null;
        List<EngineLine> lines = new ArrayList<>(1);
        lines.add(line);
        String tierName = String.valueOf(1000 + tier * 400); // 1000, 1400, 1800, 2200, 2600... gần đúng
        return new EngineResult(lines, tier + 1,
                "🎭 Lichess Opening • tier#" + tier + " (≈" + tierName + ") • "
                        + elapsed + "ms");
    }

    private EngineLine tryLichessOpening(String fen, int tier, int timeoutMs) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lichess-opening");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<EngineLine> future = exec.submit(
                    () -> doLichessOpeningRequest(fen, tier, timeoutMs));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    private EngineLine doLichessOpeningRequest(String fen, int tier, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            String ratings = tierToRatings(tier);
            String query = "?fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8.toString())
                    + "&ratings=" + URLEncoder.encode(ratings, StandardCharsets.UTF_8.toString())
                    + "&speeds=" + URLEncoder.encode("blitz,rapid,classical", StandardCharsets.UTF_8.toString())
                    + "&topGames=0&recentGames=0";
            URL url = new URL(LICHESS_OPENING_URL + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "ChessAssistant/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code == 404 || code == 429 || code != 200)
                return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray moves = json.optJSONArray("moves");
            if (moves == null || moves.length() == 0)
                return null;

            JSONObject best = null;
            long bestTotal = -1;
            long w = 0, d = 0, b = 0;
            for (int i = 0; i < moves.length(); i++) {
                JSONObject m = moves.getJSONObject(i);
                long mw = m.optLong("white", 0);
                long md = m.optLong("draws", 0);
                long mb = m.optLong("black", 0);
                long total = mw + md + mb;
                if (total > bestTotal) {
                    bestTotal = total;
                    best = m;
                    w = mw;
                    d = md;
                    b = mb;
                }
            }
            if (best == null || bestTotal <= 0)
                return null;

            String uci = best.optString("uci", "").toLowerCase(Locale.US);
            if (uci.isEmpty() || !UCI_MOVE_PATTERN.matcher(uci).matches())
                return null;

            // Eval từ win-rate: EP = (w + d/2) / total → cp = ln(EP/(1-EP)) / k
            double ep = (w + 0.5 * d) / (double) bestTotal;
            if (ep < 0.01)
                ep = 0.01;
            if (ep > 0.99)
                ep = 0.99;
            double k = 0.00368208;
            int cp = (int) Math.round(Math.log(ep / (1.0 - ep)) / k);
            if (cp > 2000)
                cp = 2000;
            if (cp < -2000)
                cp = -2000;

            return new EngineLine(uci, cp, null, 1);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ═══════════════════ LOW-LEVEL HTTP CALLS ══════════════════════════

    // ── STOCKFISH.ONLINE ────────────────────────────────────────────────

    private EngineLine tryStockfishOnline(String fen, int depth, int timeoutMs) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stockfish-online");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<EngineLine> future = exec.submit(
                    () -> doStockfishOnlineRequest(fen, depth, timeoutMs));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    private EngineLine doStockfishOnlineRequest(String fen, int depth, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            String query = "?fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8.toString())
                    + "&depth=" + depth;
            URL url = new URL(STOCKFISH_ONLINE_URL + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; ChessAssistant)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            if (conn.getResponseCode() != 200)
                return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            if (!json.optBoolean("success", false))
                return null;

            String bestMove = extractUciMove(json.optString("bestmove", ""));
            if (bestMove == null)
                return null;

            double eval = json.optDouble("evaluation", 0.0);
            Integer mate = null;
            if (json.has("mate") && !json.isNull("mate")) {
                mate = json.getInt("mate");
            }
            int cp = (int) Math.round(eval * 100);
            return new EngineLine(bestMove, cp, mate, 1);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ── CHESS-API.COM ───────────────────────────────────────────────────

    private EngineLine tryChessApi(String fen, int depth, int timeoutMs) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chess-api-com");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<EngineLine> future = exec.submit(
                    () -> doChessApiRequest(fen, depth, timeoutMs));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    private EngineLine doChessApiRequest(String fen, int depth, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CHESS_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; ChessAssistant)");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", depth);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != 200)
                return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());

            String rawMove = json.optString("move", "");
            if (rawMove.isEmpty())
                rawMove = json.optString("bestmove", "");
            if (rawMove.isEmpty())
                rawMove = json.optString("text", "");

            String uci = extractUciMove(rawMove);
            if (uci == null)
                return null;

            double eval = 0.0;
            if (json.has("eval") && !json.isNull("eval"))
                eval = json.optDouble("eval", 0.0);
            else if (json.has("evaluation") && !json.isNull("evaluation"))
                eval = json.optDouble("evaluation", 0.0);

            Integer mate = null;
            if (json.has("mate") && !json.isNull("mate")) {
                try {
                    mate = json.getInt("mate");
                } catch (Exception ignored) {
                }
            }

            int cp = (int) Math.round(eval * 100);
            return new EngineLine(uci, cp, mate, 1);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ── LICHESS CLOUD EVAL ──────────────────────────────────────────────

    private CloudEvalResult tryLichessCloudEval(String fen, int timeoutMs) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lichess-cloud-eval");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<CloudEvalResult> future = exec.submit(
                    () -> doLichessCloudEvalRequest(fen, timeoutMs));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    private CloudEvalResult doLichessCloudEvalRequest(String fen, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            String query = "?fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8.toString())
                    + "&multiPv=1";
            URL url = new URL(LICHESS_CLOUD_EVAL_URL + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "ChessAssistant/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code == 404 || code == 429 || code != 200)
                return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            int cloudDepth = json.optInt("depth", 0);

            JSONArray pvs = json.optJSONArray("pvs");
            if (pvs == null || pvs.length() == 0)
                return null;

            JSONObject pv = pvs.getJSONObject(0);
            String movesStr = pv.optString("moves", "");
            String uci = extractUciMove(movesStr);
            if (uci == null)
                return null;

            Integer mate = null;
            if (pv.has("mate") && !pv.isNull("mate")) {
                try {
                    mate = pv.getInt("mate");
                } catch (Exception ignored) {
                }
            }

            int cp = 0;
            if (pv.has("cp") && !pv.isNull("cp")) {
                cp = pv.optInt("cp", 0);
            } else if (mate != null) {
                cp = (mate > 0) ? (30000 - mate * 2) : (-30000 - mate * 2);
            }

            EngineLine el = new EngineLine(uci, cp, mate, 1);
            return new CloudEvalResult(el, cloudDepth > 0 ? cloudDepth : 20);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ═══════════════════ BACKWARD-COMPAT API ════════════════════════════

    public void evaluateOfflineAsync(String fen, int depth, int multiPV, EngineCallback callback) {
        new Thread(() -> {
            try {
                int safeDepth = Math.max(DEPTH_MIN,
                        Math.min(depth, OfflineStockfishEngine.MAX_DEPTH));
                int safePv = Math.max(1,
                        Math.min(multiPV, OfflineStockfishEngine.MAX_MULTI_PV));

                OfflineStockfishEngine engine = new OfflineStockfishEngine();
                List<OfflineStockfishEngine.OfflineLine> offlineLines = engine.analyze(
                        fen, safeDepth, safePv);

                if (offlineLines.isEmpty()) {
                    callback.onError("Offline engine không tìm thấy nước đi hợp lệ.");
                    return;
                }

                List<EngineLine> lines = new ArrayList<>(offlineLines.size());
                for (OfflineStockfishEngine.OfflineLine ol : offlineLines) {
                    Integer mate = null;
                    if (Math.abs(ol.score) > 29000) {
                        int plies = 30000 - Math.abs(ol.score);
                        mate = (plies + 1) / 2;
                        if (ol.score < 0)
                            mate = -mate;
                    }
                    lines.add(new EngineLine(ol.move, ol.score, mate, ol.rank));
                }

                callback.onSuccess(new EngineResult(
                        lines, safeDepth,
                        "Stockfish Classic HCE • depth " + safeDepth + " • " + safePv + "PV"));
            } catch (Throwable t) {
                callback.onError("Offline engine lỗi: " + t.getMessage());
            }
        }, "offline-engine").start();
    }

    // ═══════════════════ UTILITIES ═══════════════════════════════════════

    private static String extractUciMove(String raw) {
        if (raw == null)
            return null;
        String s = raw.trim();
        if (s.isEmpty())
            return null;

        String[] tokens = s.split("\\s+");
        for (String tok : tokens) {
            if (UCI_MOVE_PATTERN.matcher(tok).matches()) {
                return tok.toLowerCase(Locale.US);
            }
        }

        String lower = s.toLowerCase(Locale.US);
        if (lower.length() < 4)
            return null;
        String first4 = lower.substring(0, 4);
        if (!UCI_MOVE_PATTERN.matcher(first4).matches())
            return null;
        if (lower.length() >= 5) {
            char promo = lower.charAt(4);
            if (promo == 'q' || promo == 'r' || promo == 'b' || promo == 'n') {
                String first5 = lower.substring(0, 5);
                if (UCI_MOVE_PATTERN.matcher(first5).matches()) {
                    return first5;
                }
            }
        }
        return first4;
    }
}
