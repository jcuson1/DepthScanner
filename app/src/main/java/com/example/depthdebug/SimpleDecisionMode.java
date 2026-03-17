package com.example.depthdebug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SimpleDecisionMode implements DetectionMode {

    private static final int MIN_VALID_POINTS = 120;

    // Общее препятствие впереди
    private static final int OBSTACLE_NEAR_MM = 2200;
    private static final float LARGE_OCCUPANCY_THRESHOLD = 0.36f;

    // Низкие препятствия
    private static final int FLOOR_OBSTACLE_NEAR_MM = 1600;
    private static final float FLOOR_CLUSTER_CELL_THRESHOLD = 0.22f;
    private static final int FLOOR_GRID_COLS = 6;
    private static final int FLOOR_GRID_ROWS = 3;
    private static final int FLOOR_MIN_ACTIVE_CELLS = 3;

    private final DownStepProfileMode downStepProfileMode = new DownStepProfileMode();

    @Override
    public DetectionModeType getType() {
        return DetectionModeType.HYBRID;
    }

    @Override
    public String getDisplayName() {
        return "Русский decision mode";
    }

    @Override
    public DetectionResult process(DetectionFrame frame) {
        if (frame == null || !frame.hasDepth()) {
            return noData("Нет depth");
        }

        short[] depth = frame.getDepthMillimeters();
        int width = frame.getDepthWidth();
        int height = frame.getDepthHeight();

        ValidityStats validity = computeValidity(depth, width, height);
        if (validity.validCount < MIN_VALID_POINTS || validity.validFraction < 0.04f) {
            return noData(String.format(
                    Locale.US,
                    "Мало точек: n=%d frac=%.3f",
                    validity.validCount,
                    validity.validFraction
            ));
        }

        LargeObstacleResult large = detectLargeObstacle(depth, width, height);
        FloorClusterResult floor = detectNearFloorCluster(depth, width, height);
        DownStepProfileMode.Result down = downStepProfileMode.process(frame);

        Hazard finalHazard = null;
        float finalRisk = 0.10f;

        boolean dropSuspicious = down.risk > 0.42f;

        if (down.detected || dropSuspicious) {
            finalHazard = new Hazard(
                    HazardType.DROPOFF,
                    down.distanceMeters > 0 ? down.distanceMeters : 1.2f,
                    0f,
                    down.risk,
                    validity.validFractionClamped,
                    "Перепад вниз"
            );
            finalRisk = down.risk;

        } else if (floor.detected || large.detected) {
            float distance = positiveMin(floor.distanceMeters, large.distanceMeters, 1.3f);
            float risk = Math.max(floor.risk, large.risk);

            finalHazard = new Hazard(
                    HazardType.OBSTACLE,
                    distance,
                    0f,
                    risk,
                    validity.validFractionClamped,
                    "Впереди препятствие"
            );
            finalRisk = risk;
        }

        List<Hazard> hazards = new ArrayList<>();
        if (finalHazard != null) {
            hazards.add(finalHazard);
        }

        String decision = finalHazard != null ? finalHazard.getMessage() : "Свободно";

        String debug = String.format(
                Locale.US,
                "%s | valid=%d frac=%.2f largeOcc=%.2f floorCells=%d %s",
                decision,
                validity.validCount,
                validity.validFraction,
                large.occupancy,
                floor.activeCells,
                down.debug
        );

        return new DetectionResult(
                hazards,
                finalRisk,
                finalRisk,
                finalRisk,
                validity.validFractionClamped,
                debug,
                DetectionResult.DebugZone.NONE
        );
    }

    private DetectionResult noData(String debug) {
        return new DetectionResult(
                new ArrayList<Hazard>(),
                0.10f,
                0.10f,
                0.10f,
                0.10f,
                "Мало надежных данных | " + debug,
                DetectionResult.DebugZone.NONE
        );
    }

    private ValidityStats computeValidity(short[] depth, int width, int height) {
        int x0 = (int) (width * 0.12f);
        int x1 = (int) (width * 0.88f);
        int y0 = (int) (height * 0.25f);
        int y1 = (int) (height * 0.96f);

        int total = Math.max(1, (x1 - x0) * (y1 - y0));
        int valid = 0;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int d = depth[y * width + x] & 0xFFFF;
                if (d > 0) valid++;
            }
        }

        float frac = valid / (float) total;
        return new ValidityStats(valid, frac, Math.max(0.1f, Math.min(1f, frac * 3f)));
    }

    private LargeObstacleResult detectLargeObstacle(short[] depth, int width, int height) {
        int x0 = (int) (width * 0.18f);
        int x1 = (int) (width * 0.82f);
        int y0 = (int) (height * 0.28f);
        int y1 = (int) (height * 0.88f);

        int total = Math.max(1, (x1 - x0) * (y1 - y0));
        int nearCount = 0;
        long sumNear = 0;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int d = depth[y * width + x] & 0xFFFF;
                if (d > 0 && d < OBSTACLE_NEAR_MM) {
                    nearCount++;
                    sumNear += d;
                }
            }
        }

        float occupancy = nearCount / (float) total;
        boolean detected = occupancy > LARGE_OCCUPANCY_THRESHOLD;

        float dist = nearCount > 0 ? (sumNear / (float) nearCount) / 1000f : -1f;
        float risk = detected
                ? Math.min(1f, 0.52f + (occupancy - LARGE_OCCUPANCY_THRESHOLD) * 1.8f)
                : 0.10f;

        return new LargeObstacleResult(detected, occupancy, dist, risk);
    }

    private FloorClusterResult detectNearFloorCluster(short[] depth, int width, int height) {
        int x0 = (int) (width * 0.20f);
        int x1 = (int) (width * 0.80f);
        int y0 = (int) (height * 0.68f);
        int y1 = (int) (height * 0.96f);

        int roiW = Math.max(1, x1 - x0);
        int roiH = Math.max(1, y1 - y0);

        int activeCells = 0;
        long sumDepth = 0;
        int sumCount = 0;

        for (int gy = 0; gy < FLOOR_GRID_ROWS; gy++) {
            int cy0 = y0 + gy * roiH / FLOOR_GRID_ROWS;
            int cy1 = y0 + (gy + 1) * roiH / FLOOR_GRID_ROWS;

            for (int gx = 0; gx < FLOOR_GRID_COLS; gx++) {
                int cx0 = x0 + gx * roiW / FLOOR_GRID_COLS;
                int cx1 = x0 + (gx + 1) * roiW / FLOOR_GRID_COLS;

                int total = Math.max(1, (cx1 - cx0) * (cy1 - cy0));
                int closeCount = 0;
                long localSum = 0;

                for (int y = cy0; y < cy1; y++) {
                    for (int x = cx0; x < cx1; x++) {
                        int d = depth[y * width + x] & 0xFFFF;
                        if (d > 0 && d < FLOOR_OBSTACLE_NEAR_MM) {
                            closeCount++;
                            localSum += d;
                        }
                    }
                }

                float occ = closeCount / (float) total;
                if (occ > FLOOR_CLUSTER_CELL_THRESHOLD) {
                    activeCells++;
                    sumDepth += localSum;
                    sumCount += closeCount;
                }
            }
        }

        boolean detected = activeCells >= FLOOR_MIN_ACTIVE_CELLS;
        float dist = sumCount > 0 ? (sumDepth / (float) sumCount) / 1000f : -1f;
        float risk = detected
                ? Math.min(1f, 0.56f + (activeCells - FLOOR_MIN_ACTIVE_CELLS) * 0.08f)
                : 0.10f;

        return new FloorClusterResult(detected, activeCells, dist, risk);
    }

    private float positiveMin(float a, float b, float fallback) {
        if (a > 0f && b > 0f) return Math.min(a, b);
        if (a > 0f) return a;
        if (b > 0f) return b;
        return fallback;
    }

    private static class ValidityStats {
        final int validCount;
        final float validFraction;
        final float validFractionClamped;

        ValidityStats(int validCount, float validFraction, float validFractionClamped) {
            this.validCount = validCount;
            this.validFraction = validFraction;
            this.validFractionClamped = validFractionClamped;
        }
    }

    private static class LargeObstacleResult {
        final boolean detected;
        final float occupancy;
        final float distanceMeters;
        final float risk;

        LargeObstacleResult(boolean detected, float occupancy, float distanceMeters, float risk) {
            this.detected = detected;
            this.occupancy = occupancy;
            this.distanceMeters = distanceMeters;
            this.risk = risk;
        }
    }

    private static class FloorClusterResult {
        final boolean detected;
        final int activeCells;
        final float distanceMeters;
        final float risk;

        FloorClusterResult(boolean detected, int activeCells, float distanceMeters, float risk) {
            this.detected = detected;
            this.activeCells = activeCells;
            this.distanceMeters = distanceMeters;
            this.risk = risk;
        }
    }
}