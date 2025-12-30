# Splatman - Android Development Guidelines

## Project Overview

**Splatman** is a free and open-source Gaussian splatting 3D scanner for Android. It uses ARCore for camera tracking, on-device optimization for creating 3D Gaussian splat models, and a real-time viewer for interactive 3D visualization—all completely offline.

### Core Features
- **ARCore Video Capture**: Record video while tracking camera pose in 6DOF
- **On-Device Processing**: Optimize Gaussian splat models directly on-device (3-5 minutes)
- **3D Viewer**: Real-time rendering of Gaussian splat models (30-60fps)
- **Model Library**: Browse, manage, and export your 3D scans
- **Privacy-First**: 100% offline, no data collection, no internet required

### Technology Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Architecture**: Multi-module, MVVM/MVI patterns
- **Dependency Injection**: Hilt
- **Navigation**: Type-safe sealed classes
- **AR**: ARCore SDK
- **Graphics**: OpenGL ES for Gaussian rendering
- **Database**: Room for metadata storage

---

## Module Structure

### `app/`
Main application module containing:
- `MainActivity.kt`: Entry point with bottom navigation (Viewer, Library, Tools tabs)
- Theme configuration (`SplatTheme`, `SplatColors`)
- Dependency injection setup
- Navigation wiring

### `core/`
Shared utilities and base classes:
- Database configuration
- Common extensions
- Formatters and validators
- Base ViewModels and UseCases
- Navigation destinations

### `capture-feature/` 
ARCore video capture module (Phase 2):
- Camera preview with ARCore overlay
- Video recording with pose tracking
- Frame extraction pipeline
- Pose data serialization

### `library-feature/`
Model management module:
- Grid view of saved models
- Model metadata display
- Delete, rename, share functionality
- Thumbnail generation

### `viewer-feature/`
3D rendering and tools module:
- OpenGL ES Gaussian splat renderer
- Interactive camera controls (rotate, pan, zoom)
- Export functionality (.PLY, .SPLAT formats)
- Settings screen

---

## Code Style & Architecture

### Naming Conventions
- **Theme**: `SplatTheme`, `SplatColors`, `SplatDimens`
- **Components**: `SplatButton`, `SplatCard`, `SplatTextField`
- **ViewModels**: `[Feature]ViewModel` (e.g., `CaptureViewModel`, `LibraryViewModel`)
- **UseCases**: `[Action][Entity]UseCase` (e.g., `OptimizeGaussianSplatUseCase`)
- **Navigation**: `NavigationDestination.Viewer/Library/Tools`

### Color Palette
```kotlin
// SplatColors.kt
DeepPurple = Color(0xFF1A0A2E)     // Primary background
DarkPurple = Color(0xFF2D1B4E)      // Secondary background
MediumPurple = Color(0xFF4A148C)    // UI elements
LightPurple = Color(0xFFAB47BC)     // Highlights
AccentPurple = Color(0xFF9C27B0)    // Interactive elements
SplatGold = Color(0xFFFFD700)       // Accent color (buttons, highlights)
SplatBlack = Color(0xFF0B0B0B)      // True black for OLED
```

### Architecture Patterns
- **ViewModels**: Manage UI state and business logic
- **UseCases**: Single responsibility business logic units
- **Repositories**: Abstract data sources (database, file system)
- **Hilt Modules**: Provide dependencies per module
- **Sealed Classes**: Type-safe navigation and state management

---

## Development Workflow

### ⚠️ CRITICAL: Build Commands - Use ONLY These Exact Commands

**IMPORTANT:** When building, testing, installing, or stopping the project, you MUST use ONLY the exact commands listed below. Do not use any other commands, variations, or custom PowerShell commands for these purposes. These are the verbatim commands that must be followed exactly as written.

### Building the Project - When asked for a feature always finish with building the project to ensure no errors.
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

### Testing ARCore Features
- Requires ARCore-compatible device
- Enable USB debugging
- Install ARCore from Play Store
- Test in well-lit environment with textured surfaces

---

## Key Implementation Details

### Navigation System
- Bottom navigation with 3 tabs: Viewer, Library, Tools
- Sealed class `NavigationDestination` for type safety
- `SplatColors` styling for navigation bar

### Database
- Room database: `splatman_database`
- Stores model metadata (name, date, Gaussian count, file path)
- File storage in app's private directory

### Theme System
- Dark-first design (OLED optimized)
- Purple gradient palette
- Gold accent for CTAs
- Material 3 dynamic color disabled (custom palette)

---

## Current Development Phase

**Phase 1: Foundation** ✅ Complete
- Package renaming (com.huntercoles.splatman)
- Module restructuring
- Theme implementation
- Bottom navigation

**Phase 2: ARCore Capture** 🚧 In Progress
- ARCore session management
- Video recording with pose tracking
- Frame extraction pipeline

**Phase 3: Gaussian Optimization** 🔜 Planned
- Port Gaussian splatting algorithm
- On-device optimization (100-200k Gaussians)
- Mobile GPU acceleration

**Phase 4: 3D Viewer** 🔜 Planned
- OpenGL ES renderer
- Real-time rasterization
- Interactive controls

---

## Safety Guidelines

### Before Committing
1. **Build verification**: `.\gradlew assembleRelease` must succeed
2. **Lint checks**: `.\gradlew detekt ktlintCheck` must pass
3. **Test suite**: `.\gradlew test` must pass
4. **Code review**: Check diffs for unintended changes

### Commit Preparation Process
When the user mentions "getting ready for a commit" or asks directly to "get ready for a commit", recognize this as a command and execute the following process:

1. **Review Staged Changes**: Go through all staged changes on the current git branch
2. **Identify Issues**: Look for holes, redundancies, simplifications, or missing edge cases
3. **Code Simplification**: Simplify the code and form factor where possible
4. **Testing Verification**: Ensure all changes are properly tested
5. **Summary Report**: Provide a bullet point list of 4-5 key points with a descriptive title, structured with ``` for easy copying

### Commit Message Format
```
[Module] Brief description

- Detailed change 1
- Detailed change 2

Relates to: #issue-number
```

### Pull Request Checklist
- [ ] All tests passing
- [ ] No new lint warnings
- [ ] Documentation updated
- [ ] Screenshots for UI changes
- [ ] ARCore functionality tested on device

---

## Common Tasks

### Adding a New Screen
1. Create Composable in appropriate feature module
2. Add navigation destination to `NavigationDestination.kt`
3. Update navigation graph in feature's `NavigationFactory`
4. Add ViewModel if needed
5. Wire up Hilt dependencies

### Adding a New Feature Module
1. Create directory: `new-feature/src/main/java/com/huntercoles/splatman/newfeature`
2. Add `build.gradle.kts` with dependencies
3. Create `NavigationFactory.kt` for navigation integration
4. Add to `settings.gradle.kts`: `include(":new-feature")`
5. Add dependency in `app/build.gradle.kts`

### Updating Theme Colors
- Edit `SplatColors.kt` object
- Changes propagate automatically via `SplatTheme`
- Prefer semantic color names over raw hex values

---

## Resources

### Documentation
- [ARCore Developer Guide](https://developers.google.com/ar/develop)
- [Jetpack Compose Docs](https://developer.android.com/jetpack/compose)
- [Gaussian Splatting Paper](https://repo-sam.inria.fr/fungraph/3d-gaussian-splatting/)

### Reference Implementations
- [MonoGS](https://github.com/muskie82/MonoGS) - Monocular Gaussian SLAM
- [SplaTAM](https://github.com/spla-tam/SplaTAM) - RGB-D Gaussian SLAM
- [gsplat](https://github.com/nerfstudio-project/gsplat) - CUDA Gaussian rasterization

---

## Troubleshooting

### Build Issues
- **Duplicate class errors**: Check for renamed files not deleted
- **Unresolved references**: Verify imports after refactoring
- **Hilt errors**: Ensure `@HiltAndroidApp` and `@AndroidEntryPoint` annotations present

### ARCore Issues
- **Session fails**: Verify ARCore installed and device supported
- **Tracking lost**: Ensure good lighting and textured surfaces
- **Performance drops**: Reduce frame sampling rate, lower resolution

### Rendering Issues
- **Low FPS**: Reduce Gaussian count, optimize shader
- **Memory crashes**: Limit Gaussians to 200k max, downsample images
- **Visual artifacts**: Check sorting order, alpha blending

---

## License & Contributing

- **License**: MIT (see LICENSE.md)
- **Contributing**: See CONTRIBUTING.md
- **Code of Conduct**: See CODE_OF_CONDUCT.md

**Privacy commitment**: Splatman collects zero data. No analytics, no tracking, no internet. Your 3D scans never leave your device.