package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.BillboardDraw
import com.example.asteroidoutpost.GameplayShape
import com.example.asteroidoutpost.HighlightStyle
import com.example.asteroidoutpost.SceneObject
import com.example.asteroidoutpost.ai.Vec2

/**
 * Converts simulation state into renderable scene data.
 */
class SceneAdapter(
    private val shipMeshHandle: Long,
    private val stationMeshHandle: Long,
    private val projectileMeshHandle: Long,
    private val selectedShipIds: Set<Int>,
    private val selectedTargetId: Int,
    private val homeShips: List<ShipState>,
    private val enemyHomeShips: List<ShipState>,
    private val worldObjects: List<WorldObject>,
    private val fighterShape: GameplayShape,
    private val stationShape: GameplayShape
) {
    fun staticScene(): List<SceneObject> =
        homeShips.map { alliedShip(it) } +
        enemyHomeShips.map { enemyShipSceneObject(it) } +
        nonAlliedObjects()

    fun sceneFromWorld(world: SimulationWorld): List<SceneObject> {
        val byId = world.shipSnapshot().associateBy { it.id }
        val allied = homeShips.mapNotNull { home ->
            val ship = byId[home.id] ?: home
            if (ship.combatStats.isDestroyed) null else alliedShip(ship)
        }
        val enemies = enemyHomeShips.mapNotNull { home ->
            val ship = byId[home.id] ?: home
            if (ship.combatStats.isDestroyed) null else enemyShipSceneObject(ship)
        }
        return allied + enemies + nonAlliedObjects()
    }

    fun plasmaBillboards(world: SimulationWorld): List<BillboardDraw> =
        world.projectileSnapshot().map { projectile ->
            BillboardDraw(
                projectileMeshHandle,
                projectile.position.x,
                projectile.position.y,
                projectile.z,
                PROJECTILE_SCALE
            )
        } + world.explosionSnapshot().map { explosion ->
            BillboardDraw(
                projectileMeshHandle,
                explosion.position.x,
                explosion.position.y,
                explosion.z,
                WeaponController.explosionScale(explosion).coerceAtLeast(MIN_EXPLOSION_SCALE)
            )
        }

    private fun alliedShip(ship: ShipState): SceneObject =
        SceneObject(
            id = ship.id,
            meshHandle = shipMeshHandle,
            x = ship.position.x,
            y = ship.position.y,
            z = ship.z,
            rotationZ = ship.heading,
            selected = ship.id in selectedShipIds,
            highlightStyle = HighlightStyle.THIN,
            highlightScale = 0.78f,
            pickRadius = 0.85f,
            framePadding = 0.09f,
            gameplayShape = fighterShape,
            isEnemy = false
        )

    private fun enemyShipSceneObject(ship: ShipState): SceneObject =
        SceneObject(
            id = ship.id,
            meshHandle = shipMeshHandle,
            x = ship.position.x,
            y = ship.position.y,
            z = ship.z,
            rotationZ = ship.heading,
            selected = ship.id == selectedTargetId,
            highlightStyle = HighlightStyle.THIN,
            highlightScale = 0.78f,
            pickRadius = 0.85f,
            framePadding = 0.09f,
            gameplayShape = fighterShape,
            isEnemy = true
        )

    private fun nonAlliedObjects(): List<SceneObject> =
        worldObjects.filter { !it.combatStats.isDestroyed }.map { obj ->
            when (obj.objectType) {
                WorldObjectType.STATION -> stationSceneObject(obj)
                WorldObjectType.FIGHTER -> enemyFighterSceneObject(obj)
            }
        }

    private fun stationSceneObject(obj: WorldObject): SceneObject =
        SceneObject(
            id = obj.id,
            meshHandle = stationMeshHandle,
            x = obj.position.x,
            y = obj.position.y,
            z = obj.z,
            scale = 0.1f,
            selected = obj.id == selectedTargetId,
            pickRadius = 3f,
            framePadding = 0.09f,
            gameplayShape = stationShape,
            frameZMin = 0f,
            frameZMax = 75f,
            orbitMargin = 2f,
            isEnemy = obj.team == Team.ENEMY
        )

    private fun enemyFighterSceneObject(obj: WorldObject): SceneObject =
        SceneObject(
            id = obj.id,
            meshHandle = shipMeshHandle,
            x = obj.position.x,
            y = obj.position.y,
            z = obj.z,
            rotationZ = obj.heading,
            selected = obj.id == selectedTargetId,
            highlightStyle = HighlightStyle.THIN,
            highlightScale = 0.78f,
            pickRadius = 0.85f,
            framePadding = 0.09f,
            gameplayShape = fighterShape,
            isEnemy = true
        )

    private companion object {
        const val PROJECTILE_SCALE = 0.35f
        const val MIN_EXPLOSION_SCALE = 0.1f
    }
}
