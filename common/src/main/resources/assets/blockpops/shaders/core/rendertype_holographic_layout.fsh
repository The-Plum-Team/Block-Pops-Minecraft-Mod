#version 330
#extension GL_ARB_separate_shader_objects : require

// The holographic entity shader of 26.3, which includes files with #include and places every
// shader input and output at an explicit location.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <blockpops:holographic.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 4) in vec4 lightMapColor;
layout(location = 5) in vec4 overlayColor;
layout(location = 6) in vec2 texCoord0;
layout(location = 7) in vec3 viewPosition;
layout(location = 8) in vec3 viewNormal;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;

    float fresnel;
    vec3 reflected = holographic_reflection(viewPosition, viewNormal, fresnel);
    color.rgb = holographic_foil(color.rgb, texCoord0, reflected, fresnel);

    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    color.rgb += holographic_sparkle(texCoord0, vec2(textureSize(Sampler0, 0)), reflected, lightMapColor);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
