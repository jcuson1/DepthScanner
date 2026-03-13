package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;

public final class PassageDetector {

    public enum State { FREE, BLOCKED, UNKNOWN }
    public enum Severity { INFO, NEAR, CRITICAL }

    public static final class Result {
        public final boolean depthReady;
        public final State state;
        public final Severity severity;
        public final float quality;
        public final float distanceM;
        public final String reason;

        public Result(boolean depthReady, State state, Severity severity, float quality, float distanceM, String reason) {
            this.depthReady = depthReady;
            this.state = state;
            this.severity = severity;
            this.quality = quality;
            this.distanceM = distanceM;
            this.reason = reason;
        }
    }

    private final RawDepthTileAnalyzer analyzer = new RawDepthTileAnalyzer(
            15,
            10,
            0.22f, 0.78f,
            0.18f, 0.90f,
            110,
            250,
            5000,
            3
    );

    private int blockedStreak = 0;
    private int freeStreak = 0;
    private int unknownStreak = 0;
    private float smoothedDistanceM = Float.POSITIVE_INFINITY;

    public Result update(Frame frame, OrientationHelper.Orientation orientation) {
        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            reset();
            return new Result(false, State.UNKNOWN, Severity.INFO, 0f, Float.POSITIVE_INFINITY, "tracking_lost");
        }

        if (orientation == null) {
            resetTemporal();
            return new Result(false, State.UNKNOWN, Severity.INFO, 0f, Float.POSITIVE_INFINITY, "orientation_missing");
        }

        if (Math.abs(orientation.rollDeg) > 14f) {
            resetTemporal();
            return new Result(false, State.UNKNOWN, Severity.INFO, 0f, Float.POSITIVE_INFINITY, "hold_phone_more_level");
        }

        if (orientation.pitchDeg > -10f || orientation.pitchDeg < -75f) {
            resetTemporal();
            return new Result(false, State.UNKNOWN, Severity.INFO, 0f, Float.POSITIVE_INFINITY, "aim_phone_forward_and_slightly_down");
        }

        Image rawDepth = null;
        Image confidence = null;
        try {
            rawDepth = frame.acquireRawDepthImage16Bits();
            confidence = frame.acquireRawDepthConfidenceImage();

            RawDepthTileAnalyzer.Grid grid = analyzer.analyze(rawDepth, confidence);
            return evaluate(grid);
        } catch (Exception e) {
            resetTemporal();
            return new Result(false, State.UNKNOWN, Severity.INFO, 0f, Float.POSITIVE_INFINITY, "depth_not_ready");
        } finally {
            if (rawDepth != null) rawDepth.close();
            if (confidence != null) confidence.close();
        }
    }

    public void reset() {
        resetTemporal();
        smoothedDistanceM = Float.POSITIVE_INFINITY;
    }

    private void resetTemporal() {
        blockedStreak = 0;
        freeStreak = 0;
        unknownStreak = 0;
    }

    private Result evaluate(RawDepthTileAnalyzer.Grid grid) {
        final int cols = grid.cols;
        final int rows = grid.rows;
        final int centerC0 = cols / 2 - 2;
        final int centerC1 = cols / 2 + 2;

        float nearBlocked = 0f;
        float midBlocked = 0f;
        float farBlocked = 0f;
        float nearValid = 0f;
        float midValid = 0f;
        float farValid = 0f;
        float nearMin = Float.POSITIVE_INFINITY;
        float midMin = Float.POSITIVE_INFINITY;
        float farMin = Float.POSITIVE_INFINITY;
        float bestCentralDistance = Float.POSITIVE_INFINITY;

        int nearRows = 0;
        int midRows = 0;
        int farRows = 0;

        for (int r = 0; r < rows; r++) {
            boolean isNear = r >= 6;
            boolean isMid = r >= 4 && r <= 6;
            boolean isFar = r <= 4;
            for (int c = centerC0; c <= centerC1; c++) {
                int idx = grid.idx(c, r);
                float dist = grid.p20m[idx];
                float valid = grid.validRatio[idx];
                boolean strongValid = valid >= 0.18f;
                boolean blocked = strongValid && dist < distanceThresholdForRow(r, rows);

                if (strongValid) {
                    bestCentralDistance = Math.min(bestCentralDistance, dist);
                }

                if (isNear) {
                    nearRows++;
                    if (strongValid) nearValid += 1f;
                    if (blocked) nearBlocked += 1f;
                    if (strongValid) nearMin = Math.min(nearMin, dist);
                }
                if (isMid) {
                    midRows++;
                    if (strongValid) midValid += 1f;
                    if (blocked) midBlocked += 1f;
                    if (strongValid) midMin = Math.min(midMin, dist);
                }
                if (isFar) {
                    farRows++;
                    if (strongValid) farValid += 1f;
                    if (blocked) farBlocked += 1f;
                    if (strongValid) farMin = Math.min(farMin, dist);
                }
            }
        }

        float nearValidRate = safeDiv(nearValid, nearRows);
        float midValidRate = safeDiv(midValid, midRows);
        float farValidRate = safeDiv(farValid, farRows);

        float nearBlockedRate = safeDiv(nearBlocked, nearRows);
        float midBlockedRate = safeDiv(midBlocked, midRows);
        float farBlockedRate = safeDiv(farBlocked, farRows);

        if (!Float.isInfinite(bestCentralDistance)) {
            if (Float.isInfinite(smoothedDistanceM)) smoothedDistanceM = bestCentralDistance;
            else smoothedDistanceM = 0.7f * smoothedDistanceM + 0.3f * bestCentralDistance;
        }

        boolean qualityTooLow = grid.quality < 0.08f || (nearValidRate < 0.12f && midValidRate < 0.12f);
        if (qualityTooLow) {
            blockedStreak = 0;
            freeStreak = 0;
            unknownStreak++;
            return new Result(true, State.UNKNOWN, Severity.INFO, grid.quality, smoothedDistanceM, "not_enough_depth_points");
        }

        boolean criticalNear = nearBlockedRate >= 0.24f && finiteLt(nearMin, 0.55f);
        boolean blocked = criticalNear
                || (nearBlockedRate >= 0.32f && finiteLt(nearMin, 0.90f))
                || (midBlockedRate >= 0.38f && finiteLt(midMin, 1.25f))
                || ((nearBlockedRate + midBlockedRate) >= 0.62f && finiteLt(bestCentralDistance, 1.15f));

        boolean free = nearValidRate >= 0.28f
                && midValidRate >= 0.24f
                && nearBlockedRate <= 0.10f
                && midBlockedRate <= 0.16f
                && !(finiteLt(nearMin, 0.75f))
                && !(finiteLt(midMin, 1.0f));

        if (blocked) {
            blockedStreak++;
            freeStreak = 0;
            unknownStreak = 0;
            if (blockedStreak < (criticalNear ? 1 : 2)) {
                return new Result(true, State.UNKNOWN, Severity.INFO, grid.quality, smoothedDistanceM, "checking_obstacle");
            }
            Severity severity = criticalNear ? Severity.CRITICAL : Severity.NEAR;
            String reason = criticalNear ? "near_obstacle" : "path_blocked";
            return new Result(true, State.BLOCKED, severity, grid.quality, bestFinite(nearMin, midMin, farMin), reason);
        }

        if (free) {
            freeStreak++;
            blockedStreak = 0;
            unknownStreak = 0;
            if (freeStreak < 2) {
                return new Result(true, State.UNKNOWN, Severity.INFO, grid.quality, smoothedDistanceM, "confirming_free_path");
            }
            return new Result(true, State.FREE, Severity.INFO, grid.quality, bestFinite(nearMin, midMin, farMin), "free_corridor_confirmed");
        }

        unknownStreak++;
        blockedStreak = 0;
        freeStreak = 0;
        String reason = farValidRate < 0.10f ? "scene_too_sparse" : "unstable_corridor";
        return new Result(true, State.UNKNOWN, Severity.INFO, grid.quality, smoothedDistanceM, reason);
    }

    private static float distanceThresholdForRow(int row, int rows) {
        float t = row / (float) Math.max(1, rows - 1);
        return 1.55f - 0.65f * t;
    }

    private static boolean finiteLt(float value, float limit) {
        return !Float.isInfinite(value) && value < limit;
    }

    private static float safeDiv(float num, int den) {
        return den <= 0 ? 0f : num / (float) den;
    }

    private static float bestFinite(float... values) {
        float best = Float.POSITIVE_INFINITY;
        for (float v : values) {
            if (!Float.isInfinite(v)) best = Math.min(best, v);
        }
        return best;
    }
}
