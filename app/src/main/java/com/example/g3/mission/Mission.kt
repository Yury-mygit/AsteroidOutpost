package com.example.g3.mission

import com.example.g3.sim.CombatEvent
import com.example.g3.sim.ShipIntent
import com.example.g3.sim.SimulationWorld

interface Mission {
    fun beforeUpdate(dt: Float, world: SimulationWorld) = Unit
    fun intents(world: SimulationWorld): Map<Int, ShipIntent>
    fun onEvents(events: List<CombatEvent>) = Unit
    fun isDone(world: SimulationWorld): Boolean
}
