package com.example.aotvpathfinding.items

import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack

object SkyblockItemDetector {
    private const val AOTV_ID = "ASPECT_OF_THE_VOID"

    fun isAotv(stack: ItemStack): Boolean {
        val nbt = stack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt() ?: return false
        return nbt.getCompound("ExtraAttributes").getString("id") == AOTV_ID
    }
}
