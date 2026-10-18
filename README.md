# Phosphor

A media player for Android that plays your videos through a virtual CRT —
a 2000s Toshiba-style set, rendered end to end on **Vulkan** with five
hand-written **CRT shader presets**.

![icon](app/src/main/res/drawable/ic_launcher_foreground.xml)

## What it does

- Plays **MP4, MKV, AVI, MOV, WebM, FLV** via Media3 (ExoPlayer) hardware decoding.
- Presents every frame through a native Vulkan pipeline
  (`app/src/main/cpp/phosphor.cpp`): decoded frames travel as
  `AHardwareBuffer` → Vulkan external memory → YCbCr conversion → fullscreen
  triangle → CRT shader → swapchain. Zero-copy on the GPU.
- Five CRT presets, compiled to SPIR-V (sources in `shaders/`):
  1. **Trinitron** — aperture-grille tube, the flat 2000s look
  2. **Shadow Mask** — dot-triad curved tube
  3. **Slot Mask** — late-90s workhorse tube
  4. **Composite** — RF/composite glow, chroma bleed, dot crawl
  5. **Mono** — green / amber / paper-white phosphor
- Live PICTURE menu: scanlines, phosphor mask, curvature, brightness,
  contrast, color, vignette, screen fit (fit/fill/stretch).
- 2000s CRT interface: bezel, green OSD, front-panel keys, chunky remote
  transport cluster, INPUT browser (Storage Access Framework — pick a file or
  a whole folder of videos, no storage permission needed).

## Building

GitHub Actions builds a debug APK on every push (`.github/workflows/android.yml`):
NDK 28 + CMake 3.22 compile `libphosphor.so`; the APK artifact is
`phosphor-debug-apk`. Requires a device with Vulkan 1.1+ (any modern phone).

## Project layout

```
app/src/main/
  java/com/phosphor/player/
    MainActivity.kt        — entry point, VIEW-intent handling
    player/VideoEngine.kt  — ExoPlayer + ImageReader frame pump
    vulkan/VulkanBridge.kt — JNI bridge + preset list
    ui/                    — Compose CRT interface
  cpp/phosphor.cpp         — Vulkan renderer (instance → swapchain → shaders)
  assets/shaders/*.spv     — compiled CRT shaders (validated with spirv-val)
shaders/*.glsl, *.frag     — shader sources; rebuild .spv with glslangValidator
```

## Roadmap

- **WMV/ASF**: needs the Media3 FFmpeg decoder extension (built from source);
  currently shows a friendly "format not supported" OSD instead of crashing.
- Interlaced-content handling, deinterlace toggle.
- Playlist / resume playback.
