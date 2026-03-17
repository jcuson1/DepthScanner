package com.example.depthdebug;

import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

public class MainActivity extends AppCompatActivity {

    private GLSurfaceView glSurfaceView;
    private DetectionOverlayView overlayView;

    private Session session;
    private boolean installRequested;

    private DisplayRotationHelper displayRotationHelper;
    private DepthDebugRenderer renderer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurfaceView = findViewById(R.id.glSurfaceView);
        overlayView = findViewById(R.id.detectionOverlay);

        displayRotationHelper = new DisplayRotationHelper(this);
        renderer = new DepthDebugRenderer(this, overlayView);

        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        renderer.setDetectionMode(new SimpleDecisionMode());

        installRequested = false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;

            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                session = new Session(this);

                Config config = new Config(session);
                config.setFocusMode(Config.FocusMode.AUTO);

                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                } else {
                    config.setDepthMode(Config.DepthMode.DISABLED);
                }

                session.configure(config);
                renderer.setSession(session);

            } catch (UnavailableArcoreNotInstalledException e) {
                message = "Установите ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Обновите ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Обновите приложение";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "Устройство не поддерживает AR";
                exception = e;
            } catch (UnavailableException e) {
                message = "Не удалось создать AR session";
                exception = e;
            } catch (Exception e) {
                message = "Ошибка запуска AR";
                exception = e;
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (exception != null) {
                    exception.printStackTrace();
                }
                return;
            }
        }

        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_LONG).show();
            session = null;
            return;
        }

        glSurfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (session != null) {
            displayRotationHelper.onPause();
            glSurfaceView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show();
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)) {
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    public DisplayRotationHelper getDisplayRotationHelper() {
        return displayRotationHelper;
    }
}