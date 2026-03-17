package com.example.depthdebug;

import java.util.Arrays;

public class LocalElevationMap {

    public static class Cell {
        public float minY = Float.POSITIVE_INFINITY;
        public float maxY = Float.NEGATIVE_INFINITY;
        public int count = 0;
        public long lastTimestampMs = 0L;

        public void reset() {
            minY = Float.POSITIVE_INFINITY;
            maxY = Float.NEGATIVE_INFINITY;
            count = 0;
            lastTimestampMs = 0L;
        }

        public boolean isValid() {
            return count > 0;
        }

        public float representativeY() {
            if (!isValid()) return Float.NaN;
            return minY;
        }
    }

    private final float minX;
    private final float maxX;
    private final float minZ;
    private final float maxZ;
    private final float cellSize;

    private final int cols;
    private final int rows;
    private final Cell[][] cells;

    public LocalElevationMap(float minX, float maxX, float minZ, float maxZ, float cellSize) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.cellSize = cellSize;

        this.cols = Math.max(1, (int) Math.ceil((maxX - minX) / cellSize));
        this.rows = Math.max(1, (int) Math.ceil((maxZ - minZ) / cellSize));

        cells = new Cell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = new Cell();
            }
        }
    }

    public void clearOlderThan(long minTimestampMs) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c].lastTimestampMs < minTimestampMs) {
                    cells[r][c].reset();
                }
            }
        }
    }

    public void addPoint(float localX, float localY, float localZ, long timestampMs) {
        if (localX < minX || localX >= maxX || localZ < minZ || localZ >= maxZ) return;

        int col = (int) ((localX - minX) / cellSize);
        int row = (int) ((localZ - minZ) / cellSize);

        if (row < 0 || row >= rows || col < 0 || col >= cols) return;

        Cell cell = cells[row][col];
        cell.minY = Math.min(cell.minY, localY);
        cell.maxY = Math.max(cell.maxY, localY);
        cell.count += 1;
        cell.lastTimestampMs = timestampMs;
    }

    public float estimateGroundHeight(float x0, float x1, float z0, float z1) {
        float[] values = collectRepresentativeHeights(x0, x1, z0, z1);
        if (values.length == 0) return Float.NaN;
        Arrays.sort(values);
        return values[values.length / 2];
    }

    public float[] buildCenterProfile(float xHalfWidth, float zStart, float zEnd) {
        int bins = Math.max(1, (int) Math.ceil((zEnd - zStart) / cellSize));
        float[] profile = new float[bins];
        Arrays.fill(profile, Float.NaN);

        for (int i = 0; i < bins; i++) {
            float a = zStart + i * cellSize;
            float b = a + cellSize;
            profile[i] = estimateGroundHeight(-xHalfWidth, xHalfWidth, a, b);
        }
        return profile;
    }

    public float[] buildCoverageProfile(float xHalfWidth, float zStart, float zEnd) {
        int bins = Math.max(1, (int) Math.ceil((zEnd - zStart) / cellSize));
        float[] profile = new float[bins];
        Arrays.fill(profile, 0f);

        for (int i = 0; i < bins; i++) {
            float a = zStart + i * cellSize;
            float b = a + cellSize;
            profile[i] = coverage(-xHalfWidth, xHalfWidth, a, b);
        }
        return profile;
    }

    public float coverage(float x0, float x1, float z0, float z1) {
        int total = 0;
        int valid = 0;

        for (int r = 0; r < rows; r++) {
            float zCenter = minZ + (r + 0.5f) * cellSize;
            if (zCenter < z0 || zCenter >= z1) continue;

            for (int c = 0; c < cols; c++) {
                float xCenter = minX + (c + 0.5f) * cellSize;
                if (xCenter < x0 || xCenter >= x1) continue;

                total++;
                if (cells[r][c].isValid()) valid++;
            }
        }

        return total > 0 ? valid / (float) total : 0f;
    }

    private float[] collectRepresentativeHeights(float x0, float x1, float z0, float z1) {
        float[] tmp = new float[rows * cols];
        int n = 0;

        for (int r = 0; r < rows; r++) {
            float zCenter = minZ + (r + 0.5f) * cellSize;
            if (zCenter < z0 || zCenter >= z1) continue;

            for (int c = 0; c < cols; c++) {
                float xCenter = minX + (c + 0.5f) * cellSize;
                if (xCenter < x0 || xCenter >= x1) continue;

                Cell cell = cells[r][c];
                if (!cell.isValid()) continue;

                tmp[n++] = cell.representativeY();
            }
        }

        return Arrays.copyOf(tmp, n);
    }
}