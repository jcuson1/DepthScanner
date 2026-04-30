package com.google.ar.core.examples.java.helloar.depth20;

import android.graphics.PointF;
import android.media.Image;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Простой режим «20 точек». На экране 20 кружков в фиксированных местах, в каждом
 * показывается дистанция от depth-карты ARCore. Если что-то ближе порога — кружок
 * становится красным, и алгоритм решает что говорить голосом.
 *
 * <p>Это самый ранний наш режим, до smart-23pt и octomap. Логика простая — пороги
 * жёсткие, никакого сглаживания между кадрами. Зато работает быстро и предсказуемо.
 *
 * <p>Раскладка точек по эскизу: 4 + 3 + 3 + 5 + 5 = 20 точек.
 */
public class Depth20PointModule {

  // Не доверяем дистанциям больше 20 м — depth там и так шумный.
  private static final float MAX_VALID_DEPTH_METERS = 20.0f;

  // Пороги «препятствие» по разным частям экрана:
  // Если центральная точка ближе FRONT — спереди что-то стоит.
  private static final float FRONT_BLOCK_METERS = 1.45f;
  // Боковые точки — порог пониже (по бокам можно быть впритык).
  private static final float SIDE_BLOCK_METERS = 1.10f;
  // Верхние точки — препятствие на уровне головы.
  private static final float HEAD_BLOCK_METERS = 1.25f;
  // Аварийный стоп — если центральная точка совсем близко.
  private static final float EMERGENCY_STOP_METERS = 0.85f;

  // Готовые фразы. Дирижёр их кладёт в TTS как есть.
  private static final String MESSAGE_CLEAR = "Путь свободен.";
  private static final String MESSAGE_STOP = "Стоп. Препятствие впереди.";
  private static final String MESSAGE_LEFT = "Препятствие впереди, обходите слева.";
  private static final String MESSAGE_RIGHT = "Препятствие впереди, обходите справа.";
  private static final String MESSAGE_HEAD = "Осторожно, препятствие сверху по центру.";
  private static final String MESSAGE_SIDE_LEFT = "Слева препятствие.";
  private static final String MESSAGE_SIDE_RIGHT = "Справа препятствие.";

  // Координаты 20 точек в пространстве экрана (0..1, 0..1). Подобраны по эскизу.
  // Верхний ряд (4 точки): уровень головы.
  // Второй ряд (3): уровень плеч.
  // Третий ряд (3): уровень груди.
  // Четвёртый и пятый (по 5): пояс и пол.
  private static final PointF[] DEPTH20_POINTS = {
    new PointF(0.08f, 0.08f),
    new PointF(0.40f, 0.08f),
    new PointF(0.60f, 0.08f),
    new PointF(0.92f, 0.08f),
    new PointF(0.08f, 0.20f),
    new PointF(0.50f, 0.20f),
    new PointF(0.92f, 0.20f),
    new PointF(0.08f, 0.46f),
    new PointF(0.50f, 0.46f),
    new PointF(0.92f, 0.46f),
    new PointF(0.08f, 0.74f),
    new PointF(0.40f, 0.74f),
    new PointF(0.50f, 0.74f),
    new PointF(0.60f, 0.74f),
    new PointF(0.92f, 0.74f),
    new PointF(0.08f, 0.83f),
    new PointF(0.40f, 0.83f),
    new PointF(0.50f, 0.83f),
    new PointF(0.60f, 0.83f),
    new PointF(0.92f, 0.83f)
  };

  // Reusable массивы — чтобы не аллоцировать каждый кадр.
  private final float[] textureCoords = createViewNormalizedPoints();
  private final float[] distancesMeters = new float[DEPTH20_POINTS.length];
  private final float[] confidenceValues = new float[DEPTH20_POINTS.length];
  private final boolean[] blockedPoints = new boolean[DEPTH20_POINTS.length];
  private final float[] viewNormalizedPoints = createViewNormalizedPoints();

  /** Версия без confidence-картинки. */
  public Depth20ScanResult process(Frame frame, Image depthImage) {
    return process(frame, depthImage, null);
  }

  /**
   * Главный метод. Сэмплит depth в каждой из 20 точек, считает blocked флаги, выбирает
   * команду движения и собирает Depth20ScanResult для дирижёра.
   */
  public Depth20ScanResult process(Frame frame, Image depthImage, Image confidenceImage) {
    // Из VIEW_NORMALIZED (где у нас экран 0..1) переводим в TEXTURE_NORMALIZED depth-карты.
    // ARCore сам учитывает поворот экрана — нам не надо ничего ручно править.
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewNormalizedPoints,
        Coordinates2d.TEXTURE_NORMALIZED,
        textureCoords);

    float closestDistanceMeters = Float.MAX_VALUE;
    for (int i = 0; i < DEPTH20_POINTS.length; i++) {
      // Берём depth в окрестности точки (не один пиксель, а 3×3 — устойчивее).
      float depthMeters =
          sampleDepthMeters(depthImage, textureCoords[i * 2], textureCoords[i * 2 + 1]);
      float confidence =
          sampleConfidence(confidenceImage, textureCoords[i * 2], textureCoords[i * 2 + 1]);
      distancesMeters[i] = depthMeters;
      confidenceValues[i] = confidence;
      blockedPoints[i] = isBlockedPoint(i, depthMeters);
      if (depthMeters > 0.0f && depthMeters < closestDistanceMeters) {
        closestDistanceMeters = depthMeters;
      }
    }

    if (closestDistanceMeters == Float.MAX_VALUE) {
      closestDistanceMeters = -1.0f;
    }

    // Решаем что говорить.
    Guidance guidance = buildGuidance();
    return new Depth20ScanResult(
        distancesMeters,
        confidenceValues,
        blockedPoints,
        guidance.message,
        guidance.severity,
        closestDistanceMeters);
  }

  public static int getPointCount() {
    return DEPTH20_POINTS.length;
  }

  /**
   * Перевод 20 нормализованных точек в координаты экрана (для Depth20OverlayView).
   * ARCore сам учтёт ориентацию.
   */
  public float[] transformPointsToView(Frame frame) {
    float[] viewPoints = new float[DEPTH20_POINTS.length * 2];
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewNormalizedPoints,
        Coordinates2d.VIEW,
        viewPoints);
    return viewPoints;
  }

  /** Форматируем расстояния под экран. -1 → "n/a". */
  public String[] formatDistanceLabels(Depth20ScanResult result) {
    float[] distances = result.getDistancesMeters();
    String[] labels = new String[distances.length];
    for (int i = 0; i < distances.length; i++) {
      labels[i] = distances[i] > 0.0f ? String.format(Locale.US, "%.2f m", distances[i]) : "n/a";
    }
    return labels;
  }

  /** Форматируем confidence в проценты для экрана. */
  public String[] formatConfidenceLabels(Depth20ScanResult result) {
    float[] confidences = result.getConfidenceValues();
    String[] labels = new String[confidences.length];
    for (int i = 0; i < confidences.length; i++) {
      labels[i] =
          confidences[i] >= 0.0f ? String.format(Locale.US, "%.0f%%", confidences[i] * 100.0f) : "n/a";
    }
    return labels;
  }

  /**
   * Решает что говорить на основе того, какие точки заблокированы. По ситуациям:
   * 1) Аварийный стоп — центр совсем близко.
   * 2) Препятствие сверху по центру.
   * 3) Центр заблокирован — выбираем сторону обхода по «свободности» каждой стороны.
   * 4) Бок заблокирован, другая сторона — нет → подсказываем сторону.
   * 5) Иначе путь свободен.
   */
  private Guidance buildGuidance() {
    // Минимальные дистанции по группам точек.
    float centerNear = minPositive(12, 17); // центр на уровне пола / груди
    float leftNear = minPositive(10, 11, 15, 16);
    float rightNear = minPositive(13, 14, 18, 19);
    float centerMid = minPositive(5, 8, 12, 17); // центр на уровне торса
    float leftMid = minPositive(4, 7, 10, 15);
    float rightMid = minPositive(6, 9, 14, 19);
    float headCenter = minPositive(1, 2, 5); // верхняя зона по центру

    boolean centerEmergency =
        isBlockedDistance(centerNear, EMERGENCY_STOP_METERS)
            || isBlockedDistance(minPositive(11, 12, 13, 16, 17, 18), EMERGENCY_STOP_METERS);
    boolean centerBlocked =
        countBlocked(11, 12, 13, 16, 17, 18) >= 3
            || isBlockedDistance(centerNear, FRONT_BLOCK_METERS)
            || isBlockedDistance(centerMid, FRONT_BLOCK_METERS);
    boolean leftBlocked =
        countBlocked(4, 7, 10, 11, 15, 16) >= 3 || isBlockedDistance(leftNear, SIDE_BLOCK_METERS);
    boolean rightBlocked =
        countBlocked(6, 9, 13, 14, 18, 19) >= 3 || isBlockedDistance(rightNear, SIDE_BLOCK_METERS);
    boolean headBlocked =
        countBlocked(1, 2, 5) >= 2 || isBlockedDistance(headCenter, HEAD_BLOCK_METERS);

    if (centerEmergency) {
      return new Guidance(MESSAGE_STOP, Depth20ScanResult.Severity.STOP);
    }
    if (headBlocked) {
      return new Guidance(MESSAGE_HEAD, Depth20ScanResult.Severity.WARNING);
    }
    if (centerBlocked) {
      // Сравниваем «открытость» сторон через clearanceScore. Преимущество > 0.18 м даёт
      // совет об обходе. Если стороны почти равны — стоп.
      float leftClear = clearanceScore(leftNear, leftMid);
      float rightClear = clearanceScore(rightNear, rightMid);
      if (leftClear > rightClear + 0.18f) {
        return new Guidance(MESSAGE_LEFT, Depth20ScanResult.Severity.WARNING);
      }
      if (rightClear > leftClear + 0.18f) {
        return new Guidance(MESSAGE_RIGHT, Depth20ScanResult.Severity.WARNING);
      }
      return new Guidance(MESSAGE_STOP, Depth20ScanResult.Severity.STOP);
    }
    if (leftBlocked && !rightBlocked) {
      return new Guidance(MESSAGE_SIDE_LEFT, Depth20ScanResult.Severity.INFO);
    }
    if (rightBlocked && !leftBlocked) {
      return new Guidance(MESSAGE_SIDE_RIGHT, Depth20ScanResult.Severity.INFO);
    }
    return new Guidance(MESSAGE_CLEAR, Depth20ScanResult.Severity.CLEAR);
  }

  /** Каждая точка имеет свой порог в зависимости от позиции на экране. */
  private boolean isBlockedPoint(int pointIndex, float depthMeters) {
    if (depthMeters <= 0.0f) {
      return false;
    }
    if (pointIndex <= 6) {
      // Верхние ряды → голова.
      return depthMeters <= HEAD_BLOCK_METERS;
    }
    if (pointIndex == 12 || pointIndex == 17) {
      // Самый центр (грудь и пояс) → передний порог.
      return depthMeters <= FRONT_BLOCK_METERS;
    }
    return depthMeters <= SIDE_BLOCK_METERS;
  }

  private boolean isBlockedDistance(float depthMeters, float thresholdMeters) {
    return depthMeters > 0.0f && depthMeters <= thresholdMeters;
  }

  /**
   * «Очки» открытости стороны. Ближняя точка важнее (вес 0.65), средняя — поменьше (0.35).
   * Чем выше число — тем свободнее.
   */
  private float clearanceScore(float nearDistance, float midDistance) {
    float nearPart = nearDistance > 0.0f ? nearDistance : 0.0f;
    float midPart = midDistance > 0.0f ? midDistance : 0.0f;
    return nearPart * 0.65f + midPart * 0.35f;
  }

  /** Считает сколько из перечисленных точек помечены blocked. */
  private int countBlocked(int... indices) {
    int count = 0;
    for (int index : indices) {
      if (index >= 0 && index < blockedPoints.length && blockedPoints[index]) {
        count++;
      }
    }
    return count;
  }

  /** Минимальная положительная дистанция среди перечисленных точек. -1 если все невалидные. */
  private float minPositive(int... indices) {
    float min = Float.MAX_VALUE;
    for (int index : indices) {
      if (index < 0 || index >= distancesMeters.length) {
        continue;
      }
      float value = distancesMeters[index];
      if (value > 0.0f && value < min) {
        min = value;
      }
    }
    return min == Float.MAX_VALUE ? -1.0f : min;
  }

  /**
   * Сэмплинг depth в окрестности 3×3 пикселей вокруг точки. Берём первое валидное
   * значение (centre сначала, потом edge'и). Простой способ устойчиво получить depth даже
   * если в самой точке дырка.
   */
  private float sampleDepthMeters(Image depthImage, float textureX, float textureY) {
    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer buffer = plane.getBuffer();
    int width = depthImage.getWidth();
    int height = depthImage.getHeight();
    int pixelStride = plane.getPixelStride();
    int rowStride = plane.getRowStride();

    int clampedCenterX = clamp((int) (textureX * width), 0, width - 1);
    int clampedCenterY = clamp((int) (textureY * height), 0, height - 1);
    for (int radius = 0; radius <= 1; radius++) {
      for (int offsetY = -radius; offsetY <= radius; offsetY++) {
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
          int sampleX = clamp(clampedCenterX + offsetX, 0, width - 1);
          int sampleY = clamp(clampedCenterY + offsetY, 0, height - 1);
          int byteOffset = sampleY * rowStride + sampleX * pixelStride;
          if (byteOffset + 1 >= buffer.limit()) {
            continue;
          }
          // Little-endian uint16: младший байт + (старший << 8).
          int depthMillimeters =
              (buffer.get(byteOffset) & 0xFF) | ((buffer.get(byteOffset + 1) & 0xFF) << 8);
          float depthMeters = depthMillimeters * 0.001f;
          if (depthMeters > 0.0f && depthMeters <= MAX_VALID_DEPTH_METERS) {
            return depthMeters;
          }
        }
      }
    }
    return -1.0f;
  }

  /** Аналогично depth — сэмплинг confidence в окрестности 3×3. */
  private float sampleConfidence(Image confidenceImage, float textureX, float textureY) {
    if (confidenceImage == null) {
      return -1.0f;
    }

    Image.Plane plane = confidenceImage.getPlanes()[0];
    ByteBuffer buffer = plane.getBuffer();
    int width = confidenceImage.getWidth();
    int height = confidenceImage.getHeight();
    int pixelStride = plane.getPixelStride();
    int rowStride = plane.getRowStride();

    int centerX = clamp((int) (textureX * width), 0, width - 1);
    int centerY = clamp((int) (textureY * height), 0, height - 1);

    for (int radius = 0; radius <= 1; radius++) {
      for (int offsetY = -radius; offsetY <= radius; offsetY++) {
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
          int sampleX = clamp(centerX + offsetX, 0, width - 1);
          int sampleY = clamp(centerY + offsetY, 0, height - 1);
          int byteOffset = sampleY * rowStride + sampleX * pixelStride;
          if (byteOffset >= buffer.limit()) {
            continue;
          }
          // Confidence — один байт 0..255 → 0..1.
          return (buffer.get(byteOffset) & 0xFF) / 255.0f;
        }
      }
    }
    return -1.0f;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  /** PointF[] → flat float[] для transformCoordinates2d. */
  private static float[] createViewNormalizedPoints() {
    float[] viewCoords = new float[DEPTH20_POINTS.length * 2];
    for (int i = 0; i < DEPTH20_POINTS.length; i++) {
      viewCoords[i * 2] = DEPTH20_POINTS[i].x;
      viewCoords[i * 2 + 1] = DEPTH20_POINTS[i].y;
    }
    return viewCoords;
  }

  /** Просто пара (фраза, severity) для возврата из buildGuidance. */
  private static final class Guidance {
    final String message;
    final Depth20ScanResult.Severity severity;

    Guidance(String message, Depth20ScanResult.Severity severity) {
      this.message = message;
      this.severity = severity;
    }
  }

  /** Удобная утилита для логов — все 20 дистанций в одну строку. */
  public String dumpDistances(Depth20ScanResult result) {
    float[] distances = result.getDistancesMeters();
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < distances.length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      float value = distances[i];
      builder.append(i).append('=');
      builder.append(value > 0.0f ? String.format(Locale.US, "%.2f", value) : "n/a");
    }
    return builder.toString();
  }
}
