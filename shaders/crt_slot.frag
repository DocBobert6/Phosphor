// PRESET 2 — "Slot Mask": slot-mask tube (late-90s/2000s workhorse CRT),
// vertical slots broken into segments, medium scanlines.
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

vec3 slotMask() {
    float x = mod(gl_FragCoord.x, 3.0);
    vec3 g = x < 1.0 ? vec3(1.0, 0.15, 0.15)
           : x < 2.0 ? vec3(0.15, 1.0, 0.15)
                     : vec3(0.15, 0.15, 1.0);
    // break the stripes into slots every 4 lines
    float slot = step(0.5, fract(gl_FragCoord.y / 4.0));
    g *= mix(0.55, 1.0, slot);
    return g * 1.30;
}

void main() {
    vec2 screenUV = curveUV(vUV);
    vec2 videoUV = aspectUV(screenUV);
    if (any(lessThan(videoUV, vec2(0.0))) || any(greaterThan(videoUV, vec2(1.0)))) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 col = texture(uTex, videoUV).rgb;
    col = adjust(col);
    col *= scanlineFactor(screenUV);
    col *= mix(vec3(1.0), slotMask(), pc.mask);
    col *= vignetteFactor(screenUV);
    outColor = vec4(col, 1.0);
}
