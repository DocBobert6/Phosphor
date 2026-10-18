// PRESET 3 — "Composite": the well-loved RF/composite look — chroma bleed,
// dot crawl shimmer, soft picture, faint scanlines. Pure nostalgia.
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 screenUV = curveUV(vUV);
    vec2 videoUV = aspectUV(screenUV);
    if (any(lessThan(videoUV, vec2(0.0))) || any(greaterThan(videoUV, vec2(1.0)))) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float px = 1.0 / pc.resolution.x;
    // luma stays fairly sharp, chroma smears horizontally (composite bleed)
    vec3 sharp = texture(uTex, videoUV).rgb;
    vec3 blur = (texture(uTex, videoUV + vec2(px * 2.0, 0.0)).rgb
               + texture(uTex, videoUV - vec2(px * 2.0, 0.0)).rgb
               + texture(uTex, videoUV + vec2(px * 4.0, 0.0)).rgb
               + texture(uTex, videoUV - vec2(px * 4.0, 0.0)).rgb) * 0.25;
    float lumaSharp = dot(sharp, vec3(0.299, 0.587, 0.114));
    float lumaBlur  = dot(blur,  vec3(0.299, 0.587, 0.114));
    vec3 col = blur + (lumaSharp - lumaBlur);   // sharp luma + smeared chroma
    // faint dot-crawl shimmer on high-frequency luma
    float crawl = sin(videoUV.y * pc.resolution.y * 3.14159 + pc.time * 6.28318) * 0.02;
    col += crawl * (lumaSharp - 0.5);
    // a whisper of noise, like an analog tuner
    col += (hash(gl_FragCoord.xy + fract(pc.time) * 100.0) - 0.5) * 0.03;
    col = adjust(col);
    col *= mix(1.0, scanlineFactor(screenUV), 0.7);
    col *= vignetteFactor(screenUV);
    outColor = vec4(col, 1.0);
}
