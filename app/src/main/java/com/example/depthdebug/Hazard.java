package com.example.depthdebug;

public class Hazard {
    private final HazardType type;
    private final float distanceMeters;
    private final float lateralOffsetMeters;
    private final float riskScore;
    private final float confidenceScore;
    private final String message;

    public Hazard(
            HazardType type,
            float distanceMeters,
            float lateralOffsetMeters,
            float riskScore,
            float confidenceScore,
            String message
    ) {
        this.type = type;
        this.distanceMeters = distanceMeters;
        this.lateralOffsetMeters = lateralOffsetMeters;
        this.riskScore = riskScore;
        this.confidenceScore = confidenceScore;
        this.message = message;
    }

    public HazardType getType() {
        return type;
    }

    public float getDistanceMeters() {
        return distanceMeters;
    }

    public float getLateralOffsetMeters() {
        return lateralOffsetMeters;
    }

    public float getRiskScore() {
        return riskScore;
    }

    public float getConfidenceScore() {
        return confidenceScore;
    }

    public String getMessage() {
        return message;
    }
}