package com.example.depthdebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DetectionResult {

    public enum DebugZone {
        NONE,
        WALL,
        FLOOR,
        DROP
    }

    private final List<Hazard> hazards;
    private final float leftRisk;
    private final float centerRisk;
    private final float rightRisk;
    private final float overallConfidence;
    private final String debugText;
    private final DebugZone debugZone;

    public DetectionResult(
            List<Hazard> hazards,
            float leftRisk,
            float centerRisk,
            float rightRisk,
            float overallConfidence,
            String debugText
    ) {
        this(hazards, leftRisk, centerRisk, rightRisk, overallConfidence, debugText, DebugZone.NONE);
    }

    public DetectionResult(
            List<Hazard> hazards,
            float leftRisk,
            float centerRisk,
            float rightRisk,
            float overallConfidence,
            String debugText,
            DebugZone debugZone
    ) {
        this.hazards = hazards == null ? new ArrayList<>() : hazards;
        this.leftRisk = leftRisk;
        this.centerRisk = centerRisk;
        this.rightRisk = rightRisk;
        this.overallConfidence = overallConfidence;
        this.debugText = debugText;
        this.debugZone = debugZone == null ? DebugZone.NONE : debugZone;
    }

    public static DetectionResult empty(String debugText) {
        return new DetectionResult(
                new ArrayList<Hazard>(),
                0f,
                0f,
                0f,
                0f,
                debugText,
                DebugZone.NONE
        );
    }

    public List<Hazard> getHazards() {
        return Collections.unmodifiableList(hazards);
    }

    public float getLeftRisk() {
        return leftRisk;
    }

    public float getCenterRisk() {
        return centerRisk;
    }

    public float getRightRisk() {
        return rightRisk;
    }

    public float getOverallConfidence() {
        return overallConfidence;
    }

    public String getDebugText() {
        return debugText;
    }

    public DebugZone getDebugZone() {
        return debugZone;
    }

    public Hazard getPrimaryHazard() {
        if (hazards.isEmpty()) {
            return null;
        }

        Hazard best = hazards.get(0);
        for (Hazard h : hazards) {
            if (h.getRiskScore() > best.getRiskScore()) {
                best = h;
            }
        }
        return best;
    }
}