#pragma once

#include <cmath>
#include "Mat4.h"

namespace station::math {

    struct Quat {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float w = 1.0f; // identity

        static Quat identity() {
            return {0.0f, 0.0f, 0.0f, 1.0f};
        }

        // Rotation by `angle` radians around normalized axis
        static Quat fromAxisAngle(float ax, float ay, float az, float angle) {
            const float half = angle * 0.5f;
            const float s    = std::sin(half);
            return { ax * s, ay * s, az * s, std::cos(half) };
        }

        // Hamilton product: this * other
        Quat operator*(const Quat& o) const {
            return {
                    w*o.x + x*o.w + y*o.z - z*o.y,
                    w*o.y - x*o.z + y*o.w + z*o.x,
                    w*o.z + x*o.y - y*o.x + z*o.w,
                    w*o.w - x*o.x - y*o.y - z*o.z
            };
        }

        // Normalize to unit quaternion (fixes float drift)
        Quat normalized() const {
            const float len = std::sqrt(x*x + y*y + z*z + w*w);
            if (len < 1e-8f) return identity();
            return { x/len, y/len, z/len, w/len };
        }

        // Rotate a vector by this quaternion: q * v * q^-1
        Vec3 rotate(const Vec3& v) const {
            // Optimized formula (no conjugate needed for unit quat)
            const float tx = 2.0f * (y*v.z - z*v.y);
            const float ty = 2.0f * (z*v.x - x*v.z);
            const float tz = 2.0f * (x*v.y - y*v.x);
            return {
                    v.x + w*tx + y*tz - z*ty,
                    v.y + w*ty + z*tx - x*tz,
                    v.z + w*tz + x*ty - y*tx
            };
        }

        // Convert to column-major 4x4 rotation matrix
        Mat4 toMat4() const {
            Mat4 m{};
            const float xx = x*x, yy = y*y, zz = z*z;
            const float xy = x*y, xz = x*z, yz = y*z;
            const float wx = w*x, wy = w*y, wz = w*z;

            m.m[ 0] = 1 - 2*(yy+zz);  m.m[ 4] = 2*(xy-wz);     m.m[ 8] = 2*(xz+wy);
            m.m[ 1] = 2*(xy+wz);       m.m[ 5] = 1 - 2*(xx+zz); m.m[ 9] = 2*(yz-wx);
            m.m[ 2] = 2*(xz-wy);       m.m[ 6] = 2*(yz+wx);     m.m[10] = 1 - 2*(xx+yy);
            m.m[15] = 1.0f;
            return m;
        }
    };

} // namespace station::math