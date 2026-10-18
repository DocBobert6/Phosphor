// PRESET 1 — "Shadow Mask": dot-triad shadow-mask tube (classic curved
// consumer CRT), staggered dots, softer scanlines.
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

vec3 shadowMask() {
    float x = mod(gl_FragCoord.x, 3.0);
    vec3 g = x < 1.0 ? vec3(1.0, 0.18, 0.18)
           : x < 2.0 ? vec3(0.18, 1.0, 0.18)
                     : vec3(0.18, 0.18, 1.0);
    // stagger alternate cells for the dotted shadow-mask feel
    float cell = mod(floor(gl_FragCoord.x / 3.0) + floor(gl_FragCoord.y / 2.0), 2.0);
    g *= mix(1.0, 0.72, cell * 0.6);
    return g * 1.28;
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
    // gentle horizontal softness, like an aging tube
    vec3 soft = (texture(uTex, videoUV + vec2(1.0 / pc.resolution.x, 0.0)).rgb
               + texture(uTex, videoUV - vec2(1.0 / pc.resolution.x, 0.0)).rgb) * 0.5;
    col = mix(col, soft, 0.25);
    col *= scanlineFactor(screenUV);
    col *= mix(vec3(1.0), shadowMask(), pc.mask);
    col *= vignetteFactor(screenUV);
    outColor = vec4(col, 1.0);
}
