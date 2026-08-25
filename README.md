# 8-Ball Pool AI Guideline Overlay & Computer Vision Trajectory Engine

A high-performance, real-time Android 14+ (API 35) overlay application built in Kotlin, OpenCV, and C++ NDK. The system captures active pool games in real time, detects table felt and ball geometries, computes multi-bounce raycast trajectories, and renders ghost-ball aiming lines and 90-degree cue ball deflections directly onto a transparent floating overlay canvas at 60 FPS.

---

## Architecture Overview

```
                           [ Active 8-Ball Pool Game ]
                                        │
                                        ▼ (Screen Mirroring)
                        [ MediaProjection + ImageReader ]
                        (Downsampled 50% Scale RGBA_8888)
                                        │
                                        ▼ (Direct ByteBuffer)
                         [ TableAndBallDetector.kt ]
                    ┌───────────────────┴───────────────────┐
                    │ HSV Table Felt Isolation (Green/Blue) │
                    │ Cue Ball White Pixel Cluster Detect   │
                    │ Object Ball Grid Radial Scan          │
                    │ Aim Vector Canny/Hough Collinearity   │
                    └───────────────────┬───────────────────┘
                                        │
                                        ▼
    [ TrajectoryPhysicsEngine.kt ] ◄────────► [ Native C++ pool_cv_engine.cpp ]
    ┌─────────────────────────────────────────────────────────────────────────┐
    │ 1. Ray-Circle Intersection (Ghost Ball position: 2R distance offset)   │
    │ 2. Deflection Vector Orthogonality (90° Tangent Rule: n · t = 0)        │
    │ 3. Multi-Bounce Cushion Reflections (Rails inset by ball radius R)      │
    │ 4. Pocket Alignment Scoring (6 Standard Pockets)                        │
    └───────────────────────────────────┬─────────────────────────────────────┘
                                        │
                                        ▼ (Smoothed via EMA α = 0.35)
                         [ OverlayCanvasView.kt ]
                    (Hardware-Accelerated Zero-Allocation Canvas)
                                        │
                                        ▼
                   [ Transparent System Alert Window Overlay ]
```

---

## Key Modules & Subsystems

### 1. Verification of Mathematical & Native Layers (`TrajectoryPhysicsEngine.kt` & `pool_cv_engine.cpp`)
- **Ghost Ball Intersection**:
  $$\vec{P}(t) = \vec{C}_0 + t\vec{u}, \quad t_{\text{proj}} = (\vec{B}_i - \vec{C}_0) \cdot \vec{u} > 0$$
  $$d^2 = \|\vec{B}_i - \vec{C}_0\|^2 - t_{\text{proj}}^2 \le (2R)^2$$
  $$t_{\text{hit}} = t_{\text{proj}} - \sqrt{(2R)^2 - d^2}$$
  $$\vec{G} = \vec{C}_0 + t_{\text{hit}}\vec{u} \implies \|\vec{B}_i - \vec{G}\| = 2R$$
- **Deflection Orthogonality (90-Degree Rule)**:
  $$\hat{n} = \frac{\vec{B}_i - \vec{G}}{\|\vec{B}_i - \vec{G}\|}, \quad \hat{t} = \frac{\vec{u} - (\vec{u} \cdot \hat{n})\hat{n}}{\|\vec{u} - (\vec{u} \cdot \hat{n})\hat{n}\|}$$
  $$\hat{n} \cdot \hat{t} \equiv 0.0$$
- **Cushion Rail Insets**:
  - Cushion contact boundaries are inset by ball radius $R$ from the outer felt rectangle ($X_{\min}+R, Y_{\min}+R, X_{\max}-R, Y_{\max}-R$), ensuring trajectories bounce exactly where the ball edge contacts the rail.

### 2. Android 14+ Foreground Service Sequence (`MainActivity.kt` & `OverlayService.kt`)
To comply with Android 14/15 security requirements:
1. `MainActivity` checks `Settings.canDrawOverlays(this)`. If false, launches `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.
2. `ContextCompat.startForegroundService(OverlayService)` is invoked prior to obtaining the projection token.
3. `OverlayService.startForeground()` is called inside `onCreate()` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` within the 5-second Android startup timeout.
4. `MediaProjectionManager.createScreenCaptureIntent()` prompts user consent, and token (`resultCode`, `resultData`) is delivered to `OverlayService`.

### 3. Zero-Allocation Processing Loop (`TableAndBallDetector.kt` & `OverlayCanvasView.kt`)
- **Direct ByteBuffer Reuse**: `ImageReader.acquireLatestImage()` reads direct plane buffers into persistent integer arrays without generating intermediate Kotlin `ByteArray` instances.
- **Persistent Mat & Object Pools**: All `Paint`, `Path`, `DashPathEffect`, `RectF`, and search buffers are pre-allocated outside the rendering and frame-processing loops to eliminate GC micro-stuttering.
- **Angle & Position Smoothing (`SmoothingFilter.kt`)**: Applies Exponential Moving Average (EMA, $\alpha = 0.35$) using unit vector decomposition to eliminate single-frame frame jitter and angle discontinuity across $\pm\pi$.

---

## Project Structure

```
├── app/
│   ├── CMakeLists.txt
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/
│       │   │   ├── pool_cv_engine.cpp
│       │   │   └── pool_cv_engine.h
│       │   ├── java/com/pool/guideline/overlay/
│       │   │   ├── MainActivity.kt
│       │   │   ├── cv/
│       │   │   │   ├── BallData.kt
│       │   │   │   ├── NativeCvBridge.kt
│       │   │   │   ├── TableAndBallDetector.kt
│       │   │   │   └── TableBounds.kt
│       │   │   ├── physics/
│       │   │   │   ├── Pocket.kt
│       │   │   │   ├── TrajectoryPhysicsEngine.kt
│       │   │   │   ├── TrajectoryResult.kt
│       │   │   │   └── Vector2D.kt
│       │   │   ├── service/
│       │   │   │   ├── OverlayService.kt
│       │   │   │   └── ScreenCaptureManager.kt
│       │   │   └── ui/
│       │   │       ├── OverlayCanvasView.kt
│       │   │       └── SmoothingFilter.kt
│       │   └── res/
│       │       ├── drawable/
│       │       ├── layout/activity_main.xml
│       │       └── values/{colors.xml, strings.xml, themes.xml}
│       └── test/java/com/pool/guideline/overlay/
│           ├── SmoothingFilterTest.kt
│           └── TrajectoryPhysicsTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## Build & Run Verification

### Prerequisites
- Android Studio Ladybug / Koala or CLI with Android SDK 35 (Android 15)
- Android NDK (r25c or higher) and CMake 3.22.1+
- Java JDK 17

### Building from Android Studio
1. Open the project folder in Android Studio.
2. Allow Gradle sync to complete.
3. Connect an Android device or start an emulator running Android 10+ (API 29 to 35).
4. Run the app (`app` configuration).

### Running Unit Tests
```bash
./gradlew test
```
Tests verify:
- Ghost ball $2R$ offset accuracy.
- Orthogonal 90° cue deflection angle ($\hat{n} \cdot \hat{t} = 0$).
- Rail bounce radius insets.
- EMA smoothing and angle wrapping across $[-\pi, \pi]$.
