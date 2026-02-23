package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.util.ArrayList;
import java.util.List;

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
        public final DepthGridAnalyzer.Grid grid;
        public final Alert primaryAlert; // null if none
        public final int[] hotCells; // indices in 3x5 grid (0..14) that are confirmed dangerous

        public Result(boolean depthReady, float quality, DepthGridAnalyzer.Grid grid,
                      Alert primaryAlert, int[] hotCells) {
            this.depthReady = depthReady;
            this.quality = quality;
            this.grid = grid;
            this.primaryAlert = primaryAlert;
            this.hotCells = hotCells;
        }
    }

    // Grid 3x5
    private static final int COLS = 3;
    private static final int ROWS = 5;

    // Analyzer
    private final DepthGridAnalyzer analyzer = new DepthGridAnalyzer(COLS, ROWS);

    // Data quality gating
    private final float minQualityToDecide = 0.08f;

    // Thresholds (meters)
    // Forward corridor: warn early.
    private final float forwardOn = 2.3f;
    private final float forwardOff = 2.6f;

    // Walls / sides: warn later to avoid constant noise in narrow spaces.
    private final float wallOn = 1.4f;
    private final float wallOff = 1.7f;

    // Underfoot objects: very conservative.
    private final float underfootOn = 1.1f;
    private final float underfootOff = 1.3f;

    // Severity split
    private final float nearM = 0.9f;

    // Drop detection (unknown depth)
    private final float dropUnknownOn = 0.55f;
    private final float dropUnknownOff = 0.40f;

    // Temporal confirmation per cell
    private final int history = 14;    // ~0.45s at 30fps
    private final int confirmOn = 3;   // 3 frames out of N
    private final int confirmOff = 9;  // 9 safe frames out of N

    private final int[][] histDanger = new int[COLS * ROWS][history];
    private int histIdx = 0;
    private boolean histFilled = false;

    // Separate state for DROP (to use different signal + hysteresis)
    private final int[] dropHist = new int[history];

    public Result update(Frame frame) {
        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            return new Result(false, 0f, null, null, new int[0]);
        }

        Image depth = null;
        try {
            depth = frame.acquireDepthImage16Bits();
            DepthGridAnalyzer.Grid grid = analyzer.analyze(depth);

            if (grid.quality < minQualityToDecide) {
                // Not enough reliable depth: keep UI updated but do not scream.
                pushNoDanger();
                return new Result(true, grid.quality, grid, null, new int[0]);
            }

            boolean[] dangerNow = computeDangerNow(grid);
            pushHistory(dangerNow);
            pushDropHistory(grid);

            boolean[] confirmed = computeConfirmed();
            boolean dropConfirmed = dropConfirmed(grid);

            int[] hotCells = toHotCells(confirmed);
            Alert primary = pickPrimaryAlert(grid, confirmed, dropConfirmed);

            return new Result(true, grid.quality, grid, primary, hotCells);

        } catch (NotYetAvailableException e) {
            // depth not ready, keep calm
            return new Result(false, 0f, null, null, new int[0]);
        } finally {
            if (depth != null) depth.close();
        }
    }
    private boolean[] computeDangerNow(DepthGridAnalyzer.Grid g) {
        boolean[] danger = new boolean[COLS * ROWS];

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int i = g.idx(c, r);
                float d = g.p10m[i];

                // Regions
                boolean inForwardCorridor = (c == 1) && (r >= 1 && r <= 3);
                boolean inUnderfoot = (r >= 4) && (c == 1); // bottom-center
                boolean inWalls = (c != 1) && (r >= 1 && r <= 3);

                float on, off;
                if (inUnderfoot) {
                    on = underfootOn;
                    off = underfootOff;
                } else if (inForwardCorridor) {
                    on = forwardOn;
                    off = forwardOff;
                } else if (inWalls) {
                    on = wallOn;
                    off = wallOff;
                } else {
                    // top row and bottom corners are noisy / less useful for walking
                    continue;
                }

                if (!Float.isFinite(d)) continue;
                danger[i] = (d < on);
            }
        }

        return danger;
    }

    private void pushHistory(boolean[] dangerNow) {
        for (int i = 0; i < dangerNow.length; i++) {
            histDanger[i][histIdx] = dangerNow[i] ? 1 : 0;
        }
        histIdx++;
        if (histIdx >= history) {
            histIdx = 0;
            histFilled = true;
        }
    }

    private void pushNoDanger() {
        boolean[] none = new boolean[COLS * ROWS];
        pushHistory(none);
        dropHist[histIdx == 0 ? (history - 1) : (histIdx - 1)] = 0;
    }

    private void pushDropHistory(DepthGridAnalyzer.Grid g) {
        int bc = g.idx(1, 4);
        float u = g.unknown[bc];
        int v = (u > dropUnknownOn) ? 1 : 0;
        dropHist[histIdx == 0 ? (history - 1) : (histIdx - 1)] = v;
    }

    private boolean[] computeConfirmed() {
        int K = histFilled ? history : histIdx;
        boolean[] confirmed = new boolean[COLS * ROWS];
        for (int i = 0; i < confirmed.length; i++) {
            int sum = 0;
            for (int k = 0; k < K; k++) sum += histDanger[i][k];
            confirmed[i] = (sum >= confirmOn);
        }
        return confirmed;
    }

    private boolean dropConfirmed(DepthGridAnalyzer.Grid g) {
        int K = histFilled ? history : histIdx;
        if (K < 3) return false;

        int sum = 0;
        for (int k = 0; k < K; k++) sum += dropHist[k];
        boolean on = (sum >= confirmOn);

        int bc = g.idx(1, 4);
        float uNow = g.unknown[bc];
        if (uNow < dropUnknownOff) {
            int safe = K - sum;
            if (safe >= confirmOff) return false;
        }
        return on;
    }

    private int[] toHotCells(boolean[] confirmed) {
        List<Integer> hot = new ArrayList<>();
        for (int i = 0; i < confirmed.length; i++) {
            if (confirmed[i]) hot.add(i);
        }
        int[] out = new int[hot.size()];
        for (int i = 0; i < hot.size(); i++) out[i] = hot.get(i);
        return out;
    }

    private Alert pickPrimaryAlert(DepthGridAnalyzer.Grid g, boolean[] confirmed, boolean drop) {
        if (drop) {
            return new Alert(Kind.DROP, Direction.CENTER, Severity.CRITICAL, Float.POSITIVE_INFINITY);
        }

        Alert best = null;

        // underfoot
        int under = g.idx(1, 4);
        if (confirmed[under]) {
            float d = g.p10m[under];
            Severity s = (Float.isFinite(d) && d < nearM) ? Severity.NEAR : Severity.FAR;
            best = new Alert(Kind.UNDERFOOT, Direction.CENTER, s, d);
        }

        // forward: closest among rows 1..3 in center column
        float bestForward = Float.POSITIVE_INFINITY;
        int bestRow = -1;
        for (int r = 1; r <= 3; r++) {
            int i = g.idx(1, r);
            if (!confirmed[i]) continue;
            float d = g.p10m[i];
            if (d < bestForward) {
                bestForward = d;
                bestRow = r;
            }
        }
        if (bestRow >= 0) {
            Severity s = (Float.isFinite(bestForward) && bestForward < nearM) ? Severity.NEAR : Severity.FAR;
            Alert a = new Alert(Kind.FORWARD, Direction.CENTER, s, bestForward);
            best = pickMoreImportant(best, a);
        }

        // walls: closest of left/right (rows 1..3)
        float bestWall = Float.POSITIVE_INFINITY;
        Direction wallDir = null;
        for (int r = 1; r <= 3; r++) {
            int li = g.idx(0, r);
            int ri = g.idx(2, r);
            if (confirmed[li]) {
                float d = g.p10m[li];
                if (d < bestWall) { bestWall = d; wallDir = Direction.LEFT; }
            }
            if (confirmed[ri]) {
                float d = g.p10m[ri];
                if (d < bestWall) { bestWall = d; wallDir = Direction.RIGHT; }
            }
        }
        if (wallDir != null) {
            Severity s = (Float.isFinite(bestWall) && bestWall < nearM) ? Severity.NEAR : Severity.FAR;
            Alert a = new Alert(Kind.WALL, wallDir, s, bestWall);
            best = pickMoreImportant(best, a);
        }

        return best;
    }

    private static Alert pickMoreImportant(Alert current, Alert candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;

        int pCur = priority(current);
        int pNew = priority(candidate);
        if (pNew > pCur) return candidate;

        if (pNew == pCur) {
            if (candidate.distanceM < current.distanceM) return candidate;
        }
        return current;
    }

    private static int priority(Alert a) {
        if (a.kind == Kind.DROP) return 100;
        if (a.kind == Kind.UNDERFOOT) return (a.severity == Severity.NEAR) ? 90 : 80;
        if (a.kind == Kind.FORWARD) return (a.severity == Severity.NEAR) ? 70 : 60;
        if (a.kind == Kind.WALL) return (a.severity == Severity.NEAR) ? 50 : 40;
        return 0;
    }
}