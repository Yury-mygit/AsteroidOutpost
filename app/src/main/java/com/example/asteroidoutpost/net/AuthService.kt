package com.example.asteroidoutpost.net

import android.util.Log

/**
 * Device-token authentication. The first time the app talks to the
 * server we POST /auth/device with the locally-generated device UUID
 * and receive a bearer token. Token is persisted in ApiClient and
 * automatically attached to subsequent requests via the auth
 * interceptor.
 *
 * No retry logic here — caller (Activity startup hook) handles
 * scheduling. Failure is silent and non-fatal: app continues offline.
 */
internal class AuthService(private val api: ApiClient) {

    /**
     * Ensure we have a valid token. If already cached, returns it
     * without a network call. Otherwise registers the device. On
     * failure returns the existing (possibly null) token without
     * surfacing the error to the gameplay layer.
     */
    fun ensureToken(): String? {
        val cached = api.currentToken
        if (!cached.isNullOrBlank()) return cached
        return refresh()
    }

    /** Force a token refresh — re-register device with the server. */
    fun refresh(): String? {
        val req = AuthRequest(
            deviceId   = api.deviceId,
            platform   = "android",
            appVersion = BuildInfo.APP_VERSION,
        )
        return when (val res = api.post<AuthResponse>("auth/device", req)) {
            is ApiResult.Success -> {
                Log.i(TAG, "device auth ok — isNew=${res.body.isNewDevice}, expires=${res.body.tokenExpiresAt}")
                api.setToken(res.body.token)
                res.body.token
            }
            is ApiResult.Failure -> {
                Log.w(TAG, "device auth failed — code=${res.code} msg=${res.message}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "AuthService"
    }
}
