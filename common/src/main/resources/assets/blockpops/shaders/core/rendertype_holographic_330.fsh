#version 330

// The holographic entity shader of 26.1 and 26.2: GLSL 330 and uniform blocks.

// From 1.21.2 to 26.2 the game resolves the imports of every shader file when resources load,
// so this file imports only what all those versions ship and declares the rest itself.

#moj_import <minecraft:fog.glsl>
#moj_import <blockpops:holographic.glsl>

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewPosition;
in vec3 viewNormal;

out vec4 fragColor;

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
