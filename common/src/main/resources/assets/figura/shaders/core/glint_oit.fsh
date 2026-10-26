#version 330
#extension GL_ARB_separate_shader_objects : require

// Figura 반짝임(GLINT · GLINT2 · TEXTURED_GLINT)의 OIT 짝 — 바닐라 core/glint.fsh 의 색 · 안개에 OIT 단계를 더했다.
// 꼭짓점은 바닐라 core/glint.vsh 그대로(POSITION_TEX). 바닐라 OIT 셰이더는 엔티티 꼭짓점 꼴이라 못 쓴다.
// 빛만 더하는 표면(OIT_ADDITIVE) — 투과율에는 안 끼고(뒤를 가리지 않음), 누적 단계에서 앞의 반투명 투과율만큼 가려진다.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    // 바닐라 entity.fsh 의 OIT_ADDITIVE 와 같다 — 깊이 범위 단계는 알파 0.99 초과를 «불투명» 으로 보고(OIT_FULLY_OPAQUE_ALPHA)
    // 그 뒤를 컬링 깊이로 잘라 낸다. 반짝임 텍스처는 알파가 1 이라 이 상한이 없으면 반짝임 뒤의 반투명이 통째로 사라진다
    executeAlphaOnlyPhase(gl_FragCoord.z, min(0.99, color.a));
    #else
    float fade = (1.0f - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    vec3 glint = color.rgb * fade;
    // BlendFunction.GLINT(바탕 + src 색 × src 색)와 같은 세기 — 바닐라 item.fsh 의 GLINT 정의도 이렇게 더한다
    fragColor = sampleColorForAccumulation(vec4(glint * glint, 1.0));
    #endif
}
