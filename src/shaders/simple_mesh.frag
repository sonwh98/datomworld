#version 460 core

layout(location = 0) in vec2 outUV;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec4 outColor;
layout(location = 3) in vec3 inWorldPos;

layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 1) uniform sampler2D tex;

// Lighting block: layout matches dao.postgraphics.packing/packed-lighting-block.
// 8 lights, each 3 vec4s (color+intensity, vec+range, kind).
layout(set = 0, binding = 2) uniform Frag {
    vec4 cameraPos;   // xyz, lightCount (w)
    vec4 material0;   // specular rgb, shininess (w)
    vec4 material1;   // emissive rgb (sRGB), lightingEnabled (w)
    vec4 lights[24];
} f;

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + vec3(0.055)) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
}

vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - vec3(0.055);
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.0031308))));
}

void main() {
    vec4 texColor = texture(tex, outUV);
    vec4 texResult = texColor * outColor;
    if (f.material1.w < 0.5) {
        fragColor = clamp(texResult, 0.0, 1.0);
        return;
    }
    vec3 Kd = srgbToLinear(texResult.rgb);
    vec3 Ks = f.material0.rgb;
    float shininess = f.material0.w;
    vec3 Ke = srgbToLinear(f.material1.rgb);
    vec3 N = inNormal;
    vec3 viewDir = normalize(f.cameraPos.xyz - inWorldPos);
    vec3 amb = vec3(0.0);
    vec3 dif = vec3(0.0);
    vec3 spec = vec3(0.0);
    int count = int(f.cameraPos.w);
    for (int i = 0; i < count; i = i + 1) {
        vec4 colorIntensity = f.lights[i * 3];
        vec4 vecRange = f.lights[i * 3 + 1];
        float kind = f.lights[i * 3 + 2].x;
        vec3 lc = colorIntensity.rgb;
        float intensity = colorIntensity.w;
        if (kind < 0.5) {
            amb += lc * intensity;
        } else if (kind < 1.5) {
            vec3 L = normalize(vecRange.xyz);
            float nl = max(0.0, dot(N, L));
            vec3 H = normalize(L + viewDir);
            float nh = max(0.0, dot(N, H));
            float sp = pow(nh, shininess);
            dif += lc * nl * intensity;
            spec += lc * sp * intensity;
        } else {
            vec3 toLight = vecRange.xyz - inWorldPos;
            float dist = length(toLight);
            vec3 L = normalize(toLight);
            float nl = max(0.0, dot(N, L));
            vec3 H = normalize(L + viewDir);
            float nh = max(0.0, dot(N, H));
            float sp = pow(nh, shininess);
            float t = 1.0 - dist / vecRange.w;
            float atten = t > 0.0 ? t * t : 0.0;
            dif += lc * nl * intensity * atten;
            spec += lc * sp * intensity * atten;
        }
    }
    vec3 lit = Kd * amb + Kd * dif + Ks * spec + Ke;
    vec3 outRgb = clamp(linearToSrgb(lit), 0.0, 1.0);
    fragColor = vec4(outRgb, clamp(texResult.a, 0.0, 1.0));
}
