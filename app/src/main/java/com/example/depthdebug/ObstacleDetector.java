package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ObstacleDetector {

    public enum Zone { LEFT, CENTER, RIGHT, NONE }
    public static class Alert {
        public final boolean active;
        public final Zone zone;
        public final float distanceM;   // p10 distance (meters)
        public final float quality;
        public final float unknown;
        public final boolean depthReady;


        public Alert(boolean active, Zone zone, float distanceM, float quality, float unknown, boolean depthReady) {
            this.active = active;
            this.zone = zone;
            this.distanceM = distanceM;
            this.quality = quality;
            this.unknown = unknown;
            this.depthReady = depthReady;
        }
    }

    // === Настройки под “предупреждать за ~2м” ===
    private final float warnOnM  = 2.0f;
    private final float warnOffM = 2.3f;

    // История ~0.5с при ~30fps
    private final int historySize = 15;

    // Требования для включения/выключения
    private final int confirmOn  = 3;   // >=3 кадров опасно
    private final int confirmOff = 10;  // >=10 кадров безопасно

    private final float roiY0 = 0.45f, roiY1 = 0.92f;
    private final float roiX0 = 0.18f, roiX1 = 0.82f;

    private final int stride = 4;

    // Перцентиль для устойчивой дистанции
    private final float percentile = 0.10f; // p10

    // Ограничение диапазона depth
    private final int minMm = 200;   // 0.2м
    private final int maxMm = 4000;  // 4.0м

    // Доп. фильтрация по raw confidence (если совпадает размер)
    // 0..255 чаще всего, начальный мягкий порог:
    private final int rawConfMin = 40;

    private final float[] histL = new float[historySize];
    private final float[] histC = new float[historySize];
    private final float[] histR = new float[historySize];
    private final float[] histQuality = new float[historySize];
    private final float[] histUnknown = new float[historySize];
    private int histIdx = 0;
    private boolean histFilled = false;

    private boolean alertActive = false;
    private Zone lastZone = Zone.NONE;
    private float lastDist = Float.POSITIVE_INFINITY;

    public Alert update(Frame frame) {
        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            return new Alert(alertActive, lastZone, lastDist, 0f, 1f, false);
        }

        Image depth = null;
        Image rawConf = null;

        try {
            depth = frame.acquireDepthImage16Bits();

            // raw depth confidence есть у тебя — используем только как optional mask, если совпадает размер.
            try {
                rawConf = frame.acquireRawDepthConfidenceImage();
            } catch (Throwable ignore) {
                rawConf = null;
            }

            ZoneResult zr = computeZones(depth, rawConf);
            pushHistory(zr.leftM, zr.centerM, zr.rightM, zr.quality, zr.unknown);

            Decision d = decide();

            alertActive = d.active;
            lastZone = d.zone;
            lastDist = d.distM;

            return new Alert(alertActive, lastZone, lastDist, zr.quality, zr.unknown, true);

        } catch (NotYetAvailableException e) {
            // depth не готов — не сбрасываем тревогу
            return new Alert(alertActive, lastZone, lastDist, 0f, 1f, false);
        } finally {
            if (depth != null) depth.close();
            if (rawConf != null) rawConf.close();
        }
    }

    private static class ZoneResult {
        float leftM, centerM, rightM;
        float quality;  // valid/total
        float unknown;  // 1 - quality
    }

    private ZoneResult computeZones(Image depth, Image rawConf) {
        int w = depth.getWidth();
        int h = depth.getHeight();

        Image.Plane dp = depth.getPlanes()[0];
        ByteBuffer db = dp.getBuffer();
        int dRow = dp.getRowStride();
        int dPix = dp.getPixelStride();

        // raw conf optional
        ByteBuffer cb = null;
        int cRow = 0, cPix = 0;
        boolean useRawConf = false;
        if (rawConf != null && rawConf.getWidth() == w && rawConf.getHeight() == h) {
            Image.Plane cp = rawConf.getPlanes()[0];
            cb = cp.getBuffer();
            cRow = cp.getRowStride();
            cPix = cp.getPixelStride();
            useRawConf = true;
        }

        int x0 = (int)(roiX0 * w);
        int x1 = (int)(roiX1 * w);
        int y0 = (int)(roiY0 * h);
        int y1 = (int)(roiY1 * h);

        int width = Math.max(1, x1 - x0);
        int third = width / 3;
        int left0 = x0, left1 = x0 + third;
        int center0 = left1, center1 = x0 + 2 * third;
        int right0 = center1, right1 = x1;

        int maxSamples = ((x1 - x0) / stride + 1) * ((y1 - y0) / stride + 1);
        int[] left = new int[maxSamples];
        int[] center = new int[maxSamples];
        int[] right = new int[maxSamples];
        int nl = 0, nc = 0, nr = 0;

        int total = 0;
        int valid = 0;

        for (int y = y0; y < y1; y += stride) {
            int dRowStart = y * dRow;
            int cRowStart = y * cRow;

            for (int x = x0; x < x1; x += stride) {
                total++;

                // depth mm (little endian)
                int dOff = dRowStart + x * dPix;
                int lo = db.get(dOff) & 0xFF;
                int hi = db.get(dOff + 1) & 0xFF;
                int dmm = (hi << 8) | lo;

                if (dmm == 0 || dmm < minMm || dmm > maxMm) continue;

                if (useRawConf) {
                    int c = cb.get(cRowStart + x * cPix) & 0xFF;
                    if (c < rawConfMin) continue;
                }

                valid++;

                if (x < left1) left[nl++] = dmm;
                else if (x < center1) center[nc++] = dmm;
                else right[nr++] = dmm;
            }
        }

        ZoneResult zr = new ZoneResult();
        zr.leftM = percentileMeters(left, nl, percentile);
        zr.centerM = percentileMeters(center, nc, percentile);
        zr.rightM = percentileMeters(right, nr, percentile);
        zr.quality = (total == 0) ? 0f : (valid / (float) total);
        zr.unknown = 1f - zr.quality;
        return zr;
    }

    private float percentileMeters(int[] arr, int n, float p) {
        if (n <= 0) return Float.POSITIVE_INFINITY;
        Arrays.sort(arr, 0, n);
        int idx = (int)Math.floor(p * (n - 1));
        int mm = arr[Math.max(0, Math.min(n - 1, idx))];
        return mm / 1000f;
    }

    private void pushHistory(float l, float c, float r, float q, float u) {
        histL[histIdx] = l;
        histC[histIdx] = c;
        histR[histIdx] = r;
        histQuality[histIdx] = q;
        histUnknown[histIdx] = u;

        histIdx++;
        if (histIdx >= historySize) {
            histIdx = 0;
            histFilled = true;
        }
    }

    private static class Decision {
        boolean active;
        Zone zone;
        float distM;
    }

    private Decision decide() {
        int N = histFilled ? historySize : histIdx;

        Decision out = new Decision();
        out.active = alertActive;
        out.zone = lastZone;
        out.distM = lastDist;

        if (N < 3) return out;

        int onL = 0, onC = 0, onR = 0;
        int offL = 0, offC = 0, offR = 0;

        float bestMin = Float.POSITIVE_INFINITY;
        Zone bestZone = Zone.NONE;

        for (int i = 0; i < N; i++) {
            float l = histL[i], c = histC[i], r = histR[i];

            if (l < warnOnM) onL++;
            if (c < warnOnM) onC++;
            if (r < warnOnM) onR++;

            if (l > warnOffM) offL++;
            if (c > warnOffM) offC++;
            if (r > warnOffM) offR++;

            if (l < bestMin) { bestMin = l; bestZone = Zone.LEFT; }
            if (c < bestMin) { bestMin = c; bestZone = Zone.CENTER; }
            if (r < bestMin) { bestMin = r; bestZone = Zone.RIGHT; }
        }

        boolean anyOn = (onL >= confirmOn) || (onC >= confirmOn) || (onR >= confirmOn);
        boolean allOff = (offL >= confirmOff) && (offC >= confirmOff) && (offR >= confirmOff);

        if (!alertActive) {
            if (anyOn) {
                out.active = true;
                out.zone = bestZone;
                out.distM = bestMin;
            }
        } else {
            // Если данных мало (часто бывает в движении/плохом свете) — не сбрасываем тревогу резко.
            float meanQuality = 0f;
            for (int i = 0; i < N; i++) meanQuality += histQuality[i];
            meanQuality /= N;

            if (allOff && meanQuality > 0.15f) { // условие “данные достаточно хорошие”
                out.active = false;
                out.zone = Zone.NONE;
                out.distM = Float.POSITIVE_INFINITY;
            } else {
                out.active = true;
                out.zone = bestZone;
                out.distM = bestMin;
            }
        }

        return out;
    }
}