#pragma once

#include "math/Mat4.h"
#include "math/Quat.h"

#include <vector>

namespace station {

    class Camera {
    public:
        Camera();

        void reset();
        void orbit(float deltaYaw, float deltaPitch);
        void roll(float deltaAngle);       // rotate around camera's forward axis (screen twist)
        void pan(float dx, float dy);
        void zoom(float factor);
        void zoomAt(float factor, float screenX, float screenY,
                    float viewportWidth, float viewportHeight,
                    float planeZ = 0.0f);
        void setAspect(float aspect);

        math::Mat4 viewMatrix()     const;
        math::Mat4 projMatrix()     const;
        math::Mat4 projMatrix(float zFar) const;
        math::Mat4 viewProjection() const;
        math::Mat4 viewProjection(float zFar) const;
        math::Vec3 eyePosition() const;
        math::Vec3 forwardDirection() const;
        float farClip() const { return m_zFar; }
        math::Mat4 billboardMatrix(const math::Vec3& center, float scale) const;
        math::Mat4 frameMatrixForBounds(const float modelMatrix[16],
                                        const math::Vec3& localCenter,
                                        const math::Vec3& halfExtents,
                                        float padding,
                                        float viewportWidth,
                                        float viewportHeight) const;
        math::Mat4 frameMatrixForPoints(const float modelMatrix[16],
                                        const std::vector<math::Vec3>& localPoints,
                                        const math::Vec3& fallbackCenter,
                                        const math::Vec3& fallbackHalfExtents,
                                        float padding,
                                        float viewportWidth,
                                        float viewportHeight) const;
        math::Mat4 frameMatrixForScreenBounds(float left,
                                              float top,
                                              float right,
                                              float bottom,
                                              float viewportWidth,
                                              float viewportHeight,
                                              float depth = 5.0f) const;

    private:
        math::Vec3 m_target   = {8.0f, 28.0f, 0.0f};
        math::Quat m_rotation;
        float      m_radius   = 52.0f;
        float      m_aspect   =  1.0f;
        float      m_fovY     =  28.0f * 3.14159265f / 180.0f;
        float      m_zNear    =  0.5f;
        float      m_zFar     = 300.0f;
        mutable int m_normCounter = 0;

        bool screenPointOnPlane(float screenX, float screenY,
                                float viewportWidth, float viewportHeight,
                                float planeZ,
                                math::Vec3& outPoint) const;
    };

} // namespace station
