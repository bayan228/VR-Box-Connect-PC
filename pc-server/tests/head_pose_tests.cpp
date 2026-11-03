#include "tracking/head_pose.hpp"

#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <numbers>
#include <stdexcept>

namespace {
using Q = std::array<double, 4>;
using V = std::array<double, 3>;
int checks = 0;
void Require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
Q Product(const Q& a, const Q& b) {
    Q q{};
    for (int i = 0; i < 3; ++i) {
        const int j = (i + 1) % 3, k = (i + 2) % 3;
        q[i] = a[3] * b[i] + b[3] * a[i] + a[j] * b[k] - a[k] * b[j];
    }
    q[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
    return q;
}
Q Axis(int axis, double degrees) {
    Q result{};
    result[axis] = std::sin(degrees * std::numbers::pi / 360.0);
    result[3] = std::cos(degrees * std::numbers::pi / 360.0);
    return result;
}
bool SameRotation(const Q& a, const Q& b) {
    double dot = 0;
    for (size_t i = 0; i < 4; ++i) dot += a[i] * b[i];
    return std::abs(std::abs(dot) - 1.0) < 1e-6;
}
bool Feed(vrbox::tracking::HeadPose& tracker, const Q& q, const std::array<float, 3>& gyro = {}) {
    const float packet[4] = {float(q[0]), float(q[1]), float(q[2]), float(q[3])};
    return tracker.Update(packet, gyro.data());
}
V RotateVector(const Q& q, const V& v) {
    const auto [x, y, z, w] = q;
    return {(1 - 2 * (y * y + z * z)) * v[0] + 2 * (x * y - z * w) * v[1] + 2 * (x * z + y * w) * v[2],
        2 * (x * y + z * w) * v[0] + (1 - 2 * (x * x + z * z)) * v[1] + 2 * (y * z - x * w) * v[2],
        2 * (x * z - y * w) * v[0] + 2 * (y * z + x * w) * v[1] + (1 - 2 * (x * x + y * y)) * v[2]};
}
}

int main() {
    try {
        const Q identity{0, 0, 0, 1};
        // Physical Android landscape mounts, not the inverse of the production formula.
        // ROTATION_90: device X points up, device Y points left, device Z faces the viewer.
        // ROTATION_270: device X points down, device Y points right, device Z faces the viewer.
        // Android world: east/north/up; the neutral head looks north with its screen facing south.
        for (uint32_t display : {90u, 270u}) {
            const Q neutral = display == 90 ? Q{0.5, -0.5, 0.5, 0.5} : Q{0.5, 0.5, -0.5, 0.5};
            const double side = display == 90 ? 1.0 : -1.0;
            struct Turn { int head_axis, device_axis; double degrees, device_sign; const char* error; };
            const Turn turns[] = {
                {1, 0, -30, side, "Physical right turn did not look right"},
                {1, 0, 30, side, "Physical left turn did not look left"},
                {0, 1, 30, -side, "Physical upward turn did not look up"},
                {0, 1, -30, -side, "Physical downward turn did not look down"},
                {2, 2, -30, 1, "Right roll was inverted"},
                {2, 2, 30, 1, "Left roll was inverted"}
            };
            for (const auto& turn : turns) {
                vrbox::tracking::HeadPose physical;
                physical.SetDisplayRotation(display);
                Require(Feed(physical, neutral), "Physical neutral pose rejected");
                std::array<float, 3> gyro{};
                gyro[turn.device_axis] = float(std::copysign(1.25, turn.degrees * turn.device_sign));
                const auto sensor = Product(neutral, Axis(turn.device_axis, turn.degrees * turn.device_sign));
                Require(Feed(physical, sensor, gyro), "Physical turn sample rejected");
                Require(SameRotation(physical.Orientation(), Axis(turn.head_axis, turn.degrees)), turn.error);
                for (int axis = 0; axis < 3; ++axis) {
                    const double expected = axis == turn.head_axis ? std::copysign(1.25, turn.degrees) : 0;
                    Require(std::abs(physical.AngularVelocity()[axis] - expected) < 1e-6, "Gyro predicts against the physical turn");
                }
                if (turn.head_axis != 2) {
                    const auto forward = RotateVector(physical.Orientation(), {0, 0, -1});
                    const int direction = turn.head_axis == 1 ? 0 : 1;
                    const double sign = turn.head_axis == 1 ? -turn.degrees : turn.degrees;
                    Require(forward[direction] * sign > 0, "OpenVR pose is a view transform instead of a head transform");
                }
                Require(physical.Recenter(), "Physical recenter failed");
                Require(Feed(physical, sensor, gyro) && SameRotation(physical.Orientation(), identity), "Physical recenter did not persist");
            }
            vrbox::tracking::HeadPose combined;
            combined.SetDisplayRotation(display);
            Require(Feed(combined, neutral), "Compound neutral pose rejected");
            const Q target = Product(Axis(1, -40), Axis(0, 25));
            const Q sensor = Product(neutral, Product(Axis(0, -40 * side), Axis(1, -25 * side)));
            const std::array<float, 3> gyro{0, float(-side), 0}; // Positive pitch in the current head's frame.
            Require(Feed(combined, sensor, gyro) && SameRotation(combined.Orientation(), target), "Compound yaw/pitch order changed");
            const auto expected_velocity = RotateVector(target, {1, 0, 0});
            for (int axis = 0; axis < 3; ++axis) {
                Require(std::abs(combined.AngularVelocity()[axis] - expected_velocity[axis]) < 1e-6,
                    "Compound-turn gyro is not in the recentered tracking frame");
            }
        }
        for (uint32_t display : {0u, 90u, 180u, 270u}) {
            const Q deviceInHead = Axis(2, double(display));
            const Q world = Product(Axis(0, 73), Axis(2, -31));
            for (int axis = 0; axis < 3; ++axis) for (double angle : {-120., -45., 0., 45., 120., 179.}) {
                vrbox::tracking::HeadPose tracker;
                tracker.SetDisplayRotation(display);
                Require(!tracker.Recenter(), "Must not recenter without a real sample");
                Require(Feed(tracker, Product(world, deviceInHead)), "Neutral sample rejected");
                Require(SameRotation(tracker.Orientation(), identity), "Neutral head is not centered");
                const Q target = Axis(axis, angle);
                const Q sensor = Product(Product(world, target), deviceInHead);
                Require(Feed(tracker, sensor), "Turn sample rejected");
                Require(SameRotation(tracker.Orientation(), target), "Display rotation maps a head axis incorrectly");
                Require(tracker.Recenter(), "Recenter rejected after sample");
                Require(SameRotation(tracker.Orientation(), identity), "Recenter did not reset orientation");
                Require(Feed(tracker, sensor), "Repeated sample rejected");
                Require(SameRotation(tracker.Orientation(), identity), "Reference did not survive next sample");
                Require(Feed(tracker, {-sensor[0], -sensor[1], -sensor[2], -sensor[3]}), "Equivalent quaternion rejected");
                Require(SameRotation(tracker.Orientation(), identity), "Quaternion sign changed orientation");
            }
        }
        vrbox::tracking::HeadPose tracker;
        tracker.SetDisplayRotation(90);
        Require(Feed(tracker, Axis(2, 90), {-2, 0, 0}), "Gyro test sample rejected");
        Require(std::abs(tracker.AngularVelocity()[1] + 2) < 1e-6, "ROTATION_90 device -X gyro must produce head -Y yaw");
        Require(Feed(tracker, Axis(2, 90), {1000, 0, 0}), "Finite gyro should be bounded");
        Require(std::abs(tracker.AngularVelocity()[1]) <= 20, "Unbounded prediction rate");
        const auto before = tracker.Orientation();
        Require(!Feed(tracker, {0, 0, 0, 0}), "Zero quaternion accepted");
        Require(!Feed(tracker, {std::numeric_limits<double>::quiet_NaN(), 0, 0, 1}), "NaN accepted");
        Require(!Feed(tracker, identity, {std::numeric_limits<float>::infinity(), 0, 0}), "Infinite gyro accepted");
        Require(SameRotation(before, tracker.Orientation()), "Invalid sample modified the pose");
        bool rejected = false;
        try { tracker.SetDisplayRotation(45); } catch (const std::invalid_argument&) { rejected = true; }
        Require(rejected, "Invalid display rotation accepted");
        tracker.SetDisplayRotation(270);
        Require(!tracker.HasSample() && !tracker.Recenter(), "Changing mount kept stale reference");
        std::cout << checks << " head pose checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
