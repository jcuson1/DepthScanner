#version 300 es
/*
 * Vertex shader for the accumulated-point 3D floor visualization.
 *
 * Takes world-space points produced from the ARCore feature-point cloud plus
 * a height value and draws them as GL_POINTS; fragment color comes from the
 * shared floor_heightmap.frag gradient.
 */

uniform mat4 u_ViewProjection;
uniform float u_PointSize;

layout(location = 0) in vec4 a_PositionKind; // (x, y, z, kind)

out float v_Height;
out float v_Kind;
out float v_Valid;

void main() {
  // Use world-space Y directly as the height used for colorization.
  v_Height = a_PositionKind.y;
  v_Kind = a_PositionKind.w;
  v_Valid = 1.0;
  gl_Position = u_ViewProjection * vec4(a_PositionKind.xyz, 1.0);
  gl_PointSize = u_PointSize;
}
