#version 300 es
/*
 * Vertex shader for instanced voxel-cube rendering of the octomap.
 *
 * One static unit cube is shared across all instances. Per-instance attribute carries
 * (worldX, worldY, worldZ, heightForColour). The shader translates the unit cube to the
 * voxel centre, scales it by u_VoxelSize, and forwards the colour-height value to the
 * shared floor_heightmap.frag fragment shader.
 */

uniform mat4 u_ViewProjection;
uniform float u_VoxelSize;

layout(location = 0) in vec3 a_LocalPos;        // unit-cube vertex in [-0.5, +0.5]^3
layout(location = 1) in vec4 a_Instance;        // (cx, cy, cz, heightY)

out float v_Height;
out float v_Valid;

void main() {
  vec3 worldPos = a_Instance.xyz + a_LocalPos * u_VoxelSize;
  v_Height = a_Instance.w;
  v_Valid = 1.0;
  gl_Position = u_ViewProjection * vec4(worldPos, 1.0);
}
