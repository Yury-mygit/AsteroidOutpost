package com.example.asteroidoutpost.game

/**
 * In-flight stats for the current mission attempt. Reset on every game start.
 * Phase A: only `asteroidsDestroyed` is consumed (for the win/lose overlay).
 * Phase B will add `currentWave`, `totalWaves`, etc.
 */
data class MissionRun(
    var asteroidsDestroyed: Int = 0,
    var score: Int = 0,
    var metalEarned: Int = 0,   // total metal awarded during this run (asteroids + win bonus)
    var winBonus:    Int = 0,   // 0 unless the run ended in victory
    var currentWaveDisplay: Int = 1,  // 1-based for HUD ("Волна X/Y")
    var totalWaves:         Int = 1,
    var missionName:        String = "",
)
