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
        // E21 — public setter for the lookAt target. Used by route-mode
        // missions to follow the ship as it advances along the corridor.
        void setTarget(float x, float y, float z);

        math::Mat4 viewMatrix()     const;
        math::Mat4 projMatrix()     const;
        math::Mat4 projMatrix(float zFar) const;
        math::Mat4 viewProjection() const;
        math::Mat4 viewProjection(float zFar) const;
        math::Vec3 eyePosition() const;
        math::Vec3 forwardDirection() const;
        float farClip() const { return m_zFar; }
        // E5.2 — non-uniform billboard scale (scaleH=horizontal, scaleV=vertical).
        // See Camera.cpp for the col-1/col-2 swap fix and convention notes.
        math::Mat4 billboardMatrix(const math::Vec3& center, float scaleH, float scaleV) const;
        // Legacy uniform-scale variant; forwards to the two-scale form.
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
        // E16 — wider FOV for dramatic perspective (asteroid attack feel:
        // distant objects visibly smaller, near ones larger; nebulae
        // backdrop reads as space, not patches). Was 28° (telephoto-ish);
        // 55° is "natural" wide-angle for game POV. Combined with reduced
        // m_radius in Camera::reset() so the ship stays at similar screen
        // coverage but perspective foreshortening becomes pronounced.
        float      m_fovY     =  55.0f * 3.14159265f / 180.0f;
        float      m_zNear    =  0.5f;
        float      m_zFar     = 300.0f;
        mutable int m_normCounter = 0;

        bool screenPointOnPlane(float screenX, float screenY,
                                float viewportWidth, float viewportHeight,
                                float planeZ,
                                math::Vec3& outPoint) const;
    };

} // namespace station
