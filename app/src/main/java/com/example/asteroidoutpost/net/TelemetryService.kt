package com.example.asteroidoutpost.net

/**
 * Telemetry session lifecycle. Three calls:
 *   - openSession() at mission start.
 *   - flushFrames(...) periodically (every ~1s) with batched frames.
 *   - closeSession(...) at mission end (win/lose/abort).
 *
 * Frame upload uses fire-and-forget (`api.postAsync`) so gameplay
 * never blocks on network. Session open/close are blocking — caller
 * runs them on a background thread.
 *
 * STAGE 1: standalone methods. Wire-up to MissionRunner frame emission —
 * next iteration.
 */
internal class TelemetryService(private val api: ApiClient) {

    fun openSession(req: TelemetrySessionOpenRequest): ApiResult<TelemetrySessionOpenResponse> =
        api.post("telemetry/sessions", req)

    /** Fire-and-forget — no result delivered back to caller. */
    fun flushFrames(sessionId: String, frames: List<TelemetryFrameDto>) {
        if (frames.isEmpty()) return
        api.postAsync("telemetry/sessions/$sessionId/frames", TelemetryFramesRequest(frames))
    }

    fun closeSession(
        sessionId: String,
        req: TelemetrySessionCloseRequest,
    ): ApiResult<TelemetrySessionCloseResponse> =
        api.post("telemetry/sessions/$sessionId/close", req)
}
