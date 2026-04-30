/*
 * Анализатор препятствий пола — для режима FLOOR_HEIGHTMAP.
 *
 * Берёт узкую полоску depth-карты прямо перед пользователем (центр-низ экрана),
 * раскладывает точки в мировые координаты и группирует их по горизонтальному
 * расстоянию вперёд. У каждой группы (бина) считаем среднюю высоту относительно
 * пола (берём из ARCore Plane). Дальше по паттерну изменения высоты классифицируем
 * сцену:
 *
 *   – свободный путь
 *   – бордюр / порог
 *   – высокая ступенька
 *   – лестница вверх
 *   – лестница вниз
 *   – обрыв впереди
 *
 * Результат — короткая русская фраза для overlay'а и для дирижёра речи.
 *
 * Класс не потокобезопасный.
 */
package com.google.ar.core.examples.java.helloar.floormap;

import android.media.Image;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class FloorHazardModule {
  public enum Severity {
    NONE,
    INFO,
    WARNING,
    DANGER
  }

  public static final class Result {
    public static final Result NONE = new Result(null, Severity.NONE);

    public final String message;
    public final Severity severity;

    public Result(String message, Severity severity) {
      this.message = message;
      this.severity = severity;
    }
  }

  // Бины горизонтального расстояния (метры). Считаются ВДОЛЬ forward'а камеры в XZ
  // плоскости — поэтому показания стабильны независимо от того, как наклонён телефон.
  private static final float[] BIN_UPPER_M = {0.6f, 1.0f, 1.5f, 2.2f, 3.0f};

  // Сетка сэмплинга в координатах VIEW_NORMALIZED (экранных, не depth). Покрываем
  // центр-низ экрана — там обычно пол перед пользователем.
  private static final int SAMPLE_COLS = 5;
  private static final int SAMPLE_ROWS = 50;
  private static final int NUM_SAMPLES = SAMPLE_COLS * SAMPLE_ROWS;
  private static final float VIEW_COL_MIN = 0.36f;
  private static final float VIEW_COL_MAX = 0.64f;
  private static final float VIEW_ROW_MIN = 0.45f;
  private static final float VIEW_ROW_MAX = 0.98f;

  // Допустимый диапазон depth.
  private static final float MIN_VALID_DEPTH_M = 0.25f;
  private static final float MAX_VALID_DEPTH_M = 5.0f;

  // Если точка выше пола больше чем на 35 см — это явно стена/мебель, не ступенька.
  // Игнорируем такие точки, иначе они тянут среднее бина вверх.
  private static final float MAX_Y_ABOVE_FLOOR_M = 0.35f;

  // Если точки внутри одного бина имеют разброс > 9 см — это вертикальная поверхность
  // (стена/дверь/бок мебели), а не плоская ступень. Бин отбрасываем.
  private static final float MAX_BIN_Y_SPREAD_M = 0.09f;

  // Если в общем фане большинство точек явно выше пола — мы смотрим в стену, а не на пол.
  // Подавляем классификацию полностью (молчим).
  private static final float WALL_DOMINATED_ABOVE_FLOOR_M = 0.10f;
  private static final float WALL_DOMINATED_FRACTION = 0.45f;

  // Минимум точек в бине чтобы считать его валидным. Меньше — слишком ненадёжно, отбросим.
  private static final int MIN_SAMPLES_PER_BIN = 10;
  private static final int MAX_SAMPLES_PER_BIN = 400;

  // Голосование во времени: храним последние N классификаций и подтверждаем только то
  // что встретилось в ≥ HISTORY_MIN_AGREEMENT кадров. Так одиночный шумной кадр не
  // прорывается на экран.
  private static final int HISTORY_SIZE = 10;
  private static final int HISTORY_MIN_AGREEMENT = 6;

  private final float[][] binSamples = new float[BIN_UPPER_M.length][MAX_SAMPLES_PER_BIN];
  private final int[] binCount = new int[BIN_UPPER_M.length];
  private final float[] localPoint = new float[3];

  // Precomputed sampling grid in VIEW_NORMALIZED, transformed to TEXTURE_NORMALIZED each frame.
  private final float[] sampleViewCoords = new float[NUM_SAMPLES * 2];
  private final float[] sampleTextureCoords = new float[NUM_SAMPLES * 2];

  public FloorHazardModule() {
    int idx = 0;
    for (int r = 0; r < SAMPLE_ROWS; ++r) {
      float v =
          VIEW_ROW_MIN
              + (SAMPLE_ROWS == 1 ? 0f : r * (VIEW_ROW_MAX - VIEW_ROW_MIN) / (SAMPLE_ROWS - 1));
      for (int c = 0; c < SAMPLE_COLS; ++c) {
        float u =
            VIEW_COL_MIN
                + (SAMPLE_COLS == 1 ? 0f : c * (VIEW_COL_MAX - VIEW_COL_MIN) / (SAMPLE_COLS - 1));
        sampleViewCoords[idx++] = u;
        sampleViewCoords[idx++] = v;
      }
    }
  }

  private final String[] recentMessages = new String[HISTORY_SIZE];
  private final Severity[] recentSeverities = new Severity[HISTORY_SIZE];
  private int recentIndex = 0;
  private int recentFilled = 0;
  private Result lastConfirmed = Result.NONE;

  public Result analyze(Frame frame, Image depthImage, Collection<Plane> planes) {
    if (frame == null || depthImage == null) {
      return Result.NONE;
    }
    Camera camera = frame.getCamera();
    if (camera.getTrackingState() != TrackingState.TRACKING) {
      return Result.NONE;
    }

    Float floorYBoxed = findLowestHorizontalPlaneY(planes);
    if (floorYBoxed == null) {
      // Don't flash a "searching" message — it's noisy. Just suppress until a floor plane
      // is actually tracked.
      return voteAndConfirm(Result.NONE);
    }
    float floorY = floorYBoxed;

    Pose cameraPose = camera.getPose();
    float camX = cameraPose.tx();
    float camZ = cameraPose.tz();

    // Forward direction projected into the world horizontal plane (ignore tilt).
    float[] forward = new float[3];
    cameraPose.getTransformedAxis(2, -1.0f, forward, 0); // -Z is camera forward
    float fxw = forward[0];
    float fzw = forward[2];
    float fLen = (float) Math.sqrt(fxw * fxw + fzw * fzw);
    if (fLen < 0.05f) {
      // Phone pointed straight up or down — forward direction undefined in the floor plane.
      return voteAndConfirm(Result.NONE);
    }
    fxw /= fLen;
    fzw /= fLen;

    CameraIntrinsics intrinsics = camera.getImageIntrinsics();
    float[] focal = intrinsics.getFocalLength();
    float[] principal = intrinsics.getPrincipalPoint();
    int[] imageDims = intrinsics.getImageDimensions();
    int imgW = imageDims[0];
    int imgH = imageDims[1];
    int depthW = depthImage.getWidth();
    int depthH = depthImage.getHeight();
    float fx = focal[0] * ((float) depthW / imgW);
    float fy = focal[1] * ((float) depthH / imgH);
    float cx = principal[0] * ((float) depthW / imgW);
    float cy = principal[1] * ((float) depthH / imgH);

    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer buffer = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int pixelStride = plane.getPixelStride();

    for (int i = 0; i < BIN_UPPER_M.length; ++i) {
      binCount[i] = 0;
    }

    // Map each VIEW_NORMALIZED sample into the depth texture so the fan always covers the
    // bottom-centre of the visible screen no matter how the phone is rotated.
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        sampleViewCoords,
        Coordinates2d.TEXTURE_NORMALIZED,
        sampleTextureCoords);

    for (int i = 0; i < NUM_SAMPLES; ++i) {
      float tx = sampleTextureCoords[i * 2];
      float ty = sampleTextureCoords[i * 2 + 1];
      if (tx < 0f || tx > 1f || ty < 0f || ty > 1f) {
        continue;
      }
      int col = (int) (tx * depthW);
      int row = (int) (ty * depthH);
      if (col < 0) col = 0;
      if (col >= depthW) col = depthW - 1;
      if (row < 0) row = 0;
      if (row >= depthH) row = depthH - 1;

      int byteOffset = row * rowStride + col * pixelStride;
      if (byteOffset + 1 >= buffer.limit()) {
        continue;
      }
      int depthMm =
          (buffer.get(byteOffset) & 0xFF) | ((buffer.get(byteOffset + 1) & 0xFF) << 8);
      if (depthMm == 0) {
        continue;
      }
      float depth = depthMm * 0.001f;
      if (depth < MIN_VALID_DEPTH_M || depth > MAX_VALID_DEPTH_M) {
        continue;
      }

      float camOpticalX = (col - cx) * depth / fx;
      float camOpticalY = (row - cy) * depth / fy;
      localPoint[0] = camOpticalX;
      localPoint[1] = -camOpticalY;
      localPoint[2] = -depth;
      float[] world = cameraPose.transformPoint(localPoint);

      float relX = world[0] - camX;
      float relZ = world[2] - camZ;
      float horizDist = relX * fxw + relZ * fzw;
      if (horizDist < 0.2f) {
        continue;
      }

      float dY = world[1] - floorY;
      if (dY > MAX_Y_ABOVE_FLOOR_M || dY < -1.0f) {
        continue;
      }

      int binIdx = -1;
      for (int j = 0; j < BIN_UPPER_M.length; ++j) {
        if (horizDist < BIN_UPPER_M[j]) {
          binIdx = j;
          break;
        }
      }
      if (binIdx < 0) {
        continue;
      }
      int c = binCount[binIdx];
      if (c < MAX_SAMPLES_PER_BIN) {
        binSamples[binIdx][c] = world[1];
        binCount[binIdx] = c + 1;
      }
    }

    // Global wall check: if most samples across the whole forward fan are clearly above the
    // floor, the camera is aimed at a wall, not at the floor ahead — skip classification.
    int totalSamples = 0;
    int aboveFloorSamples = 0;
    for (int i = 0; i < BIN_UPPER_M.length; ++i) {
      int n = binCount[i];
      totalSamples += n;
      for (int k = 0; k < n; ++k) {
        if (binSamples[i][k] - floorY > WALL_DOMINATED_ABOVE_FLOOR_M) {
          aboveFloorSamples++;
        }
      }
    }
    if (totalSamples >= 30
        && aboveFloorSamples > totalSamples * WALL_DOMINATED_FRACTION) {
      return voteAndConfirm(Result.NONE);
    }

    // Compute per-bin statistics using a trimmed central window. A bin is only considered valid
    // if it has enough samples AND they cluster tightly in Y (i.e. lie on a roughly horizontal
    // surface). Wide vertical spread inside a bin means we're seeing a wall / riser / doorframe
    // rather than a flat step.
    float[] binDY = new float[BIN_UPPER_M.length];
    boolean[] binValid = new boolean[BIN_UPPER_M.length];
    int firstValid = -1;
    int lastValid = -1;
    for (int i = 0; i < BIN_UPPER_M.length; ++i) {
      int n = binCount[i];
      if (n < MIN_SAMPLES_PER_BIN) {
        continue;
      }
      Arrays.sort(binSamples[i], 0, n);
      int trimLo = n * 2 / 10;
      int trimHi = n - trimLo;
      if (trimHi <= trimLo + 2) {
        trimLo = 0;
        trimHi = n;
      }
      float trimmedMinY = binSamples[i][trimLo];
      float trimmedMaxY = binSamples[i][trimHi - 1];
      if (trimmedMaxY - trimmedMinY > MAX_BIN_Y_SPREAD_M) {
        // Samples span too wide a vertical range to belong to a single flat surface.
        continue;
      }
      float sum = 0f;
      for (int k = trimLo; k < trimHi; ++k) {
        sum += binSamples[i][k];
      }
      float meanY = sum / (trimHi - trimLo);
      binDY[i] = meanY - floorY;
      binValid[i] = true;
      if (firstValid < 0) firstValid = i;
      lastValid = i;
    }

    if (firstValid < 0) {
      return voteAndConfirm(Result.NONE);
    }

    Result current = classify(binDY, binValid, firstValid, lastValid);
    return voteAndConfirm(current);
  }

  private Result classify(float[] binDY, boolean[] binValid, int firstValid, int lastValid) {
    // 1. Cliff / big drop — only from explicit depth readings (removed the noisy
    // "missing-depth means cliff" heuristic entirely, since glossy/low-texture floors trigger it
    // constantly). Requires dY ≤ -30 cm so normal steps don't get classified as cliffs.
    for (int i = firstValid; i <= lastValid; ++i) {
      if (binValid[i] && binDY[i] <= -0.30f) {
        return new Result("Обрыв впереди", Severity.DANGER);
      }
    }

    // 2. Stairs — require ≥3 consecutive monotonic bins, each changing by ≥10 cm.
    int ascendingRun = 0;
    int ascendingMax = 0;
    int descendingRun = 0;
    int descendingMax = 0;
    for (int i = firstValid + 1; i <= lastValid; ++i) {
      if (!binValid[i] || !binValid[i - 1]) {
        ascendingRun = 0;
        descendingRun = 0;
        continue;
      }
      float delta = binDY[i] - binDY[i - 1];
      if (delta >= 0.10f) {
        ascendingRun++;
        descendingRun = 0;
        if (ascendingRun > ascendingMax) ascendingMax = ascendingRun;
      } else if (delta <= -0.10f) {
        descendingRun++;
        ascendingRun = 0;
        if (descendingRun > descendingMax) descendingMax = descendingRun;
      } else {
        ascendingRun = 0;
        descendingRun = 0;
      }
    }
    if (ascendingMax >= 2 && ascendingMax > descendingMax) {
      // ascendingMax counts transitions, so ≥2 means ≥3 bins.
      return new Result("Лестница вверх", Severity.WARNING);
    }
    if (descendingMax >= 2 && descendingMax > ascendingMax) {
      return new Result("Лестница вниз", Severity.WARNING);
    }

    // 3. Single-step cases based on the bin slightly ahead of the feet.
    int probeIdx = firstValid;
    if (firstValid + 1 <= lastValid && binValid[firstValid + 1]) {
      probeIdx = firstValid + 1;
    }
    float dY = binDY[probeIdx];

    if (dY <= -0.15f) {
      return new Result("Ступень вниз", Severity.WARNING);
    }
    if (dY >= 0.20f) {
      return new Result("Высокая ступенька", Severity.WARNING);
    }
    if (dY >= 0.08f) {
      return new Result("Порог / бордюр", Severity.INFO);
    }
    return Result.NONE;
  }

  /**
   * Append {@code candidate} to the rolling history and return the currently confirmed result.
   * A classification is only confirmed when it appears in at least {@link #HISTORY_MIN_AGREEMENT}
   * of the last {@link #HISTORY_SIZE} observations. Until then we keep showing the previous
   * confirmed result so quick noisy detections don't flash on screen.
   */
  private Result voteAndConfirm(Result candidate) {
    String key = candidate.message;
    recentMessages[recentIndex] = key;
    recentSeverities[recentIndex] = candidate.severity;
    recentIndex = (recentIndex + 1) % HISTORY_SIZE;
    if (recentFilled < HISTORY_SIZE) {
      recentFilled++;
    }

    // Don't confirm anything until we have enough observations.
    if (recentFilled < HISTORY_MIN_AGREEMENT) {
      return lastConfirmed;
    }

    // Count occurrences of each message in the window.
    Map<String, Integer> counts = new HashMap<>();
    int nullCount = 0;
    for (int i = 0; i < recentFilled; ++i) {
      String m = recentMessages[i];
      if (m == null) {
        nullCount++;
      } else {
        counts.merge(m, 1, Integer::sum);
      }
    }

    // Find the best-supported non-null message.
    String bestMessage = null;
    int bestCount = 0;
    Severity bestSeverity = Severity.NONE;
    for (int i = 0; i < recentFilled; ++i) {
      String m = recentMessages[i];
      if (m == null) continue;
      int c = counts.get(m);
      if (c > bestCount) {
        bestCount = c;
        bestMessage = m;
        bestSeverity = recentSeverities[i];
      }
    }

    if (bestCount >= HISTORY_MIN_AGREEMENT) {
      lastConfirmed = new Result(bestMessage, bestSeverity);
    } else if (nullCount >= HISTORY_MIN_AGREEMENT) {
      // Majority "nothing to report" → clear the label.
      lastConfirmed = Result.NONE;
    }
    // Else: keep the previous confirmed result (hysteresis: hazard stays on screen briefly
    // even after the raw classifier becomes noisy).
    return lastConfirmed;
  }

  /** Call when the mode is switched off so the next activation starts clean. */
  public void reset() {
    for (int i = 0; i < HISTORY_SIZE; ++i) {
      recentMessages[i] = null;
      recentSeverities[i] = null;
    }
    recentIndex = 0;
    recentFilled = 0;
    lastConfirmed = Result.NONE;
  }

  private Float findLowestHorizontalPlaneY(Collection<Plane> planes) {
    if (planes == null) {
      return null;
    }
    Float best = null;
    for (Plane plane : planes) {
      if (plane.getTrackingState() != TrackingState.TRACKING || plane.getSubsumedBy() != null) {
        continue;
      }
      if (plane.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) {
        continue;
      }
      float y = plane.getCenterPose().ty();
      if (best == null || y < best) {
        best = y;
      }
    }
    return best;
  }
}
