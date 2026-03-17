package com.example.depthdebug;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowManager;

import com.google.ar.core.Session;

public class DisplayRotationHelper implements DisplayManager.DisplayListener {

    private final Context context;
    private final Display display;
    private boolean viewportChanged;
    private int viewportWidth;
    private int viewportHeight;

    public DisplayRotationHelper(Context context) {
        this.context = context;
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        display = windowManager.getDefaultDisplay();
    }

    public void onResume() {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, null);
    }

    public void onPause() {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.unregisterDisplayListener(this);
    }

    public void onSurfaceChanged(int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        viewportChanged = true;
    }

    public void updateSessionIfNeeded(Session session) {
        if (viewportChanged) {
            int displayRotation = display.getRotation();
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight);
            viewportChanged = false;
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        viewportChanged = true;
    }
}