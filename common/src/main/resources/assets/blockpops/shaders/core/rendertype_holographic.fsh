#version 150

// A holographic figure: a soft rainbow foil and rainbow sparkles on the figure's own texture.
// Neither moves on its own. Both follow the light a face reflects towards the eye, so they hold
// still while the view does and shift as the player moves or turns, as a real foil does.
// 1.20.1 to 1.21.5 draw with this shader. 1.20.1 and 1.21.1 cannot import a mod's include, so
// it repeats the code of include/holographic.glsl, which the later shaders import: change both.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewPosition;
in vec3 viewNormal;

out vec4 fragColor;

// How strong the foil is, and how many texture cells may hold a sparkle
const float FOIL = 0.3;
const float SPARKLE = 0.9;
const float SPARKLE_DENSITY = 0.2;
// Texels per side of a sparkle's cell
const float SPARKLE_CELL = 3.0;

vec3 rainbow(float hue) {
    vec3 k = mod(vec3(5.0, 3.0, 1.0) + fract(hue) * 6.0, 6.0);
    return 1.0 - clamp(min(k, 4.0 - k), 0.0, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec4 linearFog(vec4 inColor, float distance, float fogStart, float fogEnd, vec4 fogColor) {
    if (distance <= fogStart) {
        return inColor;
    }
    float fogValue = distance < fogEnd ? smoothstep(fogStart, fogEnd, distance) : 1.0;
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;

    vec3 n = normalize(viewNormal);
    // From the eye to this point; a screen's flat projection puts the eye nowhere in particular
    vec3 eye = length(viewPosition) > 1.0E-4 ? normalize(viewPosition) : vec3(0.0, 0.0, -1.0);
    vec3 reflected = reflect(eye, n);
    float facing = abs(dot(n, eye));
    float fresnel = (1.0 - facing) * (1.0 - facing);

    // The foil: a rainbow across the texture that turns with the reflection
    float hue = (texCoord0.x + texCoord0.y) * 3.0 + reflected.x * 2.5 + reflected.y * 2.0;
    vec3 foil = rainbow(hue);
    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    color.rgb = color.rgb * (1.0 - 0.2 * FOIL)
            + foil * (0.1 + 0.25 * luminance) * FOIL
            + foil * fresnel * 0.2 * FOIL;

    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    // The sparkles: four-pointed stars in a few cells of the texture. Each faces its own way and
    // flashes when the reflection lines up with it, as glitter does
    vec2 cells = texCoord0 * vec2(textureSize(Sampler0, 0)) / SPARKLE_CELL;
    vec2 cell = floor(cells);
    if (hash(cell) < SPARKLE_DENSITY) {
        float phase = hash(cell + 7.31);
        vec3 glint = normalize(vec3(hash(cell + 1.7), hash(cell + 4.1), hash(cell + 9.3)) * 2.0 - 1.0);
        float beat = smoothstep(0.9, 0.97, dot(reflected, glint));
        vec2 f = fract(cells) - 0.5;
        float star = max(0.0, 1.0 - abs(f.x) * 2.0) * max(0.0, 1.0 - abs(f.y) * 12.0)
                + max(0.0, 1.0 - abs(f.y) * 2.0) * max(0.0, 1.0 - abs(f.x) * 12.0)
                + exp(-dot(f, f) * 60.0);
        vec3 tint = rainbow(phase + reflected.x * 2.0) * 0.85 + 0.15;
        // A sparkle catches some light even in the dark
        float light = max(dot(lightMapColor.rgb, vec3(0.333)), 0.35);
        color.rgb += tint * star * beat * SPARKLE * light;
    }

    fragColor = linearFog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
