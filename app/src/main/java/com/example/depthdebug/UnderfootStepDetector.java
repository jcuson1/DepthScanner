package com.example.depthdebug;

import java.util.Arrays;

public final class UnderfootStepDetector {

    public enum Kind { NONE, STEP_UP, STEP_DOWN }

    public static final class State {
        public final Kind kind;
        public final boolean on;
        public final float evidenceM; // величина перепада (примерно), для дебага

        State(Kind k, boolean on, float ev) { this.kind = k; this.on = on; this.evidenceM = ev; }
    }

    private static final float MIN_TILE_VALID_RATIO = 0.25f;

    // Перепад вверх: поребрик/порог/ступенька
    private static final float DELTA_UP_M = 0.25f;

    // Перепад вниз: край лестницы/яма
    private static final float DELTA_DOWN_M = 0.30f;

    private static final float MIN_QUALITY_FOR_DOWN = 0.10f;

    private final ScoreIntegrator upScore   = new ScoreIntegrator(20, 8, 3, 2, 1);
    private final ScoreIntegrator downScore = new ScoreIntegrator(20, 8, 3, 2, 1);

    public void reset() {
        upScore.reset();
        downScore.reset();
    }

    public State update(RawDepthTileAnalyzer.Grid g) {
        // Underfoot ROI: нижние 35% и центральные 30% по ширине
        int c0 = (int)Math.floor(g.cols * 0.35);
        int c1 = (int)Math.ceil (g.cols * 0.65);
        int r0 = (int)Math.floor(g.rows * 0.65);
        int r1 = g.rows;

        // Строим профиль по рядам: dRow[r] = robust distance (p50) по центральным тайлам
        float[] dRow = new float[r1 - r0];
        float[] uRow = new float[r1 - r0]; // unknown ratio для нижних рядов
        Arrays.fill(dRow, Float.POSITIVE_INFINITY);

        for (int rr = r0; rr < r1; rr++) {
            float unkSum = 0f;
            int used = 0;

            // маленький буфер для значений строки
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
            } else {
                java.util.Arrays.sort(rowVals, 0, rowN);
                dRow[k] = rowVals[rowN / 2]; // median
            }
            uRow[k] = (used == 0) ? 1f : (unkSum / used);
        }

        // Нужны хотя бы 2 валидные строки профиля
        int kBottom = dRow.length - 1;
        int kAbove = Math.max(0, kBottom - 1);

        float dBottom = dRow[kBottom];
        float dAbove  = dRow[kAbove];

        // кластерность: сколько тайлов в нижней строке реально ближе
        int bottomRow = r1 - 1;
        int validTiles = 0;
        int closeTiles = 0;

        for (int c = c0; c < c1; c++) {
            int i = g.idx(c, bottomRow);
            if (g.validRatio[i] < MIN_TILE_VALID_RATIO) continue;
            validTiles++;
            if (g.p20m[i] < 1.2f) closeTiles++; // 1.2м можно вынести в константу
        }

        boolean clusterOk = (validTiles >= 2) && (closeTiles >= 2);

        boolean has = Float.isFinite(dBottom) && Float.isFinite(dAbove);

        float delta = has ? (dAbove - dBottom) : 0f; // >0 означает "внизу ближе" (ступенька вверх/объект)

        boolean stepUpNow = has && (delta > DELTA_UP_M) && clusterOk;

        if (g.quality < 0.10f && delta < 0.30f) stepUpNow = false;

        // Step-down: внизу стало дальше (или пропало) относительно выше, но только при нормальном качестве
        float deltaDown = has ? (dBottom - dAbove) : 0f;
        boolean stepDownGeom = has && (deltaDown > DELTA_DOWN_M);

        // Доп. признак для down: низ сильно “дырявый”
        boolean stepDownUnknown = (g.quality >= MIN_QUALITY_FOR_DOWN) && (uRow[kBottom] > 0.55f);

        boolean stepDownNow = (g.quality >= MIN_QUALITY_FOR_DOWN) && stepDownGeom;

        boolean upOn = upScore.update(stepUpNow);
        boolean downOn = downScore.update(stepDownNow);

        if (downOn) return new State(Kind.STEP_DOWN, true, Math.max(deltaDown, 0f));
        if (upOn) return new State(Kind.STEP_UP, true, Math.max(delta, 0f));
        return new State(Kind.NONE, false, 0f);
    }
}