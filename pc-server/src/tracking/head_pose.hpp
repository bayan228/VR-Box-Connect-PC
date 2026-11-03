#pragma once

#include <array>
#include <cstdint>

namespace vrbox::tracking {

// Seated 3DoF: the reference is the user's forward, level head orientation at recenter.
// Quaternions are x,y,z,w; angular velocity is expressed in the recentered tracking frame.
class HeadPose {
public:
    void SetDisplayRotation(uint32_t degrees);
    bool Update(const float quaternion[4], const float gyro[3]);
    bool Recenter();
    bool HasSample() const { return have_sample_; }
    const std::array<double, 4>& Orientation() const { return orientation_; }
    const std::array<double, 3>& AngularVelocity() const { return angular_velocity_; }

private:
    std::array<double, 4> mount_{0, 0, 0, 1};
    std::array<double, 4> current_{0, 0, 0, 1};
    std::array<double, 4> origin_inverse_{0, 0, 0, 1};
    std::array<double, 4> orientation_{0, 0, 0, 1};
    std::array<double, 3> gyro_head_{};
    std::array<double, 3> angular_velocity_{};
    bool have_sample_ = false;
};

} // namespace vrbox::tracking
