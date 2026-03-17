package com.example.depthdebug;

import com.google.ar.core.Pose;

public class PointProjector {

    public static class WorldPoint {
        public final float x;
        public final float y;
        public final float z;

        public WorldPoint(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static WorldPoint depthPixelToWorld(
            int u,
            int v,
            int depthMm,
            float fx,
            float fy,
            float cx,
            float cy,
            Pose cameraPose
    ) {
        if (depthMm <= 0 || fx <= 0f || fy <= 0f || cameraPose == null) {
            return null;
        }

        float z = depthMm / 1000.0f;
        float x = ((u - cx) / fx) * z;
        float y = ((v - cy) / fy) * z;

        float[] world = cameraPose.transformPoint(new float[]{x, y, z});
        return new WorldPoint(world[0], world[1], world[2]);
    }

    public static float[] worldToCameraLocal(Pose cameraPose, WorldPoint wp) {
        if (cameraPose == null || wp == null) {
            return null;
        }
        Pose inv = cameraPose.inverse();
        return inv.transformPoint(new float[]{wp.x, wp.y, wp.z});
    }
}