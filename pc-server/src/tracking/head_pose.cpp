#include "head_pose.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>
#include <stdexcept>

namespace vrbox::tracking {
namespace {
using Q = std::array<double, 4>;
using V = std::array<double, 3>;

Q Multiply(const Q& a, const Q& b) {
    return {a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
}

Q Conjugate(const Q& q) { return {-q[0], -q[1], -q[2], q[3]}; }

V Rotate(const Q& q, const V& v) {
    const auto result = Multiply(Multiply(q, {v[0], v[1], v[2], 0}), Conjugate(q));
    return {result[0], result[1], result[2]};
}
} // namespace

void HeadPose::SetDisplayRotation(uint32_t degrees) {
    if (degrees > 270 || degrees % 90) throw std::invalid_argument("Display rotation must be 0, 90, 180 or 270 degrees");
    const double half = degrees * std::numbers::pi / 360.0;
    // Android ROTATION_90: display X = -device Y, display Y = device X.
    // The sensor quaternion is device-to-world, so compensate the display rotation on the right.
    mount_ = {0, 0, -std::sin(half), std::cos(half)};
    have_sample_ = false;
    orientation_ = {0, 0, 0, 1};
    angular_velocity_ = {};
}

bool HeadPose::Update(const float quaternion[4], const float gyro[3]) {
    if (!quaternion || !gyro) return false;
    Q q{};
    double norm = 0;
    for (size_t i = 0; i < q.size(); ++i) {
        if (!std::isfinite(quaternion[i])) return false;
        q[i] = quaternion[i];
        norm += q[i] * q[i];
    }
    for (size_t i = 0; i < 3; ++i) if (!std::isfinite(gyro[i])) return false;
    if (norm < 1e-12) return false;
    const double length = std::sqrt(norm);
    for (double& value : q) value /= length;
    current_ = Multiply(q, mount_);
    gyro_head_ = Rotate(Conjugate(mount_), {gyro[0], gyro[1], gyro[2]});
    if (!have_sample_) {
        origin_inverse_ = Conjugate(current_);
        have_sample_ = true;
    }
    auto next = Multiply(origin_inverse_, current_);
    double dot = 0;
    for (size_t i = 0; i < 4; ++i) dot += next[i] * orientation_[i];
    if (dot < 0) for (double& value : next) value = -value;
    orientation_ = next;
    angular_velocity_ = Rotate(orientation_, gyro_head_);
    // Do not let a corrupted but finite gyro sample produce extreme compositor prediction.
    for (double& value : angular_velocity_) value = std::clamp(value, -20.0, 20.0);
    return true;
}

bool HeadPose::Recenter() {
    if (!have_sample_) return false;
    origin_inverse_ = Conjugate(current_);
    orientation_ = {0, 0, 0, 1};
    angular_velocity_ = gyro_head_;
    for (double& value : angular_velocity_) value = std::clamp(value, -20.0, 20.0);
    return true;
}

} // namespace vrbox::tracking
