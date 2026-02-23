package com.example.depthdebug;

import android.Manifest;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final int REQ_CAMERA = 1001;

    private GLSurfaceView glSurface;
    private TextView metricsText;

    private Session session;

    private BackgroundRenderer backgroundRenderer;
    private DepthOverlayRenderer depthOverlayRenderer;

    private enum OverlayMode { DEPTH, UNKNOWN, OFF }
    private volatile OverlayMode overlayMode = OverlayMode.DEPTH;

    private OrientationHelper orientationHelper;
    private HazardDetector hazardDetector;
    private DebugOverlayView debugOverlay;

    private AlertOutput alertOutput;

    private static final String TAG = "Hazard";
    private long lastLogMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alertOutput = new AlertOutput(this);

        glSurface = findViewById(R.id.glSurface);
        metricsText = findViewById(R.id.metricsText);

        glSurface.setEGLContextClientVersion(2);
        glSurface.setPreserveEGLContextOnPause(true);
        glSurface.setRenderer(this);
        glSurface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        backgroundRenderer = new BackgroundRenderer();
        depthOverlayRenderer = new DepthOverlayRenderer();

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);

        orientationHelper = new OrientationHelper(this);
        hazardDetector = new HazardDetector();
        debugOverlay = findViewById(R.id.debugOverlay);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alertOutput != null) alertOutput.release();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationHelper != null) orientationHelper.start();
        if (session == null) {
            try {
                session = new Session(this);
                Config config = new Config(session);
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);
            } catch (UnavailableArcoreNotInstalledException
                     | UnavailableApkTooOldException
                     | UnavailableSdkTooOldException
                     | UnavailableDeviceNotCompatibleException e) {
                metricsText.setText("ARCore unavailable: " + e.getMessage());
                return;
            }
        }

        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            metricsText.setText("Camera not available: " + e.getMessage());
            return;
        }

        glSurface.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationHelper != null) orientationHelper.stop();
        glSurface.onPause();
        if (session != null) session.pause();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0, 0, 0, 1);

        backgroundRenderer.init();
        depthOverlayRenderer.init();

        if (session != null) {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        depthOverlayRenderer.onViewportChanged(width, height);

        if (session != null) {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            session.setDisplayGeometry(rotation, width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (session == null) return;

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        try {
            Frame frame = session.update();
            OrientationHelper.Orientation ori = orientationHelper.getOrientation();

            HazardDetector.Result r = hazardDetector.update(frame);
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastLogMs > 250) {
                android.util.Log.i(TAG,
                        "depthReady=" + r.depthReady +
                                " q=" + fmt(r.quality) +
                                " primary=" + (r.primaryAlert == null ? "none" :
                                (r.primaryAlert.kind + "/" + r.primaryAlert.direction + "/" + r.primaryAlert.severity +
                                        " d=" + fmt(r.primaryAlert.distanceM))) +
                                " hot=" + java.util.Arrays.toString(r.hotCells)
                );
                lastLogMs = now;
            }

            if (alertOutput != null) alertOutput.emit(r.primaryAlert);

            HazardDetector.Severity sev =
                    (r.primaryAlert == null) ? HazardDetector.Severity.FAR : r.primaryAlert.severity;
            debugOverlay.setHotCells(r.hotCells, sev);

            String text = String.format(java.util.Locale.US,
                    "Pitch: %.1f  Roll: %.1f\nDepth quality: %.2f\nAlert: %s",
                    ori.pitchDeg, ori.rollDeg,
                    r.quality,
                    (r.primaryAlert == null ? "none"
                            : (r.primaryAlert.kind + " " + r.primaryAlert.direction + " " + r.primaryAlert.severity)));

            runOnUiThread(() -> metricsText.setText(text));

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            session.setDisplayGeometry(rotation, glSurface.getWidth(), glSurface.getHeight());
            backgroundRenderer.draw(frame);

            if (overlayMode != OverlayMode.OFF) {
                DepthOverlayRenderer.Mode m =
                        (overlayMode == OverlayMode.DEPTH) ? DepthOverlayRenderer.Mode.DEPTH :
                                DepthOverlayRenderer.Mode.UNKNOWN;
                depthOverlayRenderer.updateFromFrame(frame, m);
                depthOverlayRenderer.draw();
            }

        } catch (Exception e) {
        }
    }

    private static String fmt(float v) {
        if (Float.isInfinite(v)) return "inf";
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}