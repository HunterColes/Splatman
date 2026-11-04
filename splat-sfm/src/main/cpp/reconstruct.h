// Incremental reconstruction (card #226, epic #200, ADR 0012): from the front end's verified matches to registered
// cameras, triangulated points and observations. OpenCV axes (x right, y down, z forward), world-to-camera; the world
// frame is the first init camera's. No bundle adjustment (splat-core's BundleAdjuster does it) and no axis flips
// (ADR 0003 conversions are Kotlin's). Pure C++17 + OpenCV; no JNI here.
#pragma once

#include <cstdint>
#include <vector>

#include "front_end.h"

namespace splat::sfm {

// Hard memory caps; a caller may ask for less, never more.
constexpr int kMaxPoints = 50000;
constexpr int kMaxObservations = 1000000;

// Reconstruction constants (card #226).
constexpr int kInitWindow = 30;              // the init pair is among the first 30 frames
constexpr double kInitMinParallaxDeg = 3.0;  // median parallax of the init pair, gyro rotation removed when given
constexpr int kMinInitPoints = 30;           // an init pair that triangulates fewer is skipped
constexpr double kMaxReprojPx = 2.0;         // full-size pixels: triangulation checks, PnP RANSAC, observations
constexpr int kMinPnpInliers = 30;           // a frame with fewer stays unregistered
constexpr int kPnpIterations = 1000;
constexpr double kPnpConfidence = 0.999;
constexpr double kMinTriangulationDeg = 2.0;  // ray angle for a new point

struct SparseResult {
    std::vector<uint8_t> registered;  // per frame, 1 = registered
    std::vector<float> rotations;     // R_Ccv_W as w, x, y, z per frame (identity when unregistered)
    std::vector<float> translations;  // t_Ccv_W, 3 per frame (x_cam = R x_world + t; zero when unregistered)
    std::vector<float> points;        // x, y, z per point, world frame
    std::vector<uint8_t> colours;     // r, g, b per point: its pixel in its first observing frame
    std::vector<int> obsFrame;
    std::vector<int> obsPoint;
    std::vector<float> obsUv;  // u, v per observation, full-size pixels (OpenCV keypoint coordinates)
    bool pointsCapped = false;
    bool observationsCapped = false;
    int64_t initMs = 0;
    int64_t registerMs = 0;
};

// Builds tracks from every pair's inliers, picks the init pair, registers the other frames by PnP and triangulates.
// At most maxPoints points (1..kMaxPoints) and maxObservations observations (1..kMaxObservations); reaching a cap
// sets its flag instead of failing. No init pair gives a result with nothing registered. Throws FrontEndError on a
// bad cap or an image that can no longer be read for colours.
SparseResult reconstruct(const FrontEndResult& fe, int maxPoints, int maxObservations);

}  // namespace splat::sfm
