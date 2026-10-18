#version 450
// Shared push-constant block for every Phosphor CRT preset.
// The native renderer fills this from CrtParams (see phosphor.cpp);
// field order and sizes must match exactly (60 bytes total).
layout(push_constant) uniform Push {
    vec2  resolution;    // render-target size in px
    float time;          // seconds, for animated effects
    float scanline;      // 0..1 scanline strength
    float mask;          // 0..1 phosphor mask strength
    float curvature;     // 0..1 screen curvature
    float brightness;    // -1..1
    float contrast;      // 0..2
    float saturation;    // 0..2
    float vignette;      // 0..1
    float aspectMode;    // 0 = fit, 1 = fill, 2 = stretch
    float videoAspect;   // video width / height
    float phosphor;      // mono preset tint: 0 = green, 1 = amber, 2 = white
    float rotation;      // 0/1/2/3 = 0/90/180/270 degrees clockwise
    float zoom;          // 1 = normal, >1 zooms in, <1 zooms out
    float colorTemp;     // 0 = Cool (~9300K, factory), 1 = Medium, 2 = Warm (~6500K)
} pc;

layout(set = 0, binding = 0) uniform sampler2D uTex;

// User picture geometry: zoom about the center, then rotation.
// Applied before aspect fitting so the aspect math sees the rotated frame.
vec2 geometryUV(vec2 uv) {
    uv = (uv - vec2(0.5)) / pc.zoom + vec2(0.5);
    vec2 c = uv - vec2(0.5);
    if (pc.rotation > 0.5 && pc.rotation < 1.5) {
        uv = vec2(0.5 + c.y, 0.5 - c.x);   // 90 deg clockwise
    } else if (pc.rotation > 1.5 && pc.rotation < 2.5) {
        uv = vec2(0.5 - c.x, 0.5 - c.y);   // 180 deg
    } else if (pc.rotation > 2.5) {
        uv = vec2(0.5 - c.y, 0.5 + c.x);   // 270 deg clockwise
    }
    return uv;
}

// Barrel distortion: the curved glass of a real tube.
vec2 curveUV(vec2 uv) {
    vec2 cc = uv - vec2(0.5);
    float r2 = dot(cc, cc);
    return uv + cc * r2 * pc.curvature * 0.6;
}

// Fit / fill / stretch the video into the (possibly curved) screen UVs.
// User rotation/zoom is applied first; a 90/270 deg rotation swaps the
// video's aspect so fit/fill math stays correct.
vec2 aspectUV(vec2 uv) {
    uv = geometryUV(uv);
    float va = pc.videoAspect;
    if ((pc.rotation > 0.5 && pc.rotation < 1.5) || pc.rotation > 2.5) {
        va = 1.0 / va;
    }
    float targetAspect = pc.resolution.x / pc.resolution.y;
    vec2 s = vec2(1.0);
    if (pc.aspectMode < 0.5) {            // fit (letterbox)
        if (va > targetAspect) { s.y = targetAspect / va; }
        else                   { s.x = va / targetAspect; }
    } else if (pc.aspectMode < 1.5) {     // fill (crop)
        if (va > targetAspect) { s.x = va / targetAspect; }
        else                   { s.y = targetAspect / va; }
    }                                     // stretch: s stays (1,1)
    return (uv - vec2(0.5)) / s + vec2(0.5);
}

vec3 adjust(vec3 c) {
    c = (c - vec2(0.5).xxx) * pc.contrast + vec2(0.5).xxx + pc.brightness;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, pc.saturation);
    // White-point shift: Toshiba shipped on COOL (~9300K, bluish); WARM is
    // the calibrator's ~6500K. Applied after contrast/saturation.
    if (pc.colorTemp < 0.5) {
        c *= vec3(0.94, 0.97, 1.09);
    } else if (pc.colorTemp > 1.5) {
        c *= vec3(1.09, 1.00, 0.91);
    }
    return c;
}

float scanlineFactor(vec2 screenUV) {
    float s = 0.5 + 0.5 * cos(6.2831853 * gl_FragCoord.y * 0.5);
    return 1.0 - pc.scanline * 0.45 * s;
}

float vignetteFactor(vec2 screenUV) {
    float d = distance(screenUV * vec2(1.0, pc.resolution.y / pc.resolution.x), vec2(0.5, 0.5));
    return 1.0 - pc.vignette * smoothstep(0.35, 0.75, d);
}
