package com.example.depthdebug;

import java.util.Locale;

public class WorldSurfaceMode {

    private final LocalElevationMap map =
            new LocalElevationMap(-1.0f, 1.0f, 0.3f, 3.0f, 0.10f);

    public static class WorldSurfaceResult {
        public final boolean dropDetected;
        public final float dropRisk;
        public final float dropDistanceMeters;
        public final String debug;

        public WorldSurfaceResult(boolean dropDetected, float dropRisk, float dropDistanceMeters, String debug) {
            this.dropDetected = dropDetected;
            this.dropRisk = dropRisk;
            this.dropDistanceMeters = dropDistanceMeters;
            this.debug = debug;
        }
    }

    public WorldSurfaceResult process(DetectionFrame frame) {
        if (frame == null || !frame.hasDepth() || !frame.hasCameraModel()) {
            return new WorldSurfaceResult(false, 0.10f, -1f, "world:no_data");
        }

        map.clearOlderThan(frame.getTimestampMs() - 900L);
        ingestDepth(frame);

        float h0 = map.estimateGroundHeight(-0.30f, 0.30f, 0.40f, 0.90f);
        if (Float.isNaN(h0)) {
            return new WorldSurfaceResult(false, 0.10f, -1f, "world:no_ground");
        }

        float[] heights = map.buildCenterProfile(0.30f, 0.80f, 2.50f);
        float[] coverages = map.buildCoverageProfile(0.30f, 0.80f, 2.50f);
        smooth(heights);
        smooth(coverages);

        float bestRisk = 0f;
        float bestDistance = -1f;
        int descendingBins = 0;

        for (int i = 0; i < heights.length; i++) {
            float h = heights[i];
            if (Float.isNaN(h)) continue;

            float deltaDown = h0 - h; // positive if floor is lower ahead
            float coverage = coverages[i];

            if (deltaDown > 0.15f) {
                float risk = 0.48f
                        + Math.min(0.25f, (deltaDown - 0.15f) * 1.4f)
                        + Math.min(0.15f, (1f - coverage) * 0.5f);
                bestRisk = Math.max(bestRisk, Math.min(1f, risk));
                if (bestDistance < 0f) {
                    bestDistance = 0.80f + i * 0.10f;
                }
            }

            if (deltaDown > 0.07f) {
                descendingBins++;
            }
        }

        if (bestRisk < 0.55f && descendingBins >= 3) {
            float avgCoverage = average(coverages);
            float risk = 0.46f + Math.min(0.18f, descendingBins * 0.05f) + Math.min(0.12f, (1f - avgCoverage) * 0.3f);
            bestRisk = Math.max(bestRisk, Math.min(1f, risk));
            if (bestDistance < 0f) {
                bestDistance = 1.1f;
            }
        }

        boolean detected = bestRisk > 0.56f;

        String debug = String.format(
                Locale.US,
                "world h0=%.2f risk=%.2f desc=%d cov=%.2f",
                h0, bestRisk, descendingBins, average(coverages)
        );

        return new WorldSurfaceResult(detected, detected ? bestRisk : 0.10f, bestDistance, debug);
    }

    private void ingestDepth(DetectionFrame frame) {
        short[] depth = frame.getDepthMillimeters();
        int width = frame.getDepthWidth();
        int height = frame.getDepthHeight();

        for (int v = 0; v < height; v += 4) {
            for (int u = 0; u < width; u += 4) {
                int d = depth[v * width + u] & 0xFFFF;
                if (d <= 0 || d > 5000) continue;

                PointProjector.WorldPoint wp = PointProjector.depthPixelToWorld(
                        u, v, d,
                        frame.getFx(), frame.getFy(), frame.getCx(), frame.getCy(),
                        frame.getCameraPose()
                );
                if (wp == null) continue;

                float[] local = PointProjector.worldToCameraLocal(frame.getCameraPose(), wp);
                if (local == null) continue;

                float localX = local[0];
                float localY = local[1];
                float localZ = local[2];

                if (localZ < 0.3f || localZ > 3.0f) continue;
                if (Math.abs(localX) > 1.2f) continue;

                map.addPoint(localX, localY, localZ, frame.getTimestampMs());
            }
        }
    }

    private void smooth(float[] arr) {
        float[] copy = arr.clone();
        for (int i = 0; i < arr.length; i++) {
            float sum = 0f;
            int count = 0;
            for (int k = i - 1; k <= i + 1; k++) {
                if (k < 0 || k >= copy.length) continue;
                if (Float.isNaN(copy[k])) continue;
                sum += copy[k];
                count++;
            }
            if (count > 0) arr[i] = sum / count;
        }
    }

    private float average(float[] arr) {
        float sum = 0f;
        int count = 0;
        for (float v : arr) {
            if (!Float.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        return count > 0 ? sum / count : 0f;
    }
}