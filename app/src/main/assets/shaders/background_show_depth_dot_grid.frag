#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
/*
 * Copyright 2026
 */
precision mediump float;

uniform samplerExternalOES u_CameraColorTexture;
uniform sampler2D u_CameraDepthTexture;
uniform float u_DepthAspectRatio;

in vec2 v_CameraTexCoord;

layout(location = 0) out vec4 o_FragColor;

float Depth_GetCameraDepthInMillimeters(const sampler2D depthTexture, const vec2 depthUv) {
  vec3 packedDepthAndVisibility = texture(depthTexture, depthUv).xyz;
  return dot(packedDepthAndVisibility.xy, vec2(255.0, 256.0 * 255.0));
}

vec3 getDepthBucketColor(float depthMeters) {
  if (depthMeters <= 0.75) {
    return vec3(1.0, 0.0, 0.0);
  }
  if (depthMeters <= 1.5) {
    return vec3(1.0, 0.5, 0.0);
  }
  if (depthMeters <= 3.0) {
    return vec3(1.0, 0.9, 0.0);
  }
  if (depthMeters <= 6.0) {
    return vec3(0.2, 0.8, 1.0);
  }
  return vec3(0.0, 0.2, 1.0);
}

void main() {
  vec3 cameraColor = texture(u_CameraColorTexture, v_CameraTexCoord).rgb;

  // A regular grid of circular points over the camera image.
  const vec2 kGridSize = vec2(90.0, 90.0);
  const float kDotRadius = 0.16;
  vec2 cell = fract(v_CameraTexCoord * kGridSize) - 0.5;
  float pointMask = 1.0 - step(kDotRadius, length(vec2(cell.x, cell.y * u_DepthAspectRatio)));

  float depthMeters =
      Depth_GetCameraDepthInMillimeters(u_CameraDepthTexture, v_CameraTexCoord) * 0.001;

  // Depth outside the supported range is ignored and only camera image is shown.
  if (depthMeters <= 0.0 || depthMeters > 8.0 || pointMask < 0.5) {
    o_FragColor = vec4(cameraColor, 1.0);
    return;
  }

  vec3 depthColor = getDepthBucketColor(depthMeters);
  vec3 composedColor = mix(cameraColor * 0.35, depthColor, 0.9);
  o_FragColor = vec4(composedColor, 1.0);
}
