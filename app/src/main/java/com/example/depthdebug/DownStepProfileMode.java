package com.example.depthdebug;

import com.google.ar.core.Pose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DownStepProfileMode {

    private static final float X_HALF_WIDTH = 0.45f;

    private static final float S_START = 0.25f;
    private static final float S_END = 2.80f;
    private static final float BIN_SIZE = 0.10f;

    private static final int PIXEL_STEP = 4;

    // Базовый пол берем очень близко перед пользователем
    private static final float BASELINE_S0 = 0.25f;
    private static final float BASELINE_S1 = 0.75f;

    private static final float STRONG_DROP_METERS = 0.10f;
    private static final float WEAK_DROP_METERS = 0.05f;

    private static final float LOW_COVERAGE_THRESHOLD = 0.35f;

    public static class Result {
        public final boolean detected;
        public final float risk;
        public final float distanceMeters;
        public final String debug;

        public Result(boolean detected, float risk, float distanceMeters, String debug) {
            this.detected = detected;
            this.risk = risk;
            this.distanceMeters = distanceMeters;
            this.debug = debug;
        }
    }

    private static class Bin {
        final float s0;
        final float s1;
        final List<Float> ys = new ArrayList<>();
        int count = 0;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        Bin(float s0, float s1) {
            this.s0 = s0;
            this.s1 = s1;
        }

        void add(float worldY) {
            ys.add(worldY);
            count++;
            minY = Math.min(minY, worldY);
            maxY = Math.max(maxY, worldY);
        }

        boolean valid() {
            return count >= 6 && ys.size() >= 6;
        }

        float spread() {
            if (!valid()) return Float.NaN;
            return maxY - minY;
        }

        float groundY() {
            if (!valid()) return Float.NaN;

            Collections.sort(ys);

            // нижняя четверть — аппроксимация опорной поверхности
            int take = Math.max(1, ys.size() / 4);
            float sum = 0f;
            for (int i = 0; i < take; i++) {
                sum += ys.get(i);
            }
            return sum / take;
        }

        float centerS() {
            return 0.5f * (s0 + s1);
        }
    }

    public Result process(DetectionFrame frame) {
        if (frame == null || !frame.hasDepth() || !frame.hasCameraModel()) {
            return new Result(false, 0.10f, -1f, "down:no_data");
        }

        List<Bin> bins = createBins();

        FrameAxes axes = buildHorizontalFrame(frame.getCameraPose());
        if (axes == null) {
            return new Result(false, 0.10f, -1f, "down:no_axes");
        }

        ingestDepth(frame, bins, axes);

        float baseline = estimateBaselineGround(bins);
        if (Float.isNaN(baseline)) {
            return new Result(false, 0.10f, -1f, "down:no_baseline");
        }

        float[] heights = new float[bins.size()];
        float[] cov = new float[bins.size()];

        for (int i = 0; i < bins.size(); i++) {
            heights[i] = bins.get(i).groundY();
            cov[i] = binCoverage(bins.get(i));
        }

        smooth(heights);
        smooth(cov);

        float bestRisk = 0f;
        float bestDistance = -1f;
        int descendingBins = 0;
        float cumulativeDrop = 0f;

        for (int i = 0; i < bins.size(); i++) {
            float h = heights[i];
            if (Float.isNaN(h)) continue;

            float deltaDown = baseline - h; // положительное => поверхность ниже впереди
            float coverage = cov[i];

            if (deltaDown > WEAK_DROP_METERS) {
                descendingBins++;
                cumulativeDrop += deltaDown;
            }

            if (deltaDown > STRONG_DROP_METERS) {
                float risk = 0.50f;
                risk += Math.min(0.22f, (deltaDown - STRONG_DROP_METERS) * 2.0f);
                risk += Math.min(0.14f, (1f - coverage) * 0.35f);

                if (coverage < LOW_COVERAGE_THRESHOLD) {
                    risk += 0.10f;
                }

                risk = Math.min(1f, risk);

                if (risk > bestRisk) {
                    bestRisk = risk;
                    bestDistance = bins.get(i).centerS();
                }
            }
        }

        // Если сильного провала нет, но есть последовательное снижение — тоже считаем лестницу вниз
        if (bestRisk < 0.50f && descendingBins >= 2) {
            float avgCov = averageFinite(cov);

            float risk = 0.44f;
            risk += Math.min(0.16f, descendingBins * 0.06f);
            risk += Math.min(0.14f, cumulativeDrop * 0.40f);
            risk += Math.min(0.10f, (1f - avgCov) * 0.25f);
            risk = Math.min(1f, risk);

            if (risk > bestRisk) {
                bestRisk = risk;
                bestDistance = firstDescendingDistance(bins, heights, baseline);
            }
        }

        boolean detected = bestRisk > 0.50f;

        String debug = String.format(
                Locale.US,
                "down base=%.2f risk=%.2f desc=%d cum=%.2f",
                baseline, bestRisk, descendingBins, cumulativeDrop
        );

        return new Result(
                detected,
                detected ? bestRisk : 0.10f,
                bestDistance,
                debug
        );
    }

    private List<Bin> createBins() {
        List<Bin> bins = new ArrayList<>();
        float s = S_START;
        while (s < S_END) {
            float next = Math.min(S_END, s + BIN_SIZE);
            bins.add(new Bin(s, next));
            s = next;
        }
        return bins;
    }

    private void ingestDepth(DetectionFrame frame, List<Bin> bins, FrameAxes axes) {
        short[] depth = frame.getDepthMillimeters();
        int width = frame.getDepthWidth();
        int height = frame.getDepthHeight();

        float[] camPos = frame.getCameraPose().getTranslation();

        for (int v = 0; v < height; v += PIXEL_STEP) {
            for (int u = 0; u < width; u += PIXEL_STEP) {
                int d = depth[v * width + u] & 0xFFFF;
                if (d <= 0 || d > 5000) continue;

                PointProjector.WorldPoint wp = PointProjector.depthPixelToWorld(
                        u, v, d,
                        frame.getFx(), frame.getFy(), frame.getCx(), frame.getCy(),
                        frame.getCameraPose()
                );
                if (wp == null) continue;

                float dx = wp.x - camPos[0];
                float dz = wp.z - camPos[2];

                float forwardS = dx * axes.forwardX + dz * axes.forwardZ;
                float lateral = dx * axes.lateralX + dz * axes.lateralZ;

                if (forwardS < S_START || forwardS >= S_END) continue;
                if (Math.abs(lateral) > X_HALF_WIDTH) continue;

                int idx = (int) ((forwardS - S_START) / BIN_SIZE);
                if (idx < 0 || idx >= bins.size()) continue;

                bins.get(idx).add(wp.y);
            }
        }
    }

    private float estimateBaselineGround(List<Bin> bins) {
        List<Float> vals = new ArrayList<>();

        for (Bin bin : bins) {
            float s = bin.centerS();
            if (s < BASELINE_S0 || s > BASELINE_S1) continue;
            if (!bin.valid()) continue;

            // для baseline берем только достаточно "ровные" бины
            float spread = bin.spread();
            if (!Float.isNaN(spread) && spread > 0.18f) {
                continue;
            }

            float y = bin.groundY();
            if (!Float.isNaN(y)) {
                vals.add(y);
            }
        }

        if (vals.size() < 2) {
            return Float.NaN;
        }

        Collections.sort(vals);
        return vals.get(vals.size() / 2);
    }

    private float binCoverage(Bin bin) {
        return Math.min(1f, bin.count / 100f);
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

            if (count > 0) {
                arr[i] = sum / count;
            }
        }
    }

    private float averageFinite(float[] arr) {
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

    private float firstDescendingDistance(List<Bin> bins, float[] heights, float baseline) {
        for (int i = 0; i < bins.size(); i++) {
            float h = heights[i];
            if (Float.isNaN(h)) continue;
            if ((baseline - h) > WEAK_DROP_METERS) {
                return bins.get(i).centerS();
            }
        }
        return 1.1f;
    }

    private FrameAxes buildHorizontalFrame(Pose pose) {
        if (pose == null) return null;

        float[] p0 = pose.getTranslation();
        float[] p1 = pose.transformPoint(new float[]{0f, 0f, 1f});

        float fx = p1[0] - p0[0];
        float fz = p1[2] - p0[2];

        float norm = (float) Math.sqrt(fx * fx + fz * fz);
        if (norm < 1e-4f) return null;

        fx /= norm;
        fz /= norm;

        // lateral = rotate 90 degrees in horizontal plane
        float lx = -fz;
        float lz = fx;

        return new FrameAxes(fx, fz, lx, lz);
    }

    private static class FrameAxes {
        final float forwardX;
        final float forwardZ;
        final float lateralX;
        final float lateralZ;

        FrameAxes(float forwardX, float forwardZ, float lateralX, float lateralZ) {
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.lateralX = lateralX;
            this.lateralZ = lateralZ;
        }
    }
}