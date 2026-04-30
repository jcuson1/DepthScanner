package com.google.ar.core.examples.java.helloar.octomap;

import android.opengl.GLES30;
import android.opengl.Matrix;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Shader.BlendFactor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Рендерим воксели как кубики через GPU instancing. Один кубик геометрии лежит на GPU
 * статически, на CPU мы только заполняем буфер позиций каждой ячейки. На draw — один
 * glDrawElementsInstanced рисует тысячи кубов одним вызовом.
 *
 * <p>Что выигрываем:
 * <ul>
 *   <li>VBO upload падает с ~600 КБ/кадр (если бы пересоздавали mesh) до ~50 КБ —
 *       только координаты ячеек.</li>
 *   <li>Один draw call вместо большого индексного draw'а.</li>
 * </ul>
 *
 * <p>Дополнительно:
 * <ul>
 *   <li>Frustum culling — кубики вне поля зрения вообще не попадают в буфер.</li>
 *   <li>Version-based batching — если octomap не менялся, заново буфер не собираем.</li>
 * </ul>
 *
 * <p>Класс использует raw GLES30 API (без обёртки Mesh) — обёртка не умеет per-instance
 * атрибуты.
 */
public class OctomapInstancedRenderer {

  // На каждый instance: vec4 (cx, cy, cz, heightForColour). 4 float'а.
  private static final int FLOATS_PER_INSTANCE = 4;

  // 12 треугольников × 3 индекса = 36.
  private static final int CUBE_INDEX_COUNT = 36;

  // Принудительная пересборка раз в 6 кадров даже если octomap не менялся. Safety net.
  private static final int MAX_FRAMES_BETWEEN_REBUILDS = 6;

  // 8 углов unit-кубика, центрированного в нуле. Vertex-shader умножит на u_VoxelSize и
  // сдвинет на a_Instance.xyz.
  private static final float[] CUBE_LOCAL_POSITIONS = {
    -0.5f, -0.5f, -0.5f,
    -0.5f, -0.5f, +0.5f,
    -0.5f, +0.5f, -0.5f,
    -0.5f, +0.5f, +0.5f,
    +0.5f, -0.5f, -0.5f,
    +0.5f, -0.5f, +0.5f,
    +0.5f, +0.5f, -0.5f,
    +0.5f, +0.5f, +0.5f
  };

  // Индексы шести граней куба (по 2 треугольника на грань).
  private static final int[] CUBE_INDICES = {
    // Грань -Z
    0, 4, 6, 0, 6, 2,
    // Грань +Z
    1, 3, 7, 1, 7, 5,
    // Грань -Y
    0, 1, 5, 0, 5, 4,
    // Грань +Y
    2, 6, 7, 2, 7, 3,
    // Грань -X
    0, 2, 3, 0, 3, 1,
    // Грань +X
    4, 5, 7, 4, 7, 6
  };

  // ───── состояние ─────

  private final int maxVoxels;
  private final Shader shader;

  private final int[] cubeVbo = {0}; // статичные вершины кубика (8 шт)
  private final int[] cubeIbo = {0}; // статичные индексы (36 шт)
  private final int[] instanceVbo = {0}; // динамический per-instance буфер
  private final int[] vao = {0}; // VAO, держит привязку всех буферов

  // CPU staging — заполняем сюда, потом одним glBufferSubData отправляем на GPU.
  private final FloatBuffer instanceData;

  // 6 плоскостей frustum'а × 4 float'а (nx, ny, nz, d).
  private final float[] frustumPlanes = new float[24];

  // Кэш view×projection — чтобы не считать дважды.
  private final float[] viewProjection = new float[16];

  // Сколько instance'ов в GPU прямо сейчас. Используется в draw().
  private int instanceCount = 0;

  // Динамические min/max для палитры цвета.
  private float lastMinHeight = 0f;
  private float lastMaxHeight = 1f;

  // Версия octomap'а на момент последнего обновления. -1 = ещё ничего не собирали.
  private long lastOctomapVersion = -1L;
  private int framesSinceLastBuild = MAX_FRAMES_BETWEEN_REBUILDS;

  /**
   * Конструктор: загрузка шейдера, создание VAO/VBO/IBO, привязка всех атрибутов.
   * Дальше на draw остаётся только glBindVertexArray + glDrawElementsInstanced.
   */
  public OctomapInstancedRenderer(SampleRender render, int maxVoxels) throws IOException {
    this.maxVoxels = maxVoxels;

    // Vertex shader специфичный для instancing'а, fragment — общий с floor heightmap.
    shader =
        Shader.createFromAssets(
                render,
                "shaders/octomap_voxel.vert",
                "shaders/floor_heightmap.frag",
                /* defines= */ null)
            .setFloat("u_Alpha", 0.9f)
            .setFloat("u_VoxelSize", OctomapModule.CELL_SIZE_METERS)
            .setBlend(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)
            .setDepthTest(true)
            .setDepthWrite(true)
            .setCullFace(false);

    // Staging буфер на CPU.
    instanceData =
        ByteBuffer.allocateDirect(maxVoxels * FLOATS_PER_INSTANCE * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    // Создаём GL объекты.
    GLES30.glGenVertexArrays(1, vao, 0);
    GLES30.glGenBuffers(1, cubeVbo, 0);
    GLES30.glGenBuffers(1, cubeIbo, 0);
    GLES30.glGenBuffers(1, instanceVbo, 0);

    GLES30.glBindVertexArray(vao[0]);

    // Заливаем статичные вершины куба в cubeVbo (location 0, divisor 0 = per-vertex).
    FloatBuffer cubePosBuf =
        ByteBuffer.allocateDirect(CUBE_LOCAL_POSITIONS.length * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    cubePosBuf.put(CUBE_LOCAL_POSITIONS).position(0);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo[0]);
    GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        CUBE_LOCAL_POSITIONS.length * Float.BYTES,
        cubePosBuf,
        GLES30.GL_STATIC_DRAW);
    GLES30.glEnableVertexAttribArray(0);
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * Float.BYTES, 0);
    GLES30.glVertexAttribDivisor(0, 0);

    // Индексы куба.
    IntBuffer cubeIdxBuf =
        ByteBuffer.allocateDirect(CUBE_INDICES.length * Integer.BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
    cubeIdxBuf.put(CUBE_INDICES).position(0);
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, cubeIbo[0]);
    GLES30.glBufferData(
        GLES30.GL_ELEMENT_ARRAY_BUFFER,
        CUBE_INDICES.length * Integer.BYTES,
        cubeIdxBuf,
        GLES30.GL_STATIC_DRAW);

    // Per-instance атрибут (location 1, divisor 1 = per-instance).
    // glVertexAttribDivisor(1, 1) → атрибут продвигается раз в instance, не раз в vertex.
    // Все 8 вершин одного куба получают один и тот же a_Instance.
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo[0]);
    GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        maxVoxels * FLOATS_PER_INSTANCE * Float.BYTES,
        null, // выделили место, заливать будем через glBufferSubData
        GLES30.GL_DYNAMIC_DRAW);
    GLES30.glEnableVertexAttribArray(1);
    GLES30.glVertexAttribPointer(
        1, 4, GLES30.GL_FLOAT, false, FLOATS_PER_INSTANCE * Float.BYTES, 0);
    GLES30.glVertexAttribDivisor(1, 1);

    // Чистим bind state — чтобы не задеть другие рендереры.
    GLES30.glBindVertexArray(0);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
  }

  /**
   * Собираем per-instance данные из octomap и заливаем в GPU.
   *
   * <p>Если octomap не менялся И прошло меньше MAX_FRAMES_BETWEEN_REBUILDS кадров — выходим
   * без работы. Это и есть batch-оптимизация — на стабильной карте мы 5-6 кадров просто
   * рисуем то что уже залито в GPU, без повторного заполнения буфера.
   */
  public void update(
      Octomap octomap, int hitThreshold, float[] projectionMatrix, float[] viewMatrix) {
    Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);

    // Проверка: меняется ли карта?
    long currentVersion = octomap.version();
    framesSinceLastBuild++;
    if (currentVersion == lastOctomapVersion
        && framesSinceLastBuild < MAX_FRAMES_BETWEEN_REBUILDS) {
      return;
    }
    lastOctomapVersion = currentVersion;
    framesSinceLastBuild = 0;

    extractFrustumPlanes(viewProjection);

    // Перебираем воксели и фильтруем.
    instanceData.clear();
    int count = 0;
    float cellSize = octomap.cellSizeMeters;
    // Радиус описанной сферы вокруг кубика — для frustum-теста. half × √3.
    float cullRadius = cellSize * 0.5f * 1.7321f;
    float minY = Float.POSITIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    for (Octomap.Voxel v : octomap.voxels().values()) {
      if (v.seenFrameCount < 2) continue;
      if (v.occupancyWeight < 2.0f) continue;
      if (count >= maxVoxels) break;

      float cx = v.worldCenterX(cellSize);
      float cy = v.worldCenterY(cellSize);
      float cz = v.worldCenterZ(cellSize);
      // Frustum culling: вне поля зрения — пропускаем.
      if (!sphereInFrustum(cx, cy, cz, cullRadius)) continue;

      // Цвет берём по weighted-average Y — точнее, чем центр ячейки. Позиция куба — центр.
      float colorY = v.weightedAvgY(cy);
      if (colorY < minY) minY = colorY;
      if (colorY > maxY) maxY = colorY;
      instanceData.put(cx).put(cy).put(cz).put(colorY);
      count++;
    }
    instanceData.flip();
    instanceCount = count;

    // Заливаем только используемую часть.
    if (count > 0) {
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo[0]);
      GLES30.glBufferSubData(
          GLES30.GL_ARRAY_BUFFER, 0, count * FLOATS_PER_INSTANCE * Float.BYTES, instanceData);
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
      // Палитра растягивается на текущий диапазон, минимум 50 см. Без минимума при «плоском»
      // обзоре (только пол) был бы один цвет на всю карту.
      lastMinHeight = minY - 0.05f;
      lastMaxHeight = Math.max(minY + 0.5f, maxY + 0.05f);
    }
  }

  /** Один draw call — все кубики разом. */
  public void draw(SampleRender render, float[] projectionMatrix, float[] viewMatrix) {
    if (instanceCount == 0) return;
    Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);
    shader.setMat4("u_ViewProjection", viewProjection);
    shader.setFloat("u_MinHeight", lastMinHeight);
    shader.setFloat("u_MaxHeight", lastMaxHeight);
    shader.lowLevelUse(); // активирует program, blend, depth-state, uniforms

    GLES30.glBindVertexArray(vao[0]);
    GLES30.glDrawElementsInstanced(
        GLES30.GL_TRIANGLES, CUBE_INDEX_COUNT, GLES30.GL_UNSIGNED_INT, 0, instanceCount);
    GLES30.glBindVertexArray(0);
  }

  /** Нужно для diagnostic overlay'я. */
  public int getInstanceCount() {
    return instanceCount;
  }

  /**
   * Достаёт 6 плоскостей frustum'а напрямую из view×projection матрицы (метод
   * Gribb-Hartmann). Каждая плоскость — линейная комбинация строк/столбцов VP-матрицы.
   * Дальше тестируем сферу вокселя против каждой плоскости.
   */
  private void extractFrustumPlanes(float[] m) {
    // m в column-major. Шесть плоскостей: левая, правая, низ, верх, ближняя, дальняя.
    // Left
    frustumPlanes[0] = m[3] + m[0];
    frustumPlanes[1] = m[7] + m[4];
    frustumPlanes[2] = m[11] + m[8];
    frustumPlanes[3] = m[15] + m[12];
    // Right
    frustumPlanes[4] = m[3] - m[0];
    frustumPlanes[5] = m[7] - m[4];
    frustumPlanes[6] = m[11] - m[8];
    frustumPlanes[7] = m[15] - m[12];
    // Bottom
    frustumPlanes[8] = m[3] + m[1];
    frustumPlanes[9] = m[7] + m[5];
    frustumPlanes[10] = m[11] + m[9];
    frustumPlanes[11] = m[15] + m[13];
    // Top
    frustumPlanes[12] = m[3] - m[1];
    frustumPlanes[13] = m[7] - m[5];
    frustumPlanes[14] = m[11] - m[9];
    frustumPlanes[15] = m[15] - m[13];
    // Near
    frustumPlanes[16] = m[3] + m[2];
    frustumPlanes[17] = m[7] + m[6];
    frustumPlanes[18] = m[11] + m[10];
    frustumPlanes[19] = m[15] + m[14];
    // Far
    frustumPlanes[20] = m[3] - m[2];
    frustumPlanes[21] = m[7] - m[6];
    frustumPlanes[22] = m[11] - m[10];
    frustumPlanes[23] = m[15] - m[14];

    // Нормализуем — делим (a,b,c,d) на |(a,b,c)|.
    for (int i = 0; i < 6; i++) {
      int o = i * 4;
      float nx = frustumPlanes[o];
      float ny = frustumPlanes[o + 1];
      float nz = frustumPlanes[o + 2];
      float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (len > 1e-6f) {
        frustumPlanes[o] /= len;
        frustumPlanes[o + 1] /= len;
        frustumPlanes[o + 2] /= len;
        frustumPlanes[o + 3] /= len;
      }
    }
  }

  /** Сфера полностью снаружи frustum'а — false. Иначе (внутри либо пересекает) — true. */
  private boolean sphereInFrustum(float x, float y, float z, float radius) {
    for (int i = 0; i < 6; i++) {
      int o = i * 4;
      float dist =
          frustumPlanes[o] * x
              + frustumPlanes[o + 1] * y
              + frustumPlanes[o + 2] * z
              + frustumPlanes[o + 3];
      if (dist < -radius) return false;
    }
    return true;
  }
}
