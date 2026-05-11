package com.example.asteroidoutpost.net

/**
 * Player-progress sync. ProgressRepository writes here in addition to
 * SharedPreferences ("dual write"). On startup we pull server state and
 * reconcile with local; conflict resolution is the application's job
 * (server returns 409 with currentServerState).
 *
 * STAGE 1: standalone methods returning wire DTOs. Reconciliation flow —
 * next iteration.
 */
internal class ProgressService(private val api: ApiClient) {

    /** GET /progress — server's current view of this device's GameProgress. */
    fun fetch(): ApiResult<ProgressDto> =
        api.get("progress")

    /**
     * PUT /progress — full replacement. Returns Success on 200,
     * or Failure(409) when revision mismatched. Caller should re-fetch
     * and merge if 409.
     */
    fun push(req: ProgressRequest): ApiResult<ProgressDto> =
        api.put("progress", req)
}
