#pragma once

#include <cmath>
#include <cstddef>

namespace station::math {

    struct Vec3 {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
    };

    inline Vec3 operator-(const Vec3& a, const Vec3& b) {
        return {a.x - b.x, a.y - b.y, a.z - b.z};
    }

    inline float dot(const Vec3& a, const Vec3& b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    inline Vec3 cross(const Vec3& a, const Vec3& b) {
        return {
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x
        };
    }

    inline float length(const Vec3& v) {
        return std::sqrt(dot(v, v));
    }

    inline Vec3 normalize(const Vec3& v) {
        const float len = length(v);
        if (len <= 0.000001f) {
            return {0.0f, 0.0f, 0.0f};
        }

        return {v.x / len, v.y / len, v.z / len};
    }

    struct Mat4 {
        // Column-major, compatible with GLSL mat4
        float m[16]{};

        static Mat4 identity() {
            Mat4 result{};
            result.m[0] = 1.0f;
            result.m[5] = 1.0f;
            result.m[10] = 1.0f;
            result.m[15] = 1.0f;
            return result;
        }

        static Mat4 rotationX(float radians) {
            Mat4 result = identity();
            const float c = std::cos(radians);
            const float s = std::sin(radians);

            result.m[5] = c;
            result.m[6] = s;
            result.m[9] = -s;
            result.m[10] = c;

            return result;
        }

        static Mat4 rotationY(float radians) {
            Mat4 result = identity();
            const float c = std::cos(radians);
            const float s = std::sin(radians);

            result.m[0] = c;
            result.m[2] = -s;
            result.m[8] = s;
            result.m[10] = c;

            return result;
        }

        static Mat4 rotationZ(float radians) {
            Mat4 result = identity();
            const float c = std::cos(radians);
            const float s = std::sin(radians);

            result.m[0] = c;
            result.m[1] = s;
            result.m[4] = -s;
            result.m[5] = c;

            return result;
        }

        static Mat4 translation(float x, float y, float z) {
            Mat4 result = identity();
            result.m[12] = x;
            result.m[13] = y;
            result.m[14] = z;
            return result;
        }

        // Orthographic projection for Vulkan (Y-flipped, depth 0-1).
        // halfH: half world-space height visible on screen.
        static Mat4 orthoVulkan(float halfW, float halfH, float zNear, float zFar) {
            Mat4 result{};
            result.m[0]  =  1.0f / halfW;
            result.m[5]  = -1.0f / halfH;   // Y-flip for Vulkan
            result.m[10] = -1.0f / (zFar - zNear);
            result.m[14] = -zNear / (zFar - zNear);
            result.m[15] =  1.0f;
            return result;
        }

        static Mat4 perspectiveVulkan(float fovyRadians, float aspect, float zNear, float zFar) {
            Mat4 result{};

            const float tanHalfFovy = std::tan(fovyRadians * 0.5f);

            result.m[0] = 1.0f / (aspect * tanHalfFovy);
            result.m[5] = -1.0f / tanHalfFovy; // flip Y for Vulkan
            result.m[10] = zFar / (zNear - zFar);
            result.m[11] = -1.0f;
            result.m[14] = (zFar * zNear) / (zNear - zFar);

            return result;
        }

        static Mat4 lookAt(const Vec3& eye, const Vec3& center, const Vec3& up) {
            const Vec3 f = normalize(center - eye);
            const Vec3 s = normalize(cross(f, up));
            const Vec3 u = cross(s, f);

            Mat4 result = identity();

            result.m[0] = s.x;
            result.m[1] = u.x;
            result.m[2] = -f.x;

            result.m[4] = s.y;
            result.m[5] = u.y;
            result.m[6] = -f.y;

            result.m[8] = s.z;
            result.m[9] = u.z;
            result.m[10] = -f.z;

            result.m[12] = -dot(s, eye);
            result.m[13] = -dot(u, eye);
            result.m[14] = dot(f, eye);

            return result;
        }
    };

    inline Mat4 multiply(const Mat4& a, const Mat4& b) {
        Mat4 result{};

        for (int col = 0; col < 4; ++col) {
            for (int row = 0; row < 4; ++row) {
                float sum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    sum += a.m[k * 4 + row] * b.m[col * 4 + k];
                }
                result.m[col * 4 + row] = sum;
            }
        }

        return result;
    }

} // namespace station::math