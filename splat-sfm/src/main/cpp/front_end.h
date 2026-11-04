// SfM front end (epic #200, ADR 0012): load frames, extract features, match the given pairs and verify them.
// Pure C++17 + OpenCV; no JNI here (jni_bridge.cpp checks the arrays and converts errors).
#pragma once

#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>

namespace splat::sfm {

// Same order as com.huntercoles.splatman.splat.sfm.FeatureKind (ORB = 0, AKAZE = 1).
enum class FeatureKind : int { ORB = 0, AKAZE = 1 };

// Stage timings, the order of NativeSfm.stageMs.
// INIT and REGISTER are filled by reconstruct (reconstruct.h); same order as splat-core's SfmStage.
enum Stage : int { LOAD = 0, FEATURES = 1, MATCHING = 2, INIT = 3, REGISTER = 4, STAGE_COUNT = 5 };

// Pinhole intrinsics of one frame, in pixels of the full-size image (width x height).
struct Intrinsics {
    float fx, fy, cx, cy;
    int width, height;
};

// A pair to match, i != j. When hasRotation, rotation (w, x, y, z) is R_Cj_Ci in the OpenCV camera frame
// (x right, y down, z forward), as splat-core's FramePair.rotationCjCi: a ray d in camera i is R d in camera j.
struct PairInput {
    int i, j;
    bool hasRotation;
    std::array<float, 4> rotation;
};

struct Params {
    FeatureKind kind;
    int maxFeatures;  // > 0
    int longSide;     // > 0: images whose long side is larger are downscaled to it
};

struct FrameFeatures {
    // Full-size pixels (pt and size scaled back from the working size).
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;  // binary (CV_8U), one row per keypoint
    double scale = 1.0;   // working size / full size
};

struct PairResult {
    int i, j;
    // Verified matches: queryIdx indexes frame i's keypoints, trainIdx frame j's.
    std::vector<cv::DMatch> inliers;
    // The pair's gyro rotation R_Cj_Ci as given (PairInput), for reconstruction.
    bool hasRotation = false;
    std::array<float, 4> rotation{};
};

struct FrontEndResult {
    // The inputs reconstruction needs again: image paths (colours) and full-size intrinsics.
    std::vector<std::string> paths;
    std::vector<Intrinsics> intrinsics;
    std::vector<FrameFeatures> frames;
    std::vector<PairResult> pairs;
    std::array<int64_t, STAGE_COUNT> stageMs{};
};

// A bad input (missing or unreadable file, a size that does not match the intrinsics, a bad parameter).
class FrontEndError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Matching constants (card #225).
constexpr float kLoweRatio = 0.8f;
constexpr double kRotationGateFraction = 0.05;  // of the working long side
constexpr double kRansacThresholdPx = 1.0;      // at the working size
constexpr double kRansacConfidence = 0.999;
constexpr int kRansacMaxIters = 2000;

// Runs the whole front end. Throws FrontEndError on bad input; indices in pairs are assumed checked by the caller
// against paths.size() (and checked again here).
FrontEndResult runFrontEnd(const std::vector<std::string>& paths, const std::vector<Intrinsics>& intrinsics,
                           const std::vector<PairInput>& pairs, const Params& params);

}  // namespace splat::sfm
