package com.google.ar.core.examples.java.helloar.octomap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Воксельная карта (типа octomap). Кубики 10×10×10 см лежат в HashMap по упакованному ключу.
 *
 * <p>Настоящий octomap — это octree (дерево), но нам это перебор: сцена маленькая, ячеек
 * максимум несколько тысяч, плоский HashMap проще и быстрее.
 *
 * <p>Что хранится на воксель:
 * <ul>
 *   <li>hits — сколько раз сюда попадали depth-пиксели (0..30).</li>
 *   <li>lastSeenFrame — на каком кадре последний раз обновляли. Нужно чтобы старые
 *       воксели не висели вечно.</li>
 *   <li>avgConfidence — средняя уверенность пикселей, формирующих воксель (плавающее
 *       среднее). Используется как фильтр шума.</li>
 *   <li>sumWeight, sumWeightedY — для усреднения реальной высоты внутри ячейки. Берём
 *       не центр кубика, а взвешенное среднее по всем измерениям.</li>
 * </ul>
 *
 * <p>Класс не потокобезопасный — всё дёргается с GL-потока.
 */
public class Octomap {

  // ───── упаковка координат в long ─────
  // Каждая координата кладётся в 20 бит. Со знаковым смещением получается диапазон
  // ±524288 ячеек — на любую разумную сцену с запасом.
  private static final int COORD_BITS = 20;
  private static final long COORD_MASK = (1L << COORD_BITS) - 1L;
  private static final int COORD_OFFSET = 1 << (COORD_BITS - 1);

  // Потолок счётчика hits. Чтобы при долгом наблюдении число не разогналось до миллионов
  // (тогда decay не успеет ячейку удалить). 30 — за секунду стабильного просмотра ячейка
  // насыщается, дальше hits не растёт.
  public static final int MAX_HITS = 30;

  // Когда Σc разрастается слишком сильно — делим вдвое (и Σc и Σc·y), чтобы старые
  // наблюдения не доминировали в среднем. Без этого долгая стабильная сессия завалила бы
  // вес и новые точки никак на цвет не повлияли.
  private static final float MAX_WEIGHT_ACCUMULATOR = 100.0f;

  // Если ray-cast встречает воксель с hits >= 4 на пути к точке попадания — НЕ дёргаем его
  // (и говорим caller'у остановиться). Это чтобы шумный одиночный луч не прорыл в стене
  // дырку.
  public static final int RAYCAST_BLOCKED_THRESHOLD = 4;


  public static final float MAX_OCCUPANCY_WEIGHT = 30.0f;
  private static final float MIN_HIT_WEIGHT = 0.02f;
  private static final float MISS_WEIGHT = 0.5f;

  // ───── состояние ─────

  public final float cellSizeMeters;
  public final int maxVoxels;

  private final HashMap<Long, Voxel> voxels = new HashMap<>(8192);
  private long currentFrame = 0L;

  // version меняется при ЛЮБОМ изменении карты. Renderer его читает чтобы понимать —
  // надо ли перебирать воксели заново или mesh с прошлого раза ещё валиден.
  private long version = 0L;

  public Octomap(float cellSizeMeters, int maxVoxels) {
    this.cellSizeMeters = cellSizeMeters;
    this.maxVoxels = maxVoxels;
  }

  /** Вызывается раз в начале каждой интеграции — двигает счётчик кадров. */
  public void beginFrame() {
    currentFrame++;
  }

  public long currentFrame() {
    return currentFrame;
  }

  public Map<Long, Voxel> voxels() {
    return voxels;
  }

  public int size() {
    return voxels.size();
  }

  public void clear() {
    voxels.clear();
    currentFrame = 0L;
    version++;
  }

  public long version() {
    return version;
  }

  // ───── интеграция точки попадания ─────

  /** Совместимая перегрузка без явной уверенности. */
  public void integrateHit(float worldX, float worldY, float worldZ, float confidence) {
    float hitWeight = confidenceToWeight(confidence);
    if (hitWeight < MIN_HIT_WEIGHT) {
      return;
    }

    int cx = (int) Math.floor(worldX / cellSizeMeters);
    int cy = (int) Math.floor(worldY / cellSizeMeters);
    int cz = (int) Math.floor(worldZ / cellSizeMeters);
    long key = packKey(cx, cy, cz);

    Voxel v = voxels.get(key);
    if (v == null) {
      if (voxels.size() >= maxVoxels) {
        return;
      }

      v = new Voxel(cx, cy, cz);
      v.avgConfidence = confidence;
      v.firstSeenFrame = currentFrame;
      v.lastSeenFrame = -1L;

      voxels.put(key, v);
      version++;
    } else {
      v.avgConfidence = 0.7f * v.avgConfidence + 0.3f * confidence;
    }

    int prevHits = v.hits;
    float prevOccupancy = v.occupancyWeight;

    // Старый счётчик пока оставляем для совместимости.
    if (v.hits < MAX_HITS) {
      v.hits++;
    }

    // Новый основной показатель занятости.
    v.occupancyWeight =
            Math.min(MAX_OCCUPANCY_WEIGHT, v.occupancyWeight + hitWeight);

    // Чтобы старые наблюдения не доминировали бесконечно.
    if (v.sumWeight > MAX_WEIGHT_ACCUMULATOR) {
      v.sumWeight *= 0.5f;
      v.sumWeightedX *= 0.5f;
      v.sumWeightedY *= 0.5f;
      v.sumWeightedZ *= 0.5f;
    }

    v.sumWeight += hitWeight;
    v.sumWeightedX += hitWeight * worldX;
    v.sumWeightedY += hitWeight * worldY;
    v.sumWeightedZ += hitWeight * worldZ;

    if (v.lastSeenFrame != currentFrame) {
      v.seenFrameCount++;
    }

    v.lastSeenFrame = currentFrame;

    if (prevHits != v.hits || prevOccupancy != v.occupancyWeight) {
      version++;
    }
  }

  public int pruneUnconfirmedVoxels(int maxAgeFrames, float minWeight) {
    int removed = 0;

    Iterator<Map.Entry<Long, Voxel>> it = voxels.entrySet().iterator();
    while (it.hasNext()) {
      Voxel v = it.next().getValue();

      boolean oldEnough = currentFrame - v.firstSeenFrame >= maxAgeFrames;
      boolean weak = v.occupancyWeight < minWeight;
      boolean unconfirmed = v.seenFrameCount < 2;

      if (oldEnough && unconfirmed && weak) {
        it.remove();
        removed++;
        version++;
      }
    }

    return removed;
  }

  // ───── ray-cast: ячейка пройдена пустым лучом ─────

  /**
   * Луч прошёл сквозь эту ячейку — значит здесь свободно. Снимаем 1 hit, при 0 удаляем.
   *
   * <p>Возвращает true если ячейка уже подтверждённое препятствие (hits ≥ 4) — тогда мы
   * её НЕ трогаем (стена не должна продырываться от одного шумного луча) и сигналим
   * caller'у остановить ray-cast.
   */
  public boolean integrateMissCell(int cx, int cy, int cz) {
    long key = packKey(cx, cy, cz);
    Voxel v = voxels.get(key);
    if (v == null) {
      return false;
    }

    if (v.hits >= RAYCAST_BLOCKED_THRESHOLD) {
      return true;
    }

    v.hits--;
    v.occupancyWeight = Math.max(0f, v.occupancyWeight - MISS_WEIGHT);

    version++;

    if (v.hits <= 0 && v.occupancyWeight <= 0f) {
      voxels.remove(key);
    }

    return false;
  }

  // ───── чистка карты ─────

  /**
   * Снижает hits у вокселей, которых давно не видели (прошло ≥ staleFrames кадров без hit).
   * Когда hits падает до 0 — ячейка удаляется.
   *
   * <p>Зачем нужно: ARCore-трекинг плывёт со временем, и старые воксели оказываются в
   * неправильном месте. Если их не чистить — карта будет двоиться. Decay убирает то, что
   * мы перестали видеть. (Видимое чистит ray-cast — он быстрее.)
   */
  public int decayStaleVoxels(int staleFrames, int decayAmount) {
    if (decayAmount <= 0) {
      return 0;
    }
    int removed = 0;
    Iterator<Map.Entry<Long, Voxel>> it = voxels.entrySet().iterator();
    while (it.hasNext()) {
      Voxel v = it.next().getValue();
      if (currentFrame - v.lastSeenFrame < staleFrames) {
        continue;
      }
      v.hits -= decayAmount;
      v.occupancyWeight = Math.max(0f, v.occupancyWeight - decayAmount);
      version++;
      if (v.hits <= 0 && v.occupancyWeight <= 0f) {
        it.remove();
        removed++;
      }
    }
    return removed;
  }

  /** Удаляет воксели, которые сейчас дальше radius от пользователя. Они нам всё равно не нужны. */
  public int cullFarVoxels(float cx, float cy, float cz, float radius) {
    float r2 = radius * radius;
    int removed = 0;
    Iterator<Map.Entry<Long, Voxel>> it = voxels.entrySet().iterator();
    while (it.hasNext()) {
      Voxel v = it.next().getValue();
      float dx = v.worldCenterX(cellSizeMeters) - cx;
      float dy = v.worldCenterY(cellSizeMeters) - cy;
      float dz = v.worldCenterZ(cellSizeMeters) - cz;
      if (dx * dx + dy * dy + dz * dz > r2) {
        it.remove();
        removed++;
        version++;
      }
    }
    return removed;
  }

  // 3 int → один long. Сдвигаем на 20 бит каждую координату, со смещением чтобы
  // отрицательные числа тоже укладывались в беззнаковое поле.
  private static long packKey(int x, int y, int z) {
    long sx = ((long) (x + COORD_OFFSET)) & COORD_MASK;
    long sy = ((long) (y + COORD_OFFSET)) & COORD_MASK;
    long sz = ((long) (z + COORD_OFFSET)) & COORD_MASK;
    return sx | (sy << COORD_BITS) | (sz << (2 * COORD_BITS));
  }


  private static float confidenceToWeight(float confidence) {
    float c = Math.max(0f, Math.min(1f, confidence));

    // Совсем мусор почти не влияет.
    if (c < 0.02f) {
      return 0f;
    }

    // Нелинейное взвешивание:
    // c=1.0 -> 1.00
    // c=0.5 -> 0.25
    // c=0.2 -> 0.04
    return c * c;
  }

  /**
   * Один воксель. Поля public — это hot-path, лишние invokes ни к чему.
   */
  public static final class Voxel {
    public final int cx, cy, cz;
    public int hits;
    public long lastSeenFrame;
    /** EMA уверенности пикселей которые сюда попали. */
    public float avgConfidence = 1.0f;

    /** Σ c — сумма уверенностей всех hits в этой ячейке. */
    public float sumWeight = 0f;
    /** Σ c·y — взвешенная сумма реальных высот. */
    public float sumWeightedY = 0f;

    /** Σ c·x — взвешенная сумма X. */
    public float sumWeightedX = 0f;

    /** Σ c·z — взвешенная сумма Z. */
    public float sumWeightedZ = 0f;


    /** Взвешенная занятость: сумма confidence-весов, а не просто количество hits. */
    public float occupancyWeight = 0f;

    Voxel(int cx, int cy, int cz) {
      this.cx = cx;
      this.cy = cy;
      this.cz = cz;
      this.firstSeenFrame = -1;
    }

    public int seenFrameCount = 0;
    public long firstSeenFrame = -1;

    public float worldCenterX(float cellSize) {
      return (cx + 0.5f) * cellSize;
    }

    public float worldCenterY(float cellSize) {
      return (cy + 0.5f) * cellSize;
    }

    public float worldCenterZ(float cellSize) {
      return (cz + 0.5f) * cellSize;
    }

    /**
     * Среднее Y по всем наблюдениям, взвешенное по уверенности.
     * Формула: Σ(c·y) / Σc. Точнее центра ячейки — точность не 10 см, а сколько данных есть.
     * Если измерений ещё нет — возвращает fallback (обычно центр ячейки).
     */
    public float weightedAvgX(float fallbackX) {
      return sumWeight > 1e-6f ? sumWeightedX / sumWeight : fallbackX;
    }

    public float weightedAvgY(float fallbackY) {
      return sumWeight > 1e-6f ? sumWeightedY / sumWeight : fallbackY;
    }

    public float weightedAvgZ(float fallbackZ) {
      return sumWeight > 1e-6f ? sumWeightedZ / sumWeight : fallbackZ;
    }
  }
}
