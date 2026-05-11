package com.example.asteroidoutpost.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client over OkHttp + Gson. One ApiClient instance per Activity
 * lifecycle — keeps connection pool, token, and device-id stable across
 * calls. All methods are blocking (call from background thread); the
 * Activity drives an Executor / coroutine layer above.
 *
 * Token persistence: SharedPreferences("api_credentials") — survives app
 * restart so we don't re-auth on every launch. deviceId generated lazily
 * on first use (UUID v4).
 *
 * Offline / error model: every call returns `ApiResult.Success(body)` or
 * `ApiResult.Failure(code, message)`. Callers branch — no exceptions
 * propagate to gameplay layer. Network errors collapse into
 * `ApiResult.Failure(0, "...")` (HTTP code 0 = pre-response failure).
 */
internal class ApiClient(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson: Gson = Gson()

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val tok = currentToken
        val newReq = if (tok != null && req.header("Authorization") == null) {
            req.newBuilder()
                .addHeader("Authorization", "Bearer $tok")
                .build()
        } else req
        chain.proceed(newReq)
    }

    private val headersInterceptor = Interceptor { chain ->
        val req = chain.request()
        val builder = req.newBuilder()
            .header("X-Client-Platform", "android")
            .header("X-Client-Version", BuildInfo.APP_VERSION)
            .header("X-Request-Id", UUID.randomUUID().toString())
        if (req.header("Content-Type") == null && req.body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
        }
        chain.proceed(builder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor { msg ->
        Log.d(TAG, msg)
    }.apply { level = HttpLoggingInterceptor.Level.BASIC }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(headersInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---- Public state ----

    /** Stable device UUID — lazily generated on first call, never changes. */
    val deviceId: String
        get() {
            val cached = prefs.getString(KEY_DEVICE_ID, null)
            if (cached != null) return cached
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    /** Current bearer token, or null if not authenticated yet. */
    var currentToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        private set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
                apply()
            }
        }

    /**
     * Replace the active token (set after a successful /auth/device call).
     * Internal — only AuthService should call this.
     */
    internal fun setToken(token: String?) {
        currentToken = token
    }

    // ---- Request helpers ----

    /** GET <base>/<path>. */
    inline fun <reified T> get(path: String): ApiResult<T> =
        execute<T>(buildRequest(path).get().build())

    /** POST <base>/<path> with JSON body. */
    inline fun <reified T> post(path: String, body: Any?): ApiResult<T> =
        execute<T>(buildRequest(path).post(jsonBody(body)).build())

    /** PUT <base>/<path> with JSON body. */
    inline fun <reified T> put(path: String, body: Any?): ApiResult<T> =
        execute<T>(buildRequest(path).put(jsonBody(body)).build())

    /** Fire-and-forget POST — used for telemetry frames. Returns immediately. */
    fun postAsync(path: String, body: Any?, onComplete: ((ApiResult<Unit>) -> Unit)? = null) {
        val req = buildRequest(path).post(jsonBody(body)).build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onComplete?.invoke(ApiResult.Failure(0, e.message ?: "network failure"))
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) onComplete?.invoke(ApiResult.Success(Unit))
                    else onComplete?.invoke(ApiResult.Failure(r.code, r.message))
                }
            }
        })
    }

    fun buildRequest(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
        return Request.Builder().url(url)
    }

    fun jsonBody(body: Any?): RequestBody {
        val json = if (body == null) "{}" else gson.toJson(body)
        return json.toRequestBody(JSON_MEDIA)
    }

    inline fun <reified T> execute(req: Request): ApiResult<T> {
        return try {
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ApiResult.Failure(resp.code, bodyStr.ifBlank { resp.message })
                } else if (T::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(Unit as T)
                } else {
                    val typeToken = object : TypeToken<T>() {}.type
                    val parsed: T = gson.fromJson(bodyStr, typeToken)
                    ApiResult.Success(parsed)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(0, e.message ?: "io error")
        } catch (e: Exception) {
            ApiResult.Failure(0, "parse error: ${e.message}")
        }
    }

    companion object {
        const val BASE_URL = "https://api.g4.raftforge.art/api/v1"
        private const val PREFS_NAME = "api_credentials"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "auth_token"
        private const val TAG = "ApiClient"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Tagged-union result for every API call. */
internal sealed class ApiResult<out T> {
    data class Success<T>(val body: T) : ApiResult<T>()
    /** code == 0 means pre-response failure (no network, bad URL, parse). */
    data class Failure(val code: Int, val message: String) : ApiResult<Nothing>()
}

/** Build-time info pulled here so net code doesn't import BuildConfig directly. */
internal object BuildInfo {
    // BuildConfig may not exist in some build flavours; fall back to a literal.
    // Update when bumping versionName in app/build.gradle.kts.
    const val APP_VERSION: String = "1.0"
}
