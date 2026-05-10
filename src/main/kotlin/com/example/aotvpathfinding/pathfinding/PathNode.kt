package com.example.aotvpathfinding.pathfinding

import net.minecraft.util.math.BlockPos

data class PathNode(
    val pos: BlockPos,
    val gCost: Double,
    val hCost: Double,
    val parent: PathNode? = null
) : Comparable<PathNode> {
    val fCost: Double get() = gCost + hCost

    override fun compareTo(other: PathNode): Int = fCost.compareTo(other.fCost)
}
