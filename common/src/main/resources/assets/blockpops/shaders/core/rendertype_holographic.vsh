#version 150

// The entity cutout shader of 1.20.1 to 1.21.5, with the view-space position and normal the
// holographic fragment shader needs. Light and fog are written out here rather than imported:
// their includes changed between those versions, and one shader serves them all.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 viewPosition;
out vec3 viewNormal;

vec4 mixLight(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    float light0 = max(0.0, dot(normalize(lightDir0), normal));
    float light1 = max(0.0, dot(normalize(lightDir1), normal));
    float lightAccum = min(1.0, (light0 + light1) * 0.6 + 0.4);
    return vec4(color.rgb * lightAccum, color.a);
}

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    // 1.20.1 hands the shader view-space positions under an identity model-view, 1.21 world
    // positions under the camera's rotation: either way this is the distance to the eye.
    vertexDistance = length(viewPos.xyz);
    vertexColor = mixLight(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    viewPosition = viewPos.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
}
