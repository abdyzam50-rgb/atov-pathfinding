package com.example.aotvpathfinding.commands

import com.example.aotvpathfinding.items.SkyblockItemDetector
import com.example.aotvpathfinding.pathfinding.AStarPathfinder
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

object PathfindCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("aotv").then(
                    literal("goto").then(
                        argument("x", IntegerArgumentType.integer()).then(
                            argument("y", IntegerArgumentType.integer()).then(
                                argument("z", IntegerArgumentType.integer()).executes { ctx ->
                                    val source = ctx.source
                                    val player = source.player
                                    val world = source.world

                                    val x = IntegerArgumentType.getInteger(ctx, "x")
                                    val y = IntegerArgumentType.getInteger(ctx, "y")
                                    val z = IntegerArgumentType.getInteger(ctx, "z")

                                    val startPos = player.blockPos
                                    val goalPos = BlockPos(x, y, z)

                                    if (!SkyblockItemDetector.isAotv(player.mainHandStack)) {
                                        source.sendFeedback(
                                            Text.literal("§eWarning: AotV not detected in main hand. Pathfinding anyway...")
                                        )
                                    }

                                    source.sendFeedback(
                                        Text.literal("§7Computing AotV path to ($x, $y, $z)...")
                                    )

                                    val path = AStarPathfinder.findPath(world, startPos, goalPos)

                                    if (path == null) {
                                        source.sendFeedback(
                                            Text.literal("§cNo path found to ($x, $y, $z) within search limit.")
                                        )
                                    } else {
                                        source.sendFeedback(
                                            Text.literal("§aFound path: §f${path.size - 1} §aAotV uses")
                                        )
                                        val display = path.drop(1).take(10)
                                        display.forEachIndexed { i, pos ->
                                            source.sendFeedback(
                                                Text.literal("  §7Step ${i + 1}: §f${pos.x}, ${pos.y}, ${pos.z}")
                                            )
                                        }
                                        if (path.size - 1 > 10) {
                                            source.sendFeedback(
                                                Text.literal("  §7... and §f${path.size - 11} §7more steps")
                                            )
                                        }
                                    }

                                    1
                                }
                            )
                        )
                    )
                )
            )
        }
    }
}
