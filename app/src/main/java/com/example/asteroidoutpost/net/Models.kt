package com.example.asteroidoutpost.net

import com.google.gson.annotations.SerializedName

/**
 * Wire models — exact mirror of `docs/api/openapi.yaml` schemas. Gson
 * matches Kotlin property names to JSON keys 1:1 (no SerializedName
 * decorator needed if names already match). Keep field names in sync
 * with the OpenAPI spec.
 *
 * Naming convention: camelCase matches server schema. If server changes
 * to snake_case later, add @SerializedName overrides; for now camelCase.
 */

// ===== Auth =====

internal data class AuthRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String,
)

internal data class AuthResponse(
    val token: String,
    val deviceId: String,
    val isNewDevice: Boolean,
    val tokenExpiresAt: String,
)

// ===== Missions =====

internal data class MissionsListResponse(
    val missions: List<MissionSummary>,
    val serverTime: String,
)

internal data class MissionSummary(
    val id: String,
    val schemaVersion: Int,
    val displayName: String,
    val description: String,
    val difficulty: String,
    val kind: String,
    val category: String,
    val order: Int? = null,
    val isRepeatable: Boolean = true,
    val updatedAt: String,
)

internal data class MissionConfigDto(
    val id: String,
    val schemaVersion: Int,
    val displayName: String,
    val description: String,
    val difficulty: String,
    val kind: String,
    val category: String,
    val order: Int? = null,
    val isRepeatable: Boolean = true,
    val baseHp: Int,
    val weaponsDisabled: Boolean = false,
    val asteroidBaseline: AsteroidBaselineDto,
    val waves: List<WaveDto> = emptyList(),
    val route: RouteDto? = null,
    val enemyShipSpawns: List<EnemyShipSpawnDto> = emptyList(),
    val updatedAt: String,
)

internal data class AsteroidBaselineDto(
    val hp: Int,
    val speed: Float,
)

internal data class WaveDto(
    val asteroidCount: Int,
    val spawnIntervalSec: Float,
    val typeWeights: Map<String, Float> = emptyMap(),
)

internal data class RouteDto(
    val startY: Float,
    val endY: Float,
    val asteroids: List<RoutePlacementDto>,
)

internal data class RoutePlacementDto(
    val absY: Float,
    val x: Float,
    val z: Float,
    val type: String,
    val hpOverride: Int? = null,
)

internal data class EnemyShipSpawnDto(
    val delaySec: Float,
    val xOffset: Float,
)

// ===== Progress =====

internal data class ProgressDto(
    val metal: Int,
    val mainWeaponDamageLevel: Int,
    val sideTurretDamageLevel: Int,
    val baseHpLevel: Int,
    val highestMissionUnlocked: Int,
    val selectedWeaponId: String,
    val revision: Int,
    val updatedAt: String? = null,
)

internal data class ProgressRequest(
    val metal: Int,
    val mainWeaponDamageLevel: Int,
    val sideTurretDamageLevel: Int,
    val baseHpLevel: Int,
    val highestMissionUnlocked: Int,
    val selectedWeaponId: String,
    val revision: Int,
)

internal data class ProgressConflictResponse(
    val error: ErrorEnvelope,
    val currentServerState: ProgressDto,
)

// ===== Telemetry =====

internal data class TelemetrySessionOpenRequest(
    val missionId: String,
    val weaponId: String,
    val startedAt: String,
    val appVersion: String,
    val missionSchemaVersion: Int,
)

internal data class TelemetrySessionOpenResponse(
    val sessionId: String,
    val frameBatchMaxSize: Int,
    val frameIntervalMs: Int,
)

internal data class TelemetryFramesRequest(
    val frames: List<TelemetryFrameDto>,
)

internal data class TelemetryFramesResponse(
    val accepted: Int,
    val rejected: Int,
)

internal data class TelemetryFrameDto(
    val ts: Long,
    val shipPosY: Float,
    val shieldHp: Int,
    val platformHp: Int,
    val energy: Int,
    val score: Int,
    val asteroids: List<TelemetryAsteroidDto>,
    val enemies: List<TelemetryEnemyDto>,
    val abilityCooldowns: List<Float>,
    val activeBuffSecLeft: Float,
    val playerPriorityAsteroidId: Long? = null,
)

internal data class TelemetryAsteroidDto(
    val id: Long,
    val type: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val hp: Int,
    val maxHp: Int,
)

internal data class TelemetryEnemyDto(
    val id: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val hp: Int,
    val maxHp: Int,
    val shieldHp: Int? = null,
    val shieldHpMax: Int? = null,
)

internal data class TelemetrySessionCloseRequest(
    val endedAt: String,
    val outcome: String,                  // "win" | "lose" | "abort"
    val score: Int,
    val metalEarned: Int,
    val asteroidsDestroyed: Int,
    val wavesCompleted: Int,
    val reason: String? = null,
)

internal data class TelemetrySessionCloseResponse(
    val sessionId: String,
    val framesReceived: Int,
    val durationSec: Float,
)

// ===== Common =====

internal data class ErrorEnvelope(
    val code: String,
    val message: String,
)

internal data class HealthResponse(
    val status: String,
    val serverTime: String,
)
