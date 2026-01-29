package com.panling.basic.dungeon

import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.concurrent.atomic.AtomicInteger

object InstanceLocationProvider {

    private const val WORLD_NAME = "panling_instances"

    // [修改] 公开常量，供 Manager 计算使用
    const val OFFSET_DISTANCE = 500

    private val currentIndex = AtomicInteger(0)

    /**
     * 获取下一个可用的副本中心点
     * [修改] 返回 Pair<Location, Int>，把索引也带出去
     */
    fun getNextLocation(): Pair<Location, Int> {
        val world = Bukkit.getWorld(WORLD_NAME)
            ?: throw IllegalStateException("副本世界 $WORLD_NAME 尚未加载！")

        val index = currentIndex.getAndIncrement()
        val x = index * OFFSET_DISTANCE

        return Pair(Location(world, x.toDouble(), 100.0, 0.0), index)
    }

    fun reset() {
        currentIndex.set(0)
    }
}