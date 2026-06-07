package com.panling.basic.quest.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.world.WorldDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

class StoneSwordSetup(private val plugin: PanlingBasic) : WorldDecoration {

    companion object {
        private const val SWORD_X = -484.5
        private const val SWORD_Y = 154.0
        private const val SWORD_Z = -286.5
    }

    override fun ensure() {
        val world = Bukkit.getWorld("world") ?: return
        val loc = Location(world, SWORD_X, SWORD_Y + 1.5, SWORD_Z)

        if (!loc.chunk.isLoaded) loc.chunk.load()

        // 清理旧石剑实体（包括初版 STONE_SWORD 和盔甲架版）
        world.getNearbyEntities(loc, 3.0, 3.0, 3.0).forEach { e ->
            if (e.scoreboardTags.contains("pl_stone_sword")) e.remove()
        }

        // 生成新石剑
        val display = world.spawn(loc, ItemDisplay::class.java)
        display.setItemStack(ItemStack(Material.IRON_AXE))
        display.isGlowing = true
        display.isPersistent = true
        display.scoreboardTags.add("pl_stone_sword")
        display.scoreboardTags.add("pl_persistent")

        display.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            Quaternionf().rotateX(Math.toRadians(110.0).toFloat())
                .rotateZ(Math.toRadians(20.0).toFloat()),
            Vector3f(1.8f, 1.8f, 1.8f),
            Quaternionf()
        )
        display.interpolationDuration = 0
        display.teleportDuration = 0

        plugin.logger.info("[StoneSword] 已生成石剑(IRON_AXE)在 ($SWORD_X, ${SWORD_Y + 1.5}, $SWORD_Z)")
    }
}
