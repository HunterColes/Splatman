#include "front_end.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <utility>

#include <opencv2/calib3d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace splat::sfm {
namespace {

using Clock = std::chrono::steady_clock;

int64_t elapsedMs(Clock::time_point since) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - since).count();
}

std::string frameName(size_t k, const std::string& path) {
    return "frame " + std::to_string(k) + " (" + path + ")";
}

// Working-size camera matrix.
cv::Matx33d workingK(const Intrinsics& in, double s) {
    return {in.fx * s, 0.0, in.cx * s, 0.0, in.fy * s, in.cy * s, 0.0, 0.0, 1.0};
}

cv::Matx33d rotationMatrix(const std::array<float, 4>& q) {
    double w = q[0], x = q[1], y = q[2], z = q[3];
    const double n = std::sqrt(w * w + x * x + y * y + z * z);
    w /= n;
    x /= n;
    y /= n;
    z /= n;
    return {1 - 2 * (y * y + z * z), 2 * (x * y - w * z),     2 * (x * z + w * y),
            2 * (x * y + w * z),     1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y),     2 * (y * z + w * x),     1 - 2 * (x * x + y * y)};
}

// Grayscale image at the working size; scale = working / full.
cv::Mat loadFrame(size_t k, const std::string& path, const Intrinsics& in, int longSide, double& scale) {
    cv::Mat full = cv::imread(path, cv::IMREAD_GRAYSCALE);
    if (full.empty()) throw FrontEndError(frameName(k, path) + ": cannot read the image (missing or not an image)");
    if (full.cols != in.width || full.rows != in.height) {
        throw FrontEndError(frameName(k, path) + ": image is " + std::to_string(full.cols) + "x" +
                            std::to_string(full.rows) + ", intrinsics say " + std::to_string(in.width) + "x" +
                            std::to_string(in.height));
    }
    const int fullLong = std::max(full.cols, full.rows);
    if (fullLong <= longSide) {
        scale = 1.0;
        return full;
    }
    scale = static_cast<double>(longSide) / fullLong;
    const int w = std::max(1, static_cast<int>(std::lround(full.cols * scale)));
    const int h = std::max(1, static_cast<int>(std::lround(full.rows * scale)));
    cv::Mat small;
    cv::resize(full, small, cv::Size(w, h), 0, 0, cv::INTER_AREA);
    return small;
}

// Keypoints and descriptors at the working size, at most maxFeatures, strongest first.
void extract(const cv::Mat& img, const Params& params, std::vector<cv::KeyPoint>& kps, cv::Mat& desc) {
    if (params.kind == FeatureKind::ORB) {
        cv::Ptr<cv::ORB> orb = cv::ORB::create(params.maxFeatures);
        orb->detectAndCompute(img, cv::noArray(), kps, desc);
        return;
    }
    cv::Ptr<cv::AKAZE> akaze = cv::AKAZE::create();
    akaze->detect(img, kps);
    std::stable_sort(kps.begin(), kps.end(),
                     [](const cv::KeyPoint& a, const cv::KeyPoint& b) { return a.response > b.response; });
    if (kps.size() > static_cast<size_t>(params.maxFeatures)) kps.resize(params.maxFeatures);
    akaze->compute(img, kps, desc);
}

// Brute-force Hamming, Lowe's ratio on the two nearest, and a mutual check (j's best match is i's keypoint too).
std::vector<cv::DMatch> mutualRatioMatches(const cv::Mat& di, const cv::Mat& dj) {
    std::vector<cv::DMatch> out;
    if (di.empty() || dj.empty()) return out;
    cv::BFMatcher matcher(cv::NORM_HAMMING, false);
    std::vector<std::vector<cv::DMatch>> forward;
    matcher.knnMatch(di, dj, forward, 2);
    std::vector<cv::DMatch> backward;
    matcher.match(dj, di, backward);
    std::vector<int> bestBack(dj.rows, -1);
    for (const cv::DMatch& b : backward) bestBack[b.queryIdx] = b.trainIdx;
    for (const std::vector<cv::DMatch>& k : forward) {
        if (k.empty()) continue;
        const cv::DMatch& best = k[0];
        if (k.size() >= 2 && !(best.distance < kLoweRatio * k[1].distance)) continue;
        if (bestBack[best.trainIdx] != best.queryIdx) continue;
        out.push_back(best);
    }
    return out;
}

// Drops matches farther than the gate from where the rotation-only homography K_j R K_i^-1 sends them.
std::vector<cv::DMatch> rotationGate(const std::vector<cv::DMatch>& matches, const std::vector<cv::KeyPoint>& ki,
                                     const std::vector<cv::KeyPoint>& kj, const cv::Matx33d& h, double gatePx) {
    std::vector<cv::DMatch> out;
    out.reserve(matches.size());
    const double gate2 = gatePx * gatePx;
    for (const cv::DMatch& m : matches) {
        const cv::Point2f& p = ki[m.queryIdx].pt;
        const cv::Vec3d q = h * cv::Vec3d(p.x, p.y, 1.0);
        if (!(q[2] > 0.0)) continue;  // behind camera j
        const double dx = q[0] / q[2] - kj[m.trainIdx].pt.x;
        const double dy = q[1] / q[2] - kj[m.trainIdx].pt.y;
        if (dx * dx + dy * dy <= gate2) out.push_back(m);
    }
    return out;
}

// Essential-matrix RANSAC on normalized coordinates (each frame its own K), threshold 1 px at the working size.
std::vector<cv::DMatch> essentialInliers(const std::vector<cv::DMatch>& matches, const std::vector<cv::KeyPoint>& ki,
                                         const std::vector<cv::KeyPoint>& kj, const cv::Matx33d& kmi,
                                         const cv::Matx33d& kmj) {
    std::vector<cv::DMatch> out;
    if (matches.size() < 5) return out;
    std::vector<cv::Point2d> pi, pj;
    pi.reserve(matches.size());
    pj.reserve(matches.size());
    for (const cv::DMatch& m : matches) {
        const cv::Point2f& a = ki[m.queryIdx].pt;
        const cv::Point2f& b = kj[m.trainIdx].pt;
        pi.emplace_back((a.x - kmi(0, 2)) / kmi(0, 0), (a.y - kmi(1, 2)) / kmi(1, 1));
        pj.emplace_back((b.x - kmj(0, 2)) / kmj(0, 0), (b.y - kmj(1, 2)) / kmj(1, 1));
    }
    const double focal = (kmi(0, 0) + kmi(1, 1) + kmj(0, 0) + kmj(1, 1)) / 4.0;
    cv::Mat mask;
    cv::Mat e = cv::findEssentialMat(pi, pj, cv::Mat::eye(3, 3, CV_64F), cv::RANSAC, kRansacConfidence,
                                     kRansacThresholdPx / focal, kRansacMaxIters, mask);
    if (e.empty() || mask.empty() || mask.total() != matches.size()) return out;
    const uchar* keep = mask.ptr<uchar>();
    for (size_t k = 0; k < matches.size(); ++k) {
        if (keep[k]) out.push_back(matches[k]);
    }
    return out;
}

void checkInputs(const std::vector<std::string>& paths, const std::vector<Intrinsics>& intrinsics,
                 const std::vector<PairInput>& pairs, const Params& params) {
    if (paths.empty()) throw FrontEndError("no frames");
    if (intrinsics.size() != paths.size()) {
        throw FrontEndError("intrinsics for " + std::to_string(intrinsics.size()) + " frames, " +
                            std::to_string(paths.size()) + " paths");
    }
    if (params.kind != FeatureKind::ORB && params.kind != FeatureKind::AKAZE) {
        throw FrontEndError("unknown feature kind " + std::to_string(static_cast<int>(params.kind)));
    }
    if (params.maxFeatures <= 0) throw FrontEndError("maxFeatures must be > 0, got " + std::to_string(params.maxFeatures));
    if (params.longSide <= 0) throw FrontEndError("longSide must be > 0, got " + std::to_string(params.longSide));
    for (size_t k = 0; k < intrinsics.size(); ++k) {
        const Intrinsics& in = intrinsics[k];
        const bool finite = std::isfinite(in.fx) && std::isfinite(in.fy) && std::isfinite(in.cx) && std::isfinite(in.cy);
        if (!finite || in.fx <= 0.0f || in.fy <= 0.0f || in.width <= 0 || in.height <= 0) {
            throw FrontEndError("frame " + std::to_string(k) + ": bad intrinsics (fx, fy, width, height must be > 0)");
        }
    }
    const int n = static_cast<int>(paths.size());
    for (size_t p = 0; p < pairs.size(); ++p) {
        const PairInput& pair = pairs[p];
        if (pair.i < 0 || pair.i >= n || pair.j < 0 || pair.j >= n || pair.i == pair.j) {
            throw FrontEndError("pair " + std::to_string(p) + ": (" + std::to_string(pair.i) + ", " +
                                std::to_string(pair.j) + ") is not two different frames of " + std::to_string(n));
        }
    }
}

}  // namespace

FrontEndResult runFrontEnd(const std::vector<std::string>& paths, const std::vector<Intrinsics>& intrinsics,
                           const std::vector<PairInput>& pairs, const Params& params) {
    checkInputs(paths, intrinsics, pairs, params);
    FrontEndResult result;
    result.frames.resize(paths.size());
    std::vector<cv::Size> workingSize(paths.size());

    // Load and extract one frame at a time, so only one image is in memory.
    for (size_t k = 0; k < paths.size(); ++k) {
        FrameFeatures& f = result.frames[k];
        auto t = Clock::now();
        cv::Mat img = loadFrame(k, paths[k], intrinsics[k], params.longSide, f.scale);
        workingSize[k] = img.size();
        result.stageMs[LOAD] += elapsedMs(t);
        t = Clock::now();
        extract(img, params, f.keypoints, f.descriptors);
        result.stageMs[FEATURES] += elapsedMs(t);
    }

    auto t = Clock::now();
    result.pairs.reserve(pairs.size());
    for (const PairInput& pair : pairs) {
        const FrameFeatures& fi = result.frames[pair.i];
        const FrameFeatures& fj = result.frames[pair.j];
        const cv::Matx33d kmi = workingK(intrinsics[pair.i], fi.scale);
        const cv::Matx33d kmj = workingK(intrinsics[pair.j], fj.scale);
        std::vector<cv::DMatch> matches = mutualRatioMatches(fi.descriptors, fj.descriptors);
        if (pair.hasRotation) {
            const cv::Matx33d h = kmj * rotationMatrix(pair.rotation) * kmi.inv();
            const cv::Size& sj = workingSize[pair.j];
            const double gatePx = kRotationGateFraction * std::max(sj.width, sj.height);
            matches = rotationGate(matches, fi.keypoints, fj.keypoints, h, gatePx);
        }
        result.pairs.push_back({pair.i, pair.j, essentialInliers(matches, fi.keypoints, fj.keypoints, kmi, kmj)});
    }
    result.stageMs[MATCHING] = elapsedMs(t);

    // Keypoints out in full-size pixels, for reconstruction.
    for (FrameFeatures& f : result.frames) {
        if (f.scale == 1.0) continue;
        const float inv = static_cast<float>(1.0 / f.scale);
        for (cv::KeyPoint& kp : f.keypoints) {
            kp.pt *= inv;
            kp.size *= inv;
        }
    }
    return result;
}

}  // namespace splat::sfm
