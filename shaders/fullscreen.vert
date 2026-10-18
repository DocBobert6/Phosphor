#version 450
// Fullscreen triangle — no vertex buffers needed.
layout(location = 0) out vec2 vUV;

void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    // NOTE: no vertical flip — Vulkan UV (0,0) is the top-left, matching the
    // CPU-uploaded image layout (row 0 = top). The old 1.0-p.y flip turned
    // every video upside down.
    vUV = vec2(p.x, p.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
