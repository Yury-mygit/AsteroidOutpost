package com.example.asteroidoutpost.net

/**
 * Mission catalog fetcher. Calls are blocking — invoke from a background
 * thread (Executor, coroutine, or HandlerThread). Caller handles offline
 * fallback to bundled `Missions.ALL`.
 *
 * STAGE 1: standalone methods that return the wire DTOs. Wiring into
 * MissionHub / Campaign / RandomMissions screens — next iteration.
 */
internal class MissionService(private val api: ApiClient) {

    /** GET /missions — short summaries for the menu screens. */
    fun list(): ApiResult<MissionsListResponse> =
        api.get("missions")

    /** GET /missions/{id} — full mission spec for runner consumption. */
    fun detail(missionId: String): ApiResult<MissionConfigDto> =
        api.get("missions/$missionId")
}
