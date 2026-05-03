package com.example.asteroidoutpost.intelligence

data class Wing(val name: String, val shipIds: List<Int>)

class FleetRegistry(val wings: List<Wing>) {

    fun resolveUnit(unit: FleetUnit): List<Int> = when (unit) {
        FleetUnit.All -> wings.flatMap { it.shipIds }
        is FleetUnit.WingByName -> wings.firstOrNull { it.name == unit.name }?.shipIds.orEmpty()
        is FleetUnit.ExplicitIds -> unit.ids.sorted()
    }

    fun unitKey(unit: FleetUnit): String = when (unit) {
        FleetUnit.All -> "all"
        is FleetUnit.WingByName -> "wing:${unit.name}"
        is FleetUnit.ExplicitIds -> "ids:${unit.ids.sorted().joinToString(",")}"
    }

    fun withShipInWing(wingIndex: Int, shipId: Int): FleetRegistry {
        val updated = wings.mapIndexed { i, wing ->
            if (i == wingIndex) wing.copy(shipIds = wing.shipIds + shipId) else wing
        }
        return FleetRegistry(updated)
    }

    companion object {
        // DRAFT — Asteroid Outpost has no fleet. StationAI/EnemyAI will be
        // replaced with turret + wave logic in M1; for now they tick over
        // empty registries and emit no commands.
        fun default() = FleetRegistry(emptyList())

        fun enemy()   = FleetRegistry(emptyList())
    }
}
