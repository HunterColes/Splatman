<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" alt="Splatman Icon" width="200"/>
</p>

<h1 align="center">Splatman</h1>

<p align="center">
  <img alt="Android API 31+" src="https://img.shields.io/badge/API%2031+-50f270?logo=android&logoColor=black&style=for-the-badge"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge"/>
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"/>
</p>

<p align="center">
  <img alt="Latest Release" src="https://img.shields.io/github/v/tag/HunterColes/Splatman?label=Latest%20Release&style=for-the-badge"/>
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge"/>
</p>

<h4 align="center">
  Real-time Gaussian splatting 3D scanner for Android. Capture, optimize, and view 3D models entirely offline with ARCore tracking and on-device processing.
</h4>

# Download

<p align="center">
  <a href="https://github.com/HunterColes/Splatman/releases">
    <img alt="Get it on GitHub" src="https://raw.githubusercontent.com/deckerst/common/main/assets/get-it-on-github.png" height="80"/>
  </a>
  &nbsp;&nbsp;&nbsp;
  <a href="https://f-droid.org/packages/com.huntercoles.splatman/">
    <img alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80"/>
  </a>
</p>

<p align="center">
  <a href="crypto/DONATIONS.md">
    <img alt="Donate Ethereum" src="https://img.shields.io/badge/Ξ-Ethereum-627EEA?logo=ethereum&logoColor=white&style=for-the-badge"/>
  </a>
  &nbsp;&nbsp;
  <a href="crypto/DONATIONS.md">
    <img alt="Donate Monero" src="https://img.shields.io/badge/Ӿ-Monero-FF6600?logo=monero&logoColor=white&style=for-the-badge"/>
  </a>
</p>

# Features

• **3D Capture & Processing**
  ◦ ARCore 6DOF camera tracking for accurate pose estimation
  ◦ Video recording with real-time frame extraction
  ◦ On-device Gaussian splatting optimization (3-5 minutes)
  ◦ 100k-200k Gaussians for mobile-optimized quality

• **Interactive 3D Viewer**
  ◦ Real-time rendering at 30-60fps
  ◦ Touch gestures: rotate, pan, zoom
  ◦ Export to .PLY and .SPLAT formats
  ◦ Screenshot and sharing capabilities

• **Model Library**
  ◦ Browse all captured 3D scans
  ◦ Metadata tracking (date, Gaussian count, file size)
  ◦ Thumbnail preview generation
  ◦ Delete, rename, and share functionality

• **Privacy & Performance**
  ◦ 100% offline operation (no internet required)
  ◦ No data collection or tracking
  ◦ OLED-optimized dark purple theme
  ◦ Material 3 design with Jetpack Compose

• **Free and Open Source**
• **Privacy-friendly**

---

# Build & Installation

## Prerequisites
- Android Studio Hedgehog or later
- Java 17 (JDK 17) — required for building
- Android SDK (API 31+)
- ARCore-compatible device for testing

## ⚠️ CRITICAL: Build Commands - Use ONLY These Exact Commands

**IMPORTANT:** When building, testing, installing, or stopping the project, you MUST use ONLY the exact commands listed below. Do not use any other commands, variations, or custom PowerShell commands for these purposes. These are the verbatim commands that must be followed exactly as written.

```powershell
# Release build (ONLY command for building)
.\gradlew assembleRelease

# Run tests (ONLY command for testing)
.\gradlew test

# Install APK (ONLY command for installing)
.\gradlew installRelease

# Stop Gradle daemons (ONLY command for stopping)
.\gradlew --stop
```

## Running on Device
```powershell
# Install and launch
.\gradlew installRelease
adb shell am start -n com.huntercoles.splatman/.MainActivity
```

## Release Signing
For production releases, set environment variables:
```bash
export ORG_GRADLE_PROJECT_RELEASE_STORE_FILE="../splatman-release.keystore"
export ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD="your_password"
export ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS="splatman"
export ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD="your_password"
```

## F-Droid Reproducible Builds
For reproducible builds matching F-Droid:
```bash
docker-shell.bat                  # Start Linux container
dos2unix gradlew                  # Fix line endings (first time only)
./gradlew clean assembleRelease   # Build with Ubuntu 22.04 + Java 17
```

## Verify APK Signature
To verify the signature of the built release APK:
```bash
apksigner verify --print-certs app/build/outputs/apk/release/Splatman-*-release.apk
```

---

# Technical Architecture

## Module Structure
```
app/                  - Main entry point, navigation, theme
core/                 - Shared utilities, database, base classes
capture-feature/      - ARCore video capture and pose tracking
library-feature/      - Model management and file operations
viewer-feature/       - 3D rendering and export functionality
```

## Technology Stack
• **Language**: Kotlin
• **UI Framework**: Jetpack Compose with Material 3
• **Architecture**: MVVM/MVI patterns with Hilt DI
• **AR Tracking**: ARCore SDK for 6DOF pose estimation
• **Graphics**: OpenGL ES for Gaussian splat rendering
• **Database**: Room for metadata storage
• **Build System**: Gradle with Kotlin DSL and Version Catalog

## Color Theme (SplatColors)
```kotlin
DeepPurple   = #1A0A2E  // Primary background
DarkPurple   = #2D1B4E  // Secondary background
MediumPurple = #4A148C  // UI elements
AccentPurple = #9C27B0  // Interactive elements
SplatGold    = #FFD700  // Highlights and CTAs
SplatBlack   = #0B0B0B  // True black for OLED
```

---

# How It Works

## Gaussian Splatting Explained
**Splatman** uses 3D Gaussian Splatting, a cutting-edge technique from SIGGRAPH 2023 that represents 3D scenes as collections of oriented 3D Gaussians. Unlike traditional photogrammetry that generates triangle meshes, Gaussian splats render 10-100x faster and capture complex lighting and materials more accurately.

**Mobile Optimization:**
```
Desktop:  5-10M Gaussians, 30k iterations → 4K quality
Mobile:   100k-200k Gaussians, 100 iterations → 720p quality
Result:   70-80% visual quality, 10x faster, 100% offline
```

## Capture Pipeline
```
[ARCore Video Recording] 
    ↓ 6DOF pose tracking at 30Hz
[Frame Extraction & Downsampling] 
    ↓ Sample at 10Hz, resize to 640x480
[Gaussian Optimization] 
    ↓ 100 iterations, 3-5 minutes on-device
[3D Gaussian Splat Model]
    ↓ Store with metadata, generate thumbnail
[Library & 3D Viewer]
    ↓ Browse, view, export
[.PLY / .SPLAT Export]
```

---

# Development Status

## Phase 1: Foundation ✅ Complete
- [x] Package renaming (com.huntercoles.splatman)
- [x] Module restructuring (capture/library/viewer features)
- [x] Dark purple theme implementation (SplatColors)
- [x] Bottom navigation with 3 tabs (Viewer/Library/Tools)
- [x] Database setup (splatman_database)

## Phase 2: ARCore Capture 🚧 In Progress
- [ ] ARCore session management
- [ ] Video recording with pose tracking
- [ ] Frame extraction pipeline
- [ ] Pose data serialization

## Phase 3: Gaussian Optimization 🔜 Planned
- [ ] Port Gaussian splatting algorithm to mobile
- [ ] On-device optimization (100-200k Gaussians)
- [ ] Mobile GPU acceleration
- [ ] Progressive optimization preview

## Phase 4: 3D Viewer 🔜 Planned
- [ ] OpenGL ES Gaussian splat renderer
- [ ] Real-time rasterization (30-60fps)
- [ ] Interactive camera controls
- [ ] Export to .PLY and .SPLAT formats

---

# Contribute

Pull requests are welcome! Check [issues](https://github.com/HunterColes/Splatman/issues) for contribution opportunities or open a new issue to discuss ideas.

**How to contribute:**
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

**Areas we need help:**
- Android device testing (especially non-Pixel phones)
- UI/UX improvements
- Performance optimization
- Additional export formats (OBJ, FBX, USD)
- Internationalization (translations)
- Documentation and tutorials

For help or discussions, open an issue or discussion on GitHub.

---

# Resources & References

This project builds on groundbreaking research:

- **[3D Gaussian Splatting](https://github.com/graphdeco-inria/gaussian-splatting)** - Original SIGGRAPH 2023 paper
- **[MonoGS](https://github.com/muskie82/MonoGS)** - Monocular Gaussian SLAM (CVPR 2024)
- **[SplaTAM](https://github.com/spla-tam/SplaTAM)** - RGB-D Gaussian SLAM (CVPR 2024)
- **[gsplat](https://github.com/nerfstudio-project/gsplat)** - CUDA-accelerated rasterization
- **[ARCore](https://developers.google.com/ar)** - Google's AR platform

---

# License

• [MIT License](LICENSE.md)
• Free and open source software
• See also: [CONTRIBUTING.md](CONTRIBUTING.md) • [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

**Privacy commitment**: Splatman collects zero data. No analytics, no tracking, no internet. Your 3D scans never leave your device.
