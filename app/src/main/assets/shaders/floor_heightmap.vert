#version 300 es
/*
 * Vertex shader for the 3D floor heightmap visualization.
 *
 * Each vertex carries a world-space position plus a "height" value that is
 * forwarded to the fragment shader so it can be mapped to a gradient color.
 * Invalid samples (no depth reading) are encoded with w < 0 so the fragment
 * shader can discard them.
 */

uniform mat4 u_ViewProjection;

layout(location = 0) in vec4 a_PositionHeight; // (x, y, z, height); w < 0 = invalid

out float v_Height;
out float v_Valid;

void main() {
  vec3 pos = a_PositionHeight.xyz;
  v_Height = a_PositionHeight.w;
  v_Valid = (a_PositionHeight.w > -9000.0) ? 1.0 : 0.0;
  gl_Position = u_ViewProjection * vec4(pos, 1.0);
}
