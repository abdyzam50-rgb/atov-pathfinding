package com.example.aotvpathfinding

import com.example.aotvpathfinding.commands.PathfindCommand
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object AotvPathfindingMod : ClientModInitializer {
    const val MOD_ID = "aotv-pathfinding"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        PathfindCommand.register()
        LOGGER.info("AotV Pathfinding loaded")
    }
}
