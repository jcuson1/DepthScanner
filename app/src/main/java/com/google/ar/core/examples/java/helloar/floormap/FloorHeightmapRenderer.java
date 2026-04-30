/*
 * Рендерер 3D-карты пола.
 *
 * Что делает: строит регулярную сетку вершин в координатах depth-карты, для каждой
 * точки разворачивает (depth → camera local → world) и собирает mesh из треугольников.
 * Цвет вершин — по world-Y (высоте), радужная палитра «деньги/высота»: синий (низ) →
 * cyan → green → yellow → red (верх).
 *
 * Получается «топографическая карта» с настоящими неровностями пола — ступеньки, бордюры,
 * перепады уровня видны как цветовые градиенты.
 *
 * Mesh пересобирается каждый кадр. Это дорого, но проще чем накопительная карта:
 * floor heightmap всё равно работает только пока пользователь смотрит вниз.
 */
package com.google.ar.core.examples.java.helloar.floormap;

import android.media.Image;
import android.opengl.Matrix;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.samplerender.IndexBuffer;
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
import java.util.Collection;

public class FloorHeightmapRenderer {
  // Сетка 96×72 = 6912 вершин. Достаточно для гладкого вида и не слишком тяжело.
  private static final int GRID_COLS = 96;
  private static final int GRID_ROWS = 72;
  private static final int FLOATS_PER_VERTEX = 4; // x, y, z, height
  // Маркер «вершина невалидна» — фрагментный шейдер по нему делает discard.
  private static final float INVALID_HEIGHT = -10000.0f;

  // Допустимый диапазон depth для индорной сцены.
  private static final float MIN_VALID_DEPTH_M = 0.25f;
  private static final float MAX_VALID_DEPTH_M = 6.0f;

  // Полоса вокруг пола, в которой оставляем точки. Выше — это стены/двери/мебель,
  // их выкидываем, чтобы цветовой градиент фокусировался на деталях пола (порогах,
  // ступенях, бордюрах).
  private static final float FLOOR_BAND_BELOW_M = 0.08f;
  private static final float FLOOR_BAND_ABOVE_M = 0.35f;

  // Диапазон цвета относительно высоты пола. Узкий — чтобы кочка 3 см была уже зелёной,
  // а порог 10 см уходил в красный.
  private static final float COLOR_MIN_OFFSET_M = -0.02f;
  private static final float COLOR_MAX_OFFSET_M = 0.15f;

  private final Mesh mesh;
  private final VertexBuffer vertexBufferObject;
  private final IndexBuffer indexBufferObject;
  private final Shader shader;

  private final FloatBuffer vertexData;
  private final IntBuffer indexData;

  // Reusable scratch.
  private final float[] localPoint = new float[3];
  private float lastMinHeight = 0.0f;
  private float lastMaxHeight = 0.5f;

  public FloorHeightmapRenderer(SampleRender render) throws IOException {
    shader =
        Shader.createFromAssets(
                render,
                "shaders/floor_heightmap.vert",
                "shaders/floor_heightmap.frag",
                /* defines= */ null)
            .setFloat("u_Alpha", 0.75f)
            .setBlend(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)
            .setDepthTest(true)
            // Write to depth so the framebuffer we draw into can be composited with proper
            // real-world occlusion by BackgroundRenderer.drawVirtualScene.
            .setDepthWrite(true)
            .setCullFace(false);

    int vertexCount = GRID_COLS * GRID_ROWS;
    vertexData =
        ByteBuffer.allocateDirect(vertexCount * FLOATS_PER_VERTEX * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    // Worst case: every cell contributes two triangles.
    int maxIndexCount = (GRID_COLS - 1) * (GRID_ROWS - 1) * 6;
    indexData =
        ByteBuffer.allocateDirect(maxIndexCount * Integer.BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();

    vertexBufferObject = new VertexBuffer(render, FLOATS_PER_VERTEX, /* entries= */ null);
    indexBufferObject = new IndexBuffer(render, /* entries= */ null);
    VertexBuffer[] vertexBuffers = {vertexBufferObject};
    mesh =
        new Mesh(render, Mesh.PrimitiveMode.TRIANGLES, indexBufferObject, vertexBuffers);
  }

  /**
   * Samples the depth image, builds the heightmap mesh, and uploads it to the GPU.
   *
   * @return true if at least one valid vertex was produced, false otherwise.
   */
  public boolean update(Frame frame, Image depthImage, Collection<Plane> planes) {
    Camera camera = frame.getCamera();
    if (camera.getTrackingState() != TrackingState.TRACKING) {
      return false;
    }

    CameraIntrinsics intrinsics = camera.getImageIntrinsics();
    float[] focal = intrinsics.getFocalLength();
    float[] principal = intrinsics.getPrincipalPoint();
    int[] imageDims = intrinsics.getImageDimensions();
    int imageW = imageDims[0];
    int imageH = imageDims[1];
    if (imageW <= 0 || imageH <= 0) {
      return false;
    }

    int depthW = depthImage.getWidth();
    int depthH = depthImage.getHeight();
    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer depthBuffer = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int pixelStride = plane.getPixelStride();

    // Scale intrinsics from the full camera image down to the depth-image resolution.
    float fx = focal[0] * ((float) depthW / imageW);
    float fy = focal[1] * ((float) depthH / imageH);
    float cx = principal[0] * ((float) depthW / imageW);
    float cy = principal[1] * ((float) depthH / imageH);

    Pose cameraPose = camera.getPose();

    vertexData.clear();
    float sampledMinY = Float.POSITIVE_INFINITY;
    float sampledMaxY = Float.NEGATIVE_INFINITY;
    int validCount = 0;

    for (int r = 0; r < GRID_ROWS; ++r) {
      for (int c = 0; c < GRID_COLS; ++c) {
        float u = (c + 0.5f) / GRID_COLS;
        float v = (r + 0.5f) / GRID_ROWS;
        int px = (int) (u * depthW);
        int py = (int) (v * depthH);
        if (px >= depthW) px = depthW - 1;
        if (py >= depthH) py = depthH - 1;

        int byteOffset = py * rowStride + px * pixelStride;
        float depthMeters = -1.0f;
        if (byteOffset + 1 < depthBuffer.limit()) {
          int depthMm =
              (depthBuffer.get(byteOffset) & 0xFF)
                  | ((depthBuffer.get(byteOffset + 1) & 0xFF) << 8);
          if (depthMm > 0) {
            depthMeters = depthMm * 0.001f;
          }
        }

        if (depthMeters >= MIN_VALID_DEPTH_M && depthMeters <= MAX_VALID_DEPTH_M) {
          // Unproject pixel (px, py) with depth d into camera optical space (OpenCV convention:
          // +X right, +Y down, +Z forward), then convert to ARCore pose-local (OpenGL: +X right,
          // +Y up, -Z forward) by flipping Y and Z before transforming by the camera pose.
          float camX = (px - cx) * depthMeters / fx;
          float camY = (py - cy) * depthMeters / fy;
          localPoint[0] = camX;
          localPoint[1] = -camY;
          localPoint[2] = -depthMeters;
          float[] world = cameraPose.transformPoint(localPoint);
          vertexData.put(world[0]);
          vertexData.put(world[1]);
          vertexData.put(world[2]);
          vertexData.put(world[1]); // height = world Y
          if (world[1] < sampledMinY) sampledMinY = world[1];
          if (world[1] > sampledMaxY) sampledMaxY = world[1];
          validCount++;
        } else {
          vertexData.put(0.0f);
          vertexData.put(0.0f);
          vertexData.put(0.0f);
          vertexData.put(INVALID_HEIGHT);
        }
      }
    }
    vertexData.flip();

    if (validCount == 0) {
      vertexBufferObject.set(vertexData);
      return false;
    }

    // Decide floor reference: a tracked horizontal plane wins, otherwise fall back to the
    // lowest sampled Y (noisy but workable when no plane is tracked yet).
    Float planeFloorY = findLowestHorizontalPlaneY(planes);
    float floorY = (planeFloorY != null) ? planeFloorY : sampledMinY;

    // Filter out everything outside the floor band (walls, ceiling, furniture) by marking
    // those vertices invalid. This concentrates the gradient on real floor features.
    float minKeepY = floorY - FLOOR_BAND_BELOW_M;
    float maxKeepY = floorY + FLOOR_BAND_ABOVE_M;
    int totalVertices = GRID_COLS * GRID_ROWS;
    for (int i = 0; i < totalVertices; ++i) {
      int hOffset = i * FLOATS_PER_VERTEX + 3;
      float h = vertexData.get(hOffset);
      if (h <= -9000.0f) {
        continue;
      }
      if (h < minKeepY || h > maxKeepY) {
        vertexData.put(hOffset, INVALID_HEIGHT);
      }
    }
    vertexBufferObject.set(vertexData);

    // Rebuild triangle indices, but only emit triangles whose 4 corner samples are all valid
    // and close enough in depth to avoid stretched triangles across depth discontinuities.
    indexData.clear();
    float maxEdgeDrop = 0.25f; // meters; reject edges across which Y jumps too far
    for (int r = 0; r < GRID_ROWS - 1; ++r) {
      for (int c = 0; c < GRID_COLS - 1; ++c) {
        int a = r * GRID_COLS + c;
        int b = a + 1;
        int d = a + GRID_COLS;
        int e = d + 1;
        if (isValid(a) && isValid(b) && isValid(d) && isValid(e)
            && maxYGap(a, b, d, e) < maxEdgeDrop) {
          indexData.put(a).put(b).put(d);
          indexData.put(b).put(e).put(d);
        }
      }
    }
    indexData.flip();
    indexBufferObject.set(indexData);

    lastMinHeight = floorY + COLOR_MIN_OFFSET_M;
    lastMaxHeight = floorY + COLOR_MAX_OFFSET_M;

    return indexData.limit() > 0;
  }

  public void draw(SampleRender render, float[] projectionMatrix, float[] viewMatrix) {
    float[] viewProjection = new float[16];
    Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);
    shader.setMat4("u_ViewProjection", viewProjection);
    shader.setFloat("u_MinHeight", lastMinHeight);
    shader.setFloat("u_MaxHeight", lastMaxHeight);
    render.draw(mesh, shader);
  }

  private boolean isValid(int vertexIndex) {
    int offset = vertexIndex * FLOATS_PER_VERTEX + 3;
    return vertexData.get(offset) > -9000.0f;
  }

  private float maxYGap(int a, int b, int c, int d) {
    float ya = vertexData.get(a * FLOATS_PER_VERTEX + 1);
    float yb = vertexData.get(b * FLOATS_PER_VERTEX + 1);
    float yc = vertexData.get(c * FLOATS_PER_VERTEX + 1);
    float yd = vertexData.get(d * FLOATS_PER_VERTEX + 1);
    float min = Math.min(Math.min(ya, yb), Math.min(yc, yd));
    float max = Math.max(Math.max(ya, yb), Math.max(yc, yd));
    return max - min;
  }

  private Float findLowestHorizontalPlaneY(Collection<Plane> planes) {
    if (planes == null) {
      return null;
    }
    Float best = null;
    for (Plane p : planes) {
      if (p.getTrackingState() != TrackingState.TRACKING || p.getSubsumedBy() != null) {
        continue;
      }
      if (p.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) {
        continue;
      }
      float y = p.getCenterPose().ty();
      if (best == null || y < best) {
        best = y;
      }
    }
    return best;
  }
}
