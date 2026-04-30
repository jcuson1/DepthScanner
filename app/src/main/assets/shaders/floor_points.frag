#version 300 es
/*
 * Fragment shader for the accumulated-point 3D floor visualization.
 *
 * Draws each GL_POINT as a soft circular disc. Each point also carries a
 * "kind" value classifying it as floor (0), wall (1) or ceiling (2); the
 * color is chosen from a different gradient per class so the user can tell
 * the three apart at a glance.
 */

precision mediump float;

in float v_Height;
in float v_Kind;
in float v_Valid;

uniform float u_MinHeight;
uniform float u_MaxHeight;

out vec4 o_FragColor;

vec3 floorColor(float t) {
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

vec3 wallColor(float t) {
  // Deep violet at the base of the wall to bright magenta near the ceiling.
  t = clamp(t, 0.0, 1.0);
  return mix(vec3(0.30, 0.05, 0.55), vec3(0.95, 0.25, 0.85), t);
}

vec3 ceilingColor() {
  return vec3(0.25, 0.30, 0.45);
}

void main() {
  if (v_Valid < 0.5) {
    discard;
  }
  vec2 coord = gl_PointCoord * 2.0 - 1.0;
  float r2 = dot(coord, coord);
  if (r2 > 1.0) {
    discard;
  }
  float alpha = smoothstep(1.0, 0.6, r2);

  float range = max(u_MaxHeight - u_MinHeight, 0.001);
  float t = (v_Height - u_MinHeight) / range;

  vec3 color;
  if (v_Kind > 1.5) {
    color = ceilingColor();
  } else if (v_Kind > 0.5) {
    color = wallColor(t);
  } else {
    color = floorColor(t);
  }
  o_FragColor = vec4(color, alpha);
}
