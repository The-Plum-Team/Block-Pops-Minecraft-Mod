#ifndef BLOCKPOPS_HOLOGRAPHIC_GLSL
#define BLOCKPOPS_HOLOGRAPHIC_GLSL

// A holographic figure or plush: a soft rainbow foil and rainbow sparkles on its own texture.
// Neither moves on its own. Both follow the light a face reflects towards the eye, so they hold
// still while the view does and shift as the player moves or turns, as a real foil does.
// From 1.21.4 every holographic shader imports this; 1.20.1 and 1.21.1 cannot import a mod's
// file, so core/rendertype_holographic.fsh carries the same code and must change with it.

// How strong the foil is, and how many texture cells may hold a sparkle
const float HOLOGRAPHIC_FOIL = 0.3;
const float HOLOGRAPHIC_SPARKLE = 0.9;
const float HOLOGRAPHIC_SPARKLE_DENSITY = 0.2;
// Texels per side of a sparkle's cell
const float HOLOGRAPHIC_SPARKLE_CELL = 3.0;

vec3 holographic_rainbow(float hue) {
    vec3 k = mod(vec3(5.0, 3.0, 1.0) + fract(hue) * 6.0, 6.0);
    return 1.0 - clamp(min(k, 4.0 - k), 0.0, 1.0);
}

float holographic_hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// The eye's view reflected off a face, in view space. A screen's flat projection puts the eye
// nowhere in particular, so there it looks straight in.
vec3 holographic_reflection(vec3 viewPosition, vec3 viewNormal, out float fresnel) {
    vec3 n = normalize(viewNormal);
    vec3 eye = length(viewPosition) > 1.0E-4 ? normalize(viewPosition) : vec3(0.0, 0.0, -1.0);
    float facing = abs(dot(n, eye));
    fresnel = (1.0 - facing) * (1.0 - facing);
    return reflect(eye, n);
}

// The foil over a lit colour: a rainbow across the texture that turns with the reflection
vec3 holographic_foil(vec3 color, vec2 uv, vec3 reflected, float fresnel) {
    float hue = (uv.x + uv.y) * 3.0 + reflected.x * 2.5 + reflected.y * 2.0;
    vec3 foil = holographic_rainbow(hue);
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    return color * (1.0 - 0.2 * HOLOGRAPHIC_FOIL)
            + foil * (0.1 + 0.25 * luminance) * HOLOGRAPHIC_FOIL
            + foil * fresnel * 0.2 * HOLOGRAPHIC_FOIL;
}

// What a sparkle adds: four-pointed stars in a few cells of the texture. Each faces its own way
// and flashes when the reflection lines up with it, as glitter does.
vec3 holographic_sparkle(vec2 uv, vec2 textureSize, vec3 reflected, vec4 lightMapColor) {
    vec2 cells = uv * textureSize / HOLOGRAPHIC_SPARKLE_CELL;
    vec2 cell = floor(cells);
    if (holographic_hash(cell) >= HOLOGRAPHIC_SPARKLE_DENSITY) {
        return vec3(0.0);
    }
    float phase = holographic_hash(cell + 7.31);
    vec3 glint = normalize(vec3(holographic_hash(cell + 1.7), holographic_hash(cell + 4.1),
            holographic_hash(cell + 9.3)) * 2.0 - 1.0);
    float beat = smoothstep(0.9, 0.97, dot(reflected, glint));
    vec2 f = fract(cells) - 0.5;
    float star = max(0.0, 1.0 - abs(f.x) * 2.0) * max(0.0, 1.0 - abs(f.y) * 12.0)
            + max(0.0, 1.0 - abs(f.y) * 2.0) * max(0.0, 1.0 - abs(f.x) * 12.0)
            + exp(-dot(f, f) * 60.0);
    vec3 tint = holographic_rainbow(phase + reflected.x * 2.0) * 0.85 + 0.15;
    // A sparkle catches some light even in the dark
    float light = max(dot(lightMapColor.rgb, vec3(0.333)), 0.35);
    return tint * star * beat * HOLOGRAPHIC_SPARKLE * light;
}

#endif
