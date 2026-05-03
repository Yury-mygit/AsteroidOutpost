package com.example.g3.ai

import com.example.g3.sim.ShipMath

/**
 * Converts wedge slot offsets (local frame) into world-space positions.
 *
 * Local frame convention:
 *   +X = right relative to direction of travel
 *   +Y = forward (direction of travel)
 *
 * Slot 0 is the leader at the tip of the wedge.
 */
object FormationDef {

    val slotCount: Int get() = FlightConfig.WEDGE_OFFSETS.size

    /**
     * World-space position of [slotIndex] given:
     *   [anchorPos]   — world position of the formation anchor
     *   [direction]   — unit vector pointing in the direction of travel
     */
    fun worldSlot(anchorPos: Vec2, direction: Vec2, slotIndex: Int): Vec2 {
        val offset = FlightConfig.WEDGE_OFFSETS[slotIndex]
        val angle  = ShipMath.headingFromDirection(direction)
        return anchorPos + offset.rotate(angle)
    }

    /**
     * All slot world positions at once.
     */
    fun allSlots(anchorPos: Vec2, direction: Vec2): List<Vec2> =
        FlightConfig.WEDGE_OFFSETS.indices.map { worldSlot(anchorPos, direction, it) }
}
