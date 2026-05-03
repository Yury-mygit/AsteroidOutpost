package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.ai.Vec2

data class ExplosionState(
    val id: Int,
    val position: Vec2,
    val z: Float,
    var timeLeft: Float,
    val duration: Float,
    val maxScale: Float
) {
    val progress: Float
        get() = if (duration > 1e-5f) 1f - timeLeft / duration else 1f
}
