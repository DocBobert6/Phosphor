// PRESET 0 — "Trinitron": aperture-grille tube (Sony-style vertical stripes),
// sharp scanlines, curved glass. The 2000s Toshiba flat-CRT look.
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

vec3 apertureGrille() {
    float x = mod(maskCoord().x, 3.0);
    vec3 g = x < 1.0 ? vec3(1.0, 0.12, 0.12)
           : x < 2.0 ? vec3(0.12, 1.0, 0.12)
                     : vec3(0.12, 0.12, 1.0);
    return g * 1.30;
}

void main() {
    vec2 screenUV = curveUV(vUV);
    vec2 videoUV = aspectUV(screenUV);
    if (any(lessThan(videoUV, vec2(0.0))) || any(greaterThan(videoUV, vec2(1.0)))) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 col = sampleVideo(videoUV).rgb;
    col = adjust(col);
    col *= scanlineFactor(screenUV);
    col *= mix(vec3(1.0), apertureGrille(), pc.mask);
    col *= vignetteFactor(screenUV);
    outColor = vec4(col, 1.0);
}
