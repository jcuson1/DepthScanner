package com.example.depthdebug;

import java.util.Arrays;

public final class UnderfootStepDetector {

    public enum Kind { NONE, STEP_UP, STEP_DOWN }

    public static final class State {
        public final Kind kind;
        public final boolean on;
        public final float evidenceM; // величина перепада (jump), для дебага
        public final int atRow;       // где в профиле нашли перепад (индекс пары)

        State(Kind k, boolean on, float ev, int atRow) {
            this.kind = k;
            this.on = on;
            this.evidenceM = ev;
            this.atRow = atRow;
        }
    }

    // --- Tunables ---
    private static final float MIN_TILE_VALID_RATIO = 0.25f;

    // STEP_UP (бордюр/порог): ниже стало ближе
    private static final float DELTA_UP_M = 0.25f;

    // STEP_DOWN (лестница вниз/яма): ниже стало дальше
    private static final float DELTA_DOWN_M = 0.35f;

    // Для down нужна приемлемая глобальная качество глубины
    private static final float MIN_QUALITY_FOR_DOWN = 0.10f;

    // Unknown используем как усилитель
    private static final float EDGE_UNKNOWN_HINT = 0.60f;

    // Накопление
    private final ScoreIntegrator upScore   = new ScoreIntegrator(20, 8, 3, 2, 1);
    private final ScoreIntegrator downScore = new ScoreIntegrator(20, 8, 3, 2, 1);

    public void reset() {
        upScore.reset();
        downScore.reset();
    }

    public State update(RawDepthTileAnalyzer.Grid g) {
        // === Underfoot ROI ===
        // Под наклонённый телефон край лестницы обычно попадает выше, чем самый низ кадра.
        // Поэтому берём нижнюю ПОЛОВИНУ, а не только самый низ.
        int c0 = (int)Math.floor(g.cols * 0.35);
        int c1 = (int)Math.ceil (g.cols * 0.65);

        int r0 = (int)Math.floor(g.rows * 0.50); // было ~0.65 — слишком низко
        int r1 = g.rows;

        int profN = r1 - r0;
        if (profN < 3) {
            // слишком мало рядов — ничего не делаем
            return new State(Kind.NONE, false, 0f, -1);
        }

        float[] dRow = new float[profN];
        float[] uRow = new float[profN];
        Arrays.fill(dRow, Float.POSITIVE_INFINITY);

        // --- Профиль по строкам: медиана p50 по тайлам строки (робастно) ---
        for (int rr = r0; rr < r1; rr++) {
            float unkSum = 0f;
            int used = 0;

            float[] rowVals = new float[Math.max(1, c1 - c0)];
            int rowN = 0;

            for (int c = c0; c < c1; c++) {
                int i = g.idx(c, rr);
                if (g.validRatio[i] < MIN_TILE_VALID_RATIO) continue;

                float d = g.p50m[i];
                if (!Float.isFinite(d)) continue;

                rowVals[rowN++] = d;
                unkSum += g.unknownRatio[i];
                used++;
            }

            int k = rr - r0;
            if (rowN == 0) {
                dRow[k] = Float.POSITIVE_INFINITY;
                uRow[k] = 1f;
            } else {
                Arrays.sort(rowVals, 0, rowN);
                dRow[k] = rowVals[rowN / 2]; // median distance
                uRow[k] = unkSum / used;
            }
        }

        // --- Ищем скачки по всему профилю ---
        // jumpDown: ниже по кадру стало дальше (лестница вниз / край)
        float bestJumpDown = 0f;
        int bestDownAt = -1;

        // jumpUp: ниже по кадру стало ближе (бордюр/порог/объект)
        float bestJumpUp = 0f;
        int bestUpAt = -1;

        for (int i = 0; i < profN - 1; i++) {
            float a = dRow[i];
            float b = dRow[i + 1];
            if (!Float.isFinite(a) || !Float.isFinite(b)) continue;

            float down = b - a; // >0 значит "вниз стало дальше"
            if (down > bestJumpDown) {
                bestJumpDown = down;
                bestDownAt = i;
            }

            float up = a - b; // >0 значит "вниз стало ближе"
            if (up > bestJumpUp) {
                bestJumpUp = up;
                bestUpAt = i;
            }
        }

        // --- STEP_DOWN (лестница/яма) ---
        // ВАЖНО: чтобы не было ложных фиолетовых — down включаем только по геометрии,
        // unknown лишь помогает понять, что это край.
        boolean stepDownGeom = (bestDownAt >= 0) && (bestJumpDown > DELTA_DOWN_M);

        boolean stepDownUnknownHint = false;
        if (bestDownAt >= 0) {
            float u1 = uRow[bestDownAt];
            float u2 = uRow[bestDownAt + 1];
            stepDownUnknownHint = (Math.max(u1, u2) > EDGE_UNKNOWN_HINT);
        }

        boolean stepDownNow =
                (g.quality >= MIN_QUALITY_FOR_DOWN) &&
                        stepDownGeom;

        // --- STEP_UP (бордюр/порог/препятствие под ногами) ---
        boolean stepUpNow =
                (bestUpAt >= 0) &&
                        (bestJumpUp > DELTA_UP_M);

        // Доп. спокойствие: если качество плохое — требуем более сильный UP
        if (g.quality < 0.10f && bestJumpUp < 0.32f) {
            stepUpNow = false;
        }

        boolean downOn = downScore.update(stepDownNow);
        boolean upOn = upScore.update(stepUpNow);

        if (downOn) {
            // evidence усилим подсказкой unknown (только для дебага/логов)
            float ev = bestJumpDown + (stepDownUnknownHint ? 0.05f : 0f);
            return new State(Kind.STEP_DOWN, true, ev, bestDownAt);
        }
        if (upOn) {
            return new State(Kind.STEP_UP, true, bestJumpUp, bestUpAt);
        }
        return new State(Kind.NONE, false, 0f, -1);
    }
}