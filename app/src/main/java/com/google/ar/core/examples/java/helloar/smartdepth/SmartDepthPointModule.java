package com.google.ar.core.examples.java.helloar.smartdepth;

import android.graphics.PointF;
import android.media.Image;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.examples.java.helloar.depth20.Depth20ScanResult;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Умный режим 23 точек. Развитие простого Depth20PointModule.
 *
 * <p>Что добавлено по сравнению с Depth20:
 * <ol>
 *   <li>Сглаживание дистанций между кадрами (EMA с разной альфой для приближения и отдаления —
 *       приближение быстрое, отдаление плавное).</li>
 *   <li>Подтверждение команды несколькими кадрами подряд — не дёргаем совет на одном
 *       выбросе.</li>
 *   <li>Hold time — нельзя сменить команду пока прошлая активна.</li>
 *   <li>Внутренний gating речи — модуль сам решает когда фраза достойна озвучки.</li>
 *   <li>Детектор обрывов и лестниц — реальные паттерны изменения высоты, а не только
 *       абсолютная дистанция.</li>
 * </ol>
 *
 * <p>23 точки расположены в 5 рядов: 5 + 5 + 5 + 5 + 3. Бин 0..4 — голова, 5..9 — плечи,
 * 10..14 — пояс, 15..19 — колени, 20..22 — пол.
 *
 * <p>Класс не потокобезопасный.
 */
public class SmartDepthPointModule {
  // ───── общие ограничители ─────
  private static final float MAX_VALID_DEPTH_METERS = 20.0f;
  private static final float MIN_CONFIDENCE = 0.05f;

  // ───── параметры детектора обрывов / лестниц ─────
  // Коэффициент учёта pitch'а в оценке высоты по экранной координате.
  private static final float HEIGHT_PITCH_COEFFICIENT = 1.0f;
  // Перепад высоты для «обрыв впереди».
  private static final float DROP_HEIGHT_THRESHOLD_METERS = 0.45f;
  private static final float DROP_DISTANCE_THRESHOLD_METERS = 0.75f;
  private static final float DROP_HEIGHT_CONFIRMATION_DISTANCE_METERS = 0.35f;
  // Параметры лестницы вниз: ближняя ступенька + дальняя + перепад.
  private static final float STAIR_DROP_NEAR_MIN_METERS = 1.00f;
  private static final float STAIR_DROP_NEAR_MAX_METERS = 2.40f;
  private static final float STAIR_DROP_FAR_MIN_METERS = 1.60f;
  private static final float STAIR_DROP_FAR_NEAR_GAP_METERS = 0.55f;
  private static final float STAIR_DROP_HEIGHT_DELTA_METERS = 0.20f;

  // ───── параметры детектора низких препятствий ─────
  // «Низкое препятствие» — что-то близко на уровне ног.
  private static final float LOW_OBSTACLE_DISTANCE_METERS = 1.10f;
  private static final float LOW_OBSTACLE_EXTENDED_DISTANCE_METERS = 1.55f;
  private static final float LOW_OBSTACLE_FLOOR_GAP_METERS = 0.30f;

  // ───── пороги для центра / боков ─────
  private static final float FRONT_OBSTACLE_DISTANCE_METERS = 1.45f;
  private static final float SIDE_CLEAR_DISTANCE_METERS = 1.55f;

  // ───── EMA для дистанций ─────
  // При приближении (новое значение меньше) — берём агрессивно (0.65), быстро среагировать.
  // При удалении — медленно (0.25), не дёргать выводы.
  private static final float APPROACH_SMOOTHING_ALPHA = 0.65f;
  private static final float RETREAT_SMOOTHING_ALPHA = 0.25f;
  // Сколько кадров без depth держим прошлое значение прежде чем сбросить.
  private static final int MAX_MISSING_DEPTH_FRAMES = 2;

  // ───── подтверждение команды (сколько кадров подряд должна быть одинаковой) ─────
  // Чем серьёзнее severity — тем меньше требуется подтверждений (быстрее реакция).
  private static final int STOP_CONFIRM_FRAMES = 2;
  private static final int WARNING_CONFIRM_FRAMES = 2;
  private static final int INFO_CONFIRM_FRAMES = 3;
  private static final int CLEAR_CONFIRM_FRAMES = 4;

  // ───── минимальная длительность удержания команды ─────
  // Нельзя поменять совет раньше чем прошло столько ns с прошлого активного.
  private static final long STOP_HOLD_NS = 1_200_000_000L;
  private static final long WARNING_HOLD_NS = 1_500_000_000L;
  private static final long INFO_HOLD_NS = 1_600_000_000L;
  private static final long CLEAR_HOLD_NS = 2_200_000_000L;

  // ───── интервалы повтора одной фразы ─────
  // Если ситуация не меняется, повторяем фразу не чаще раз в N секунд.
  private static final long STOP_REPEAT_NS = 3_000_000_000L;
  private static final long WARNING_REPEAT_NS = 4_000_000_000L;
  private static final long INFO_REPEAT_NS = 5_000_000_000L;
  private static final long CLEAR_REPEAT_NS = 6_000_000_000L;
  // Перед первой «путь свободен» ждём чуть-чуть, чтобы не сказать сразу после старта.
  private static final long CLEAR_ANNOUNCE_DELAY_NS = 1_200_000_000L;
  private static final long DEBUG_LOG_INTERVAL_NS = 1_000_000_000L;

  private static final String MESSAGE_CLEAR = "Путь свободен.";
  private static final String MESSAGE_DROP = "Стоп. Опасный перепад вниз.";
  private static final String MESSAGE_LOW_OBSTACLE = "Осторожно. Низкое препятствие.";
  private static final String MESSAGE_FRONT_BLOCKED = "Стоп. Путь впереди заблокирован.";
  private static final String MESSAGE_LEG_LEVEL_OBSTACLE = "Препятствие на уровне ног.";
  private static final String MESSAGE_GO_LEFT = "Препятствие впереди. Обходите слева.";
  private static final String MESSAGE_GO_RIGHT = "Препятствие впереди. Обходите справа.";
  private static final String MESSAGE_GO_EITHER =
      "Препятствие впереди. Возможен обход слева или справа.";
  private static final PointF[] SMART_POINTS = {
    new PointF(0.06f, 0.10f),
    new PointF(0.30f, 0.10f),
    new PointF(0.50f, 0.10f),
    new PointF(0.70f, 0.10f),
    new PointF(0.94f, 0.10f),
    new PointF(0.06f, 0.27f),
    new PointF(0.30f, 0.27f),
    new PointF(0.50f, 0.27f),
    new PointF(0.70f, 0.27f),
    new PointF(0.94f, 0.27f),
    new PointF(0.10f, 0.50f),
    new PointF(0.36f, 0.50f),
    new PointF(0.50f, 0.50f),
    new PointF(0.64f, 0.50f),
    new PointF(0.90f, 0.50f),
    new PointF(0.08f, 0.76f),
    new PointF(0.36f, 0.76f),
    new PointF(0.50f, 0.76f),
    new PointF(0.64f, 0.76f),
    new PointF(0.92f, 0.76f),
    new PointF(0.36f, 0.96f),
    new PointF(0.50f, 0.96f),
    new PointF(0.64f, 0.96f)
  };

  private final float[] viewNormalizedPoints = createViewNormalizedPoints();
  private final float[] textureCoords = createViewNormalizedPoints();
  private final float[] distancesMeters = new float[SMART_POINTS.length];
  private final float[] confidenceValues = new float[SMART_POINTS.length];
  private final float[] correctedHeightsMeters = new float[SMART_POINTS.length];
  private final float[] texturePointYPixels = new float[SMART_POINTS.length];
  private final boolean[] blockedPoints = new boolean[SMART_POINTS.length];
  private final float[] filteredDistancesMeters = new float[SMART_POINTS.length];
  private final int[] missingDepthFrameCounts = new int[SMART_POINTS.length];
  private final boolean[] stableBlockedPoints = new boolean[SMART_POINTS.length];

  private Guidance stableGuidance = Guidance.clear();
  private Guidance pendingGuidance = Guidance.none();
  private Guidance lastAnnouncedGuidance = Guidance.none();
  private Guidance lastRawGuidance = Guidance.none();
  private Guidance lastActiveGuidance = Guidance.clear();
  private int pendingGuidanceFrames = 0;
  private long stableGuidanceTimestampNs = 0L;
  private long lastSpeechTimestampNs = 0L;
  private long lastDebugLogTimestampNs = 0L;
  private String lastDebugSignature = "";
  private String lastSpeechPhrase = "";
  private float currentPitchRadians = 0.0f;
  private float currentTextureFocalY = 0.0f;
  private float currentTexturePrincipalY = 0.0f;

  public SmartDepthPointModule() {
    reset();
  }

  public Depth20ScanResult process(Frame frame, Image depthImage, Image confidenceImage) {
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewNormalizedPoints,
        Coordinates2d.TEXTURE_NORMALIZED,
        textureCoords);

    float pitchRadians = estimatePitchRadians(frame);
    CameraIntrinsics textureIntrinsics = frame.getCamera().getTextureIntrinsics();
    float[] textureFocalLength = textureIntrinsics.getFocalLength();
    float[] texturePrincipalPoint = textureIntrinsics.getPrincipalPoint();
    int[] textureDimensions = textureIntrinsics.getImageDimensions();
    float textureHeightPixels = textureDimensions.length > 1 ? textureDimensions[1] : 0.0f;
    currentPitchRadians = pitchRadians;
    currentTextureFocalY = textureFocalLength.length > 1 ? textureFocalLength[1] : 0.0f;
    currentTexturePrincipalY = texturePrincipalPoint.length > 1 ? texturePrincipalPoint[1] : 0.0f;
    float closestDistanceMeters = Float.MAX_VALUE;
    for (int i = 0; i < SMART_POINTS.length; i++) {
      float rawDepthMeters =
          sampleDepthMeters(depthImage, textureCoords[i * 2], textureCoords[i * 2 + 1]);
      float confidence =
          sampleConfidence(confidenceImage, textureCoords[i * 2], textureCoords[i * 2 + 1]);
      float smoothedDepthMeters = smoothDepthMeasurement(i, rawDepthMeters);

      distancesMeters[i] = smoothedDepthMeters;
      confidenceValues[i] = confidence;
      texturePointYPixels[i] =
          textureHeightPixels > 0.0f ? textureCoords[i * 2 + 1] * textureHeightPixels : -1.0f;
      correctedHeightsMeters[i] =
          estimateCorrectedHeightMeters(i, smoothedDepthMeters, pitchRadians);
      blockedPoints[i] = false;

      if (isValidDepth(i) && smoothedDepthMeters < closestDistanceMeters) {
        closestDistanceMeters = smoothedDepthMeters;
      }
    }

    if (closestDistanceMeters == Float.MAX_VALUE) {
      closestDistanceMeters = -1.0f;
    }

    Guidance rawGuidance = buildGuidance();
    long frameTimestampNs = frame.getTimestamp();
    Guidance activeGuidance = stabilizeGuidance(rawGuidance, frameTimestampNs);
    String phraseToSpeak = selectPhraseForSpeech(activeGuidance, frameTimestampNs);
    lastRawGuidance = rawGuidance;
    lastActiveGuidance = activeGuidance;
    lastSpeechPhrase = phraseToSpeak;

    return new Depth20ScanResult(
        distancesMeters,
        confidenceValues,
        buildOverlayBlockedPoints(rawGuidance, activeGuidance),
        phraseToSpeak,
        activeGuidance.severity,
        closestDistanceMeters);
  }

  public void reset() {
    for (int i = 0; i < SMART_POINTS.length; i++) {
      distancesMeters[i] = -1.0f;
      confidenceValues[i] = -1.0f;
      correctedHeightsMeters[i] = Float.MAX_VALUE;
      texturePointYPixels[i] = -1.0f;
      blockedPoints[i] = false;
      filteredDistancesMeters[i] = -1.0f;
      missingDepthFrameCounts[i] = 0;
      stableBlockedPoints[i] = false;
    }
    stableGuidance = Guidance.clear();
    pendingGuidance = Guidance.none();
    lastAnnouncedGuidance = Guidance.none();
    lastRawGuidance = Guidance.none();
    lastActiveGuidance = Guidance.clear();
    pendingGuidanceFrames = 0;
    stableGuidanceTimestampNs = 0L;
    lastSpeechTimestampNs = 0L;
    lastDebugLogTimestampNs = 0L;
    lastDebugSignature = "";
    lastSpeechPhrase = "";
    currentPitchRadians = 0.0f;
    currentTextureFocalY = 0.0f;
    currentTexturePrincipalY = 0.0f;
  }

  public static int getPointCount() {
    return SMART_POINTS.length;
  }

  public float[] transformPointsToView(Frame frame) {
    float[] viewPoints = new float[SMART_POINTS.length * 2];
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewNormalizedPoints,
        Coordinates2d.VIEW,
        viewPoints);
    return viewPoints;
  }

  public String[] formatDistanceLabels(Depth20ScanResult result) {
    float[] distances = result.getDistancesMeters();
    String[] labels = new String[distances.length];
    for (int i = 0; i < distances.length; i++) {
      labels[i] =
          distances[i] > 0.0f
              ? String.format(Locale.US, "%02d %.2f m", i, distances[i])
              : i + " n/a";
    }
    return labels;
  }

  public String[] formatConfidenceLabels(Depth20ScanResult result) {
    float[] confidences = result.getConfidenceValues();
    String[] labels = new String[confidences.length];
    for (int i = 0; i < confidences.length; i++) {
      labels[i] =
          confidences[i] >= 0.0f
              ? String.format(Locale.US, "%.0f%%", confidences[i] * 100.0f)
              : "n/a";
    }
    return labels;
  }

  public String consumeDebugLog(Depth20ScanResult result, long frameTimestampNs) {
    if (result == null) {
      return null;
    }

    String signature =
        lastRawGuidance.type
            + "|"
            + lastActiveGuidance.type
            + "|"
            + pendingGuidance.type
            + "|"
            + pendingGuidanceFrames
            + "|"
            + result.getSeverity()
            + "|"
            + safePhrase(result.getSpokenPhrase())
            + "|"
            + formatBlockedIndices(result.getBlockedPoints());
    boolean signatureChanged = !signature.equals(lastDebugSignature);
    boolean intervalElapsed = frameTimestampNs - lastDebugLogTimestampNs >= DEBUG_LOG_INTERVAL_NS;
    if (!signatureChanged && !intervalElapsed) {
      return null;
    }

    lastDebugSignature = signature;
    lastDebugLogTimestampNs = frameTimestampNs;
    return buildDebugSummary(result);
  }

  private Guidance buildGuidance() {
    if (isDescendingStaircase()) {
      return new Guidance(GuidanceType.DROP, MESSAGE_DROP, Depth20ScanResult.Severity.STOP);
    }
    if (isDropColumn(11, 16, 20)) {
      markBlocked(11, 16, 20);
      return new Guidance(GuidanceType.DROP, MESSAGE_DROP, Depth20ScanResult.Severity.STOP);
    }
    if (isDropColumn(12, 17, 21)) {
      markBlocked(12, 17, 21);
      return new Guidance(GuidanceType.DROP, MESSAGE_DROP, Depth20ScanResult.Severity.STOP);
    }
    if (isDropColumn(13, 18, 22)) {
      markBlocked(13, 18, 22);
      return new Guidance(GuidanceType.DROP, MESSAGE_DROP, Depth20ScanResult.Severity.STOP);
    }

    boolean centerBlocked = isCenterBlocked();
    boolean leftClear = isSideClear(10, 11, 15, 16);
    boolean rightClear = isSideClear(13, 14, 18, 19);
    boolean wallLikeBlockage = isWallLikeBlockage();
    boolean legLevelObstacle = isLegLevelObstacle();
    if (centerBlocked) {
      markBlockedIfClose(FRONT_OBSTACLE_DISTANCE_METERS, 11, 12, 13, 16, 17, 18);
      if (wallLikeBlockage) {
        markBlockedIfClose(
            FRONT_OBSTACLE_DISTANCE_METERS, 6, 7, 8, 11, 12, 13, 16, 17, 18);
        return new Guidance(
            GuidanceType.FRONT_BLOCKED,
            MESSAGE_FRONT_BLOCKED,
            Depth20ScanResult.Severity.STOP);
      }
      if (legLevelObstacle) {
        markBlockedIfClose(FRONT_OBSTACLE_DISTANCE_METERS, 16, 17, 18);
        return new Guidance(
            GuidanceType.LEG_LEVEL_OBSTACLE,
            MESSAGE_LEG_LEVEL_OBSTACLE,
            Depth20ScanResult.Severity.STOP);
      }
      if (leftClear && rightClear) {
        return new Guidance(
            GuidanceType.GO_EITHER, MESSAGE_GO_EITHER, Depth20ScanResult.Severity.WARNING);
      }
      if (leftClear) {
        return new Guidance(
            GuidanceType.GO_LEFT, MESSAGE_GO_LEFT, Depth20ScanResult.Severity.WARNING);
      }
      if (rightClear) {
        return new Guidance(
            GuidanceType.GO_RIGHT, MESSAGE_GO_RIGHT, Depth20ScanResult.Severity.WARNING);
      }
      return new Guidance(
          GuidanceType.FRONT_BLOCKED,
          MESSAGE_FRONT_BLOCKED,
          Depth20ScanResult.Severity.STOP);
    }

    if (hasLowObstacle()) {
      return new Guidance(
          GuidanceType.LOW_OBSTACLE,
          MESSAGE_LOW_OBSTACLE,
          Depth20ScanResult.Severity.WARNING);
    }

    return Guidance.clear();
  }

  private String buildDebugSummary(Depth20ScanResult result) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("decision: raw=")
        .append(lastRawGuidance.type)
        .append(", active=")
        .append(lastActiveGuidance.type)
        .append(", pending=")
        .append(pendingGuidance.type)
        .append(" (")
        .append(pendingGuidanceFrames)
        .append('/')
        .append(getConfirmFrames(pendingGuidance))
        .append(')')
        .append(", severity=")
        .append(result.getSeverity())
        .append(", closest=")
        .append(formatDistance(result.getClosestDistanceMeters()))
        .append(", speak=")
        .append(safePhrase(lastSpeechPhrase))
        .append(", resultPhrase=")
        .append(safePhrase(result.getSpokenPhrase()))
        .append(", blocked=")
        .append(formatBlockedIndices(result.getBlockedPoints()));

    appendPointRow(builder, 0, 4);
    appendPointRow(builder, 5, 9);
    appendPointRow(builder, 10, 14);
    appendPointRow(builder, 15, 19);
    appendPointRow(builder, 20, 22);
    return builder.toString();
  }

  private Guidance stabilizeGuidance(Guidance rawGuidance, long frameTimestampNs) {
    if (sameGuidance(rawGuidance, stableGuidance)) {
      copyBlockedPoints(blockedPoints, stableBlockedPoints);
      pendingGuidance = Guidance.none();
      pendingGuidanceFrames = 0;
      if (stableGuidanceTimestampNs == 0L) {
        stableGuidanceTimestampNs = frameTimestampNs;
      }
      return stableGuidance;
    }

    if (sameGuidance(rawGuidance, pendingGuidance)) {
      pendingGuidanceFrames++;
    } else {
      pendingGuidance = rawGuidance;
      pendingGuidanceFrames = 1;
    }

    if (pendingGuidanceFrames < getConfirmFrames(rawGuidance)) {
      return stableGuidance;
    }

    if (!canReplaceStableGuidance(rawGuidance, frameTimestampNs)) {
      return stableGuidance;
    }

    stableGuidance = rawGuidance;
    stableGuidanceTimestampNs = frameTimestampNs;
    copyBlockedPoints(blockedPoints, stableBlockedPoints);
    return stableGuidance;
  }

  private String selectPhraseForSpeech(Guidance activeGuidance, long frameTimestampNs) {
    if (activeGuidance.type == GuidanceType.NONE) {
      return "";
    }

    if (sameGuidance(activeGuidance, lastAnnouncedGuidance)) {
      if (activeGuidance.severity == Depth20ScanResult.Severity.CLEAR) {
        return "";
      }
      if (frameTimestampNs - lastSpeechTimestampNs < getRepeatIntervalNs(activeGuidance.severity)) {
        return "";
      }
      lastSpeechTimestampNs = frameTimestampNs;
      return activeGuidance.message;
    }

    if (activeGuidance.severity == Depth20ScanResult.Severity.CLEAR) {
      boolean canAnnounceClear = lastAnnouncedGuidance.severity != Depth20ScanResult.Severity.CLEAR;
      boolean clearIsStable =
          stableGuidanceTimestampNs != 0L
              && (frameTimestampNs - stableGuidanceTimestampNs) >= CLEAR_ANNOUNCE_DELAY_NS;
      if (!canAnnounceClear || !clearIsStable) {
        return "";
      }
    }

    lastAnnouncedGuidance = activeGuidance;
    lastSpeechTimestampNs = frameTimestampNs;
    return activeGuidance.message;
  }

  private boolean[] buildOverlayBlockedPoints(Guidance rawGuidance, Guidance activeGuidance) {
    boolean[] overlayBlocked = new boolean[SMART_POINTS.length];
    if (activeGuidance.severity != Depth20ScanResult.Severity.CLEAR) {
      copyBlockedPoints(stableBlockedPoints, overlayBlocked);
    }
    if (isMoreUrgent(rawGuidance, activeGuidance)) {
      for (int i = 0; i < blockedPoints.length; i++) {
        overlayBlocked[i] = overlayBlocked[i] || blockedPoints[i];
      }
    }
    // Каждая точка отдельно загорается красной, если её собственная дистанция меньше
    // персонального порога — независимо от того, какое решение принял дирижёр.
    for (int i = 0; i < SMART_POINTS.length; i++) {
      if (isPointVisuallyClose(i)) {
        overlayBlocked[i] = true;
      }
    }
    return overlayBlocked;
  }

  private boolean isPointVisuallyClose(int pointIndex) {
    if (!isValidDepth(pointIndex)) {
      return false;
    }
    return distancesMeters[pointIndex] <= overlayThresholdMetersFor(pointIndex);
  }

  private static float overlayThresholdMetersFor(int pointIndex) {
    // Нижний ряд (20–22) — точки опоры пола, естественно близкие; для них берём более жёсткий
    // порог, чтобы пол сам по себе не подсвечивался красным.
    if (pointIndex >= 20 && pointIndex <= 22) {
      return LOW_OBSTACLE_DISTANCE_METERS;
    }
    return FRONT_OBSTACLE_DISTANCE_METERS;
  }

  private boolean canReplaceStableGuidance(Guidance nextGuidance, long frameTimestampNs) {
    if (stableGuidanceTimestampNs == 0L) {
      return true;
    }
    if (isMoreUrgent(nextGuidance, stableGuidance)) {
      return true;
    }
    return frameTimestampNs - stableGuidanceTimestampNs >= getHoldDurationNs(stableGuidance.severity);
  }

  private void appendPointRow(StringBuilder builder, int startIndex, int endIndex) {
    builder.append('\n').append("points ").append(startIndex).append('-').append(endIndex).append(": ");
    for (int i = startIndex; i <= endIndex; i++) {
      if (i > startIndex) {
        builder.append(" | ");
      }
      builder
          .append(String.format(Locale.US, "%02d=", i))
          .append(formatDistance(distancesMeters[i]))
          .append('/')
          .append(formatConfidence(confidenceValues[i]));
      if (stableBlockedPoints[i]) {
        builder.append("*");
      } else if (blockedPoints[i]) {
        builder.append("~");
      }
    }
  }

  private int getConfirmFrames(Guidance guidance) {
    switch (guidance.severity) {
      case STOP:
        return STOP_CONFIRM_FRAMES;
      case WARNING:
        return WARNING_CONFIRM_FRAMES;
      case INFO:
        return INFO_CONFIRM_FRAMES;
      case CLEAR:
      default:
        return CLEAR_CONFIRM_FRAMES;
    }
  }

  private long getHoldDurationNs(Depth20ScanResult.Severity severity) {
    switch (severity) {
      case STOP:
        return STOP_HOLD_NS;
      case WARNING:
        return WARNING_HOLD_NS;
      case INFO:
        return INFO_HOLD_NS;
      case CLEAR:
      default:
        return CLEAR_HOLD_NS;
    }
  }

  private long getRepeatIntervalNs(Depth20ScanResult.Severity severity) {
    switch (severity) {
      case STOP:
        return STOP_REPEAT_NS;
      case WARNING:
        return WARNING_REPEAT_NS;
      case INFO:
        return INFO_REPEAT_NS;
      case CLEAR:
      default:
        return CLEAR_REPEAT_NS;
    }
  }

  private boolean isMoreUrgent(Guidance first, Guidance second) {
    return severityRank(first.severity) > severityRank(second.severity);
  }

  private int severityRank(Depth20ScanResult.Severity severity) {
    switch (severity) {
      case STOP:
        return 3;
      case WARNING:
        return 2;
      case INFO:
        return 1;
      case CLEAR:
      default:
        return 0;
    }
  }

  private boolean sameGuidance(Guidance first, Guidance second) {
    return first.type == second.type && first.severity == second.severity;
  }

  private String formatBlockedIndices(boolean[] blocked) {
    StringBuilder builder = new StringBuilder("[");
    boolean first = true;
    for (int i = 0; i < blocked.length; i++) {
      if (!blocked[i]) {
        continue;
      }
      if (!first) {
        builder.append(',');
      }
      builder.append(i);
      first = false;
    }
    builder.append(']');
    return builder.toString();
  }

  private String formatDistance(float distanceMeters) {
    return distanceMeters > 0.0f ? String.format(Locale.US, "%.2fm", distanceMeters) : "n/a";
  }

  private String formatConfidence(float confidence) {
    return confidence >= 0.0f
        ? String.format(Locale.US, "%.0f%%", confidence * 100.0f)
        : "n/a";
  }

  private String safePhrase(String phrase) {
    return phrase == null || phrase.trim().isEmpty() ? "<silent>" : phrase;
  }

  private void copyBlockedPoints(boolean[] source, boolean[] target) {
    for (int i = 0; i < source.length; i++) {
      target[i] = source[i];
    }
  }

  private float smoothDepthMeasurement(int pointIndex, float rawDepthMeters) {
    if (rawDepthMeters > 0.0f && rawDepthMeters <= MAX_VALID_DEPTH_METERS) {
      missingDepthFrameCounts[pointIndex] = 0;
      float previousDepth = filteredDistancesMeters[pointIndex];
      if (previousDepth <= 0.0f) {
        filteredDistancesMeters[pointIndex] = rawDepthMeters;
        return rawDepthMeters;
      }
      float alpha =
          rawDepthMeters < previousDepth ? APPROACH_SMOOTHING_ALPHA : RETREAT_SMOOTHING_ALPHA;
      float filteredDepth = previousDepth + alpha * (rawDepthMeters - previousDepth);
      filteredDistancesMeters[pointIndex] = filteredDepth;
      return filteredDepth;
    }

    if (filteredDistancesMeters[pointIndex] > 0.0f
        && missingDepthFrameCounts[pointIndex] < MAX_MISSING_DEPTH_FRAMES) {
      missingDepthFrameCounts[pointIndex]++;
      return filteredDistancesMeters[pointIndex];
    }

    filteredDistancesMeters[pointIndex] = -1.0f;
    missingDepthFrameCounts[pointIndex] = 0;
    return -1.0f;
  }

  private boolean isDropColumn(int upper, int middle, int lower) {
    return isDropPair(upper, middle) || isDropPair(middle, lower);
  }

  private boolean isDropPair(int upper, int lower) {
    if (!isValidDepth(upper) || !isValidDepth(lower)) {
      return false;
    }
    float heightDrop = correctedHeightsMeters[upper] - correctedHeightsMeters[lower];
    float distanceDrop = distancesMeters[lower] - distancesMeters[upper];
    boolean strongDepthJump = distanceDrop > DROP_DISTANCE_THRESHOLD_METERS;
    boolean heightDropConfirmedByDepth =
        heightDrop > DROP_HEIGHT_THRESHOLD_METERS
            && distanceDrop > DROP_HEIGHT_CONFIRMATION_DISTANCE_METERS;
    return strongDepthJump || heightDropConfirmedByDepth;
  }

  private boolean isDescendingStaircase() {
    boolean leftMatched = matchesDescendingStairPair(20, 16);
    boolean centerMatched = matchesDescendingStairPair(21, 17);
    boolean rightMatched = matchesDescendingStairPair(22, 18);
    int matchedPairs = 0;
    if (leftMatched) {
      matchedPairs++;
    }
    if (centerMatched) {
      matchedPairs++;
    }
    if (rightMatched) {
      matchedPairs++;
    }
    if (matchedPairs < 2) {
      return false;
    }
    if (leftMatched) {
      markBlocked(16, 20);
    }
    if (centerMatched) {
      markBlocked(17, 21);
    }
    if (rightMatched) {
      markBlocked(18, 22);
    }
    return true;
  }

  private boolean matchesDescendingStairPair(int nearPoint, int farPoint) {
    if (!isValidDepth(nearPoint) || !isValidDepth(farPoint)) {
      return false;
    }

    float nearDistance = distancesMeters[nearPoint];
    float farDistance = distancesMeters[farPoint];
    boolean nearLooksLikeNearestStep =
        nearDistance >= STAIR_DROP_NEAR_MIN_METERS && nearDistance <= STAIR_DROP_NEAR_MAX_METERS;
    boolean farFallsAway =
        farDistance >= STAIR_DROP_FAR_MIN_METERS
            && (farDistance - nearDistance) >= STAIR_DROP_FAR_NEAR_GAP_METERS;
    float heightDelta = estimateVerticalDeltaMeters(nearPoint, farPoint);
    return nearLooksLikeNearestStep
        && farFallsAway
        && !Float.isNaN(heightDelta)
        && Math.abs(heightDelta) >= STAIR_DROP_HEIGHT_DELTA_METERS;
  }

  private float estimateVerticalDeltaMeters(int nearPoint, int farPoint) {
    if (currentTextureFocalY <= 0.0f) {
      return Float.NaN;
    }
    float nearPixelY = texturePointYPixels[nearPoint];
    float farPixelY = texturePointYPixels[farPoint];
    if (nearPixelY < 0.0f || farPixelY < 0.0f) {
      return Float.NaN;
    }

    float nearDistance = distancesMeters[nearPoint];
    float farDistance = distancesMeters[farPoint];
    float alphaNear = (float) Math.atan(-(nearPixelY - currentTexturePrincipalY) / currentTextureFocalY);
    float alphaFar = (float) Math.atan(-(farPixelY - currentTexturePrincipalY) / currentTextureFocalY);
    float betaNear = -currentPitchRadians + alphaNear;
    float betaFar = -currentPitchRadians + alphaFar;
    return (float) (farDistance * Math.sin(betaFar) - nearDistance * Math.sin(betaNear));
  }

  private boolean hasLowObstacle() {
    boolean hasObstacle = false;
    float floorDistance = averageDistance(20, 21, 22);
    int[] lowNodes = {16, 17, 18};
    for (int node : lowNodes) {
      if (!isValidDepth(node)) {
        continue;
      }
      boolean closeObstacle = distancesMeters[node] <= LOW_OBSTACLE_DISTANCE_METERS;
      boolean protrudesFromFloor =
          floorDistance > 0.0f
              && distancesMeters[node] <= LOW_OBSTACLE_EXTENDED_DISTANCE_METERS
              && (floorDistance - distancesMeters[node]) >= LOW_OBSTACLE_FLOOR_GAP_METERS;
      if (closeObstacle || protrudesFromFloor) {
        blockedPoints[node] = true;
        hasObstacle = true;
      }
    }
    return hasObstacle;
  }

  private boolean isCenterBlocked() {
    int[] centerNodes = {11, 12, 13, 16, 17, 18};
    for (int node : centerNodes) {
      if (isValidDepth(node) && distancesMeters[node] <= FRONT_OBSTACLE_DISTANCE_METERS) {
        return true;
      }
    }
    return false;
  }

  private boolean isSideClear(int... nodes) {
    for (int node : nodes) {
      if (isValidDepth(node) && distancesMeters[node] <= SIDE_CLEAR_DISTANCE_METERS) {
        return false;
      }
    }
    return true;
  }

  private boolean isWallLikeBlockage() {
    int[] frontWallNodes = {6, 7, 8, 11, 12, 13, 16, 17, 18};
    int[] upperFrontNodes = {6, 7, 8, 11, 12, 13};
    int closeCount = countNodesCloserThan(frontWallNodes, FRONT_OBSTACLE_DISTANCE_METERS);
    int upperCloseCount = countNodesCloserThan(upperFrontNodes, FRONT_OBSTACLE_DISTANCE_METERS);
    return closeCount >= 5 && upperCloseCount >= 2;
  }

  private boolean isLegLevelObstacle() {
    int[] upperCenterNodes = {11, 12, 13};
    int[] lowerCenterNodes = {16, 17, 18};
    int upperCloseCount = countNodesCloserThan(upperCenterNodes, FRONT_OBSTACLE_DISTANCE_METERS);
    int lowerCloseCount = countNodesCloserThan(lowerCenterNodes, FRONT_OBSTACLE_DISTANCE_METERS);
    return lowerCloseCount >= 2 && upperCloseCount <= 1;
  }

  private float averageHeight(int... nodes) {
    float sum = 0.0f;
    int count = 0;
    for (int node : nodes) {
      if (isValidDepth(node)) {
        sum += correctedHeightsMeters[node];
        count++;
      }
    }
    return count > 0 ? sum / count : Float.MAX_VALUE;
  }

  private float averageDistance(int... nodes) {
    float sum = 0.0f;
    int count = 0;
    for (int node : nodes) {
      if (isValidDepth(node)) {
        sum += distancesMeters[node];
        count++;
      }
    }
    return count > 0 ? sum / count : -1.0f;
  }

  private float estimateCorrectedHeightMeters(int pointIndex, float depthMeters, float pitchRadians) {
    if (depthMeters <= 0.0f) {
      return Float.MAX_VALUE;
    }
    float screenHeightProxy = (0.5f - SMART_POINTS[pointIndex].y) * depthMeters;
    return screenHeightProxy
        + HEIGHT_PITCH_COEFFICIENT * (float) Math.tan(pitchRadians) * depthMeters;
  }

  private float estimatePitchRadians(Frame frame) {
    Pose pose = frame.getCamera().getPose();
    float[] forward = new float[3];
    pose.getTransformedAxis(2, -1.0f, forward, 0);
    return (float) Math.asin(clampFloat(-forward[1], -1.0f, 1.0f));
  }

  private int countNodesCloserThan(int[] nodes, float distanceThresholdMeters) {
    int count = 0;
    for (int node : nodes) {
      if (isValidDepth(node) && distancesMeters[node] <= distanceThresholdMeters) {
        count++;
      }
    }
    return count;
  }

  private boolean isValidDepth(int pointIndex) {
    return distancesMeters[pointIndex] > 0.0f
        && distancesMeters[pointIndex] <= MAX_VALID_DEPTH_METERS
        && (confidenceValues[pointIndex] < 0.0f || confidenceValues[pointIndex] >= MIN_CONFIDENCE);
  }

  private void markBlocked(int... nodes) {
    for (int node : nodes) {
      if (node >= 0 && node < blockedPoints.length) {
        blockedPoints[node] = true;
      }
    }
  }

  private void markBlockedIfClose(float thresholdMeters, int... nodes) {
    for (int node : nodes) {
      if (node < 0 || node >= blockedPoints.length) {
        continue;
      }
      if (isValidDepth(node) && distancesMeters[node] <= thresholdMeters) {
        blockedPoints[node] = true;
      }
    }
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
    for (int radius = 0; radius <= 1; radius++) {
      for (int offsetY = -radius; offsetY <= radius; offsetY++) {
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
          int sampleX = clamp(centerX + offsetX, 0, width - 1);
          int sampleY = clamp(centerY + offsetY, 0, height - 1);
          int byteOffset = sampleY * rowStride + sampleX * pixelStride;
          if (byteOffset + 1 >= buffer.limit()) {
            continue;
          }
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
          return (buffer.get(byteOffset) & 0xFF) / 255.0f;
        }
      }
    }
    return -1.0f;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private float clampFloat(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  private static float[] createViewNormalizedPoints() {
    float[] viewCoords = new float[SMART_POINTS.length * 2];
    for (int i = 0; i < SMART_POINTS.length; i++) {
      viewCoords[i * 2] = SMART_POINTS[i].x;
      viewCoords[i * 2 + 1] = SMART_POINTS[i].y;
    }
    return viewCoords;
  }

  private enum GuidanceType {
    NONE,
    CLEAR,
    DROP,
    LOW_OBSTACLE,
    LEG_LEVEL_OBSTACLE,
    FRONT_BLOCKED,
    GO_LEFT,
    GO_RIGHT,
    GO_EITHER,
  }

  private static final class Guidance {
    final GuidanceType type;
    final String message;
    final Depth20ScanResult.Severity severity;

    Guidance(GuidanceType type, String message, Depth20ScanResult.Severity severity) {
      this.type = type;
      this.message = message;
      this.severity = severity;
    }

    static Guidance none() {
      return new Guidance(GuidanceType.NONE, "", Depth20ScanResult.Severity.CLEAR);
    }

    static Guidance clear() {
      return new Guidance(
          GuidanceType.CLEAR, MESSAGE_CLEAR, Depth20ScanResult.Severity.CLEAR);
    }
  }
}
