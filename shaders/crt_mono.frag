// PRESET 4 — "Mono": single-phosphor tube. pc.phosphor picks the tint:
// 0 = P1 green, 1 = amber, 2 = paper white.
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 screenUV = curveUV(vUV);
    vec2 videoUV = aspectUV(screenUV);
    if (any(lessThan(videoUV, vec2(0.0))) || any(greaterThan(videoUV, vec2(1.0)))) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 col = sampleVideo(videoUV).rgb;
    float l = dot(col, vec3(0.299, 0.587, 0.114));
    vec3 tint = pc.phosphor < 0.5 ? vec3(0.25, 1.0, 0.35)
              : pc.phosphor < 1.5 ? vec3(1.0, 0.62, 0.12)
                                  : vec3(0.95, 0.95, 0.90);
    col = vec3(l) * tint;
    col = adjust(col);
    col *= scanlineFactor(screenUV);
    // faint vertical grille, like a mono tube's shadow mask
    float x = mod(maskCoord().x, 3.0);
    float g = x < 1.0 ? 1.0 : 0.82;
    col *= mix(1.0, g, pc.mask * 0.6);
    col *= vignetteFactor(screenUV);
    outColor = vec4(col, 1.0);
}
