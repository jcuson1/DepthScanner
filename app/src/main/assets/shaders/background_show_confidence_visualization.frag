#version 300 es
/*
 * Copyright 2026
 */
precision mediump float;

uniform sampler2D u_RawDepthConfidenceTexture;

in vec2 v_CameraTexCoord;

layout(location = 0) out vec4 o_FragColor;

void main() {
  float confidence = texture(u_RawDepthConfidenceTexture, v_CameraTexCoord).r;
  o_FragColor = vec4(vec3(confidence), 1.0);
}
