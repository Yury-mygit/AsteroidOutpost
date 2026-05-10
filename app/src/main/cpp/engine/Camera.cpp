#include "Camera.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace station {

    Camera::Camera() { reset(); }

    void Camera::reset() {
        // DRAFT — Asteroid Outpost: side-view, X=right, Z=up, Y=depth.
        // pitch=π/2 rotates the camera so it looks horizontally instead of top-down.
        using namespace math;
        // Radical 3D — target between original (z=4, ship at -0.91 clipped)
        // and the over-lifted z=1 (ship at -0.31 mid-screen). z=2.5 puts
        // the ship's lower visible edge (stern bottom corner) at NDC.y
        // ≈ -0.73 — about halfway between the deck plane and the bottom
        // edge of the screen. Asteroid trajectory still spans NDC.y from
        // +0.34 (upper) to -0.60 (mid-low) → ~94% of screen vertical with
        // ~3× growth in apparent size.
        m_target   = {0.0f, 0.0f, 2.5f};
        // E16 — closer camera (was 22) compensates for the wider 55° FOV
        // (was 28°) so the ship occupies similar screen coverage, but the
        // depth ratio between near/far objects ~doubles → asteroids visibly
        // grow from spawn to impact.
        m_radius   = 11.0f;
        m_normCounter = 0;
        Quat pitch = Quat::fromAxisAngle(1, 0, 0, 1.5707963f); // 90°
        m_rotation = pitch.normalized();
    }

    void Camera::orbit(float deltaYaw, float deltaPitch) {
        using namespace math;
        Vec3 localUp    = m_rotation.rotate({0, 1, 0});
        Vec3 localRight = m_rotation.rotate({1, 0, 0});
        Quat qYaw   = Quat::fromAxisAngle(localUp.x,    localUp.y,    localUp.z,    deltaYaw);
        Quat qPitch = Quat::fromAxisAngle(localRight.x, localRight.y, localRight.z, deltaPitch);
        m_rotation = (qPitch * qYaw * m_rotation).normalized();
        if (++m_normCounter >= 120) { m_rotation = m_rotation.normalized(); m_normCounter = 0; }
    }

    void Camera::roll(float deltaAngle) {
        using namespace math;
        // Rotate around camera's local forward axis (into the screen)
        // forward = -Z in camera space, rotated to world space
        Vec3 localForward = m_rotation.rotate({0, 0, -1});
        Quat qRoll = Quat::fromAxisAngle(
                localForward.x, localForward.y, localForward.z, deltaAngle);
        m_rotation = (qRoll * m_rotation).normalized();
    }

    void Camera::pan(float dx, float dy) {
        using namespace math;
        const float scale = m_radius * 0.001f;
        Vec3 right = m_rotation.rotate({1, 0, 0});
        Vec3 up    = m_rotation.rotate({0, 1, 0});
        m_target.x -= (right.x * dx - up.x * dy) * scale;
        m_target.y -= (right.y * dx - up.y * dy) * scale;
        m_target.z -= (right.z * dx - up.z * dy) * scale;
    }

    void Camera::zoom(float factor) {
        using namespace math;
        const Vec3 forward = m_rotation.rotate({0.0f, 0.0f, -1.0f});
        const float step = m_radius * (1.0f - factor) * 4.0f;
        m_target.x += forward.x * step;
        m_target.y += forward.y * step;
        m_target.z += forward.z * step;
    }

    void Camera::zoomAt(float factor, float screenX, float screenY,
                        float viewportWidth, float viewportHeight,
                        float planeZ) {
        using namespace math;
        Vec3 before{};
        if (!screenPointOnPlane(screenX, screenY, viewportWidth, viewportHeight, planeZ, before)) {
            zoom(factor);
            return;
        }

        zoom(factor);

        Vec3 after{};
        if (!screenPointOnPlane(screenX, screenY, viewportWidth, viewportHeight, planeZ, after)) {
            return;
        }

        m_target.x += before.x - after.x;
        m_target.y += before.y - after.y;
        m_target.z += before.z - after.z;
    }

    void Camera::setAspect(float aspect) { m_aspect = aspect; }

    math::Mat4 Camera::viewMatrix() const {
        using namespace math;
        Vec3 eye    = eyePosition();
        Vec3 up     = m_rotation.rotate({0.0f, 1.0f, 0.0f});
        return Mat4::lookAt(eye, m_target, up);
    }

    math::Mat4 Camera::projMatrix() const {
        return math::Mat4::perspectiveVulkan(m_fovY, m_aspect, m_zNear, m_zFar);
    }

    math::Mat4 Camera::projMatrix(float zFar) const {
        return math::Mat4::perspectiveVulkan(m_fovY, m_aspect, m_zNear,
                                             std::max(zFar, m_zNear + 1.0f));
    }

    math::Mat4 Camera::viewProjection() const {
        return math::multiply(projMatrix(), viewMatrix());
    }

    math::Mat4 Camera::viewProjection(float zFar) const {
        return math::multiply(projMatrix(zFar), viewMatrix());
    }

    math::Vec3 Camera::eyePosition() const {
        using namespace math;
        const Vec3 offset = m_rotation.rotate({0.0f, 0.0f, m_radius});
        return {m_target.x + offset.x, m_target.y + offset.y, m_target.z + offset.z};
    }

    math::Vec3 Camera::forwardDirection() const {
        return m_rotation.rotate({0.0f, 0.0f, -1.0f});
    }

    bool Camera::screenPointOnPlane(float screenX, float screenY,
                                    float viewportWidth, float viewportHeight,
                                    float planeZ,
                                    math::Vec3& outPoint) const {
        using namespace math;
        if (viewportWidth <= 1.0f || viewportHeight <= 1.0f) return false;

        const float ndcX = (screenX / viewportWidth) * 2.0f - 1.0f;
        const float ndcY = (screenY / viewportHeight) * 2.0f - 1.0f;
        const float tanHalfFov = std::tan(m_fovY * 0.5f);

        const Vec3 localRay = normalize({
                ndcX * m_aspect * tanHalfFov,
                -ndcY * tanHalfFov,
                -1.0f
        });
        const Vec3 ray = normalize(m_rotation.rotate(localRay));
        if (std::abs(ray.z) <= 1e-5f) return false;

        const Vec3 eye = eyePosition();
        const float t = (planeZ - eye.z) / ray.z;
        if (t <= 0.0f) return false;

        outPoint = {
                eye.x + ray.x * t,
                eye.y + ray.y * t,
                eye.z + ray.z * t
        };
        return true;
    }

    // E5.2 — Outpost-camera billboard convention (pitch=π/2 around X, target
    // at +Y, up = world +Z). The project's quad meshes (quad.gltf and the
    // procedural soft-disks) live in the X-Z plane (model.y = 0). The
    // pre-E5.2 layout (model.x → right, model.y → up, model.z → back) made
    // model.z map to camera depth, so X-Z-plane quads ended up lying flat
    // in a horizontal world plane (Z=cz constant) — they appeared on screen
    // as horizontal strips with perspective foreshortening, not as
    // screen-aligned billboards. The radial soft-fade (E2.1) and heat-ramp
    // (E4) computed circular patterns in `vLocalXZ` model-space; with the
    // pre-fix matrix that circular pattern got squashed by projection and
    // read only because flashes were small. Fix: swap col 1 ↔ col 2 so
    // model.y → camera-back (depth, no extent for our quads) and model.z →
    // camera-up (screen-vertical). Now X-Z-plane meshes are truly
    // screen-aligned and `vLocalXZ` traces a circle on screen as intended.
    //
    // (scaleH, scaleV) lets callers stretch the quad along screen-horizontal
    // (col 0) and screen-vertical (col 2) independently — for streak bullets,
    // shockwave-style flat explosions, etc. The depth column (col 1) keeps
    // scale=1 since our quads have model.y=0 anyway, so it never contributes.
    math::Mat4 Camera::billboardMatrix(const math::Vec3& center,
                                       float scaleH, float scaleV) const {
        using namespace math;
        const Vec3 right = m_rotation.rotate({1.0f, 0.0f, 0.0f});
        const Vec3 up    = m_rotation.rotate({0.0f, 1.0f, 0.0f});
        const Vec3 back  = m_rotation.rotate({0.0f, 0.0f, 1.0f});

        Mat4 result{};
        // col 0 — model.x axis → camera-right (screen horizontal)
        result.m[0] = right.x * scaleH;
        result.m[1] = right.y * scaleH;
        result.m[2] = right.z * scaleH;

        // col 1 — model.y axis → camera-back (depth). Not scaled because
        // project meshes have model.y=0; scale=1 keeps non-zero meshes
        // depth-correct without inflation.
        result.m[4] = back.x;
        result.m[5] = back.y;
        result.m[6] = back.z;

        // col 2 — model.z axis → camera-up (screen vertical)
        result.m[8]  = up.x * scaleV;
        result.m[9]  = up.y * scaleV;
        result.m[10] = up.z * scaleV;

        result.m[12] = center.x;
        result.m[13] = center.y;
        result.m[14] = center.z;
        result.m[15] = 1.0f;
        return result;
    }

    // Legacy uniform-scale variant — keeps existing call sites working until
    // they're migrated to (scaleH, scaleV). Forwards to the two-scale form.
    math::Mat4 Camera::billboardMatrix(const math::Vec3& center, float scale) const {
        return billboardMatrix(center, scale, scale);
    }

    math::Mat4 Camera::frameMatrixForBounds(const float modelMatrix[16],
                                            const math::Vec3& localCenter,
                                            const math::Vec3& halfExtents,
                                            float padding,
                                            float viewportWidth,
                                            float viewportHeight) const {
        std::vector<math::Vec3> corners;
        corners.reserve(8);
        for (float sx : {-1.0f, 1.0f}) {
            for (float sy : {-1.0f, 1.0f}) {
                for (float sz : {-1.0f, 1.0f}) {
                    corners.push_back({
                            localCenter.x + sx * halfExtents.x,
                            localCenter.y + sy * halfExtents.y,
                            localCenter.z + sz * halfExtents.z
                    });
                }
            }
        }
        return frameMatrixForPoints(modelMatrix, corners, localCenter, halfExtents, padding,
                                    viewportWidth, viewportHeight);
    }

    math::Mat4 Camera::frameMatrixForPoints(const float modelMatrix[16],
                                            const std::vector<math::Vec3>& localPoints,
                                            const math::Vec3& fallbackCenter,
                                            const math::Vec3& fallbackHalfExtents,
                                            float padding,
                                            float viewportWidth,
                                            float viewportHeight) const {
        using namespace math;
        if (localPoints.empty()) {
            return frameMatrixForBounds(modelMatrix, fallbackCenter, fallbackHalfExtents, padding,
                                        viewportWidth, viewportHeight);
        }

        const Vec3 right = m_rotation.rotate({1.0f, 0.0f, 0.0f});
        const Vec3 up    = m_rotation.rotate({0.0f, 1.0f, 0.0f});
        const Vec3 back  = m_rotation.rotate({0.0f, 0.0f, 1.0f});
        const Vec3 forward{-back.x, -back.y, -back.z};
        const Vec3 eye{
                m_target.x + back.x * m_radius,
                m_target.y + back.y * m_radius,
                m_target.z + back.z * m_radius
        };

        float minX =  std::numeric_limits<float>::max();
        float maxX = -std::numeric_limits<float>::max();
        float minY =  std::numeric_limits<float>::max();
        float maxY = -std::numeric_limits<float>::max();
        float depthSum = 0.0f;
        int projectedCount = 0;
        const Mat4 vp = viewProjection();

        for (const Vec3& local : localPoints) {
            const Vec3 world{
                    modelMatrix[0] * local.x + modelMatrix[4] * local.y + modelMatrix[8]  * local.z + modelMatrix[12],
                    modelMatrix[1] * local.x + modelMatrix[5] * local.y + modelMatrix[9]  * local.z + modelMatrix[13],
                    modelMatrix[2] * local.x + modelMatrix[6] * local.y + modelMatrix[10] * local.z + modelMatrix[14]
            };
            const float clipX = vp.m[0] * world.x + vp.m[4] * world.y + vp.m[8]  * world.z + vp.m[12];
            const float clipY = vp.m[1] * world.x + vp.m[5] * world.y + vp.m[9]  * world.z + vp.m[13];
            const float clipW = vp.m[3] * world.x + vp.m[7] * world.y + vp.m[11] * world.z + vp.m[15];
            if (clipW <= 0.0001f) continue;

            const float ndcX = clipX / clipW;
            const float ndcY = clipY / clipW;
            const float screenX = (ndcX * 0.5f + 0.5f) * viewportWidth;
            const float screenY = (ndcY * 0.5f + 0.5f) * viewportHeight;
            minX = std::min(minX, screenX);
            maxX = std::max(maxX, screenX);
            minY = std::min(minY, screenY);
            maxY = std::max(maxY, screenY);
            depthSum += dot(world - eye, forward);
            ++projectedCount;
        }

        if (projectedCount == 0 || viewportWidth <= 1.0f || viewportHeight <= 1.0f) {
            return billboardMatrix({modelMatrix[12], modelMatrix[13], modelMatrix[14]}, 0.5f);
        }

        const float centerScreenX = (minX + maxX) * 0.5f;
        const float centerScreenY = (minY + maxY) * 0.5f;
        const float depth = std::max(depthSum / static_cast<float>(projectedCount), 0.1f);
        const float worldHeight = 2.0f * depth * std::tan(m_fovY * 0.5f);
        const float worldWidth = worldHeight * m_aspect;
        const float unitsPerPixelX = worldWidth / viewportWidth;
        const float unitsPerPixelY = worldHeight / viewportHeight;

        const float centerR = (centerScreenX - viewportWidth * 0.5f) * unitsPerPixelX;
        const float centerU = -(centerScreenY - viewportHeight * 0.5f) * unitsPerPixelY;

        const float frameScale = std::max(depth * std::tan(m_fovY * 0.5f) * padding, 0.05f);
        const float scaleR = frameScale;
        const float scaleU = frameScale;

        const Vec3 center{
                eye.x + forward.x * depth + right.x * centerR + up.x * centerU,
                eye.y + forward.y * depth + right.y * centerR + up.y * centerU,
                eye.z + forward.z * depth + right.z * centerR + up.z * centerU
        };

        Mat4 result{};
        result.m[0] = right.x * scaleR;
        result.m[1] = right.y * scaleR;
        result.m[2] = right.z * scaleR;

        result.m[4] = up.x * scaleU;
        result.m[5] = up.y * scaleU;
        result.m[6] = up.z * scaleU;

        result.m[8]  = back.x;
        result.m[9]  = back.y;
        result.m[10] = back.z;

        result.m[12] = center.x;
        result.m[13] = center.y;
        result.m[14] = center.z;
        result.m[15] = 1.0f;
        return result;
    }

    math::Mat4 Camera::frameMatrixForScreenBounds(float left,
                                                  float top,
                                                  float right,
                                                  float bottom,
                                                  float viewportWidth,
                                                  float viewportHeight,
                                                  float depth) const {
        using namespace math;
        const float safeDepth = std::max(depth, m_zNear + 0.1f);
        const Vec3 rightAxis = m_rotation.rotate({1.0f, 0.0f, 0.0f});
        const Vec3 upAxis    = m_rotation.rotate({0.0f, 1.0f, 0.0f});
        const Vec3 backAxis  = m_rotation.rotate({0.0f, 0.0f, 1.0f});
        const Vec3 forward{-backAxis.x, -backAxis.y, -backAxis.z};
        const Vec3 eye = eyePosition();

        const float W = std::max(viewportWidth, 1.0f);
        const float H = std::max(viewportHeight, 1.0f);
        const float tanHalfFov = std::tan(m_fovY * 0.5f);

        // Perspective-correct: unproject screen bounds to world space at object depth.
        auto unproject = [&](float px, float py) -> Vec3 {
            const float ndcX = (px / W) * 2.0f - 1.0f;
            const float ndcY = (py / H) * 2.0f - 1.0f;
            const Vec3 rayDir = normalize(m_rotation.rotate(
                    Vec3{ndcX * m_aspect * tanHalfFov, -ndcY * tanHalfFov, -1.0f}));
            const float dotFwd = dot(rayDir, forward);
            const float t = (dotFwd > 1e-6f) ? safeDepth / dotFwd : safeDepth;
            return {eye.x + rayDir.x * t, eye.y + rayDir.y * t, eye.z + rayDir.z * t};
        };

        const float midX = (left + right) * 0.5f;
        const float midY = (top + bottom) * 0.5f;
        const Vec3 worldCenter = unproject(midX, midY);
        const Vec3 dRight = unproject(right, midY) - worldCenter;
        const Vec3 dUp    = unproject(midX, top)   - worldCenter;

        const float halfExtR = std::sqrt(dRight.x*dRight.x + dRight.y*dRight.y + dRight.z*dRight.z);
        const float halfExtU = std::sqrt(dUp.x*dUp.x       + dUp.y*dUp.y       + dUp.z*dUp.z);

        constexpr float kFrameMeshHalfExtent = 0.5f;
        const float scaleR = std::max(halfExtR / kFrameMeshHalfExtent, 0.001f);
        const float scaleU = std::max(halfExtU / kFrameMeshHalfExtent, 0.001f);

        Mat4 result{};
        result.m[0] = rightAxis.x * scaleR;
        result.m[1] = rightAxis.y * scaleR;
        result.m[2] = rightAxis.z * scaleR;

        result.m[4] = upAxis.x * scaleU;
        result.m[5] = upAxis.y * scaleU;
        result.m[6] = upAxis.z * scaleU;

        result.m[8]  = backAxis.x;
        result.m[9]  = backAxis.y;
        result.m[10] = backAxis.z;

        result.m[12] = worldCenter.x;
        result.m[13] = worldCenter.y;
        result.m[14] = worldCenter.z;
        result.m[15] = 1.0f;
        return result;
    }

} // namespace station
