#version 460 core

layout(location = 0) in vec2 outUV;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec4 outColor;

layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 1) uniform sampler2D tex;

void main() {
    vec4 texColor = texture(tex, outUV);
    fragColor = texColor * outColor;
}
