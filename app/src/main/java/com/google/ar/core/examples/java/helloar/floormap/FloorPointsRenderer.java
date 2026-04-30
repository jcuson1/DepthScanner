/*
 * Накопительный point cloud — для режима «3D точки пола».
 *
 * ARCore выдаёт feature points (углы, текстурные особенности) с уникальными ID. Мы их
 * накапливаем в LinkedHashMap по ID — каждый фич остаётся даже когда камера от него
 * отвернулась. Через несколько секунд водения телефоном собирается 3D-портрет комнаты.
 *
 * Точки рисуются как круглые цветные «диски» через GL_POINTS. Цвет — по высоте world Y
 * (палитра как у floor heightmap). Дополнительно классифицируем точку: пол / стена /
 * потолок — и красим разными градиентами.
 *
 * При повторном наблюдении точки её позиция фильтруется (low-pass) — гасит джиттер.
 */
package com.google.ar.core.examples.java.helloar.floormap;

import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Shader.BlendFactor;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FloorPointsRenderer {
  private static final int FLOATS_PER_VERTEX = 4; // x, y, z, kind
  private static final float MIN_CONFIDENCE = 0.15f;
  private static final float POINT_SIZE_PX = 14.0f;

  // Point kinds (must match the branches in floor_points.frag).
  private static final float KIND_FLOOR = 0.0f;
  private static final float KIND_WALL = 1.0f;
  private static final float KIND_CEILING = 2.0f;

  // Max perpendicular distance from a tracked plane for a point to be classified as belonging
  // to that plane. Anything farther is classified by the Y-based fallback.
  private static final float PLANE_SNAP_DISTANCE_M = 0.25f;

  // Cap stored points to keep memory and upload cost bounded. Older entries are dropped in
  // insertion order (LinkedHashMap, access-order = false).
  private static final int MAX_ACCUMULATED_POINTS = 30000;

  // Color gradient spans from the floor up to this many metres; wide enough to cover a full
  // flight of stairs so each step gets a visibly different color.
  private static final float COLOR_MIN_OFFSET_M = -0.05f;
  private static final float COLOR_MAX_OFFSET_M = 2.5f;

  private final Shader shader;
  private final VertexBuffer vertexBuffer;
  private final Mesh mesh;
  private FloatBuffer vertexData;

  // id -> {x, y, z}
  private final LinkedHashMap<Integer, float[]> accumulatedPoints =
      new LinkedHashMap<Integer, float[]>(1024, 0.75f, /* accessOrder= */ false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, float[]> eldest) {
          return size() > MAX_ACCUMULATED_POINTS;
        }
      };

  private float lastMinHeight = 0.0f;
  private float lastMaxHeight = 2.5f;

  public FloorPointsRenderer(SampleRender render) throws IOException {
    shader =
        Shader.createFromAssets(
                render,
                "shaders/floor_points.vert",
                "shaders/floor_points.frag",
                /* defines= */ null)
            .setFloat("u_PointSize", POINT_SIZE_PX)
            .setBlend(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)
            .setDepthTest(true)
            // Write depth so the virtual-scene framebuffer can be composited with proper
            // real-world occlusion by BackgroundRenderer.drawVirtualScene.
            .setDepthWrite(true);

    vertexData =
        ByteBuffer.allocateDirect(MAX_ACCUMULATED_POINTS * FLOATS_PER_VERTEX * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    vertexBuffer = new VertexBuffer(render, FLOATS_PER_VERTEX, /* entries= */ null);
    VertexBuffer[] vertexBuffers = {vertexBuffer};
    mesh = new Mesh(render, Mesh.PrimitiveMode.POINTS, /* indexBuffer= */ null, vertexBuffers);
  }

  /** Integrates the latest point cloud sample into the persistent accumulation store. */
  public void accumulate(PointCloud pointCloud) {
    if (pointCloud == null) {
      return;
    }
    FloatBuffer points = pointCloud.getPoints();
    IntBuffer ids = pointCloud.getIds();
    if (points == null || ids == null) {
      return;
    }
    // The point cloud buffers are shared views; other consumers (e.g. the default point cloud
    // vertex buffer upload) may advance their cursor. Rewind here to be self-contained, and use
    // absolute indexing so we don't rely on a particular cursor position.
    points.rewind();
    ids.rewind();
    int count = ids.limit();
    if (count > points.limit() / 4) {
      count = points.limit() / 4;
    }
    for (int i = 0; i < count; ++i) {
      int id = ids.get(i);
      int base = i * 4;
      float x = points.get(base);
      float y = points.get(base + 1);
      float z = points.get(base + 2);
      float confidence = points.get(base + 3);
      if (confidence < MIN_CONFIDENCE) {
        continue;
      }
      float[] stored = accumulatedPoints.get(id);
      if (stored == null) {
        accumulatedPoints.put(id, new float[] {x, y, z});
      } else {
        // Low-pass filter against the previously stored position to damp jitter when the
        // same feature is re-observed with a refined estimate.
        stored[0] = stored[0] * 0.6f + x * 0.4f;
        stored[1] = stored[1] * 0.6f + y * 0.4f;
        stored[2] = stored[2] * 0.6f + z * 0.4f;
      }
    }
  }

  /** Clears all accumulated points. */
  public void reset() {
    accumulatedPoints.clear();
  }

  /** Returns true if there is at least one accumulated point to draw. */
  public boolean hasData() {
    return !accumulatedPoints.isEmpty();
  }

  public int getAccumulatedPointCount() {
    return accumulatedPoints.size();
  }

  /** Uploads the current accumulation to GPU and returns true if there is something to draw. */
  public boolean uploadForDraw(Collection<Plane> planes) {
    if (accumulatedPoints.isEmpty()) {
      return false;
    }

    List<PlaneInfo> planeInfos = snapshotPlanes(planes);
    Float planeFloorY = lowestFloorY(planeInfos);
    float minY = Float.POSITIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    for (float[] p : accumulatedPoints.values()) {
      if (p[1] < minY) minY = p[1];
      if (p[1] > maxY) maxY = p[1];
    }
    float floorY = (planeFloorY != null) ? planeFloorY : minY;

    vertexData.clear();
    for (float[] p : accumulatedPoints.values()) {
      float kind = classifyPoint(p, planeInfos, floorY);
      vertexData.put(p[0]);
      vertexData.put(p[1]);
      vertexData.put(p[2]);
      vertexData.put(kind);
    }
    vertexData.flip();
    vertexBuffer.set(vertexData);

    lastMinHeight = floorY + COLOR_MIN_OFFSET_M;
    // Cap the upper bound so very distant ceiling points do not wash out the stair gradient,
    // but always at least cover the actual observed range.
    float observedCeiling = maxY - floorY;
    float upperOffset = Math.min(COLOR_MAX_OFFSET_M, Math.max(0.4f, observedCeiling));
    lastMaxHeight = floorY + upperOffset;

    return true;
  }

  public void draw(SampleRender render, float[] viewProjectionMatrix) {
    shader.setMat4("u_ViewProjection", viewProjectionMatrix);
    shader.setFloat("u_MinHeight", lastMinHeight);
    shader.setFloat("u_MaxHeight", lastMaxHeight);
    render.draw(mesh, shader);
  }

  /** Cached plane data (center + world-space normal + kind) used for per-point classification. */
  private static final class PlaneInfo {
    final float cx, cy, cz;
    final float nx, ny, nz;
    final float kind;

    PlaneInfo(float cx, float cy, float cz, float nx, float ny, float nz, float kind) {
      this.cx = cx;
      this.cy = cy;
      this.cz = cz;
      this.nx = nx;
      this.ny = ny;
      this.nz = nz;
      this.kind = kind;
    }
  }

  private List<PlaneInfo> snapshotPlanes(Collection<Plane> planes) {
    List<PlaneInfo> result = new ArrayList<>();
    if (planes == null) {
      return result;
    }
    float[] normalScratch = new float[3];
    for (Plane plane : planes) {
      if (plane.getTrackingState() != TrackingState.TRACKING || plane.getSubsumedBy() != null) {
        continue;
      }
      float kind;
      switch (plane.getType()) {
        case HORIZONTAL_UPWARD_FACING:
          kind = KIND_FLOOR;
          break;
        case HORIZONTAL_DOWNWARD_FACING:
          kind = KIND_CEILING;
          break;
        case VERTICAL:
          kind = KIND_WALL;
          break;
        default:
          continue;
      }
      Pose center = plane.getCenterPose();
      center.getTransformedAxis(1, 1.0f, normalScratch, 0);
      result.add(
          new PlaneInfo(
              center.tx(),
              center.ty(),
              center.tz(),
              normalScratch[0],
              normalScratch[1],
              normalScratch[2],
              kind));
    }
    return result;
  }

  private Float lowestFloorY(List<PlaneInfo> planeInfos) {
    Float best = null;
    for (PlaneInfo info : planeInfos) {
      if (info.kind != KIND_FLOOR) {
        continue;
      }
      if (best == null || info.cy < best) {
        best = info.cy;
      }
    }
    return best;
  }

  private float classifyPoint(float[] p, List<PlaneInfo> planeInfos, float floorY) {
    float bestDistance = PLANE_SNAP_DISTANCE_M;
    float bestKind = Float.NaN;
    for (PlaneInfo info : planeInfos) {
      // Perpendicular distance from the point to the plane (signed along the plane normal).
      float dx = p[0] - info.cx;
      float dy = p[1] - info.cy;
      float dz = p[2] - info.cz;
      float signed = dx * info.nx + dy * info.ny + dz * info.nz;
      float absDist = signed < 0.0f ? -signed : signed;
      if (absDist < bestDistance) {
        bestDistance = absDist;
        bestKind = info.kind;
      }
    }
    if (!Float.isNaN(bestKind)) {
      return bestKind;
    }
    // Fallback: use the point's world-space Y relative to the floor.
    float dyFloor = p[1] - floorY;
    if (dyFloor < 0.22f) {
      return KIND_FLOOR;
    }
    if (dyFloor > 2.3f) {
      return KIND_CEILING;
    }
    return KIND_WALL;
  }
}
