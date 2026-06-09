#version 460 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec3 inNormal;
layout(location = 3) in vec4 inColor;

layout(location = 0) out vec2 outUV;
layout(location = 1) out vec3 outNormal;
layout(location = 2) out vec4 outColor;
layout(location = 3) out vec3 outWorldPos;

layout(set = 0, binding = 0) uniform Uniforms {
    mat4 mvp;
    mat4 model;
    mat4 modelInv;
} u;

void main() {
    gl_Position = u.mvp * vec4(inPosition, 1.0);
    outWorldPos = (u.model * vec4(inPosition, 1.0)).xyz;
    // Inverse-transpose of the model's upper-left 3x3, matching
    // dao.postgraphics.software/prepare-vertex (apply-normal-matrix).
    outNormal = normalize(transpose(mat3(u.modelInv)) * inNormal);
    outUV = inUV;
    outColor = inColor;
}
