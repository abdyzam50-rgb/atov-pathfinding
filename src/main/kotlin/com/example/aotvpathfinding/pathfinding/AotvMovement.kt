package com.example.aotvpathfinding.pathfinding

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.*

object AotvMovement {
    const val RANGE = 8.0
    private const val STEP = 0.5

    // 26 cube-face/edge/corner directions + 16 fine horizontal directions
    private val DIRECTIONS: List<Vec3d> by lazy {
        val dirs = mutableListOf<Vec3d>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            dirs.add(Vec3d(dx / len, dy / len, dz / len))
        }
        // 16 evenly-spaced horizontal directions for better horizontal coverage
        repeat(16) { i ->
            val angle = i * PI / 8.0
            dirs.add(Vec3d(cos(angle), 0.0, sin(angle)))
        }
        dirs
    }

    fun getReachableNeighbors(world: World, from: BlockPos): List<BlockPos> {
        // Eye position — roughly player eye height above feet
        val origin = Vec3d(from.x + 0.5, from.y + 1.62, from.z + 0.5)
        val results = linkedSetOf<BlockPos>()
        for (dir in DIRECTIONS) {
            simulateTeleport(world, origin, dir)?.let { results.add(it) }
        }
        return results.toList()
    }

    private fun simulateTeleport(world: World, origin: Vec3d, dir: Vec3d): BlockPos? {
        var dist = STEP
        while (dist <= RANGE) {
            val point = origin.add(dir.multiply(dist))
            val blockPos = BlockPos.ofFloored(point)
            if (!world.isAir(blockPos)) {
                // Hit a solid block; land at the previous step
                val landPoint = origin.add(dir.multiply(dist - STEP))
                return findValidLanding(world, BlockPos.ofFloored(landPoint))
            }
            dist += STEP
        }
        // No obstacle — travel the full range
        val landPoint = origin.add(dir.multiply(RANGE))
        return findValidLanding(world, BlockPos.ofFloored(landPoint))
    }

    // Finds the highest valid standing position at or below `pos` within 4 blocks
    private fun findValidLanding(world: World, pos: BlockPos): BlockPos? {
        for (dy in 0..4) {
            val candidate = pos.down(dy)
            if (!world.isAir(candidate)) continue                 // feet blocked
            if (!world.isAir(candidate.up())) continue            // head blocked
            if (world.isAir(candidate.down())) continue           // no floor
            return candidate
        }
        return null
    }
}
