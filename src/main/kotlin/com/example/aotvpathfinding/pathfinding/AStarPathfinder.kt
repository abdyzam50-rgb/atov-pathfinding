package com.example.aotvpathfinding.pathfinding

import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.PriorityQueue
import kotlin.math.sqrt

object AStarPathfinder {
    private const val MAX_NODES = 5000
    private const val GOAL_RADIUS = 2.0

    fun findPath(world: World, start: BlockPos, goal: BlockPos): List<BlockPos>? {
        val open = PriorityQueue<PathNode>()
        val closed = HashSet<Long>()
        val bestG = HashMap<Long, Double>()

        val startNode = PathNode(start, 0.0, heuristic(start, goal))
        open.add(startNode)
        bestG[start.asLong()] = 0.0

        var explored = 0
        while (open.isNotEmpty() && explored < MAX_NODES) {
            val current = open.poll()
            val key = current.pos.asLong()

            if (closed.contains(key)) continue
            closed.add(key)
            explored++

            if (distanceTo(current.pos, goal) <= GOAL_RADIUS) {
                return reconstructPath(current)
            }

            for (neighbor in AotvMovement.getReachableNeighbors(world, current.pos)) {
                val nKey = neighbor.asLong()
                if (closed.contains(nKey)) continue

                val gCost = current.gCost + 1.0
                if (gCost >= (bestG[nKey] ?: Double.MAX_VALUE)) continue

                bestG[nKey] = gCost
                open.add(PathNode(neighbor, gCost, heuristic(neighbor, goal), current))
            }
        }

        return null
    }

    // Min AotV uses remaining (admissible: AotV moves at most RANGE blocks)
    private fun heuristic(pos: BlockPos, goal: BlockPos): Double =
        distanceTo(pos, goal) / AotvMovement.RANGE

    private fun distanceTo(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun reconstructPath(node: PathNode): List<BlockPos> {
        val path = ArrayDeque<BlockPos>()
        var current: PathNode? = node
        while (current != null) {
            path.addFirst(current.pos)
            current = current.parent
        }
        return path.toList()
    }
}
