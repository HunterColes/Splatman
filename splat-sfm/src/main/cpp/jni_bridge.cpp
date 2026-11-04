// JNI bridge for NativeSfm (Kotlin: com.huntercoles.splatman.splat.sfm.nativebridge.NativeSfm). Every array length
// is checked against the frame and pair counts before an element is read; every error becomes a NativeSfmException
// with the reason, never a crash. A handle is a FrontEndResult* that is only dereferenced while it is in the live set.
#include <jni.h>

#include <cmath>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include <vector>

#include "front_end.h"

namespace {

using splat::sfm::FrontEndError;
using splat::sfm::FrontEndResult;

constexpr const char* kExceptionClass = "com/huntercoles/splatman/splat/sfm/nativebridge/NativeSfmException";
constexpr int kIntrinsicsPerFrame = 6;  // fx fy cx cy w h
constexpr int kRotationPerPair = 4;     // w x y z
constexpr float kMaxImageSide = 65536.0f;
constexpr double kUnitTolerance = 1e-2;

std::mutex gLiveMutex;
std::unordered_set<const FrontEndResult*> gLive;

void throwSfm(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;  // keep the first exception (e.g. an OutOfMemoryError from the VM)
    jclass cls = env->FindClass(kExceptionClass);
    if (cls == nullptr) return;  // NoClassDefFoundError is pending
    env->ThrowNew(cls, message.c_str());
    env->DeleteLocalRef(cls);
}

std::vector<float> floats(JNIEnv* env, jfloatArray a) {
    std::vector<float> out(static_cast<size_t>(env->GetArrayLength(a)));
    if (!out.empty()) env->GetFloatArrayRegion(a, 0, static_cast<jsize>(out.size()), out.data());
    return out;
}

std::vector<int> ints(JNIEnv* env, jintArray a) {
    std::vector<int> out(static_cast<size_t>(env->GetArrayLength(a)));
    if (!out.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(out.size()), reinterpret_cast<jint*>(out.data()));
    return out;
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray a) {
    const jsize n = env->GetArrayLength(a);
    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(n));
    for (jsize k = 0; k < n; ++k) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(a, k));
        if (s == nullptr) throw FrontEndError("path " + std::to_string(k) + " is null");
        const char* chars = env->GetStringUTFChars(s, nullptr);
        if (chars == nullptr) {
            env->DeleteLocalRef(s);
            throw std::bad_alloc();
        }
        out.emplace_back(chars);
        env->ReleaseStringUTFChars(s, chars);
        env->DeleteLocalRef(s);
    }
    return out;
}

int imageSide(float v, size_t frame, const char* which) {
    if (!(v >= 1.0f && v <= kMaxImageSide) || std::floor(v) != v) {
        throw FrontEndError("frame " + std::to_string(frame) + ": " + which + " must be a whole number of pixels in [1, 65536]");
    }
    return static_cast<int>(v);
}

std::vector<splat::sfm::Intrinsics> intrinsicsOf(const std::vector<float>& a, size_t frames) {
    if (a.size() != frames * kIntrinsicsPerFrame) {
        throw FrontEndError("intrinsics has " + std::to_string(a.size()) + " floats, expected 6 per frame = " +
                            std::to_string(frames * kIntrinsicsPerFrame));
    }
    std::vector<splat::sfm::Intrinsics> out(frames);
    for (size_t k = 0; k < frames; ++k) {
        const float* v = &a[k * kIntrinsicsPerFrame];
        out[k] = {v[0], v[1], v[2], v[3], imageSide(v[4], k, "width"), imageSide(v[5], k, "height")};
    }
    return out;
}

std::vector<splat::sfm::PairInput> pairsOf(const std::vector<int>& idx, const std::vector<float>& rot, size_t frames) {
    if (idx.size() % 2 != 0) throw FrontEndError("pairs has " + std::to_string(idx.size()) + " ints, expected i,j per pair");
    const size_t count = idx.size() / 2;
    if (rot.size() != count * kRotationPerPair) {
        throw FrontEndError("pairRotations has " + std::to_string(rot.size()) + " floats, expected 4 per pair = " +
                            std::to_string(count * kRotationPerPair));
    }
    std::vector<splat::sfm::PairInput> out(count);
    for (size_t p = 0; p < count; ++p) {
        const int i = idx[2 * p];
        const int j = idx[2 * p + 1];
        if (i < 0 || j < 0 || static_cast<size_t>(i) >= frames || static_cast<size_t>(j) >= frames || i == j) {
            throw FrontEndError("pair " + std::to_string(p) + ": (" + std::to_string(i) + ", " + std::to_string(j) +
                                ") is not two different frames of " + std::to_string(frames));
        }
        const float* q = &rot[p * kRotationPerPair];
        int nans = 0;
        for (int c = 0; c < kRotationPerPair; ++c) nans += std::isnan(q[c]) ? 1 : 0;
        splat::sfm::PairInput& in = out[p];
        in.i = i;
        in.j = j;
        in.hasRotation = nans == 0;
        in.rotation = {q[0], q[1], q[2], q[3]};
        if (nans == kRotationPerPair) continue;
        const double norm = std::sqrt(double(q[0]) * q[0] + double(q[1]) * q[1] + double(q[2]) * q[2] + double(q[3]) * q[3]);
        if (nans != 0 || !std::isfinite(norm) || std::fabs(norm - 1.0) > kUnitTolerance) {
            throw FrontEndError("pair " + std::to_string(p) + ": rotation must be a unit quaternion (w, x, y, z) or four NaN");
        }
    }
    return out;
}

// Runs body; any C++ exception becomes a NativeSfmException with its reason.
template <typename T, typename F>
T guarded(JNIEnv* env, T onError, F&& body) {
    try {
        return body();
    } catch (const FrontEndError& e) {
        throwSfm(env, e.what());
    } catch (const cv::Exception& e) {
        throwSfm(env, std::string("OpenCV: ") + e.what());
    } catch (const std::bad_alloc&) {
        throwSfm(env, "out of native memory");
    } catch (const std::exception& e) {
        throwSfm(env, e.what());
    } catch (...) {
        throwSfm(env, "unknown native error");
    }
    return onError;
}

// Runs use(result) under the live-set lock, so release cannot free it meanwhile.
template <typename F>
auto withHandle(jlong handle, F&& use) {
    const auto* result = reinterpret_cast<const FrontEndResult*>(static_cast<intptr_t>(handle));
    std::lock_guard<std::mutex> lock(gLiveMutex);
    if (result == nullptr || gLive.count(result) == 0) throw FrontEndError("unknown or released handle");
    return use(*result);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_huntercoles_splatman_splat_sfm_nativebridge_NativeSfm_frontEnd(
    JNIEnv* env, jclass, jobjectArray paths, jfloatArray intrinsics, jintArray pairs, jfloatArray pairRotations,
    jint kind, jint maxFeatures, jint longSide) {
    return guarded<jlong>(env, 0, [&]() -> jlong {
        if (paths == nullptr || intrinsics == nullptr || pairs == nullptr || pairRotations == nullptr) {
            throw FrontEndError("null array argument");
        }
        const std::vector<std::string> pathList = strings(env, paths);
        const size_t frames = pathList.size();
        if (frames == 0) throw FrontEndError("no frames");
        const std::vector<splat::sfm::Intrinsics> k = intrinsicsOf(floats(env, intrinsics), frames);
        const std::vector<splat::sfm::PairInput> pairList = pairsOf(ints(env, pairs), floats(env, pairRotations), frames);
        const splat::sfm::Params params{static_cast<splat::sfm::FeatureKind>(kind), maxFeatures, longSide};
        auto result = std::make_unique<FrontEndResult>(splat::sfm::runFrontEnd(pathList, k, pairList, params));
        std::lock_guard<std::mutex> lock(gLiveMutex);
        gLive.insert(result.get());
        return static_cast<jlong>(reinterpret_cast<intptr_t>(result.release()));
    });
}

JNIEXPORT jintArray JNICALL Java_com_huntercoles_splatman_splat_sfm_nativebridge_NativeSfm_matchStats(
    JNIEnv* env, jclass, jlong handle) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        const std::vector<jint> counts = withHandle(handle, [](const FrontEndResult& r) {
            std::vector<jint> out;
            out.reserve(r.pairs.size());
            for (const auto& p : r.pairs) out.push_back(static_cast<jint>(p.inliers.size()));
            return out;
        });
        jintArray out = env->NewIntArray(static_cast<jsize>(counts.size()));
        if (out == nullptr) return nullptr;  // OutOfMemoryError is pending
        if (!counts.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(counts.size()), counts.data());
        return out;
    });
}

JNIEXPORT jlongArray JNICALL Java_com_huntercoles_splatman_splat_sfm_nativebridge_NativeSfm_stageMs(
    JNIEnv* env, jclass, jlong handle) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        const auto ms = withHandle(handle, [](const FrontEndResult& r) { return r.stageMs; });
        jlongArray out = env->NewLongArray(static_cast<jsize>(ms.size()));
        if (out == nullptr) return nullptr;
        jlong values[splat::sfm::STAGE_COUNT];
        for (size_t s = 0; s < ms.size(); ++s) values[s] = static_cast<jlong>(ms[s]);
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(ms.size()), values);
        return out;
    });
}

JNIEXPORT void JNICALL Java_com_huntercoles_splatman_splat_sfm_nativebridge_NativeSfm_release(
    JNIEnv* env, jclass, jlong handle) {
    guarded<int>(env, 0, [&]() -> int {
        auto* result = reinterpret_cast<FrontEndResult*>(static_cast<intptr_t>(handle));
        {
            std::lock_guard<std::mutex> lock(gLiveMutex);
            if (result == nullptr || gLive.erase(result) == 0) throw FrontEndError("unknown or released handle");
        }
        delete result;
        return 0;
    });
}

}  // extern "C"
