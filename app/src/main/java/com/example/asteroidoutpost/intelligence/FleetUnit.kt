package com.example.asteroidoutpost.intelligence

sealed interface FleetUnit {
    data object All : FleetUnit
    data class WingByName(val name: String) : FleetUnit
    data class ExplicitIds(val ids: Set<Int>) : FleetUnit
}
