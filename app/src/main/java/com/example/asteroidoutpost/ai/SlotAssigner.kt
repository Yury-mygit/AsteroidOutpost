package com.example.asteroidoutpost.ai

/**
 * Assigns each agent to the nearest unoccupied formation slot.
 *
 * Algorithm: greedy nearest-free — O(n²), fine for n=5.
 * For each agent (sorted by distance to closest slot), assign
 * the nearest slot not yet taken.
 *
 * Returns a list where result[agentIndex] = slotIndex.
 */
object SlotAssigner {

    fun assign(agentPositions: List<Vec2>, slotPositions: List<Vec2>): IntArray {
        val n          = agentPositions.size
        val assignment = IntArray(n) { -1 }
        val taken      = BooleanArray(slotPositions.size)

        // For each agent, compute distance to every slot
        val distances = Array(n) { a ->
            FloatArray(slotPositions.size) { s ->
                (agentPositions[a] - slotPositions[s]).length()
            }
        }

        // Process agents in order of their minimum slot distance (closest-first)
        val order = (0 until n).sortedBy { a -> distances[a].min() }

        for (agentIdx in order) {
            var bestSlot = -1
            var bestDist = Float.MAX_VALUE
            for (slotIdx in slotPositions.indices) {
                if (!taken[slotIdx] && distances[agentIdx][slotIdx] < bestDist) {
                    bestDist = distances[agentIdx][slotIdx]
                    bestSlot = slotIdx
                }
            }
            if (bestSlot >= 0) {
                assignment[agentIdx] = bestSlot
                taken[bestSlot] = true
            }
        }

        return assignment
    }
}
