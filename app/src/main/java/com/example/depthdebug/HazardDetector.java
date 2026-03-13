package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

public final class HazardDetector {

    public enum Direction { LEFT, CENTER, RIGHT }
    public enum Severity { FAR, NEAR, CRITICAL }
    public enum Kind { FORWARD, WALL, UNDERFOOT, DROP }

    public static final class Alert {
        public final Kind kind;
        public final Direction direction;
        public final Severity severity;
        public final float distanceM;

        public Alert(Kind kind, Direction direction, Severity severity, float distanceM) {
            this.kind = kind;
            this.direction = direction;
            this.severity = severity;
            this.distanceM = distanceM;
        }
    }

    public static final class Result {
        public final boolean depthReady;
        public final float quality;  // 0..1
        public final RawDepthTileAnalyzer.Grid grid; // теперь tile-grid, не старый DepthGridAnalyzer
        public final Alert primaryAlert; // null if none
        public final int[] hotCells; // indices in 3x5 grid (0..14)

        public Result(boolean depthReady, float quality, RawDepthTileAnalyzer.Grid grid, Alert primaryAlert, int[] hotCells) {
            this.depthReady = depthReady;
            this.quality = quality;
            this.grid = grid;
            this.primaryAlert = primaryAlert;
            this.hotCells = hotCells;
        }
    }

    // Детекция (тайлы)
    private final RawDepthTileAnalyzer analyzer = new RawDepthTileAnalyzer(20, 12);

    // Два детектора
    private final WallForwardDetector wallForward = new WallForwardDetector();
    private final UnderfootStepDetector underfoot = new UnderfootStepDetector();

    // Thresholds for severity
    private final float nearM = 0.9f;

    // Quality gating: если совсем плохо — не даём FAR-алерты
    private final float minQualityForNonCritical = 0.10f;

    public Result update(Frame frame) {
        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            wallForward.reset();
            underfoot.reset();
            return new Result(false, 0f, null, null, new int[0]);
        }

        Image rawDepth = null;
        Image conf = null;

        try {
            rawDepth = frame.acquireRawDepthImage16Bits();
            conf = frame.acquireRawDepthConfidenceImage();

            RawDepthTileAnalyzer.Grid g = analyzer.analyze(rawDepth, conf);

            // Update detectors
            WallForwardDetector.State wf = wallForward.update(g);
            UnderfootStepDetector.State uf = underfoot.update(g);

            Alert primary = composePrimaryAlert(g, wf, uf);
            int[] hot = buildHotCells3x5(wf, uf);

            // Если качество слабое — глушим не критичные оповещения
            if (g.quality < minQualityForNonCritical && primary != null) {
                boolean critical = (primary.severity == Severity.CRITICAL) || (primary.severity == Severity.NEAR);
                if (!critical) primary = null;
            }

            return new Result(true, g.quality, g, primary, hot);

        } catch (NotYetAvailableException e) {
            return new Result(false, 0f, null, null, new int[0]);
        } finally {
            if (rawDepth != null) rawDepth.close();
            if (conf != null) conf.close();
        }
    }

    private Alert composePrimaryAlert(RawDepthTileAnalyzer.Grid g, WallForwardDetector.State wf, UnderfootStepDetector.State uf) {
        // 1) STEP_DOWN = CRITICAL DROP/UNDERFOOT
        if (uf.on && uf.kind == UnderfootStepDetector.Kind.STEP_DOWN) {
            return new Alert(Kind.DROP, Direction.CENTER, Severity.CRITICAL, Float.POSITIVE_INFINITY);
        }

        // 2) STEP_UP = UNDERFOOT (обычно NEAR/FAR по дистанции не всегда корректно, но можно приблизить)
        if (uf.on && uf.kind == UnderfootStepDetector.Kind.STEP_UP) {
            // как "distance": берём ближайшую оценку (для вибро-уровня)
            float d = estimateUnderfootDistance(g);
            Severity s = (Float.isFinite(d) && d < nearM) ? Severity.NEAR : Severity.FAR;
            return new Alert(Kind.UNDERFOOT, Direction.CENTER, s, d);
        }

        // 3) FORWARD
        if (wf.forwardOn) {
            float d = wf.forwardDistM;
            Severity s = (Float.isFinite(d) && d < nearM) ? Severity.NEAR : Severity.FAR;
            return new Alert(Kind.FORWARD, Direction.CENTER, s, d);
        }

        // 4) WALLS
        if (wf.leftWallOn || wf.rightWallOn) {
            boolean left = wf.leftWallOn && (!wf.rightWallOn || wf.leftDistM <= wf.rightDistM);
            Direction dir = left ? Direction.LEFT : Direction.RIGHT;
            float d = left ? wf.leftDistM : wf.rightDistM;
            Severity s = (Float.isFinite(d) && d < nearM) ? Severity.NEAR : Severity.FAR;
            return new Alert(Kind.WALL, dir, s, d);
        }

        return null;
    }

    private float estimateUnderfootDistance(RawDepthTileAnalyzer.Grid g) {
        // Берём самое близкое в нижнем центре
        int c0 = (int)Math.floor(g.cols * 0.35);
        int c1 = (int)Math.ceil (g.cols * 0.65);
        int r0 = (int)Math.floor(g.rows * 0.70);
        int r1 = g.rows;

        float best = Float.POSITIVE_INFINITY;
        for (int r = r0; r < r1; r++) {
            for (int c = c0; c < c1; c++) {
                int i = g.idx(c, r);
                if (g.validRatio[i] < 0.25f) continue;
                best = Math.min(best, g.p20m[i]);
            }
        }
        return best;
    }

    // Твоя сетка 3x5 для DebugOverlayView (0..14)
    private int[] buildHotCells3x5(WallForwardDetector.State wf, UnderfootStepDetector.State uf) {
        // Явно подсвечиваем зоны:
        // rows: 0..4, cols: 0..2
        // forward = center col, rows 1..3
        // walls   = left/right cols, rows 1..3
        // underfoot/drop = bottom center (row 4, col 1)
        boolean[] hot = new boolean[15];

        if (wf.forwardOn) {
            hot[1 * 3 + 1] = true;
            hot[2 * 3 + 1] = true;
            hot[3 * 3 + 1] = true;
        }
        if (wf.leftWallOn) {
            hot[1 * 3 + 0] = true;
            hot[2 * 3 + 0] = true;
            hot[3 * 3 + 0] = true;
        }
        if (wf.rightWallOn) {
            hot[1 * 3 + 2] = true;
            hot[2 * 3 + 2] = true;
            hot[3 * 3 + 2] = true;
        }
        if (uf.on) {
            hot[4 * 3 + 1] = true;
        }

        int n = 0;
        for (boolean b : hot) if (b) n++;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < hot.length; i++) if (hot[i]) out[k++] = i;
        return out;
    }
}