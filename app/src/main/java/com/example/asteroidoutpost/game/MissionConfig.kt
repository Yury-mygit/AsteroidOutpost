package com.example.asteroidoutpost.game

/**
 * One wave of asteroids inside a mission. The wave spawns [asteroidCount]
 * asteroids one by one with [spawnIntervalSec] between each. The wave is
 * considered done when every asteroid it spawned is gone (destroyed by
 * bullets or having reached the platform).
 */
data class WaveConfig(
    val asteroidCount: Int,
    val spawnIntervalSec: Float,
)

/**
 * One playable mission. All numbers needed to drive a full run.
 */
data class MissionConfig(
    val id: Int,
    val name: String,
    val description: String,
    val difficulty: String,
    val waves: List<WaveConfig>,
    val asteroidHp: Int,
    val asteroidSpeed: Float,
    val baseHp: Int,
)
