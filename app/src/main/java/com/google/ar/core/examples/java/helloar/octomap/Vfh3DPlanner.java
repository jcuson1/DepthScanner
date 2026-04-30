package com.google.ar.core.examples.java.helloar.octomap;

import com.google.ar.core.examples.java.helloar.depth20.Depth20ScanResult;

/**
 * 3DVFH+ — алгоритм планирования направления движения. Оригинал из статьи:
 *
 * <p>Vanneste, Bellekens, Weyn — «3DVFH+: Real-Time Three-Dimensional Obstacle Avoidance
 * Using an Octomap», MORSE 2014, стр. 91-102.
 *
 * <p>В статье 5 этапов. У нас работает 4 — этап 3 (Physical Characteristics:
 * turning circle и climbing) пропущен, потому что пешеход разворачивается на месте,
 * никаких ограничений по инерции у него нет.
 *
 * <p>Идея алгоритма по-простому: вокруг пользователя 36 направлений по 10° (азимут) ×
 * 7 уровней по высоте (элевация). На каждое препятствие смотрим — в какое направление
 * оно попадает, и накидываем туда «вес». Дальше выбираем самое свободное направление
 * ближе к «вперёд».
 *
 * <p>Связка статья ↔ код:
 * <ul>
 *   <li>§4.1 (Octomap Exploring) — фильтр по bounding sphere в plan().</li>
 *   <li>§4.2 (Primary Polar Histogram) — наполнение primary[][], формулы (1)-(7).</li>
 *   <li>§4.3 (Physical Characteristics) — пропущен.</li>
 *   <li>§4.4 (Binary Histogram) — бинаризация с гистерезисом (eq 18).</li>
 *   <li>§4.5 (Path Detection) — поиск окна + cost-функция (eq 19).</li>
 * </ul>
 *
 * <p>Класс не потокобезопасный.
 */
public class Vfh3DPlanner {

  // ───── размер гистограммы ─────

  // 36 бинов по 10° = полный круг 360°. Параметр α из статьи у нас = 10°.
  public static final int AZIMUTH_BINS = 36;

  // 7 бинов на ±30° от уровня глаз. В статье полная сфера, но пешеход не лазит вверх-вниз —
  // нам узкого диапазона хватает. Меньше бинов = быстрее планировщик.
  public static final int ELEVATION_BINS = 7;

  // Шаг бина в радианах = 10°.
  private static final float ALPHA_RAD = (float) Math.toRadians(10.0);

  // Бин «прямо вперёд» — индекс 18 при 36 бинах.
  private static final int FORWARD_AZIMUTH_BIN = AZIMUTH_BINS / 2;

  // Бин «уровень глаз» — индекс 3 при 7 бинах.
  private static final int LEVEL_ELEVATION_BIN = ELEVATION_BINS / 2;

  // ───── размеры пользователя и окружения ─────

  // Радиус сферы вокруг пользователя — дальние воксели в planner не идут (всё равно
  // ничего не сделаешь с тем что в 5 м, а CPU кушает).
  private static final float BOUNDING_RADIUS_M = 4.0f;

  // r_r из статьи. У нас 15 см → ширина пользователя 30 см.
  private static final float USER_RADIUS_M = 0.15f;

  // s из статьи — запас «не вплотную». 20 см.
  private static final float SAFETY_RADIUS_M = 0.20f;

  // ───── веса в формулах статьи ─────

  // a и b в формуле (6). a — большое, b — линейно убывающий с расстоянием.
  // Подобрано так чтобы вес обнулялся на границе bounding sphere.
  private static final float A_CONST = 2.0f;
  private static final float B_CONST = A_CONST / BOUNDING_RADIUS_M;

  // Гистерезис из формулы (18). Если вес ячейки выше TAU_HIGH — точно занято, ниже
  // TAU_LOW — точно свободно, между — оставляем как было в прошлом кадре. Без этого
  // ячейки на пороге мерцают, и совет дёргается каждый кадр.
  private static final float TAU_LOW = 0.5f;
  private static final float TAU_HIGH = 1.5f;

  // ───── веса cost-функции пути (eq 19) ─────

  // μ₁ — насколько хочется попасть в «цель» (направление куда пользователь идёт).
  private static final float MU_GOAL = 5.0f;

  // μ₃ — стабильность относительно прошлого совета. Если только что сказали «прямо», то
  // через секунду внезапно «развернитесь» — пугает. Поэтому штрафуем резкие смены.
  private static final float MU_SMOOTH = 2.0f;

  // Доп. штраф за отклонение по высоте — пешеход не лезет к потолку. У статьи такого нет.
  private static final float MU_ELEVATION = 4.0f;

  // ───── фильтры «реальных» вокселей ─────

  // Минимум hits чтобы воксель попал в planner. Совпадает с render-порогом, так что
  // что видно на экране — то и идёт в принятие решения.
  private static final int OCCUPIED_HIT_THRESHOLD = 4;

  // Минимум средней уверенности. Шумные воксели игнорим. Опущен с 0.30 до 0.20 — тоже
  // в синхронизации с render-стадией, чтобы planner не игнорил то что видно на экране.
  private static final float OCCUPIED_AVG_CONFIDENCE_THRESHOLD = 0.20f;

  // Размер окна для поиска прохода. Кандидат «свободно» только если ВСЕ ячейки в квадрате
  // 5×5 (= 2·HALF+1) вокруг него тоже свободны. Так мы гарантируем что пройдёт тело,
  // а не один пиксель в гистограмме.
  private static final int WINDOW_HALF = 2;

  // ───── состояние ─────

  // primary гистограмма — обнуляется каждый plan().
  private final float[][] primary = new float[AZIMUTH_BINS][ELEVATION_BINS];

  // binary гистограмма — переносится между кадрами для гистерезиса.
  private final boolean[][] binary = new boolean[AZIMUTH_BINS][ELEVATION_BINS];

  // Прошлое решение — нужно для μ_SMOOTH в cost-функции.
  private int previousAzimuthBin = FORWARD_AZIMUTH_BIN;
  private int previousElevationBin = LEVEL_ELEVATION_BIN;

  /**
   * Главный метод. Прогоняет все этапы и возвращает выбранное направление.
   *
   * @param camX,camY,camZ позиция пользователя (= камеры).
   * @param forwardX,forwardZ куда «хочется идти». Это либо forward камеры либо сглаженный
   *     вектор реальной ходьбы — решает caller.
   */
  public Decision plan(
      Octomap octomap,
      float camX,
      float camY,
      float camZ,
      float forwardX,
      float forwardZ) {

    // Если телефон вертикально (forward проекция почти 0) — направление не понять.
    float fLen = (float) Math.sqrt(forwardX * forwardX + forwardZ * forwardZ);
    if (fLen < 1e-3f) {
      return Decision.unavailable("Поднимите телефон, наклон слишком сильный.");
    }
    forwardX /= fLen;
    forwardZ /= fLen;

    // Вектор «вправо» в горизонтальной плоскости — перпендикуляр к forward.
    float rightX = forwardZ;
    float rightZ = -forwardX;

    // ───── обнулим primary гистограмму ─────
    for (int a = 0; a < AZIMUTH_BINS; a++) {
      for (int e = 0; e < ELEVATION_BINS; e++) {
        primary[a][e] = 0f;
      }
    }

    // === Этапы 1+2 (§4.1, §4.2): пробегаем все воксели и наполняем primary ===

    float cellHalf = octomap.cellSizeMeters * 0.5f;
    // envelope — это «r_r + s + r_v» из формул (4) и (5). Радиус «воздушной подушки»
    // вокруг пользователя.
    float envelope = USER_RADIUS_M + SAFETY_RADIUS_M + cellHalf;

    int activeVoxelCount = 0;
    for (Octomap.Voxel v : octomap.voxels().values()) {
      // Фильтр шума: и hits и средняя уверенность должны быть нормальными.
      if (v.seenFrameCount < 2) continue;
      if (v.occupancyWeight < 2.0f) continue;

      float vxCenter = v.worldCenterX(octomap.cellSizeMeters);
      float vyCenter = v.worldCenterY(octomap.cellSizeMeters);
      float vzCenter = v.worldCenterZ(octomap.cellSizeMeters);

      float vx = v.weightedAvgX(vxCenter);
      float vy = v.weightedAvgY(vyCenter);
      float vz = v.weightedAvgZ(vzCenter);

      float dx = vx - camX;
      float dy = vy - camY;
      float dz = vz - camZ;
      float distSq = dx * dx + dy * dy + dz * dz;

      // §4.1: дальние воксели игнорим.
      if (distSq > BOUNDING_RADIUS_M * BOUNDING_RADIUS_M) continue;
      float dist = (float) Math.sqrt(distSq);
      if (dist < 1e-3f) continue; // воксель прямо в камере — atan/asin сошёл бы с ума
      activeVoxelCount++;

      // Раскладываем (dx, dz) на «вперёд» и «вправо».
      float forwardDist = dx * forwardX + dz * forwardZ;
      float rightDist = dx * rightX + dz * rightZ;
      float horizDist =
          (float) Math.sqrt(forwardDist * forwardDist + rightDist * rightDist);

      // (1) азимут — угол в горизонтальной плоскости
      float azimuth = (float) Math.atan2(rightDist, forwardDist);
      // (2) элевация — угол подъёма над горизонтом
      float elevation = (float) Math.atan2(dy, Math.max(0.001f, horizDist));

      int azBin = azimuthBin(azimuth);
      int elBin = elevationBin(elevation);

      // (4) расширение footprint'а: чем ближе воксель — тем «шире» он перекрывает.
      // λ = arcsin(envelope / dist). Если воксель в самом теле (envelope > dist) —
      // блокируем полусферу.
      float ratio = envelope / dist;
      int lambdaBins;
      if (ratio >= 1f) {
        lambdaBins = AZIMUTH_BINS;
      } else {
        float lambda = (float) Math.asin(ratio);
        lambdaBins = (int) Math.ceil(lambda / ALPHA_RAD);
      }
      if (lambdaBins < 1) lambdaBins = 1;

      // (6) вес препятствия: H = occ² · (a − b·l). Чем уверенней воксель и чем он ближе —
      // тем сильнее «давит» на гистограмму.
      float occ = Math.min(1f, v.occupancyWeight / Octomap.MAX_OCCUPANCY_WEIGHT);
      float l = Math.max(0f, dist - envelope);
      float baseWeight = occ * occ * Math.max(0f, A_CONST - B_CONST * l);
      if (baseWeight <= 0f) continue;

      // Заливаем вес в прямоугольник (2λ+1) × (2λ+1) ячеек вокруг (azBin, elBin).
      // Wrap по азимуту (круговой), clamp по элевации.
      int azClampHalf = Math.min(lambdaBins, AZIMUTH_BINS / 2);
      for (int da = -azClampHalf; da <= azClampHalf; da++) {
        int aIdx = ((azBin + da) % AZIMUTH_BINS + AZIMUTH_BINS) % AZIMUTH_BINS;
        for (int de = -lambdaBins; de <= lambdaBins; de++) {
          int eIdx = elBin + de;
          if (eIdx < 0 || eIdx >= ELEVATION_BINS) continue;
          primary[aIdx][eIdx] += baseWeight;
        }
      }
    }

    // === Этап 4 (§4.4): бинаризация с гистерезисом (eq 18) ===
    for (int a = 0; a < AZIMUTH_BINS; a++) {
      for (int e = 0; e < ELEVATION_BINS; e++) {
        float w = primary[a][e];
        if (w >= TAU_HIGH) {
          binary[a][e] = true; // точно занято
        } else if (w < TAU_LOW) {
          binary[a][e] = false; // точно свободно
        }
        // Между TAU_LOW и TAU_HIGH — оставляем как было в прошлом кадре. Гистерезис.
      }
    }

    // === Этап 5 (§4.5): ищем минимум cost-функции среди свободных ячеек ===

    int bestAz = -1;
    int bestEl = -1;
    float bestCost = Float.MAX_VALUE;
    for (int a = 0; a < AZIMUTH_BINS; a++) {
      for (int e = 0; e < ELEVATION_BINS; e++) {
        if (!isPathOpen(a, e)) continue;
        // (19) cost = μ_GOAL · отклонение от цели + μ_ELEV · отклонение от уровня +
        //             μ_SMOOTH · отклонение от прошлого совета
        float cost =
            MU_GOAL * azimuthDist(a, FORWARD_AZIMUTH_BIN)
                + MU_ELEVATION * Math.abs(e - LEVEL_ELEVATION_BIN)
                + MU_SMOOTH
                    * (azimuthDist(a, previousAzimuthBin)
                        + Math.abs(e - previousElevationBin));
        if (cost < bestCost) {
          bestCost = cost;
          bestAz = a;
          bestEl = e;
        }
      }
    }

    // Ничего свободного не нашли.
    if (bestAz < 0) {
      previousAzimuthBin = FORWARD_AZIMUTH_BIN;
      previousElevationBin = LEVEL_ELEVATION_BIN;
      if (activeVoxelCount == 0) {
        return Decision.unavailable("Карта пуста. Поводите телефоном вокруг.");
      }
      return Decision.blocked("Стоп. Препятствия со всех сторон.");
    }

    // Запоминаем выбор для следующего кадра (μ_SMOOTH использует это).
    previousAzimuthBin = bestAz;
    previousElevationBin = bestEl;

    // Преобразуем бины обратно в градусы — для голосовой фразы.
    float azimuthDeg = (bestAz - FORWARD_AZIMUTH_BIN) * 10f;
    if (azimuthDeg > 180f) azimuthDeg -= 360f;
    if (azimuthDeg < -180f) azimuthDeg += 360f;
    float elevationDeg = (bestEl - LEVEL_ELEVATION_BIN) * 10f;
    return new Decision(
        true,
        azimuthDeg,
        elevationDeg,
        buildPhrase(azimuthDeg, elevationDeg),
        severityFor(azimuthDeg));
  }

  /**
   * Проверка окна вокруг кандидата — все ячейки в квадрате 5×5 должны быть свободны.
   * Так мы гарантируем что в это направление реально пролезет тело.
   */
  private boolean isPathOpen(int azCenter, int elCenter) {
    for (int da = -WINDOW_HALF; da <= WINDOW_HALF; da++) {
      int a = ((azCenter + da) % AZIMUTH_BINS + AZIMUTH_BINS) % AZIMUTH_BINS;
      for (int de = -WINDOW_HALF; de <= WINDOW_HALF; de++) {
        int e = elCenter + de;
        if (e < 0 || e >= ELEVATION_BINS) continue;
        if (binary[a][e]) return false;
      }
    }
    return true;
  }

  /** Сброс — при выходе из режима или ручном reset'е. */
  public void reset() {
    previousAzimuthBin = FORWARD_AZIMUTH_BIN;
    previousElevationBin = LEVEL_ELEVATION_BIN;
    for (int a = 0; a < AZIMUTH_BINS; a++) {
      for (int e = 0; e < ELEVATION_BINS; e++) {
        primary[a][e] = 0f;
        binary[a][e] = false;
      }
    }
  }

  // ───── маленькие утилиты ─────

  private static int azimuthBin(float azimuthRad) {
    int bin = (int) Math.floor(azimuthRad / ALPHA_RAD) + FORWARD_AZIMUTH_BIN;
    return ((bin % AZIMUTH_BINS) + AZIMUTH_BINS) % AZIMUTH_BINS;
  }

  private static int elevationBin(float elevationRad) {
    int bin = (int) Math.floor(elevationRad / ALPHA_RAD) + LEVEL_ELEVATION_BIN;
    if (bin < 0) bin = 0;
    if (bin >= ELEVATION_BINS) bin = ELEVATION_BINS - 1;
    return bin;
  }

  /** Кратчайшее расстояние между бинами по кругу (учёт wrap-around). */
  private static int azimuthDist(int a, int b) {
    int d = Math.abs(a - b);
    return Math.min(d, AZIMUTH_BINS - d);
  }

  // ───── голосовые фразы ─────

  /**
   * По углу отклонения собираем фразу для пешехода. Элевацию НЕ озвучиваем — пол
   * обычно даёт ложно положительную элевацию (телефон чуть наклонён вниз), и фраза
   * «препятствие сверху» срабатывала когда никакого препятствия нет.
   */
  private static String buildPhrase(float azimuthDeg, float elevationDeg) {
    String dir = azimuthDeg >= 0 ? "вправо" : "влево";
    float aAbs = Math.abs(azimuthDeg);
    if (aAbs < 10f) return "Путь свободен.";
    if (aAbs < 30f) return "Чуть " + dir + ".";
    if (aAbs < 60f) return "Поверните " + dir + ".";
    if (aAbs < 120f) return "Резко " + dir + ".";
    return "Развернитесь " + dir + ".";
  }

  /** Severity по углу — дирижёр использует это для выбора кулдауна. */
  private static Depth20ScanResult.Severity severityFor(float azimuthDeg) {
    float aAbs = Math.abs(azimuthDeg);
    if (aAbs < 10f) return Depth20ScanResult.Severity.CLEAR;
    if (aAbs < 30f) return Depth20ScanResult.Severity.INFO;
    if (aAbs < 60f) return Depth20ScanResult.Severity.WARNING;
    return Depth20ScanResult.Severity.STOP;
  }

  /** Результат plan(). Иммутабельный. */
  public static final class Decision {
    public final boolean hasPath;
    public final float azimuthDeg;
    public final float elevationDeg;
    public final String message;
    public final Depth20ScanResult.Severity severity;

    public Decision(
        boolean hasPath,
        float azimuthDeg,
        float elevationDeg,
        String message,
        Depth20ScanResult.Severity severity) {
      this.hasPath = hasPath;
      this.azimuthDeg = azimuthDeg;
      this.elevationDeg = elevationDeg;
      this.message = message;
      this.severity = severity;
    }

    /** Не смогли посчитать (вертикально, карта пуста и т.п.). */
    static Decision unavailable(String message) {
      return new Decision(false, 0f, 0f, message, Depth20ScanResult.Severity.WARNING);
    }

    /** Все направления заблокированы. */
    static Decision blocked(String message) {
      return new Decision(false, 0f, 0f, message, Depth20ScanResult.Severity.STOP);
    }

    /** Пустое решение — дирижёр пропускает. Используется при scanning. */
    public static Decision muted() {
      return new Decision(false, 0f, 0f, "", Depth20ScanResult.Severity.CLEAR);
    }
  }
}
