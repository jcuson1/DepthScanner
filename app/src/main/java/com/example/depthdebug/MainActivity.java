package com.example.depthdebug;

import android.Manifest;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final int REQ_CAMERA = 1001;
    private static final String TAG = "Passage";

    private GLSurfaceView glSurface;
    private TextView metricsText;

    private Session session;
    private BackgroundRenderer backgroundRenderer;

    private OrientationHelper orientationHelper;
    private PassageDetector passageDetector;
    private AlertOutput alertOutput;

    private long lastLogMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurface = findViewById(R.id.glSurface);
        metricsText = findViewById(R.id.metricsText);

        glSurface.setEGLContextClientVersion(2);
        glSurface.setPreserveEGLContextOnPause(true);
        glSurface.setRenderer(this);
        glSurface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        backgroundRenderer = new BackgroundRenderer();
        orientationHelper = new OrientationHelper(this);
        passageDetector = new PassageDetector();
        alertOutput = new AlertOutput(this);

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
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
        if (session != null) {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
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
            OrientationHelper.Orientation orientation = orientationHelper.getOrientation();
            PassageDetector.Result result = passageDetector.update(frame, orientation);

            if (alertOutput != null) alertOutput.emit(result);

            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastLogMs > 250) {
                Log.i(TAG, "state=" + result.state
                        + " severity=" + result.severity
                        + " q=" + fmt(result.quality)
                        + " d=" + fmt(result.distanceM)
                        + " reason=" + result.reason
                        + " pitch=" + fmt(orientation.pitchDeg)
                        + " roll=" + fmt(orientation.rollDeg));
                lastLogMs = now;
            }

            String uiText = String.format(
                    Locale.US,
                    "%s\nreason: %s\ndistance: %s m\ndepth: %.2f\npitch %.1f°, roll %.1f°",
                    stateLabel(result),
                    result.reason,
                    Float.isInfinite(result.distanceM) ? "—" : fmt(result.distanceM),
                    result.quality,
                    orientation.pitchDeg,
                    orientation.rollDeg
            );
            runOnUiThread(() -> metricsText.setText(uiText));

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            session.setDisplayGeometry(rotation, glSurface.getWidth(), glSurface.getHeight());
            backgroundRenderer.draw(frame);
        } catch (Exception e) {
            Log.e(TAG, "Frame processing failed", e);
            runOnUiThread(() -> metricsText.setText("frame error: " + e.getClass().getSimpleName()));
        }
    }

    private static String stateLabel(PassageDetector.Result result) {
        switch (result.state) {
            case FREE:
                return "FREE PASSAGE";
            case BLOCKED:
                return result.severity == PassageDetector.Severity.CRITICAL
                        ? "STOP — OBSTACLE VERY CLOSE"
                        : "BLOCKED AHEAD";
            case UNKNOWN:
            default:
                return "UNKNOWN — REPOSITION PHONE";
        }
    }

    private static String fmt(float value) {
        if (Float.isInfinite(value)) return "inf";
        return String.format(Locale.US, "%.2f", value);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
