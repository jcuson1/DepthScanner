package com.example.depthdebug;

public class DenseDepthData {

    private final short[] depthMillimeters;
    private final int width;
    private final int height;

    public DenseDepthData(short[] depthMillimeters, int width, int height) {
        this.depthMillimeters = depthMillimeters;
        this.width = width;
        this.height = height;
    }

    public short[] getDepthMillimeters() {
        return depthMillimeters;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}