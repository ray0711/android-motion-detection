package com.jjoe64.motiondetection;

public interface MotionDetectorCallback {
    void onMotionDetected();
    void onTooDark();
}
