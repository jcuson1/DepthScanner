package com.example.depthdebug;

public interface DetectionMode {
    DetectionModeType getType();
    String getDisplayName();
    DetectionResult process(DetectionFrame frame);
}
