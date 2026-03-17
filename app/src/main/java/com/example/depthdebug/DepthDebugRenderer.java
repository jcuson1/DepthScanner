package com.example.depthdebug;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class DepthDebugRenderer implements GLSurfaceView.Renderer {

    private final Activity activity;
    private final DetectionOverlayView overlayView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final DenseDepthProvider denseDepthProvider = new DenseDepthProvider();
    private final DetectionStabilizer stabilizer = new DetectionStabilizer();

    private Session session;
    private DetectionMode detectionMode;

    public DepthDebugRenderer(Activity activity, DetectionOverlayView overlayView) {
        this.activity = activity;
        this.overlayView = overlayView;
    }

    public void setSession(Session session) {
        this.session = session;
        stabilizer.reset();
    }

    public void setDetectionMode(DetectionMode detectionMode) {
        this.detectionMode = detectionMode;
        stabilizer.reset();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        try {
            backgroundRenderer.createOnGlThread(activity);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create background renderer", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        MainActivity mainActivity = (MainActivity) activity;
        mainActivity.getDisplayRotationHelper().onSurfaceChanged(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }

        MainActivity mainActivity = (MainActivity) activity;
        mainActivity.getDisplayRotationHelper().updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            Frame frame = session.update();
            Camera camera = frame.getCamera();

            backgroundRenderer.draw(frame);

            DetectionResult rawResult;

            if (camera.getTrackingState() != TrackingState.TRACKING) {
                rawResult = DetectionResult.empty("Камера не отслеживается");
            } else {
                DenseDepthData denseDepthData = denseDepthProvider.tryAcquire(frame);

                DetectionFrame detectionFrame;
                if (denseDepthData != null) {
                    float[] focal = camera.getTextureIntrinsics().getFocalLength();
                    float[] principal = camera.getTextureIntrinsics().getPrincipalPoint();

                    detectionFrame = new DetectionFrame(
                            System.currentTimeMillis(),
                            denseDepthData.getDepthMillimeters(),
                            denseDepthData.getWidth(),
                            denseDepthData.getHeight(),
                            camera.getPose(),
                            focal[0],
                            focal[1],
                            principal[0],
                            principal[1]
                    );
                } else {
                    detectionFrame = new DetectionFrame(
                            System.currentTimeMillis(),
                            null,
                            0,
                            0,
                            camera.getPose(),
                            0f,
                            0f,
                            0f,
                            0f
                    );
                }

                if (detectionMode != null) {
                    rawResult = detectionMode.process(detectionFrame);
                } else {
                    rawResult = DetectionResult.empty("Нет режима");
                }
            }

            DetectionResult stabilized = stabilizer.stabilize(rawResult);

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlayView.setDetectionResult(stabilized);
                }
            });

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}