package com.example.depthdebug;

import com.google.ar.core.Pose;

public class DetectionFrame {

    private final long timestampMs;
    private final short[] depthMillimeters;
    private final int depthWidth;
    private final int depthHeight;

    private final Pose cameraPose;
    private final float fx;
    private final float fy;
    private final float cx;
    private final float cy;

    public DetectionFrame(
            long timestampMs,
            short[] depthMillimeters,
            int depthWidth,
            int depthHeight,
            Pose cameraPose,
            float fx,
            float fy,
            float cx,
            float cy
    ) {
        this.timestampMs = timestampMs;
        this.depthMillimeters = depthMillimeters;
        this.depthWidth = depthWidth;
        this.depthHeight = depthHeight;
        this.cameraPose = cameraPose;
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public short[] getDepthMillimeters() {
        return depthMillimeters;
    }

    public int getDepthWidth() {
        return depthWidth;
    }

    public int getDepthHeight() {
        return depthHeight;
    }

    public Pose getCameraPose() {
        return cameraPose;
    }

    public float getFx() {
        return fx;
    }

    public float getFy() {
        return fy;
    }

    public float getCx() {
        return cx;
    }

    public float getCy() {
        return cy;
    }

    public boolean hasDepth() {
        return depthMillimeters != null
                && depthWidth > 0
                && depthHeight > 0;
    }

    public boolean hasCameraModel() {
        return cameraPose != null && fx > 0f && fy > 0f;
    }
}