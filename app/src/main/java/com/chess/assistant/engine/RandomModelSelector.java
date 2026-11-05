package com.chess.assistant.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bộ chọn model ngẫu nhiên với ràng buộc:
 * • Không dùng cùng model quá MAX_CONSECUTIVE (2) nước liên tiếp.
 * • Sau khi ngừng dùng 1 model, phải chờ COOLDOWN_MOVES (2) nước mới dùng lại.
 *
 * UPDATE (2026-11-05):
 * - [KEEP] Logic chọn y nguyên.
 * - Concrete pool giờ có 5 model (thêm LICHESS_OPENING).
 * - Khi trả về model tier-based, caller (service) tự chọn tier ngẫu nhiên.
 */
public class RandomModelSelector {

    private static final int MAX_CONSECUTIVE = 2;
    private static final int COOLDOWN_MOVES = 2;

    private final ChessModel[] models;
    private final int[] lastUsedAt;
    private final int[] consecutiveCount;
    private int moveIndex = 0;
    private final Random random = new Random();

    public RandomModelSelector() {
        models = ChessModel.concreteModels();
        lastUsedAt = new int[models.length];
        consecutiveCount = new int[models.length];
        for (int i = 0; i < models.length; i++)
            lastUsedAt[i] = -1;
    }

    public void reset() {
        for (int i = 0; i < models.length; i++) {
            lastUsedAt[i] = -1;
            consecutiveCount[i] = 0;
        }
        moveIndex = 0;
    }

    public ChessModel pick(int userDepth) {
        List<Integer> candidates = new ArrayList<>();
        List<Integer> relaxed = new ArrayList<>();

        for (int i = 0; i < models.length; i++) {
            if (!models[i].supportsDepth(userDepth))
                continue;
            if (consecutiveCount[i] < MAX_CONSECUTIVE)
                relaxed.add(i);
            if (canUse(i))
                candidates.add(i);
        }

        List<Integer> pool;
        if (!candidates.isEmpty())
            pool = candidates;
        else if (!relaxed.isEmpty())
            pool = relaxed;
        else
            pool = fallbackPool(userDepth);

        if (pool.isEmpty())
            return ChessModel.OFFLINE_HCE;

        int pickedIdx = pool.get(random.nextInt(pool.size()));
        return updateState(pickedIdx);
    }

    /** Random tier index cho model tier-based (0..N-1). */
    public int pickTierFor(ChessModel model) {
        int n = model.getTierCount();
        if (n <= 0)
            return 0;
        return random.nextInt(n);
    }

    private boolean canUse(int i) {
        if (consecutiveCount[i] >= MAX_CONSECUTIVE)
            return false;
        int last = lastUsedAt[i];
        if (last < 0)
            return true;
        if (last == moveIndex - 1)
            return true;
        return (moveIndex - last) > COOLDOWN_MOVES;
    }

    private List<Integer> fallbackPool(int userDepth) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < models.length; i++) {
            if (models[i].supportsDepth(userDepth))
                result.add(i);
        }
        return result;
    }

    private ChessModel updateState(int pickedIdx) {
        for (int i = 0; i < models.length; i++) {
            if (i != pickedIdx && lastUsedAt[i] == moveIndex - 1) {
                consecutiveCount[i] = 0;
            }
        }
        if (lastUsedAt[pickedIdx] == moveIndex - 1) {
            consecutiveCount[pickedIdx]++;
        } else {
            consecutiveCount[pickedIdx] = 1;
        }
        lastUsedAt[pickedIdx] = moveIndex;
        moveIndex++;
        return models[pickedIdx];
    }
}