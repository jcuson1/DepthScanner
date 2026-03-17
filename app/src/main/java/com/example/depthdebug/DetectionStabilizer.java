package com.example.depthdebug;

import java.util.ArrayList;
import java.util.List;

public class DetectionStabilizer {

    private static final float RISK_ALPHA = 0.78f;
    private static final float CONF_ALPHA = 0.80f;

    private static final int HAZARD_HOLD_FRAMES = 10;
    private static final float SHOW_THRESHOLD = 0.52f;
    private static final float HIDE_THRESHOLD = 0.30f;

    private float smoothedLeftRisk = 0f;
    private float smoothedCenterRisk = 0f;
    private float smoothedRightRisk = 0f;
    private float smoothedConfidence = 0f;

    private Hazard latchedHazard = null;
    private int latchedHazardFramesLeft = 0;
    private boolean hazardVisible = false;

    public DetectionResult stabilize(DetectionResult input) {
        if (input == null) {
            return DetectionResult.empty("stabilizer:null");
        }

        smoothedLeftRisk = smooth(smoothedLeftRisk, input.getLeftRisk(), RISK_ALPHA);
        smoothedCenterRisk = smooth(smoothedCenterRisk, input.getCenterRisk(), RISK_ALPHA);
        smoothedRightRisk = smooth(smoothedRightRisk, input.getRightRisk(), RISK_ALPHA);
        smoothedConfidence = smooth(smoothedConfidence, input.getOverallConfidence(), CONF_ALPHA);

        Hazard currentPrimary = input.getPrimaryHazard();
        Hazard outputHazard = updateLatchedHazard(currentPrimary);

        float strongestRisk = Math.max(smoothedLeftRisk, Math.max(smoothedCenterRisk, smoothedRightRisk));

        if (!hazardVisible && strongestRisk >= SHOW_THRESHOLD) {
            hazardVisible = true;
        } else if (hazardVisible && strongestRisk <= HIDE_THRESHOLD) {
            hazardVisible = false;
        }

        List<Hazard> outputHazards = new ArrayList<>();
        if (hazardVisible && outputHazard != null) {
            outputHazards.add(adjustHazard(outputHazard, strongestRisk, smoothedConfidence));
        }

        String debug = input.getDebugText()
                + " | stab"
                + " lr=" + shortFmt(smoothedLeftRisk)
                + " cr=" + shortFmt(smoothedCenterRisk)
                + " rr=" + shortFmt(smoothedRightRisk)
                + " hv=" + (hazardVisible ? "1" : "0")
                + " hold=" + latchedHazardFramesLeft;

        return new DetectionResult(
                outputHazards,
                smoothedLeftRisk,
                smoothedCenterRisk,
                smoothedRightRisk,
                smoothedConfidence,
                debug
        );
    }

    public void reset() {
        smoothedLeftRisk = 0f;
        smoothedCenterRisk = 0f;
        smoothedRightRisk = 0f;
        smoothedConfidence = 0f;
        latchedHazard = null;
        latchedHazardFramesLeft = 0;
        hazardVisible = false;
    }

    private Hazard updateLatchedHazard(Hazard currentPrimary) {
        if (currentPrimary != null) {
            if (latchedHazard == null || shouldReplaceLatched(latchedHazard, currentPrimary)) {
                latchedHazard = currentPrimary;
            }
            latchedHazardFramesLeft = HAZARD_HOLD_FRAMES;
            return latchedHazard;
        }

        if (latchedHazard != null && latchedHazardFramesLeft > 0) {
            latchedHazardFramesLeft--;
            return latchedHazard;
        }

        latchedHazard = null;
        latchedHazardFramesLeft = 0;
        return null;
    }

    private boolean shouldReplaceLatched(Hazard oldHazard, Hazard newHazard) {
        if (newHazard.getRiskScore() > oldHazard.getRiskScore() + 0.12f) {
            return true;
        }

        if (newHazard.getType() != oldHazard.getType()
                && newHazard.getRiskScore() >= oldHazard.getRiskScore() - 0.05f) {
            return true;
        }

        return false;
    }

    private Hazard adjustHazard(Hazard base, float strongestRisk, float smoothedConfidence) {
        float risk = Math.max(base.getRiskScore(), strongestRisk);
        float confidence = Math.max(base.getConfidenceScore(), smoothedConfidence);

        return new Hazard(
                base.getType(),
                base.getDistanceMeters(),
                base.getLateralOffsetMeters(),
                clamp01(risk),
                clamp01(confidence),
                base.getMessage()
        );
    }

    private float smooth(float prev, float current, float alpha) {
        return alpha * prev + (1f - alpha) * current;
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private String shortFmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}