package com.example.depthdebug;

public class RawDepthData {

    private final short[] depthMillimeters;
    private final byte[] confidence;
    private final int width;
    private final int height;

    public RawDepthData(short[] depthMillimeters, byte[] confidence, int width, int height) {
        this.depthMillimeters = depthMillimeters;
        this.confidence = confidence;
        this.width = width;
        this.height = height;
    }

    public short[] getDepthMillimeters() {
        return depthMillimeters;
    }

    public byte[] getConfidence() {
        return confidence;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}