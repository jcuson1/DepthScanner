/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.helloar;

import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.media.Image;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer.BackgroundVisualizationMode;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.helloar.conductor.TemporaryConductorModule;
import com.google.ar.core.examples.java.helloar.diagnostics.DiagnosticsLogger;
import com.google.ar.core.examples.java.helloar.depth20.Depth20OverlayView;
import com.google.ar.core.examples.java.helloar.depth20.Depth20PointModule;
import com.google.ar.core.examples.java.helloar.depth20.Depth20ScanResult;
import com.google.ar.core.examples.java.helloar.floormap.FloorHazardModule;
import com.google.ar.core.examples.java.helloar.floormap.FloorHeightmapRenderer;
import com.google.ar.core.examples.java.helloar.floormap.FloorPointsRenderer;
import com.google.ar.core.examples.java.helloar.octomap.OctomapInstancedRenderer;
import com.google.ar.core.examples.java.helloar.octomap.OctomapModule;
import com.google.ar.core.examples.java.helloar.settings.DepthVisualizationSettings;
import com.google.ar.core.examples.java.helloar.settings.DepthVisualizationSettings.DepthVisualizationMode;
import com.google.ar.core.examples.java.helloar.smartdepth.SmartDepthPointModule;
import com.google.ar.core.examples.java.helloar.speech.SpeechModule;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;

/**
 * Главная Activity приложения. Управляет ARCore-сессией, GL-рендером и связкой режимов
 * визуализации (просто камера / depth-карты / 20 точек / smart 23 / floor heightmap /
 * floor points / octomap+3DVFH+).
 *
 * <p>Что происходит:
 * <ul>
 *   <li>onCreate — инициализируем модули, диагностику, UI.</li>
 *   <li>onResume — поднимаем ARCore-сессию.</li>
 *   <li>onSurfaceCreated — создаём GL-ресурсы (фон камеры, рендереры режимов).</li>
 *   <li>onDrawFrame — главный цикл, ~30 FPS. Получаем кадр от ARCore, прогоняем через
 *       выбранный модуль анализа, передаём решение дирижёру речи, рисуем кадр.</li>
 *   <li>onPause/onDestroy — корректное освобождение всего.</li>
 * </ul>
 *
 * <p>Файл большой (~1000 строк) — это плата за то что вся логика связки режимов лежит в
 * одном месте. Каждый режим сам инкапсулирован в свой модуль (Depth20PointModule,
 * SmartDepthPointModule и т.д.), здесь только wiring.
 */
public class MainActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = MainActivity.class.getSimpleName();

  // ───── параметры режима DISTANCE_PROBES ─────
  // 8 крупных дальномеров обновляются раз в 100 ms, фиксированы по экрану.
  private static final long DISTANCE_PROBE_UPDATE_INTERVAL_NS = 100_000_000L;
  private static final String DISTANCE_UNAVAILABLE_LABEL = "--.- m";
  private static final float[] DISTANCE_PROBE_VIEW_COORDS = {
    0.125f, 0.28f,
    0.375f, 0.28f,
    0.625f, 0.28f,
    0.875f, 0.28f,
    0.125f, 0.72f,
    0.375f, 0.72f,
    0.625f, 0.72f,
    0.875f, 0.72f
  };

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  // ───── UI views ─────
  private GLSurfaceView surfaceView;            // GL-поверхность (на ней всё рисуется)
  private View depthProbeOverlay;               // overlay с 8 крупными пробниками
  private Depth20OverlayView depth20OverlayView; // overlay с 20/23 точками
  private TextView[] depthProbeLabels;          // лейблы дистанций для пробников

  // Флаг для ARCore install-flow.
  private boolean installRequested;

  // ───── модули анализа (по одному на каждый режим) ─────
  private DiagnosticsLogger diagnosticsLogger;             // запись логов
  private Depth20PointModule depth20PointModule;           // простой режим 20 точек
  private SmartDepthPointModule smartDepthPointModule;     // умный режим 23 точек
  private FloorHazardModule floorHazardModule;             // классификация препятствий пола
  private FloorHeightmapRenderer floorHeightmapRenderer;   // 3D mesh пола
  private FloorPointsRenderer floorPointsRenderer;         // накопление feature points
  private OctomapModule octomapModule;                     // octomap + 3DVFH+
  private OctomapInstancedRenderer octomapRenderer;        // GPU instanced кубики
  private boolean floorHeightmapHasMesh = false;
  private boolean loggedOctomapNotYetAvailable = false;
  private TextView floorHazardLabel;
  private String lastFloorHazardMessage;
  private DepthVisualizationMode lastFloorPointsAccumulationMode;
  private TextView octomapStatsLabel;
  private boolean octomapStatsVisible = false;
  private long lastOctomapStatsUpdateNs = 0L;
  // Дирижёр речи + текущий режим, который ему «командует».
  private TemporaryConductorModule temporaryConductorModule;
  private DepthVisualizationMode activeConductorMode;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private SampleRender render;

  private BackgroundRenderer backgroundRenderer;
  private PlaneRenderer planeRenderer;
  private boolean hasSetTextureNames = false;

  private final DepthVisualizationSettings depthVisualizationSettings =
      new DepthVisualizationSettings();
  private int selectedDepthVisualizationModeDialogIndex = DepthVisualizationMode.CAMERA.ordinal();

  private final float[] distanceProbeTextureCoords = new float[DISTANCE_PROBE_VIEW_COORDS.length];
  private long lastDistanceProbeUpdateTimestampNs = 0;
  private boolean isDistanceProbeOverlayVisible = false;
  private TrackingState lastLoggedTrackingState;
  private TrackingFailureReason lastLoggedTrackingFailureReason = TrackingFailureReason.NONE;
  private DepthVisualizationMode lastLoggedDepthVisualizationMode;
  private boolean loggedFullDepthNotYetAvailable = false;
  private boolean loggedRawDepthNotYetAvailable = false;
  private boolean loggedConfidenceNotYetAvailable = false;
  private boolean loggedDepth20NotYetAvailable = false;
  private boolean loggedSmartDepthNotYetAvailable = false;
  private boolean loggedFloorNotYetAvailable = false;
  private boolean isDepth20OverlayVisible = false;

  @Override
  // ═══════════════════════════════════════════════════════════════════════════════
  // Lifecycle
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Создание Activity. Поднимаем диагностику, биндим UI, создаём CPU-side модули
   * (рендереры — позже, в onSurfaceCreated, потому что им нужен GL context).
   */
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    diagnosticsLogger = new DiagnosticsLogger(this);
    diagnosticsLogger.startNewSession();
    diagnosticsLogger.logAppAndDeviceInfo();
    diagnosticsLogger.startLogcatCapture();
    logInfo("LIFECYCLE", "onCreate");
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    depthProbeOverlay = findViewById(R.id.depth_probe_overlay);
    depth20OverlayView = findViewById(R.id.depth20_overlay);
    depthProbeLabels =
        new TextView[] {
          findViewById(R.id.depth_probe_1),
          findViewById(R.id.depth_probe_2),
          findViewById(R.id.depth_probe_3),
          findViewById(R.id.depth_probe_4),
          findViewById(R.id.depth_probe_5),
          findViewById(R.id.depth_probe_6),
          findViewById(R.id.depth_probe_7),
          findViewById(R.id.depth_probe_8)
        };
    setDistanceProbeLabels(createUnavailableDistanceLabels());
    floorHazardLabel = findViewById(R.id.floor_hazard_label);
    octomapStatsLabel = findViewById(R.id.octomap_stats_label);
    TextView dateLabel = findViewById(R.id.date_label);
    dateLabel.setText(new SimpleDateFormat("dd.MM.yyyy", new Locale("ru", "RU")).format(new Date()));
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());
    depth20PointModule = new Depth20PointModule();
    smartDepthPointModule = new SmartDepthPointModule();
    floorHazardModule = new FloorHazardModule();
    octomapModule = new OctomapModule();
    temporaryConductorModule = new TemporaryConductorModule(new SpeechModule(this));
    logInfo("SESSION", "Depth20 module initialized with " + Depth20PointModule.getPointCount() + " points");
    logInfo(
        "SESSION",
        "Smart depth module initialized with " + SmartDepthPointModule.getPointCount() + " points");

    installRequested = false;

    depthVisualizationSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            logInfo("INPUT", "Settings menu opened");
            PopupMenu popup = new PopupMenu(MainActivity.this, v);
            popup.setOnMenuItemClickListener(MainActivity.this::settingsMenuClick);
            popup.inflate(R.menu.settings_menu);
            MenuItem rawDepthItem = popup.getMenu().findItem(R.id.octomap_use_raw_depth);
            if (rawDepthItem != null) {
              rawDepthItem.setChecked(depthVisualizationSettings.octomapUseRawDepth());
              rawDepthItem.setTitle(
                  depthVisualizationSettings.octomapUseRawDepth()
                      ? "Octomap: raw depth"
                      : "Octomap: full depth");
            }
            popup.show();
          }
        });
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_visualization_settings) {
      logInfo("INPUT", "Depth visualization settings selected");
      launchDepthVisualizationSettingsMenuDialog();
      return true;
    } else if (item.getItemId() == R.id.octomap_use_raw_depth) {
      boolean nextValue = !depthVisualizationSettings.octomapUseRawDepth();
      depthVisualizationSettings.setOctomapUseRawDepth(nextValue);
      logInfo(
          "INPUT",
          "Octomap depth source toggled to " + (nextValue ? "RAW" : "FULL") + " depth");
      // Сбрасываем октомап — старая накопленная карта построена на другом типе depth.
      if (octomapModule != null) {
        octomapModule.reset();
      }
      return true;
    } else if (item.getItemId() == R.id.share_logs) {
      shareDiagnosticsLog();
      return true;
    } else if (item.getItemId() == R.id.clear_logs) {
      clearDiagnosticsLogs();
      return true;
    }
    return false;
  }

  @Override
  protected void onDestroy() {
    logInfo("LIFECYCLE", "onDestroy");
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      logInfo("ARCORE", "Closing ARCore session");
      session.close();
      session = null;
    }
    if (temporaryConductorModule != null) {
      temporaryConductorModule.shutdown();
    }
    diagnosticsLogger.stopLogcatCapture();

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    logInfo("LIFECYCLE", "onResume");

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        // Always check the latest availability.
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        diagnosticsLogger.logArCoreAvailability(availability);

        // In all other cases, try to install ARCore and handle installation failures.
        if (availability != Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              logWarning("ARCORE", "ARCore installation requested by Google Play Services for AR");
              installRequested = true;
              return;
            case INSTALLED:
              logInfo("ARCORE", "ARCore installation flow finished successfully");
              break;
          }
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          logWarning("PERMISSION", "Camera permission missing, requesting permission dialog");
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
        logInfo("ARCORE", "ARCore session created successfully");
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        logError("ARCORE", "Exception while creating ARCore session: " + message, exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
      logInfo("ARCORE", "ARCore session resumed");
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      logError("ARCORE", "Camera not available while resuming session", e);
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    logInfo("LIFECYCLE", "onPause");
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
      logInfo("ARCORE", "ARCore session paused");
    }
    if (temporaryConductorModule != null) {
      temporaryConductorModule.reset();
    }
    if (smartDepthPointModule != null) {
      smartDepthPointModule.reset();
    }
    if (floorHazardModule != null) {
      floorHazardModule.reset();
    }
    if (floorPointsRenderer != null) {
      floorPointsRenderer.reset();
    }
    if (octomapModule != null) {
      octomapModule.reset();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      logWarning("PERMISSION", "Camera permission denied");
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        logWarning("PERMISSION", "Camera permission denied with 'Do not ask again'");
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    } else {
      logInfo("PERMISSION", "Camera permission granted");
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  /**
   * GL surface создан — теперь можно создавать рендереры (им нужен живой GL context).
   * Рендереры могут бросить IOException при загрузке шейдеров — ловим и логируем.
   */
  @Override
  public void onSurfaceCreated(SampleRender render) {
    logInfo("RENDER", "onSurfaceCreated: initializing camera/depth GL resources");
    backgroundRenderer = new BackgroundRenderer(render);
    diagnosticsLogger.logGlInfo(
        GLES30.glGetString(GLES30.GL_VENDOR),
        GLES30.glGetString(GLES30.GL_RENDERER),
        GLES30.glGetString(GLES30.GL_VERSION));
    try {
      floorHeightmapRenderer = new FloorHeightmapRenderer(render);
      floorPointsRenderer = new FloorPointsRenderer(render);
      logInfo("FLOORMAP", "Floor heightmap renderers initialized");
    } catch (IOException e) {
      logError("FLOORMAP", "Failed to initialize floor heightmap renderers", e);
    }
    try {
      planeRenderer = new PlaneRenderer(render);
      logInfo("FLOORMAP", "Plane renderer initialized");
    } catch (IOException e) {
      logError("FLOORMAP", "Failed to initialize plane renderer", e);
    }
    try {
      octomapRenderer = new OctomapInstancedRenderer(render, OctomapModule.MAX_VOXELS);
      logInfo(
          "OCTOMAP",
          "Octomap instanced renderer initialized (cap=" + OctomapModule.MAX_VOXELS + ")");
    } catch (IOException e) {
      logError("OCTOMAP", "Failed to initialize octomap renderer", e);
    }
    logInfo("RENDER", "Camera/depth renderer initialized; sample 3D placement is disabled");
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    logInfo("RENDER", "Surface changed to " + width + "x" + height);
  }

  /**
   * Главный цикл — вызывается ARCore'ом примерно 30 раз в секунду на GL-thread'е.
   *
   * <p>Что делаем по порядку:
   * <ol>
   *   <li>Текстуры камеры биндим один раз (флаг hasSetTextureNames).</li>
   *   <li>frame.update() — получаем свежий кадр от ARCore (depth, pose).</li>
   *   <li>Определяем активный режим визуализации — флаги isXxxMode.</li>
   *   <li>Решаем кто командует дирижёром (nextConductorMode), при смене сбрасываем модули.</li>
   *   <li>Озвучиваем потерю трекинга если она наступила.</li>
   *   <li>Если режим требует depth — берём depth + confidence и прогоняем через нужный
   *       модуль анализа.</li>
   *   <li>Рисуем фон камеры. Поверх — overlay'и: planes, floor mesh / points / octomap.</li>
   * </ol>
   */
  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from the AR Session. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      logError("ARCORE", "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update BackgroundRenderer state to match the depth settings.
    DepthVisualizationMode selectedDepthVisualizationMode =
        depthVisualizationSettings.depthVisualizationMode();
    boolean supportsDepth = isDepthModeSupported();
    boolean isDistanceProbeMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.DISTANCE_PROBES && supportsDepth;
    boolean isDepth20ProbeMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.DEPTH20_PROBES && supportsDepth;
    boolean isSmartDepthMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.SMART_DEPTH_20PT && supportsDepth;
    boolean isFloorHeightmapMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.FLOOR_HEIGHTMAP && supportsDepth;
    boolean isFloorPoints3dMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.FLOOR_POINTS_3D && supportsDepth;
    boolean isOctomapMode =
        selectedDepthVisualizationMode == DepthVisualizationMode.OCTOMAP_3D && supportsDepth;
    boolean isDepth20ModuleActive = isDepth20ProbeMode && depth20PointModule != null;
    boolean isSmartDepthModuleActive =
        isSmartDepthMode && smartDepthPointModule != null;
    boolean isFloorHeightmapModuleActive =
        isFloorHeightmapMode
            && floorHeightmapRenderer != null
            && floorHazardModule != null;
    boolean isFloorPoints3dModuleActive =
        isFloorPoints3dMode && floorPointsRenderer != null;
    boolean isOctomapModuleActive =
        isOctomapMode && octomapModule != null && octomapRenderer != null;
    if (floorPointsRenderer != null
        && isFloorPoints3dMode
        && lastFloorPointsAccumulationMode != DepthVisualizationMode.FLOOR_POINTS_3D) {
      floorPointsRenderer.reset();
    }
    lastFloorPointsAccumulationMode = selectedDepthVisualizationMode;
    if (!isFloorHeightmapMode) {
      clearFloorHazardLabel();
    }
    updateOctomapStatsLabelVisibility(isOctomapModuleActive);
    boolean isDepthPointOverlayMode = isDepth20ProbeMode || isSmartDepthMode;

    // Дирижер работает только в режимах, которые реально создают команды движения.
    // DEPTH20_PROBES передает старый список правил из Depth20PointModule.
    // SMART_DEPTH_20PT передает умные правила из SmartDepthPointModule.
    // В остальных режимах источник команд выключен, чтобы озвучка не мешала просмотру depth-карт.
    DepthVisualizationMode nextConductorMode = null;
    if (isDepth20ModuleActive) {
      nextConductorMode = DepthVisualizationMode.DEPTH20_PROBES;
    } else if (isSmartDepthModuleActive) {
      nextConductorMode = DepthVisualizationMode.SMART_DEPTH_20PT;
    } else if (isFloorHeightmapModuleActive) {
      nextConductorMode = DepthVisualizationMode.FLOOR_HEIGHTMAP;
    } else if (isOctomapModuleActive) {
      nextConductorMode = DepthVisualizationMode.OCTOMAP_3D;
    }
    // FLOOR_POINTS_3D — чисто визуализация накопленных точек, дирижёр в этом режиме молчит.
    updateConductorMode(nextConductorMode);
    if (temporaryConductorModule != null) {
      TrackingFailureReason failureReason = camera.getTrackingFailureReason();
      if (camera.getTrackingState() == TrackingState.TRACKING) {
        temporaryConductorModule.onTrackingRestored();
      } else if (failureReason != TrackingFailureReason.NONE) {
        // Озвучиваем конкретную причину сбоя ARCore (тёмно, мало деталей, резкое движение и т.п.)
        // — независимо от выбранного режима, чтобы пользователь сразу слышал, что делать.
        temporaryConductorModule.onTrackingFailure(failureReason);
      } else if (nextConductorMode != null) {
        // Причина не определена, но трекинг потерян — общая подсказка только в режимах с дирижёром.
        temporaryConductorModule.onTrackingLost();
      }
    }
    BackgroundVisualizationMode backgroundVisualizationMode =
        session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            ? toBackgroundVisualizationMode(selectedDepthVisualizationMode)
            : BackgroundVisualizationMode.CAMERA;
    updateDistanceProbeOverlayVisibility(isDistanceProbeMode);
    updateDepth20OverlayVisibility(isDepthPointOverlayMode);
    try {
      backgroundRenderer.setBackgroundVisualizationMode(render, backgroundVisualizationMode);
    } catch (IOException e) {
      logError("RENDER", "Failed to read a required asset file while switching background mode", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    logRuntimeState(frame, camera, selectedDepthVisualizationMode);
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING
        && (backgroundVisualizationMode == BackgroundVisualizationMode.FULL_DEPTH
            || backgroundVisualizationMode == BackgroundVisualizationMode.DEPTH_DOT_GRID
            || isDistanceProbeMode
            || isDepth20ModuleActive
            || isSmartDepthModuleActive
            || isFloorHeightmapModuleActive
            || isFloorPoints3dModuleActive
            || isOctomapModuleActive
            || isDepthPointOverlayMode)) {
      try (Image depthImage = frame.acquireDepthImage16Bits()) {
        loggedFullDepthNotYetAvailable = false;
        loggedDepth20NotYetAvailable = false;
        loggedSmartDepthNotYetAvailable = false;
        loggedFloorNotYetAvailable = false;
        if (backgroundVisualizationMode == BackgroundVisualizationMode.FULL_DEPTH
            || backgroundVisualizationMode == BackgroundVisualizationMode.DEPTH_DOT_GRID) {
          backgroundRenderer.updateCameraDepthTexture(depthImage);
        }
          if (isDistanceProbeMode) {
            updateDistanceProbes(frame, depthImage, frame.getTimestamp());
          }
          if (isFloorPoints3dModuleActive) {
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
              floorPointsRenderer.accumulate(pointCloud);
            }
          }
          if (isOctomapModuleActive) {
            // Источник depth выбирается из меню. Raw depth — точнее по геометрии (без
            // сглаживания), но разрежен; full depth — плотнее, но интерполированные края
            // дают «висящие» воксели. Confidence доступен только для raw — при full depth
            // фильтр не применяется, но MIN_VOXEL_AVG_CONFIDENCE и hit threshold всё равно
            // отсекают шум на render-стадии.
            boolean useRawDepth = depthVisualizationSettings.octomapUseRawDepth();
            Image octomapDepthImage = null;
            Image octomapConfidenceImage = null;
            boolean acquiredOwn = false;
            try {
              try {
                if (useRawDepth) {
                  octomapDepthImage = frame.acquireRawDepthImage16Bits();
                  octomapConfidenceImage = frame.acquireRawDepthConfidenceImage();
                  acquiredOwn = true;
                } else {
                  // Full depth уже acquired в общем блоке выше — используем его напрямую.
                  octomapDepthImage = depthImage;
                  // Confidence не имеет прямого соответствия для full depth, но raw
                  // confidence коррелирует пространственно — попробуем его получить.
                  try {
                    octomapConfidenceImage = frame.acquireRawDepthConfidenceImage();
                  } catch (NotYetAvailableException ignored) {
                    octomapConfidenceImage = null;
                  }
                }
                loggedConfidenceNotYetAvailable = false;
              } catch (NotYetAvailableException e) {
                if (!loggedConfidenceNotYetAvailable) {
                  logWarning("DEPTH", "Octomap depth source not available yet");
                  loggedConfidenceNotYetAvailable = true;
                }
              }
              if (octomapDepthImage != null) {
                octomapModule.process(frame, octomapDepthImage, octomapConfidenceImage);
              }
              float[] octomapProj = new float[16];
              float[] octomapView = new float[16];
              camera.getProjectionMatrix(octomapProj, 0, 0.1f, 100.0f);
              camera.getViewMatrix(octomapView, 0);
              octomapRenderer.update(
                  octomapModule.getOctomap(),
                  OctomapModule.RENDER_HIT_THRESHOLD,
                  octomapProj,
                  octomapView);
              temporaryConductorModule.onDepth20Result(
                  octomapModule.buildScanResult(), "OctomapPlanner");
              updateOctomapStatsLabelText(frame.getTimestamp());
              loggedOctomapNotYetAvailable = false;
            } finally {
              if (acquiredOwn && octomapDepthImage != null) {
                octomapDepthImage.close();
              }
              if (octomapConfidenceImage != null) {
                octomapConfidenceImage.close();
              }
            }
          }
          if (isDepth20ModuleActive
              || isSmartDepthModuleActive
              || isFloorHeightmapModuleActive) {
            Image rawDepthConfidenceImage = null;
            try {
              if (isDepthPointOverlayMode) {
                try {
                  rawDepthConfidenceImage = frame.acquireRawDepthConfidenceImage();
                  loggedConfidenceNotYetAvailable = false;
                } catch (NotYetAvailableException e) {
                  if (!loggedConfidenceNotYetAvailable) {
                    logWarning("DEPTH", "Raw confidence image is not available yet for overlay mode");
                    loggedConfidenceNotYetAvailable = true;
                  }
                }
              }

              if (isSmartDepthModuleActive) {
                Depth20ScanResult smartDepthScanResult =
                    smartDepthPointModule.process(frame, depthImage, rawDepthConfidenceImage);
                logSmartDepthDiagnosticsIfNeeded(frame, smartDepthScanResult);
                // В smart-режиме дирижер получает уже готовую команду от SmartDepthPointModule.
                temporaryConductorModule.onDepth20Result(
                    smartDepthScanResult, "SmartDepthPointModule");
                updateSmartDepthProbes(frame, smartDepthScanResult);
              } else if (isFloorHeightmapModuleActive) {
                Collection<Plane> planes = session.getAllTrackables(Plane.class);
                floorHeightmapHasMesh =
                    floorHeightmapRenderer.update(frame, depthImage, planes);
                FloorHazardModule.Result hazard =
                    floorHazardModule.analyze(frame, depthImage, planes);
                updateFloorHazardLabel(hazard);
                temporaryConductorModule.onDepth20Result(
                    adaptHazardToScanResult(hazard), "FloorHazardModule");
              } else {
                Depth20ScanResult depth20ScanResult =
                    depth20PointModule.process(frame, depthImage, rawDepthConfidenceImage);
                // В обычном режиме 20 точек дирижер получает старую команду от Depth20PointModule.
                temporaryConductorModule.onDepth20Result(depth20ScanResult, "Depth20PointModule");
                if (isDepth20ProbeMode) {
                  updateDepth20Probes(frame, depth20ScanResult);
                }
              }
            } finally {
              if (rawDepthConfidenceImage != null) {
                rawDepthConfidenceImage.close();
              }
            }
          }
        } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
        if (!loggedFullDepthNotYetAvailable) {
          logWarning("DEPTH", "Full depth image is not available yet");
          loggedFullDepthNotYetAvailable = true;
        }
        if (isDepth20ModuleActive && !loggedDepth20NotYetAvailable) {
          logWarning("DEPTH20", "Depth20 module is waiting for the first full depth frame");
          loggedDepth20NotYetAvailable = true;
        }
        if (isSmartDepthModuleActive && !loggedSmartDepthNotYetAvailable) {
          logWarning("SMART_DEPTH", "Smart depth module is waiting for the first full depth frame");
          loggedSmartDepthNotYetAvailable = true;
        }
        if (isFloorHeightmapModuleActive && !loggedFloorNotYetAvailable) {
          logWarning("FLOORMAP", "Floor heightmap module is waiting for the first full depth frame");
          loggedFloorNotYetAvailable = true;
        }
        if (isOctomapModuleActive && !loggedOctomapNotYetAvailable) {
          logWarning("OCTOMAP", "Octomap module is waiting for the first full depth frame");
          loggedOctomapNotYetAvailable = true;
        }
        if (isDistanceProbeMode) {
          clearDistanceProbesIfNeeded();
        }
        if (isDepthPointOverlayMode) {
          clearDepth20OverlayIfNeeded();
        }
      }
    } else {
      if (isDistanceProbeMode) {
        clearDistanceProbesIfNeeded();
      }
      if (isDepthPointOverlayMode) {
        clearDepth20OverlayIfNeeded();
      }
      if (nextConductorMode != null && temporaryConductorModule != null) {
        temporaryConductorModule.reset();
      }
    }

    if (camera.getTrackingState() == TrackingState.TRACKING
        && backgroundVisualizationMode == BackgroundVisualizationMode.RAW_DEPTH) {
      try (Image rawDepthImage = frame.acquireRawDepthImage16Bits()) {
        loggedRawDepthNotYetAvailable = false;
        backgroundRenderer.updateRawDepthTexture(rawDepthImage);
      } catch (NotYetAvailableException e) {
        // Raw depth is still warming up. Keep the last uploaded texture.
        if (!loggedRawDepthNotYetAvailable) {
          logWarning("DEPTH", "Raw depth image is not available yet");
          loggedRawDepthNotYetAvailable = true;
        }
      }
    }

    if (camera.getTrackingState() == TrackingState.TRACKING
        && backgroundVisualizationMode == BackgroundVisualizationMode.CONFIDENCE) {
      try (Image rawDepthConfidenceImage = frame.acquireRawDepthConfidenceImage()) {
        loggedConfidenceNotYetAvailable = false;
        backgroundRenderer.updateRawDepthConfidenceTexture(rawDepthConfidenceImage);
      } catch (NotYetAvailableException e) {
        // Confidence image is still warming up. Keep the last uploaded texture.
        if (!loggedConfidenceNotYetAvailable) {
          logWarning("DEPTH", "Raw depth confidence image is not available yet");
          loggedConfidenceNotYetAvailable = true;
        }
      }
    }

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show only tracking-state messages. The sample object placement flow is disabled.
    String message = null;
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
        message = "Starting AR tracking...";
      } else {
        message = TrackingStateHelper.getTrackingFailureReasonString(camera);
      }
    }
    if (message == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, message);
    }

    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);

      if ((isFloorHeightmapModuleActive
              || isFloorPoints3dModuleActive
              || isOctomapModuleActive)
          && camera.getTrackingState() == TrackingState.TRACKING) {
        float[] projectionMatrix = new float[16];
        float[] viewMatrix = new float[16];
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
        camera.getViewMatrix(viewMatrix, 0);
        if (planeRenderer != null
            && (isFloorHeightmapModuleActive || isFloorPoints3dModuleActive)) {
          planeRenderer.drawPlanes(
              render,
              session.getAllTrackables(Plane.class),
              camera.getDisplayOrientedPose(),
              projectionMatrix);
        }
        if (isOctomapModuleActive && octomapRenderer != null) {
          octomapRenderer.draw(render, projectionMatrix, viewMatrix);
        }
        if (isFloorHeightmapModuleActive && floorHeightmapHasMesh) {
          floorHeightmapRenderer.draw(render, projectionMatrix, viewMatrix);
        }
        if (isFloorPoints3dModuleActive) {
          if (floorPointsRenderer.uploadForDraw(session.getAllTrackables(Plane.class))) {
            float[] viewProjection = new float[16];
            android.opengl.Matrix.multiplyMM(
                viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);
            floorPointsRenderer.draw(render, viewProjection);
          }
        }
      }
    }
  }

  /** Shows a single-choice dialog to switch which depth-related image is shown as the background. */
  private void launchDepthVisualizationSettingsMenuDialog() {
    if (!isDepthModeSupported()) {
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_without_depth)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }

    resetSettingsMenuDialogCheckboxes();
    Resources resources = getResources();
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_depth_visualization)
        .setSingleChoiceItems(
            resources.getStringArray(R.array.depth_visualization_options_array),
            selectedDepthVisualizationModeDialogIndex,
            (DialogInterface dialog, int which) -> selectedDepthVisualizationModeDialogIndex = which)
        .setPositiveButton(
            R.string.done,
            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
        .setNegativeButton(
            android.R.string.cancel,
            (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
        .show();
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthVisualizationSettings.setDepthVisualizationMode(
        DepthVisualizationMode.values()[selectedDepthVisualizationModeDialogIndex]);
    logInfo(
        "SESSION",
        "Settings applied: visualization="
            + DepthVisualizationMode.values()[selectedDepthVisualizationModeDialogIndex]);
    if (session != null) {
      configureSession();
    }
  }

  private void resetSettingsMenuDialogCheckboxes() {
    selectedDepthVisualizationModeDialogIndex =
        depthVisualizationSettings.depthVisualizationMode().ordinal();
  }

  /**
   * Настройки ARCore-сессии. Дёргается при каждом изменении режима из меню.
   * Включает/выключает features в зависимости от того что нужно текущему режиму
   * (light estimation, plane finding, depth mode и т.д.).
   */
  private void configureSession() {
    Config config = session.getConfig();
    DepthVisualizationMode mode = depthVisualizationSettings.depthVisualizationMode();
    // В octomap-режиме включаем оценку освещённости — она нужна для адаптивного
    // порога confidence (в темноте мы не доверяем depth-карте).
    boolean wantLightEstimation = mode == DepthVisualizationMode.OCTOMAP_3D;
    config.setLightEstimationMode(
        wantLightEstimation
            ? Config.LightEstimationMode.AMBIENT_INTENSITY
            : Config.LightEstimationMode.DISABLED);
    if (isDepthModeSupported()) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    boolean wantPlanes =
        mode == DepthVisualizationMode.FLOOR_HEIGHTMAP
            || mode == DepthVisualizationMode.FLOOR_POINTS_3D;
    config.setPlaneFindingMode(
        wantPlanes
            ? Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            : Config.PlaneFindingMode.DISABLED);
    session.configure(config);
    logInfo(
        "ARCORE",
        "Session configured: light=DISABLED, depth="
            + config.getDepthMode()
            + ", visualization="
            + depthVisualizationSettings.depthVisualizationMode());
  }

  private boolean isDepthModeSupported() {
    return session != null && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
  }

  private void updateDistanceProbeOverlayVisibility(boolean shouldBeVisible) {
    if (isDistanceProbeOverlayVisible == shouldBeVisible) {
      return;
    }
    isDistanceProbeOverlayVisible = shouldBeVisible;
    runOnUiThread(
        () -> depthProbeOverlay.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE));
    if (!shouldBeVisible) {
      clearDistanceProbesIfNeeded();
    }
  }

  private void updateDepth20OverlayVisibility(boolean shouldBeVisible) {
    if (isDepth20OverlayVisible == shouldBeVisible) {
      return;
    }
    isDepth20OverlayVisible = shouldBeVisible;
    runOnUiThread(
        () -> depth20OverlayView.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE));
    if (!shouldBeVisible) {
      clearDepth20OverlayIfNeeded();
    }
  }

  private void updateDistanceProbes(Frame frame, Image depthImage, long frameTimestampNs) {
    if (frameTimestampNs != 0
        && frameTimestampNs - lastDistanceProbeUpdateTimestampNs
            < DISTANCE_PROBE_UPDATE_INTERVAL_NS) {
      return;
    }

    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        DISTANCE_PROBE_VIEW_COORDS,
        Coordinates2d.TEXTURE_NORMALIZED,
        distanceProbeTextureCoords);

    String[] labels = new String[depthProbeLabels.length];
    for (int i = 0; i < depthProbeLabels.length; ++i) {
      float textureX = distanceProbeTextureCoords[i * 2];
      float textureY = distanceProbeTextureCoords[i * 2 + 1];
      float distanceMeters = sampleDepthMeters(depthImage, textureX, textureY);
      labels[i] =
          distanceMeters > 0.0f
              ? String.format(Locale.US, "%.2f m", distanceMeters)
              : DISTANCE_UNAVAILABLE_LABEL;
    }
    lastDistanceProbeUpdateTimestampNs = frameTimestampNs;
    setDistanceProbeLabels(labels);
  }

  private void updateDepth20Probes(Frame frame, Depth20ScanResult result) {
    float[] viewPoints = depth20PointModule.transformPointsToView(frame);
    String[] distanceLabels = depth20PointModule.formatDistanceLabels(result);
    String[] confidenceLabels = depth20PointModule.formatConfidenceLabels(result);
    boolean[] blocked = result.getBlockedPoints();
    runOnUiThread(
        () -> depth20OverlayView.updateOverlay(viewPoints, distanceLabels, confidenceLabels, blocked));
  }

  private void updateSmartDepthProbes(Frame frame, Depth20ScanResult result) {
    float[] viewPoints = smartDepthPointModule.transformPointsToView(frame);
    String[] distanceLabels = smartDepthPointModule.formatDistanceLabels(result);
    String[] confidenceLabels = smartDepthPointModule.formatConfidenceLabels(result);
    boolean[] blocked = result.getBlockedPoints();
    runOnUiThread(
        () -> depth20OverlayView.updateOverlay(viewPoints, distanceLabels, confidenceLabels, blocked));
  }

  private void logSmartDepthDiagnosticsIfNeeded(Frame frame, Depth20ScanResult result) {
    if (smartDepthPointModule == null || frame == null || result == null) {
      return;
    }
    String debugLog = smartDepthPointModule.consumeDebugLog(result, frame.getTimestamp());
    if (debugLog != null && !debugLog.isEmpty()) {
      logInfo("SMART_DEPTH", debugLog);
    }
  }

  private void updateConductorMode(DepthVisualizationMode nextMode) {
    if (activeConductorMode == nextMode) {
      return;
    }
    activeConductorMode = nextMode;
    if (temporaryConductorModule != null) {
      temporaryConductorModule.reset();
    }
    if (smartDepthPointModule != null) {
      smartDepthPointModule.reset();
    }
    if (floorHazardModule != null) {
      floorHazardModule.reset();
    }
    if (octomapModule != null) {
      octomapModule.reset();
    }
    floorHeightmapHasMesh = false;
    clearFloorHazardLabel();
    logInfo("CONDUCTOR", "Active command source: " + (nextMode == null ? "OFF" : nextMode));
  }

  private void updateFloorHazardLabel(FloorHazardModule.Result result) {
    if (floorHazardLabel == null) {
      return;
    }
    final String message = result != null ? result.message : null;
    final FloorHazardModule.Severity severity =
        result != null ? result.severity : FloorHazardModule.Severity.NONE;
    if (message == null || severity == FloorHazardModule.Severity.NONE) {
      clearFloorHazardLabel();
      return;
    }
    if (message.equals(lastFloorHazardMessage)) {
      return;
    }
    lastFloorHazardMessage = message;
    final int backgroundColor;
    switch (severity) {
      case DANGER:
        backgroundColor = 0xCCC62828;
        break;
      case WARNING:
        backgroundColor = 0xCCEF6C00;
        break;
      case INFO:
      default:
        backgroundColor = 0xCC000000;
        break;
    }
    runOnUiThread(
        () -> {
          floorHazardLabel.setText(message);
          floorHazardLabel.setBackgroundColor(backgroundColor);
          floorHazardLabel.setVisibility(View.VISIBLE);
        });
  }

  private void clearFloorHazardLabel() {
    if (floorHazardLabel == null || lastFloorHazardMessage == null) {
      return;
    }
    lastFloorHazardMessage = null;
    runOnUiThread(() -> floorHazardLabel.setVisibility(View.GONE));
  }

  private void updateOctomapStatsLabelVisibility(boolean shouldBeVisible) {
    if (octomapStatsLabel == null) {
      return;
    }
    if (shouldBeVisible == octomapStatsVisible) {
      return;
    }
    octomapStatsVisible = shouldBeVisible;
    runOnUiThread(
        () -> octomapStatsLabel.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE));
  }

  private void updateOctomapStatsLabelText(long frameTimestampNs) {
    if (octomapStatsLabel == null
        || octomapModule == null
        || frameTimestampNs == 0L) {
      return;
    }
    // Обновляем текст не чаще 2 раз в секунду чтобы не дёргать UI thread зря.
    if (frameTimestampNs - lastOctomapStatsUpdateNs < 500_000_000L) {
      return;
    }
    lastOctomapStatsUpdateNs = frameTimestampNs;
    final int voxels = octomapModule.getOctomap().size();
    final int rendered =
        octomapRenderer == null ? 0 : octomapRenderer.getInstanceCount();
    final boolean scanning = octomapModule.isScanning();
    final float adaptiveConf = octomapModule.getAdaptiveMinConfidence();
    final String text =
        String.format(
            Locale.US,
            "voxels: %d  drawn: %d  conf: %.2f  %s",
            voxels,
            rendered,
            adaptiveConf,
            scanning ? "[scan]" : "");
    runOnUiThread(() -> octomapStatsLabel.setText(text));
  }

  private static Depth20ScanResult adaptHazardToScanResult(FloorHazardModule.Result hazard) {
    String phrase = hazard != null && hazard.message != null ? hazard.message : "";
    Depth20ScanResult.Severity sev;
    FloorHazardModule.Severity src =
        hazard != null ? hazard.severity : FloorHazardModule.Severity.NONE;
    switch (src) {
      case DANGER:
        sev = Depth20ScanResult.Severity.STOP;
        break;
      case WARNING:
        sev = Depth20ScanResult.Severity.WARNING;
        break;
      case INFO:
        sev = Depth20ScanResult.Severity.INFO;
        break;
      case NONE:
      default:
        sev = Depth20ScanResult.Severity.CLEAR;
        break;
    }
    return new Depth20ScanResult(
        new float[0], new float[0], new boolean[0], phrase, sev, 0f);
  }

  private float sampleDepthMeters(Image depthImage, float textureX, float textureY) {
    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer buffer = plane.getBuffer();
    int width = depthImage.getWidth();
    int height = depthImage.getHeight();
    int pixelStride = plane.getPixelStride();
    int rowStride = plane.getRowStride();

    int centerX = clamp((int) (textureX * width), 0, width - 1);
    int centerY = clamp((int) (textureY * height), 0, height - 1);

    for (int radius = 0; radius <= 1; ++radius) {
      for (int offsetY = -radius; offsetY <= radius; ++offsetY) {
        for (int offsetX = -radius; offsetX <= radius; ++offsetX) {
          int sampleX = clamp(centerX + offsetX, 0, width - 1);
          int sampleY = clamp(centerY + offsetY, 0, height - 1);
          int byteOffset = sampleY * rowStride + sampleX * pixelStride;
          if (byteOffset + 1 >= buffer.limit()) {
            continue;
          }
          int depthMillimeters =
              (buffer.get(byteOffset) & 0xFF) | ((buffer.get(byteOffset + 1) & 0xFF) << 8);
          if (depthMillimeters > 0) {
            return depthMillimeters * 0.001f;
          }
        }
      }
    }
    return 0.0f;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private void setDistanceProbeLabels(String[] labels) {
    runOnUiThread(
        () -> {
          for (int i = 0; i < depthProbeLabels.length; ++i) {
            depthProbeLabels[i].setText(labels[i]);
          }
        });
  }

  private void clearDistanceProbesIfNeeded() {
    lastDistanceProbeUpdateTimestampNs = 0;
    setDistanceProbeLabels(createUnavailableDistanceLabels());
  }

  private void clearDepth20OverlayIfNeeded() {
    if (depth20OverlayView != null) {
      runOnUiThread(() -> depth20OverlayView.clear());
    }
  }

  private String[] createUnavailableDistanceLabels() {
    String[] labels = new String[8];
    for (int i = 0; i < labels.length; ++i) {
      labels[i] = DISTANCE_UNAVAILABLE_LABEL;
    }
    return labels;
  }

  private void shareDiagnosticsLog() {
    Uri logUri = diagnosticsLogger.getSharableUri();
    if (logUri == null) {
      logWarning("SHARE", "Share requested before log file was available");
      Toast.makeText(this, R.string.share_logs_unavailable, Toast.LENGTH_SHORT).show();
      return;
    }

    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_logs_title));
    intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_logs_message));
    intent.putExtra(Intent.EXTRA_STREAM, logUri);
    intent.setClipData(ClipData.newRawUri(getString(R.string.share_logs_title), logUri));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    logInfo("SHARE", "Sharing diagnostics log: " + logUri);
    startActivity(Intent.createChooser(intent, getString(R.string.share_logs_title)));
  }

  private void clearDiagnosticsLogs() {
    boolean success = diagnosticsLogger.clearAllLogs();
    diagnosticsLogger.startNewSession();
    diagnosticsLogger.logAppAndDeviceInfo();
    diagnosticsLogger.startLogcatCapture();
    logInfo("SESSION", "Logs were cleared by user; new logging session started");

    if (success) {
      Toast.makeText(this, R.string.clear_logs_done, Toast.LENGTH_SHORT).show();
    } else {
      logWarning("SESSION", "Some old log files could not be deleted completely");
      Toast.makeText(this, R.string.clear_logs_failed, Toast.LENGTH_SHORT).show();
    }
  }

  private void logRuntimeState(
      Frame frame, Camera camera, DepthVisualizationMode selectedDepthVisualizationMode) {
    if (selectedDepthVisualizationMode != lastLoggedDepthVisualizationMode) {
      logInfo("DEPTH", "Visualization mode: " + selectedDepthVisualizationMode);
      lastLoggedDepthVisualizationMode = selectedDepthVisualizationMode;
    }

    TrackingState trackingState = camera.getTrackingState();
    if (trackingState != lastLoggedTrackingState) {
      logInfo("TRACKING", "Camera tracking state changed to " + trackingState);
      lastLoggedTrackingState = trackingState;
    }

    TrackingFailureReason trackingFailureReason = camera.getTrackingFailureReason();
    if (trackingFailureReason != lastLoggedTrackingFailureReason) {
      logInfo("TRACKING", "Tracking failure reason: " + trackingFailureReason);
      lastLoggedTrackingFailureReason = trackingFailureReason;
    }

  }

  private void logInfo(String section, String message) {
    Log.i(TAG, section + " | " + message);
    diagnosticsLogger.logInfo(section, message);
  }

  private void logWarning(String section, String message) {
    Log.w(TAG, section + " | " + message);
    diagnosticsLogger.logWarning(section, message);
  }

  private void logError(String section, String message, Throwable throwable) {
    Log.e(TAG, section + " | " + message, throwable);
    diagnosticsLogger.logError(section, message, throwable);
  }

  private BackgroundVisualizationMode toBackgroundVisualizationMode(
      DepthVisualizationMode depthVisualizationMode) {
    switch (depthVisualizationMode) {
      case FULL_DEPTH:
        return BackgroundVisualizationMode.FULL_DEPTH;
      case RAW_DEPTH:
        return BackgroundVisualizationMode.RAW_DEPTH;
      case CONFIDENCE:
        return BackgroundVisualizationMode.CONFIDENCE;
      case DEPTH_DOT_GRID:
        return BackgroundVisualizationMode.DEPTH_DOT_GRID;
      case DISTANCE_PROBES:
        return BackgroundVisualizationMode.CAMERA;
      case DEPTH20_PROBES:
        return BackgroundVisualizationMode.CAMERA;
      case SMART_DEPTH_20PT:
      case FLOOR_HEIGHTMAP:
      case FLOOR_POINTS_3D:
      case OCTOMAP_3D:
        return BackgroundVisualizationMode.CAMERA;
      case CAMERA:
      default:
        return BackgroundVisualizationMode.CAMERA;
    }
  }
}
