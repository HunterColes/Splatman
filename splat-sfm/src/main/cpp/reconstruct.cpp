#include "reconstruct.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <numeric>
#include <string>
#include <utility>

#include <opencv2/calib3d.hpp>
#include <opencv2/imgcodecs.hpp>

namespace splat::sfm {
namespace {

using Clock = std::chrono::steady_clock;

int64_t elapsedMs(Clock::time_point since) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - since).count();
}

constexpr double kRadToDeg = 180.0 / 3.14159265358979323846;

// World-to-camera: x_cam = R x_world + t.
struct Pose {
    cv::Matx33d r = cv::Matx33d::eye();
    cv::Vec3d t{0.0, 0.0, 0.0};
};

struct Obs {
    int frame;
    int kp;
};

struct Track {
    std::vector<Obs> obs;  // sorted by frame, one per frame
    int point = -1;
};

class UnionFind {
public:
    explicit UnionFind(size_t n) : parent_(n) { std::iota(parent_.begin(), parent_.end(), 0); }
    int find(int x) {
        while (parent_[x] != x) {
            parent_[x] = parent_[parent_[x]];
            x = parent_[x];
        }
        return x;
    }
    void unite(int a, int b) {
        a = find(a);
        b = find(b);
        if (a != b) parent_[std::max(a, b)] = std::min(a, b);
    }

private:
    std::vector<int> parent_;
};

cv::Matx33d quatToMatrix(const std::array<float, 4>& q) {
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

// Shepperd's method; w >= 0.
std::array<double, 4> matrixToQuat(const cv::Matx33d& m) {
    const double tr = m(0, 0) + m(1, 1) + m(2, 2);
    double w, x, y, z;
    if (tr > 0.0) {
        const double s = std::sqrt(tr + 1.0) * 2.0;
        w = 0.25 * s;
        x = (m(2, 1) - m(1, 2)) / s;
        y = (m(0, 2) - m(2, 0)) / s;
        z = (m(1, 0) - m(0, 1)) / s;
    } else if (m(0, 0) > m(1, 1) && m(0, 0) > m(2, 2)) {
        const double s = std::sqrt(1.0 + m(0, 0) - m(1, 1) - m(2, 2)) * 2.0;
        w = (m(2, 1) - m(1, 2)) / s;
        x = 0.25 * s;
        y = (m(0, 1) + m(1, 0)) / s;
        z = (m(0, 2) + m(2, 0)) / s;
    } else if (m(1, 1) > m(2, 2)) {
        const double s = std::sqrt(1.0 + m(1, 1) - m(0, 0) - m(2, 2)) * 2.0;
        w = (m(0, 2) - m(2, 0)) / s;
        x = (m(0, 1) + m(1, 0)) / s;
        y = 0.25 * s;
        z = (m(1, 2) + m(2, 1)) / s;
    } else {
        const double s = std::sqrt(1.0 + m(2, 2) - m(0, 0) - m(1, 1)) * 2.0;
        w = (m(1, 0) - m(0, 1)) / s;
        x = (m(0, 2) + m(2, 0)) / s;
        y = (m(1, 2) + m(2, 1)) / s;
        z = 0.25 * s;
    }
    const double n = std::sqrt(w * w + x * x + y * y + z * z);
    const double sign = w < 0.0 ? -1.0 : 1.0;
    return {sign * w / n, sign * x / n, sign * y / n, sign * z / n};
}

cv::Vec2d normalized(const Intrinsics& k, const cv::Point2f& p) {
    return {(p.x - k.cx) / k.fx, (p.y - k.cy) / k.fy};
}

cv::Vec3d ray(const Intrinsics& k, const cv::Point2f& p) {
    const cv::Vec2d n = normalized(k, p);
    return cv::normalize(cv::Vec3d(n[0], n[1], 1.0));
}

double angleDeg(const cv::Vec3d& a, const cv::Vec3d& b) {
    return std::acos(std::clamp(a.dot(b) / (cv::norm(a) * cv::norm(b)), -1.0, 1.0)) * kRadToDeg;
}

// Squared reprojection error in full-size pixels; infinity when the point is not in front of the camera.
double reprojError2(const Pose& pose, const Intrinsics& k, const cv::Vec3d& x, const cv::Point2f& uv) {
    const cv::Vec3d c = pose.r * x + pose.t;
    if (!(c[2] > 0.0)) return std::numeric_limits<double>::infinity();
    const double du = k.fx * c[0] / c[2] + k.cx - uv.x;
    const double dv = k.fy * c[1] / c[2] + k.cy - uv.y;
    return du * du + dv * dv;
}

// Linear (DLT) triangulation of two normalized observations; false when the point is at infinity.
bool triangulate(const Pose& a, const cv::Vec2d& na, const Pose& b, const cv::Vec2d& nb, cv::Vec3d& out) {
    cv::Matx44d m;
    const Pose* poses[2] = {&a, &b};
    const cv::Vec2d* obs[2] = {&na, &nb};
    for (int v = 0; v < 2; ++v) {
        const Pose& p = *poses[v];
        const cv::Vec2d& n = *obs[v];
        for (int c = 0; c < 4; ++c) {
            const double r0 = c < 3 ? p.r(0, c) : p.t[0];
            const double r1 = c < 3 ? p.r(1, c) : p.t[1];
            const double r2 = c < 3 ? p.r(2, c) : p.t[2];
            m(2 * v, c) = n[0] * r2 - r0;
            m(2 * v + 1, c) = n[1] * r2 - r1;
        }
    }
    cv::Mat x;
    cv::SVD::solveZ(cv::Mat(m), x);
    const double w = x.at<double>(3);
    if (!(std::fabs(w) > 1e-12)) return false;
    out = {x.at<double>(0) / w, x.at<double>(1) / w, x.at<double>(2) / w};
    return std::isfinite(out[0]) && std::isfinite(out[1]) && std::isfinite(out[2]);
}

class Reconstructor {
public:
    Reconstructor(const FrontEndResult& fe, int maxPoints, int maxObservations)
        : fe_(fe), n_(static_cast<int>(fe.frames.size())), maxPoints_(maxPoints), maxObservations_(maxObservations),
          poses_(n_), registered_(n_, 0), lastTried_(n_, -1) {}

    SparseResult run() {
        auto t = Clock::now();
        buildTracks();
        const bool initialised = initialise();
        result_.initMs = elapsedMs(t);
        t = Clock::now();
        if (initialised) registerAll();
        emit();
        result_.registerMs = elapsedMs(t);
        return std::move(result_);
    }

private:
    const Intrinsics& k(int frame) const { return fe_.intrinsics[frame]; }
    const cv::Point2f& pt(const Obs& o) const { return fe_.frames[o.frame].keypoints[o.kp].pt; }
    int node(int frame, int kp) const { return offset_[frame] + kp; }

    // Union of every pair's inliers; a track seen twice in one frame, or only once, is dropped.
    void buildTracks() {
        offset_.assign(n_ + 1, 0);
        for (int f = 0; f < n_; ++f) offset_[f + 1] = offset_[f] + static_cast<int>(fe_.frames[f].keypoints.size());
        const int nodes = offset_[n_];
        UnionFind uf(nodes);
        std::vector<uint8_t> touched(nodes, 0);
        for (const PairResult& p : fe_.pairs) {
            for (const cv::DMatch& m : p.inliers) {
                const int a = node(p.i, m.queryIdx);
                const int b = node(p.j, m.trainIdx);
                uf.unite(a, b);
                touched[a] = touched[b] = 1;
            }
        }
        std::vector<int> rootTrack(nodes, -1);
        std::vector<Track> all;
        for (int f = 0; f < n_; ++f) {
            for (int kp = 0; kp < offset_[f + 1] - offset_[f]; ++kp) {
                const int nd = node(f, kp);
                if (!touched[nd]) continue;
                const int root = uf.find(nd);
                if (rootTrack[root] < 0) {
                    rootTrack[root] = static_cast<int>(all.size());
                    all.emplace_back();
                }
                all[rootTrack[root]].obs.push_back({f, kp});  // f ascending: obs sorted by frame
            }
        }
        nodeTrack_.assign(nodes, -1);
        for (Track& tr : all) {
            if (tr.obs.size() < 2) continue;
            bool twice = false;
            for (size_t o = 1; o < tr.obs.size(); ++o) twice = twice || tr.obs[o].frame == tr.obs[o - 1].frame;
            if (twice) continue;
            const int id = static_cast<int>(tracks_.size());
            for (const Obs& o : tr.obs) nodeTrack_[node(o.frame, o.kp)] = id;
            tracks_.push_back(std::move(tr));
        }
    }

    int trackOf(int frame, int kp) const { return nodeTrack_[node(frame, kp)]; }

    // The pair among the first kInitWindow frames with the most inliers whose median parallax is at least
    // kInitMinParallaxDeg; the first such pair that triangulates kMinInitPoints points wins.
    bool initialise() {
        struct Candidate {
            size_t pair;
            size_t inliers;
        };
        std::vector<Candidate> candidates;
        for (size_t p = 0; p < fe_.pairs.size(); ++p) {
            const PairResult& pr = fe_.pairs[p];
            if (pr.i >= kInitWindow || pr.j >= kInitWindow) continue;
            if (pr.inliers.size() < static_cast<size_t>(kMinInitPoints)) continue;
            if (medianParallaxDeg(pr) < kInitMinParallaxDeg) continue;
            candidates.push_back({p, pr.inliers.size()});
        }
        std::stable_sort(candidates.begin(), candidates.end(),
                         [](const Candidate& a, const Candidate& b) { return a.inliers > b.inliers; });
        for (const Candidate& c : candidates) {
            if (tryInit(fe_.pairs[c.pair])) return true;
        }
        return false;
    }

    // Median angle between matched rays once the rotation is removed: the pair's gyro rotation when it has one,
    // else the two-view rotation from the essential matrix (raw ray angles read an orbit's parallax as ~0).
    double medianParallaxDeg(const PairResult& pr) const {
        cv::Matx33d r = cv::Matx33d::eye();
        if (pr.hasRotation) {
            r = quatToMatrix(pr.rotation);
        } else {
            Pose b;
            if (!twoViewPose(pr, b)) return 0.0;
            r = b.r;
        }
        std::vector<double> angles;
        angles.reserve(pr.inliers.size());
        for (const cv::DMatch& m : pr.inliers) {
            const cv::Vec3d di = r * ray(k(pr.i), fe_.frames[pr.i].keypoints[m.queryIdx].pt);
            const cv::Vec3d dj = ray(k(pr.j), fe_.frames[pr.j].keypoints[m.trainIdx].pt);
            angles.push_back(angleDeg(di, dj));
        }
        if (angles.empty()) return 0.0;
        auto mid = angles.begin() + static_cast<std::ptrdiff_t>(angles.size() / 2);
        std::nth_element(angles.begin(), mid, angles.end());
        return *mid;
    }

    // Camera j relative to camera i (x_j = R x_i + t, |t| = 1): essential-matrix RANSAC at kMaxReprojPx on the
    // pair's inliers, then recoverPose; false when fewer than kMinInitPoints pass its cheirality check.
    bool twoViewPose(const PairResult& pr, Pose& b) const {
        std::vector<cv::Point2d> pi, pj;
        pi.reserve(pr.inliers.size());
        pj.reserve(pr.inliers.size());
        for (const cv::DMatch& m : pr.inliers) {
            const cv::Vec2d a = normalized(k(pr.i), fe_.frames[pr.i].keypoints[m.queryIdx].pt);
            const cv::Vec2d b = normalized(k(pr.j), fe_.frames[pr.j].keypoints[m.trainIdx].pt);
            pi.emplace_back(a[0], a[1]);
            pj.emplace_back(b[0], b[1]);
        }
        const double focal = (k(pr.i).fx + k(pr.i).fy + k(pr.j).fx + k(pr.j).fy) / 4.0;
        const cv::Mat eye = cv::Mat::eye(3, 3, CV_64F);
        cv::Mat mask;
        cv::Mat e = cv::findEssentialMat(pi, pj, eye, cv::RANSAC, kRansacConfidence, kMaxReprojPx / focal,
                                         kRansacMaxIters, mask);
        if (e.rows != 3 || e.cols != 3) return false;  // none, or several stacked solutions
        cv::Mat r, t;
        if (cv::recoverPose(e, pi, pj, eye, r, t, mask) < kMinInitPoints) return false;
        b.r = cv::Matx33d(r);
        b.t = cv::Vec3d(t.at<double>(0), t.at<double>(1), t.at<double>(2));
        return true;
    }

    bool tryInit(const PairResult& pr) {
        Pose a;
        Pose b;
        if (!twoViewPose(pr, b)) return false;

        std::vector<int> made;
        for (size_t q = 0; q < pr.inliers.size(); ++q) {
            const cv::DMatch& m = pr.inliers[q];
            const int tr = trackOf(pr.i, m.queryIdx);
            if (tr < 0 || tr != trackOf(pr.j, m.trainIdx) || tracks_[tr].point >= 0) continue;
            const Obs oa{pr.i, m.queryIdx};
            const Obs ob{pr.j, m.trainIdx};
            if (addPoint(tr, a, oa, b, ob)) made.push_back(tr);
        }
        if (made.size() < static_cast<size_t>(kMinInitPoints)) {
            for (int tr : made) tracks_[tr].point = -1;
            points_.clear();
            pointTrack_.clear();
            result_.pointsCapped = false;
            return false;
        }
        poses_[pr.i] = a;
        poses_[pr.j] = b;
        registered_[pr.i] = registered_[pr.j] = 1;
        return true;
    }

    // Triangulates track tr from two posed observations; kept when in front of both cameras and within
    // kMaxReprojPx in both. False (and the cap flag) once kMaxPoints points exist.
    bool addPoint(int tr, const Pose& pa, const Obs& a, const Pose& pb, const Obs& b) {
        if (static_cast<int>(points_.size()) >= maxPoints_) {
            result_.pointsCapped = true;
            return false;
        }
        cv::Vec3d x;
        if (!triangulate(pa, normalized(k(a.frame), pt(a)), pb, normalized(k(b.frame), pt(b)), x)) return false;
        const double max2 = kMaxReprojPx * kMaxReprojPx;
        if (!(reprojError2(pa, k(a.frame), x, pt(a)) <= max2) || !(reprojError2(pb, k(b.frame), x, pt(b)) <= max2)) {
            return false;
        }
        tracks_[tr].point = static_cast<int>(points_.size());
        points_.push_back(x);
        pointTrack_.push_back(tr);
        return true;
    }

    // Video order, then the frames left over again while any registers; a frame is retried only when it sees more
    // triangulated tracks than at its last attempt.
    void registerAll() {
        bool progress = true;
        while (progress) {
            progress = false;
            for (int f = 0; f < n_; ++f) {
                if (!registered_[f] && tryRegister(f)) progress = true;
            }
        }
    }

    bool tryRegister(int f) {
        std::vector<cv::Point3d> obj;
        std::vector<cv::Point2d> img;
        const std::vector<cv::KeyPoint>& kps = fe_.frames[f].keypoints;
        for (int kp = 0; kp < static_cast<int>(kps.size()); ++kp) {
            const int tr = trackOf(f, kp);
            if (tr < 0 || tracks_[tr].point < 0) continue;
            const cv::Vec3d& x = points_[tracks_[tr].point];
            obj.emplace_back(x[0], x[1], x[2]);
            img.emplace_back(kps[kp].pt.x, kps[kp].pt.y);
        }
        const int seen = static_cast<int>(obj.size());
        if (seen <= lastTried_[f]) return false;
        lastTried_[f] = seen;
        if (seen < kMinPnpInliers) return false;

        const Intrinsics& in = k(f);
        const cv::Matx33d km(in.fx, 0.0, in.cx, 0.0, in.fy, in.cy, 0.0, 0.0, 1.0);
        cv::Mat rvec, tvec;
        std::vector<int> inliers;
        const bool ok = cv::solvePnPRansac(obj, img, km, cv::noArray(), rvec, tvec, false, kPnpIterations,
                                           static_cast<float>(kMaxReprojPx), kPnpConfidence, inliers,
                                           cv::SOLVEPNP_EPNP);
        if (!ok || inliers.size() < static_cast<size_t>(kMinPnpInliers)) return false;
        std::vector<cv::Point3d> objIn;
        std::vector<cv::Point2d> imgIn;
        for (int q : inliers) {
            objIn.push_back(obj[q]);
            imgIn.push_back(img[q]);
        }
        cv::solvePnPRefineLM(objIn, imgIn, km, cv::noArray(), rvec, tvec);
        cv::Mat rm;
        cv::Rodrigues(rvec, rm);
        Pose pose;
        pose.r = cv::Matx33d(rm);
        pose.t = cv::Vec3d(tvec.at<double>(0), tvec.at<double>(1), tvec.at<double>(2));

        int good = 0;
        const double max2 = kMaxReprojPx * kMaxReprojPx;
        for (size_t q = 0; q < obj.size(); ++q) {
            const cv::Point2f uv(static_cast<float>(img[q].x), static_cast<float>(img[q].y));
            if (reprojError2(pose, in, cv::Vec3d(obj[q].x, obj[q].y, obj[q].z), uv) <= max2) ++good;
        }
        if (good < kMinPnpInliers) return false;
        poses_[f] = pose;
        registered_[f] = 1;
        triangulateFrom(f);
        return true;
    }

    // New points from frame f and the registered frame of each untriangulated track with the widest ray angle,
    // at least kMinTriangulationDeg.
    void triangulateFrom(int f) {
        const std::vector<cv::KeyPoint>& kps = fe_.frames[f].keypoints;
        const Pose& pf = poses_[f];
        for (int kp = 0; kp < static_cast<int>(kps.size()); ++kp) {
            const int tr = trackOf(f, kp);
            if (tr < 0 || tracks_[tr].point >= 0) continue;
            const cv::Vec3d rf = pf.r.t() * ray(k(f), kps[kp].pt);
            double best = -1.0;
            const Obs* other = nullptr;
            for (const Obs& o : tracks_[tr].obs) {
                if (o.frame == f || !registered_[o.frame]) continue;
                const double a = angleDeg(rf, poses_[o.frame].r.t() * ray(k(o.frame), pt(o)));
                if (a > best) {
                    best = a;
                    other = &o;
                }
            }
            if (other == nullptr || best < kMinTriangulationDeg) continue;
            if (!addPoint(tr, pf, Obs{f, kp}, poses_[other->frame], *other) && result_.pointsCapped) return;
        }
    }

    // Poses, points, observations (every registered track entry within kMaxReprojPx) and colours.
    void emit() {
        result_.registered = registered_;
        result_.rotations.resize(4 * static_cast<size_t>(n_));
        result_.translations.resize(3 * static_cast<size_t>(n_));
        for (int f = 0; f < n_; ++f) {
            const Pose& p = poses_[f];
            const std::array<double, 4> q = registered_[f] ? matrixToQuat(p.r) : std::array<double, 4>{1, 0, 0, 0};
            for (int c = 0; c < 4; ++c) result_.rotations[4 * f + c] = static_cast<float>(q[c]);
            for (int c = 0; c < 3; ++c) result_.translations[3 * f + c] = registered_[f] ? static_cast<float>(p.t[c]) : 0.0f;
        }
        const size_t np = points_.size();
        result_.points.resize(3 * np);
        result_.colours.assign(3 * np, 0);
        std::vector<std::vector<std::pair<int, cv::Point2f>>> colourAt(n_);
        const double max2 = kMaxReprojPx * kMaxReprojPx;
        for (size_t p = 0; p < np; ++p) {
            for (int c = 0; c < 3; ++c) result_.points[3 * p + c] = static_cast<float>(points_[p][c]);
            const Track& tr = tracks_[pointTrack_[p]];
            bool coloured = false;
            for (const Obs& o : tr.obs) {
                if (!registered_[o.frame] || !(reprojError2(poses_[o.frame], k(o.frame), points_[p], pt(o)) <= max2)) {
                    continue;
                }
                if (!coloured) {
                    colourAt[o.frame].emplace_back(static_cast<int>(p), pt(o));
                    coloured = true;
                }
                if (static_cast<int>(result_.obsFrame.size()) >= maxObservations_) {
                    result_.observationsCapped = true;
                    break;
                }
                result_.obsFrame.push_back(o.frame);
                result_.obsPoint.push_back(static_cast<int>(p));
                result_.obsUv.push_back(pt(o).x);
                result_.obsUv.push_back(pt(o).y);
            }
        }
        // One colour image in memory at a time.
        for (int f = 0; f < n_; ++f) {
            if (colourAt[f].empty()) continue;
            cv::Mat bgr = cv::imread(fe_.paths[f], cv::IMREAD_COLOR);
            if (bgr.empty() || bgr.cols != k(f).width || bgr.rows != k(f).height) {
                throw FrontEndError("frame " + std::to_string(f) + " (" + fe_.paths[f] +
                                    "): cannot read the image again for colours");
            }
            for (const auto& [p, uv] : colourAt[f]) {
                const int x = std::clamp(static_cast<int>(std::lround(uv.x)), 0, bgr.cols - 1);
                const int y = std::clamp(static_cast<int>(std::lround(uv.y)), 0, bgr.rows - 1);
                const cv::Vec3b px = bgr.at<cv::Vec3b>(y, x);
                result_.colours[3 * p] = px[2];
                result_.colours[3 * p + 1] = px[1];
                result_.colours[3 * p + 2] = px[0];
            }
        }
    }

    const FrontEndResult& fe_;
    const int n_;
    const int maxPoints_;
    const int maxObservations_;
    std::vector<int> offset_;
    std::vector<int> nodeTrack_;
    std::vector<Track> tracks_;
    std::vector<cv::Vec3d> points_;
    std::vector<int> pointTrack_;
    std::vector<Pose> poses_;
    std::vector<uint8_t> registered_;
    std::vector<int> lastTried_;
    SparseResult result_;
};

}  // namespace

SparseResult reconstruct(const FrontEndResult& fe, int maxPoints, int maxObservations) {
    if (maxPoints < 1 || maxPoints > kMaxPoints) {
        throw FrontEndError("maxPoints must be in [1, " + std::to_string(kMaxPoints) + "], got " + std::to_string(maxPoints));
    }
    if (maxObservations < 1 || maxObservations > kMaxObservations) {
        throw FrontEndError("maxObservations must be in [1, " + std::to_string(kMaxObservations) + "], got " +
                            std::to_string(maxObservations));
    }
    if (fe.intrinsics.size() != fe.frames.size() || fe.paths.size() != fe.frames.size()) {
        throw FrontEndError("front-end result without its inputs");
    }
    return Reconstructor(fe, maxPoints, maxObservations).run();
}

}  // namespace splat::sfm
