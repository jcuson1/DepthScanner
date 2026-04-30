package com.google.ar.core.examples.java.helloar.octomap;

import android.media.Image;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.helloar.depth20.Depth20ScanResult;
import java.nio.ByteBuffer;

/**
 * Главный модуль octomap-режима. Связывает четыре вещи:
 *
 * <ol>
 *   <li>Берёт depth-карту от ARCore и кладёт точки в octomap.</li>
 *   <li>Чистит карту от старых вокселей (decay) и далёких (cull).</li>
 *   <li>Следит за движением пользователя и за тем, не вертит ли он камерой.</li>
 *   <li>Раз в 250 ms запускает 3DVFH+ planner и отдаёт результат дирижёру речи.</li>
 * </ol>
 *
 * <p>Всё работает последовательно на GL-потоке. Параллельных потоков нет — нагрузка
 * 5-10 ms на кадр, никаких race condition'ов искать не приходится.
 */
public class OctomapModule {

  // ───── параметры octomap'a ─────

  // 10 см — компромисс между точностью и скоростью. ARCore сам шумит на ~5-10 см,
  // мельче нет смысла. Плюс мельче ячейка → больше вокселей → не вытянет рендер.
  public static final float CELL_SIZE_METERS = 0.10f;

  // Потолок числа ячеек. С 4500 в насыщенных сценах cap иногда достигался → хорошие
  // воксели отбрасывались. 7000 — небольшой запас памяти, карта живёт стабильнее.
  public static final int MAX_VOXELS = 7000;

  // Минимум hits чтобы воксель появился на экране. 4 — компромисс: быстрее, но всё ещё
  // отсечёт одиночные шумные пиксели.
  public static final int RENDER_HIT_THRESHOLD = 4;

  // ───── параметры интеграции depth ─────

  // Берём не каждый пиксель, а каждый второй.
  private static final int DEPTH_PIXEL_STEP = 2;

  // Интегрируем не каждый кадр, а каждый второй. step=2 + кадр через один = та же
  // средняя нагрузка что step=4 каждый кадр, но воксели подтверждаются быстрее.
  private static final int INTEGRATE_EVERY_NTH_FRAME = 2;

  // Фильтр depth: меньше 30 см и больше 5 м — не доверяем (шум на близком и дальнем).
  private static final float MIN_VALID_DEPTH = 0.30f;
  private static final float MAX_VALID_DEPTH = 4.0f;

  // Адаптивный порог уверенности. В ярком свете берём низкий (больше пикселей пройдёт),
  // в темноте поднимаем (только самые надёжные).
  private static final float MIN_CONFIDENCE_BRIGHT = 0.20f;
  private static final float MIN_CONFIDENCE_DARK = 0.45f;

  // ───── динамический порог (AGC по числу вокселей) ─────
  //
  // Классическая схема target-tracking систем: смотрим на «сигнал» (число вокселей в карте)
  // и автоматически подкручиваем «чувствительность» (порог confidence). Если воксей мало —
  // снижаем планку, больше пикселей пройдут. Если карта насыщена — поднимаем планку,
  // отсекаем шум.
  //
  // Это решает «промах цели»: на бедно текстурированных сценах статичный порог 0.20 даёт
  // 5-10 вокселей, и алгоритм фактически слепой.
  //
  // Цель — держать в карте 200-500 вокселей. Меньше → adaptive порог понижается, больше → растёт.
  private static final int TARGET_VOXEL_COUNT_LOW = 200;
  private static final int TARGET_VOXEL_COUNT_HIGH = 500;
  // Жёсткие границы динамического порога — не ниже 0.05 (иначе накачаем чистого шума) и
  // не выше 0.55 (иначе совсем перестанем видеть в темноте).
  private static final float ADAPTIVE_CONF_MIN = 0.05f;
  private static final float ADAPTIVE_CONF_MAX = 0.55f;
  // Шаг изменения порога за один кадр. 0.005 × 30 fps = 0.15 в секунду — плавно, без рывков.
  private static final float ADAPTIVE_CONF_STEP = 0.005f;

  // Порог средней уверенности на воксель. Если воксель собран из шумных пикселей —
  // не показываем и в planner не пропускаем. Опущен с 0.30 до 0.20 — было слишком жёстко
  // в типичных сценах с не очень текстурированными поверхностями.
  public static final float MIN_VOXEL_AVG_CONFIDENCE = 0.20f;

  // Максимум шагов 3D-DDA. 50 шагов × 10 см = 5 м, дальше depth всё равно не достаёт.
  private static final int MAX_RAYCAST_STEPS = 50;

  // Planner вызываем 4 раза в секунду. Чаще не нужно — голос всё равно дольше говорит.
  private static final long PLANNER_INTERVAL_NS = 250_000_000L;

  // ───── параметры чистки карты ─────

  // Сейчас decay медленный, потому что ray-cast и так чистит видимое в реальном времени.
  // Раньше параметры были агрессивнее (15/30/2). 30/90/1 = карта помнит ~30 секунд
  // того что было за спиной.
  private static final int DECAY_INTERVAL_FRAMES = 30;
  private static final int DECAY_STALE_FRAMES = 90;
  private static final int DECAY_AMOUNT = 1;

  // Радиус мгновенного удаления.
  private static final float CULL_RADIUS_M = 7.0f;
  private static final int CULL_INTERVAL_FRAMES = 60; // = 2 сек

  // ───── параметры детекции движения и сканирования ─────

  // EMA для вектора движения. α=0.3 — 30% веса нового шага.
  private static final float MOVE_SMOOTH_ALPHA = 0.3f;

  // Минимальное смещение между выборками — обрезает джиттер.
  private static final float MIN_MOVE_M = 0.05f;

  // Минимальная скорость чтобы считать «человек идёт». 0.25 м/с уверенно отличает
  // ходьбу от джиттера ARCore-трекинга.
  private static final float MIN_MOVE_SPEED_MPS = 0.25f;

  // Угловая скорость камеры для входа в scanning mode. 50°/с — типичный «оглядываюсь».
  private static final float SCAN_ANGULAR_VELOCITY_RADS = (float) Math.toRadians(50.0);

  // Сколько надо «затишья» чтобы выйти из scanning. 500 ms — отзывчиво и не дёргает.
  private static final long STABILITY_REQUIRED_NS = 500_000_000L;

  private static final int PRUNE_UNCONFIRMED_EVERY_FRAMES = 15;
  private static final int UNCONFIRMED_MAX_AGE_FRAMES = 15;
  private static final float UNCONFIRMED_MIN_WEIGHT = 2.0f;

  // ───── зависимости и состояние ─────

  private final Octomap octomap = new Octomap(CELL_SIZE_METERS, MAX_VOXELS);
  private final Vfh3DPlanner planner = new Vfh3DPlanner();

  // Reusable buffer для медианы 3×3. Не аллоцируем на каждый пиксель.
  private final int[] medianBuffer = new int[9];

  private Vfh3DPlanner.Decision lastDecision = null;
  private long lastPlannerTimestampNs = 0L;
  private long lastDecayFrame = 0L;
  private long lastCullFrame = 0L;
  private long integrationFrameCounter = 0L;
  private final float[] localPoint = new float[3];

  // ───── оценка движения ─────

  // Сглаженный вектор куда пользователь идёт (XZ-плоскость).
  private float smoothedMoveX = 0f;
  private float smoothedMoveZ = 0f;
  private float lastCamX = Float.NaN;
  private float lastCamZ = Float.NaN;
  private long lastMoveSampleNs = 0L;

  // ───── оценка сканирования ─────

  // Прошлый forward камеры — нужен для расчёта угловой скорости.
  private final float[] lastForwardWorld = new float[] {Float.NaN, 0f, 0f};
  private long lastAngVelSampleNs = 0L;
  private long stableSinceNs = 0L;
  private boolean isScanning = false;

  // Чтобы фразу «Сканирую» сказать ровно один раз за эпизод.
  private boolean scanAnnouncedThisEpisode = false;

  // Текущий адаптивный порог. Стартуем с BRIGHT, дальше AGC сам подкрутит.
  private float adaptiveMinConfidence = MIN_CONFIDENCE_BRIGHT;

  /** Совместимая перегрузка без confidence image. Confidence-фильтр тогда не активен. */
  public void process(Frame frame, Image depthImage) {
    process(frame, depthImage, null);
  }

  /**
   * Один кадр обработки. Делает по порядку:
   * 1) интеграцию depth (через кадр, чтобы не нагружать CPU);
   * 2) чистку карты (decay/cull);
   * 3) обновление вектора движения и сканирующего состояния;
   * 4) если прошло 250 ms — запуск planner'а.
   */
  public void process(Frame frame, Image depthImage, Image confidenceImage) {
    Camera camera = frame.getCamera();
    if (camera.getTrackingState() != TrackingState.TRACKING) {
      return; // ARCore потерял трекинг — не дёргаем карту
    }
    octomap.beginFrame();
    integrationFrameCounter++;

    if (!isScanning && integrationFrameCounter % INTEGRATE_EVERY_NTH_FRAME == 0) {
      float effectiveMinConfidence = computeEffectiveMinConfidence(frame);
      integrateDepth(frame, depthImage, confidenceImage, effectiveMinConfidence);
    }

    Pose pose = camera.getPose();
    long now = octomap.currentFrame();

    // 2) decay/cull — раз в секунду / раз в 2 секунды
    if (now - lastDecayFrame >= DECAY_INTERVAL_FRAMES) {
      octomap.decayStaleVoxels(DECAY_STALE_FRAMES, DECAY_AMOUNT);
      lastDecayFrame = now;
    }
    if (now - lastCullFrame >= CULL_INTERVAL_FRAMES) {
      octomap.cullFarVoxels(pose.tx(), pose.ty(), pose.tz(), CULL_RADIUS_M);
      lastCullFrame = now;
    }

    if (now % PRUNE_UNCONFIRMED_EVERY_FRAMES == 0) {
      octomap.pruneUnconfirmedVoxels(
              UNCONFIRMED_MAX_AGE_FRAMES,
              UNCONFIRMED_MIN_WEIGHT
      );
    }

    // 3) обновляем оценки движения и сканирования
    long nowNs = frame.getTimestamp();
    updateMovementEstimate(pose, nowNs);
    boolean scanningNow = updateScanningState(pose, nowNs);

    // 4) дросселим planner до 4 Гц
    if (nowNs == 0L
        || (lastPlannerTimestampNs != 0L
            && nowNs - lastPlannerTimestampNs < PLANNER_INTERVAL_NS)) {
      return;
    }
    lastPlannerTimestampNs = nowNs;

    // Если пользователь сейчас вертит камерой — молчим. Карта пока неточная,
    // не хочется советовать на лету.
    if (scanningNow) {
      if (!scanAnnouncedThisEpisode) {
        scanAnnouncedThisEpisode = true;
        // Один раз в эпизоде «Сканирую» — чтобы пользователь знал что мы живы.
        lastDecision =
            new Vfh3DPlanner.Decision(
                false, 0f, 0f, "Сканирую.", Depth20ScanResult.Severity.INFO);
      } else {
        lastDecision = Vfh3DPlanner.Decision.muted();
      }
      return;
    }
    scanAnnouncedThisEpisode = false;

    // Берём цель: либо реальный вектор движения (если идём), либо forward камеры.
    float[] forwardWorld = new float[3];
    pose.getTransformedAxis(2, -1.0f, forwardWorld, 0); // -Z = «куда смотрит камера»

    float targetX = forwardWorld[0];
    float targetZ = forwardWorld[2];
    float moveLen =
        (float) Math.sqrt(smoothedMoveX * smoothedMoveX + smoothedMoveZ * smoothedMoveZ);
    if (moveLen > 0.5f) {
      // Сглаженный вектор уверенный → берём его.
      targetX = smoothedMoveX / moveLen;
      targetZ = smoothedMoveZ / moveLen;
    }

    lastDecision =
        planner.plan(octomap, pose.tx(), pose.ty(), pose.tz(), targetX, targetZ);
  }



  private boolean isDepthLocallyConsistent(
          ByteBuffer buf,
          int x,
          int y,
          int width,
          int height,
          int rowStride,
          int pixelStride,
          int centerDepthMm) {

    int valid = 0;
    int consistent = 0;

    final int maxDiffMm = 250;

    for (int dy = -1; dy <= 1; dy++) {
      int yy = y + dy;
      if (yy < 0 || yy >= height) continue;

      for (int dx = -1; dx <= 1; dx++) {
        int xx = x + dx;
        if (xx < 0 || xx >= width) continue;
        if (dx == 0 && dy == 0) continue;

        int d = sampleDepthMm(buf, xx, yy, rowStride, pixelStride);
        if (d <= 0) continue;

        valid++;

        if (Math.abs(d - centerDepthMm) <= maxDiffMm) {
          consistent++;
        }
      }
    }

    if (valid < 2) {
      return false;
    }

    return consistent >= 2;
  }



  /**
   * Считаем угловую скорость forward-вектора между кадрами. Если выше порога — режим
   * scanning. Чтобы выйти из scanning'а, нужно «затишье» STABILITY_REQUIRED_NS подряд.
   */
  private boolean updateScanningState(Pose pose, long nowNs) {
    float[] forward = new float[3];
    pose.getTransformedAxis(2, -1.0f, forward, 0);

    // Первый вызов — просто запоминаем baseline и выходим.
    if (Float.isNaN(lastForwardWorld[0]) || lastAngVelSampleNs == 0L || nowNs == 0L) {
      lastForwardWorld[0] = forward[0];
      lastForwardWorld[1] = forward[1];
      lastForwardWorld[2] = forward[2];
      lastAngVelSampleNs = nowNs;
      stableSinceNs = nowNs;
      return false;
    }
    float dt = (nowNs - lastAngVelSampleNs) * 1e-9f;
    if (dt < 0.03f) {
      return isScanning; // выборка слишком частая, ждём
    }

    // Угол между двумя forward'ами через arccos скалярного произведения. Clamp на [-1,1]
    // чтобы acos не nan'ил из-за погрешностей.
    float dot =
        forward[0] * lastForwardWorld[0]
            + forward[1] * lastForwardWorld[1]
            + forward[2] * lastForwardWorld[2];
    if (dot > 1f) dot = 1f;
    if (dot < -1f) dot = -1f;
    float angleRad = (float) Math.acos(dot);
    float angularVelocity = angleRad / dt;

    lastForwardWorld[0] = forward[0];
    lastForwardWorld[1] = forward[1];
    lastForwardWorld[2] = forward[2];
    lastAngVelSampleNs = nowNs;

    if (angularVelocity > SCAN_ANGULAR_VELOCITY_RADS) {
      isScanning = true;
      stableSinceNs = 0L; // эту тишину начинаем заново
    } else {
      // Камера спокойна. Засекаем затишье и ждём STABILITY_REQUIRED_NS.
      if (stableSinceNs == 0L) stableSinceNs = nowNs;
      if (isScanning && nowNs - stableSinceNs >= STABILITY_REQUIRED_NS) {
        isScanning = false;
      }
    }
    return isScanning;
  }

  /**
   * Эффективный порог уверенности для пикселей. Складывается из двух источников:
   * <ol>
   *   <li><b>AGC по числу вокселей</b> — основной regulator. Если карта пустая → порог
   *       снижается, больше пикселей проходит. Если насыщена → растёт.</li>
   *   <li><b>Поправка на темноту</b> — в тёмной сцене ARCore сам по себе менее надёжен,
   *       добавляем небольшой запас сверху.</li>
   * </ol>
   */
  private float computeEffectiveMinConfidence(Frame frame) {
    // 1) Двигаем adaptiveMinConfidence в зависимости от того, сколько у нас вокселей.
    int voxelCount = octomap.size();
    if (voxelCount < TARGET_VOXEL_COUNT_LOW) {
      // Карта бедная — снижаем планку. Воксели бедны на текстурированных сценах,
      // тут и нужно «доверять» более слабым пикселям.
      adaptiveMinConfidence =
          Math.max(ADAPTIVE_CONF_MIN, adaptiveMinConfidence - ADAPTIVE_CONF_STEP);
    } else if (voxelCount > TARGET_VOXEL_COUNT_HIGH) {
      // Карта забита — поднимаем планку, отсекаем лишний шум.
      adaptiveMinConfidence =
          Math.min(ADAPTIVE_CONF_MAX, adaptiveMinConfidence + ADAPTIVE_CONF_STEP);
    }
    // Между LOW и HIGH — оставляем как есть (нет смысла дёргать).

    float base = adaptiveMinConfidence;

    // 2) Доп. поправка на темноту. В очень тёмной сцене (intensity < 0.2) добавляем
    // запас, потому что ARCore раздаёт более низкие confidence даже для надёжных точек.
    LightEstimate le = frame.getLightEstimate();
    if (le != null && le.getState() == LightEstimate.State.VALID) {
      float intensity = le.getPixelIntensity();
      if (intensity < 0.2f) {
        // 0.10 → 0 при intensity → 0.2.
        float darkPenalty = 0.10f * Math.max(0f, 1f - intensity / 0.2f);
        base += darkPenalty;
      }
    }
    return Math.min(ADAPTIVE_CONF_MAX, base);
  }

  /** Текущее значение динамического порога — для diagnostic overlay'я. */
  public float getAdaptiveMinConfidence() {
    return adaptiveMinConfidence;
  }

  /**
   * Обновляем сглаженный вектор движения. Разница позиций → нормализованный шаг → EMA.
   * Если стоит — постепенно затухаем.
   */
  private void updateMovementEstimate(Pose pose, long nowNs) {
    float curX = pose.tx();
    float curZ = pose.tz();

    if (Float.isNaN(lastCamX) || lastMoveSampleNs == 0L || nowNs == 0L) {
      lastCamX = curX;
      lastCamZ = curZ;
      lastMoveSampleNs = nowNs;
      return;
    }
    float dt = (nowNs - lastMoveSampleNs) * 1e-9f;
    if (dt < 0.05f) return;

    float dx = curX - lastCamX;
    float dz = curZ - lastCamZ;
    float dist = (float) Math.sqrt(dx * dx + dz * dz);
    float speed = dist / dt;

    if (dist >= MIN_MOVE_M && speed >= MIN_MOVE_SPEED_MPS) {
      // Реально идёт — обновляем EMA.
      float nx = dx / dist;
      float nz = dz / dist;
      smoothedMoveX = MOVE_SMOOTH_ALPHA * nx + (1f - MOVE_SMOOTH_ALPHA) * smoothedMoveX;
      smoothedMoveZ = MOVE_SMOOTH_ALPHA * nz + (1f - MOVE_SMOOTH_ALPHA) * smoothedMoveZ;
    } else {
      // Стоит — затухание. Через ~10 сек вектор почти ноль.
      smoothedMoveX *= 0.92f;
      smoothedMoveZ *= 0.92f;
    }
    lastCamX = curX;
    lastCamZ = curZ;
    lastMoveSampleNs = nowNs;
  }

  /**
   * Один проход по depth-карте. Для каждого пикселя:
   * 1) медианой 3×3 чистим шум;
   * 2) проверяем confidence;
   * 3) разворачиваем в мировые координаты через pinhole-модель + camera pose;
   * 4) ray-cast от камеры до точки попадания (decrement промежуточных ячеек);
   * 5) integrateHit на endpoint.
   */
  private void integrateDepth(
      Frame frame, Image depthImage, Image confidenceImage, float minConfidence) {
    Camera camera = frame.getCamera();

    // Берём intrinsics и масштабируем под разрешение depth-карты (она обычно меньше
    // цветной картинки).
    CameraIntrinsics intrinsics = camera.getImageIntrinsics();
    float[] focal = intrinsics.getFocalLength();
    float[] principal = intrinsics.getPrincipalPoint();
    int[] dims = intrinsics.getImageDimensions();
    int imgW = dims[0];
    int imgH = dims[1];
    if (imgW <= 0 || imgH <= 0) return;
    int dW = depthImage.getWidth();
    int dH = depthImage.getHeight();
    float fx = focal[0] * ((float) dW / imgW);
    float fy = focal[1] * ((float) dH / imgH);
    float cx = principal[0] * ((float) dW / imgW);
    float cy = principal[1] * ((float) dH / imgH);

    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer buf = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int pixelStride = plane.getPixelStride();

    // Confidence-картинка опциональна.
    ByteBuffer confBuf = null;
    int confW = 0, confH = 0, confRowStride = 0, confPixelStride = 0;
    if (confidenceImage != null) {
      Image.Plane confPlane = confidenceImage.getPlanes()[0];
      confBuf = confPlane.getBuffer();
      confW = confidenceImage.getWidth();
      confH = confidenceImage.getHeight();
      confRowStride = confPlane.getRowStride();
      confPixelStride = confPlane.getPixelStride();
    }

    Pose pose = camera.getPose();

    // Главный цикл — пробегаем пиксели через step.
    for (int y = 0; y < dH; y += DEPTH_PIXEL_STEP) {
      for (int x = 0; x < dW; x += DEPTH_PIXEL_STEP) {
        // 1) медиана 3×3.
        int depthMm = sampleDepthMm(buf, x, y, rowStride, pixelStride);
        if (depthMm <= 0) continue;

        if (!isDepthLocallyConsistent(buf, x, y, dW, dH, rowStride, pixelStride, depthMm)) continue;

        float d = depthMm * 0.001f;
        if (d < MIN_VALID_DEPTH || d > MAX_VALID_DEPTH) continue;

        // 2) confidence фильтр.
        float pixelConfidence = 1.0f;
        if (confBuf != null) {
          int confX = (int) ((long) x * confW / dW);
          int confY = (int) ((long) y * confH / dH);
          int confOffset = confY * confRowStride + confX * confPixelStride;
          if (confOffset >= 0 && confOffset < confBuf.limit()) {
            pixelConfidence = (confBuf.get(confOffset) & 0xFF) / 255.0f;
          }
        }

        // Абсолютный нижний порог оставляем только против полного мусора.
        if (pixelConfidence < 0.02f) {
          continue;
        }

        // 3) Pinhole unprojection: пиксель + глубина → точка в локальных координатах
        // камеры. ARCore-конвенция: +X right, +Y up, -Z forward → флипаем Y и Z.
        float optX = (x - cx) * d / fx;
        float optY = (y - cy) * d / fy;
        localPoint[0] = optX;
        localPoint[1] = -optY;
        localPoint[2] = -d;
        // Локальная → мировая через pose камеры.
        float[] world = pose.transformPoint(localPoint);

        boolean reachedEndpoint =
                rayCastDecrement(pose.tx(), pose.ty(), pose.tz(), world[0], world[1], world[2]);

        if (reachedEndpoint) {
          octomap.integrateHit(world[0], world[1], world[2], pixelConfidence);
        }
      }
    }
  }
  private int sampleDepthMm(
          ByteBuffer buf, int x, int y, int rowStride, int pixelStride) {
    int off = y * rowStride + x * pixelStride;
    if (off < 0 || off + 1 >= buf.limit()) {
      return 0;
    }
    return (buf.get(off) & 0xFF) | ((buf.get(off + 1) & 0xFF) << 8);
  }
  /**
   * Идём по лучу от (sx,sy,sz) к (ex,ey,ez), пошагово через ячейки воксельной сетки.
   * Все промежуточные ячейки decrement'им (видели сквозь — значит пусто). Конечную
   * ячейку не трогаем (её инкрементит integrateHit отдельно).
   *
   * <p>Это стандартный 3D-DDA (digital differential analyzer) от Amanatides & Woo.
   * Идея: на каждом шаге выбираем ось (X, Y или Z), на которой ближайшая граница ячейки
   * дальше от текущей точки, и шагаем по ней.
   *
   * <p>MAX_RAYCAST_STEPS — страховка от бесконечного цикла из-за float-погрешностей.
   *
   * <p>Если на пути встретился подтверждённый воксель (hits ≥ 4) — стоп. Стену не
   * прорываем шумным лучом.
   */
  private boolean rayCastDecrement(float sx, float sy, float sz, float ex, float ey, float ez) {
    float cellSize = octomap.cellSizeMeters;

    int curX = (int) Math.floor(sx / cellSize);
    int curY = (int) Math.floor(sy / cellSize);
    int curZ = (int) Math.floor(sz / cellSize);
    int endX = (int) Math.floor(ex / cellSize);
    int endY = (int) Math.floor(ey / cellSize);
    int endZ = (int) Math.floor(ez / cellSize);

    if (curX == endX && curY == endY && curZ == endZ) return true; // в одной ячейке, идти некуда

    float dx = ex - sx;
    float dy = ey - sy;
    float dz = ez - sz;

    int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
    int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
    int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

    // tDelta — насколько по лучу пройти чтобы пересечь одну ячейку по оси.
    float tDeltaX = stepX != 0 ? Math.abs(cellSize / dx) : Float.POSITIVE_INFINITY;
    float tDeltaY = stepY != 0 ? Math.abs(cellSize / dy) : Float.POSITIVE_INFINITY;
    float tDeltaZ = stepZ != 0 ? Math.abs(cellSize / dz) : Float.POSITIVE_INFINITY;

    // tMax — сколько до ПЕРВОЙ границы по каждой оси.
    float voxelBoundaryX = (stepX > 0 ? curX + 1 : curX) * cellSize;
    float voxelBoundaryY = (stepY > 0 ? curY + 1 : curY) * cellSize;
    float voxelBoundaryZ = (stepZ > 0 ? curZ + 1 : curZ) * cellSize;
    float tMaxX =
        stepX != 0 ? (voxelBoundaryX - sx) / dx : Float.POSITIVE_INFINITY;
    float tMaxY =
        stepY != 0 ? (voxelBoundaryY - sy) / dy : Float.POSITIVE_INFINITY;
    float tMaxZ =
        stepZ != 0 ? (voxelBoundaryZ - sz) / dz : Float.POSITIVE_INFINITY;

    for (int step = 0; step < MAX_RAYCAST_STEPS; step++) {
      // Шагаем по оси с минимальным tMax — там ближайшая граница.
      if (tMaxX < tMaxY && tMaxX < tMaxZ) {
        curX += stepX;
        tMaxX += tDeltaX;
      } else if (tMaxY < tMaxZ) {
        curY += stepY;
        tMaxY += tDeltaY;
      } else {
        curZ += stepZ;
        tMaxZ += tDeltaZ;
      }
      if (curX == endX && curY == endY && curZ == endZ) return true; // дошли до endpoint'а
      // decrement промежуточной ячейки. Если там подтверждённое препятствие — стоп.
      if (octomap.integrateMissCell(curX, curY, curZ)) return false;
    }
    return false;
  }

  /**
   * Медиана 9-окрестности (3×3) центрального пикселя. Если валидных соседей < 5 — возвращает 0.
   * Сортировка insertion-sort, она быстрее всего на массивах меньше 16 элементов.
   *
   * <p>Зачем нужно: depth-карта ARCore имеет одиночные «вылетевшие» пиксели (блики, края
   * объектов). Один аутлайер не сдвигает медиану.
   */
  private int sampleMedianDepthMm(
      ByteBuffer buf, int cx, int cy, int w, int h, int rowStride, int pixelStride) {
    int count = 0;
    for (int dy = -1; dy <= 1; dy++) {
      int yy = cy + dy;
      if (yy < 0 || yy >= h) continue;
      int rowBase = yy * rowStride;
      for (int dx = -1; dx <= 1; dx++) {
        int xx = cx + dx;
        if (xx < 0 || xx >= w) continue;
        int off = rowBase + xx * pixelStride;
        if (off + 1 >= buf.limit()) continue;
        int v = (buf.get(off) & 0xFF) | ((buf.get(off + 1) & 0xFF) << 8);
        if (v <= 0) continue;
        medianBuffer[count++] = v;
      }
    }
    if (count < 5) return 0; // мало данных — недоверяем
    for (int i = 1; i < count; i++) {
      int v = medianBuffer[i];
      int j = i - 1;
      while (j >= 0 && medianBuffer[j] > v) {
        medianBuffer[j + 1] = medianBuffer[j];
        j--;
      }
      medianBuffer[j + 1] = v;
    }
    return medianBuffer[count / 2];
  }

  // ───── public API ─────

  public Octomap getOctomap() {
    return octomap;
  }

  /** Используется diagnostic overlay'ем. */
  public boolean isScanning() {
    return isScanning;
  }

  /** Готовый результат для дирижёра речи. Если решения нет — пустой CLEAR. */
  public Depth20ScanResult buildScanResult() {
    if (lastDecision == null) {
      return new Depth20ScanResult(
          new float[0], new float[0], new boolean[0], "", Depth20ScanResult.Severity.CLEAR, 0f);
    }
    return new Depth20ScanResult(
        new float[0],
        new float[0],
        new boolean[0],
        lastDecision.message,
        lastDecision.severity,
        0f);
  }

  /** Полный сброс — карта, planner, оценки. Вызывается при смене режима. */
  public void reset() {
    octomap.clear();
    planner.reset();
    lastDecision = null;
    lastPlannerTimestampNs = 0L;
    lastDecayFrame = 0L;
    lastCullFrame = 0L;
    smoothedMoveX = 0f;
    smoothedMoveZ = 0f;
    lastCamX = Float.NaN;
    lastCamZ = Float.NaN;
    lastMoveSampleNs = 0L;
    lastForwardWorld[0] = Float.NaN;
    lastAngVelSampleNs = 0L;
    stableSinceNs = 0L;
    isScanning = false;
    scanAnnouncedThisEpisode = false;
    integrationFrameCounter = 0L;
    adaptiveMinConfidence = MIN_CONFIDENCE_BRIGHT;
  }
}
