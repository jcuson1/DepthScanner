#version 300 es
/*
 * Fragment shader for the 3D floor heightmap visualization.
 *
 * Colors each pixel based on its world-space Y coordinate, producing a
 * topographic gradient: deep blue (low) -> cyan -> green -> yellow -> red
 * (high), similar to an engineering/terrain elevation map.
 */

precision mediump float;

in float v_Height;
in float v_Valid;

uniform float u_MinHeight;
uniform float u_MaxHeight;
uniform float u_Alpha;

out vec4 o_FragColor;

vec3 heightToColor(float t) {
  t = clamp(t, 0.0, 1.0);
  if (t < 0.25) {
    return mix(vec3(0.05, 0.05, 0.55), vec3(0.0, 0.55, 0.95), t / 0.25);
  } else if (t < 0.50) {
    return mix(vec3(0.0, 0.55, 0.95), vec3(0.10, 0.85, 0.35), (t - 0.25) / 0.25);
  } else if (t < 0.75) {
    return mix(vec3(0.10, 0.85, 0.35), vec3(1.0, 0.90, 0.10), (t - 0.50) / 0.25);
  } else {
    return mix(vec3(1.0, 0.90, 0.10), vec3(0.90, 0.10, 0.05), (t - 0.75) / 0.25);
  }
}

void main() {
  if (v_Valid < 0.5) {
    discard;
  }
  float range = max(u_MaxHeight - u_MinHeight, 0.001);
  float t = (v_Height - u_MinHeight) / range;
  vec3 color = heightToColor(t);
  o_FragColor = vec4(color, u_Alpha);
}
