package com.example.aotvpathfinding.commands

import com.abdy2.aotvpathfinder.TeleportPathfinder
import com.abdy2.aotvpathfinder.TeleportPathfinder.MovementMode
import com.abdy2.aotvpathfinder.TeleportPathfinder.TeleportMode
import com.example.aotvpathfinding.items.SkyblockItemDetector
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

object PathfindCommand {
    private val pathfinder = TeleportPathfinder()

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

                                    source.sendFeedback(Text.literal("§7Computing path to ($x, $y, $z)..."))

                                    val hops = pathfinder.findPath(
                                        player,
                                        startPos,
                                        goalPos,
                                        4000,
                                        MovementMode.HYBRID,
                                        TeleportMode.HYBRID_TELEPORT,
                                        true
                                    )

                                    if (hops.isEmpty()) {
                                        source.sendFeedback(Text.literal("§cNo path found to ($x, $y, $z)."))
                                    } else {
                                        val teleportSteps = hops.count { !it.isWalk }
                                        val walkSteps = hops.count { it.isWalk }
                                        val totalMana = hops.sumOf { it.manaCost() }
                                        source.sendFeedback(
                                            Text.literal("§aPath found: §f${hops.size} §asteps (§f$teleportSteps §ateleport, §f$walkSteps §awalk) — §f$totalMana §amana")
                                        )
                                        hops.take(10).forEachIndexed { i, hop ->
                                            val label = when {
                                                hop.requiresShift() -> "§bETHER"
                                                hop.isWalk -> "§7WALK "
                                                else -> "§aAOTV "
                                            }
                                            val pos = hop.landing()
                                            source.sendFeedback(
                                                Text.literal("  $label §f${pos.x}, ${pos.y}, ${pos.z}")
                                            )
                                        }
                                        if (hops.size > 10) {
                                            source.sendFeedback(
                                                Text.literal("  §7... and §f${hops.size - 10} §7more steps")
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
