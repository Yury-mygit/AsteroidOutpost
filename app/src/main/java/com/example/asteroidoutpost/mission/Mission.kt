package com.example.asteroidoutpost.mission

import com.example.asteroidoutpost.sim.CombatEvent
import com.example.asteroidoutpost.sim.ShipIntent
import com.example.asteroidoutpost.sim.SimulationWorld

interface Mission {
    fun beforeUpdate(dt: Float, world: SimulationWorld) = Unit
    fun intents(world: SimulationWorld): Map<Int, ShipIntent>
    fun onEvents(events: List<CombatEvent>) = Unit
    fun isDone(world: SimulationWorld): Boolean
}
