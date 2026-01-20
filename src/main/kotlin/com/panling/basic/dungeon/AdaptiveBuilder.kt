package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World

object AdaptiveBuilder {

    enum class Mode { BUILD, CLEAN }

    private const val CHECK_INTERVAL = 20

    // 全局预算控制
    private var currentTickId = -1
    private var timeUsedInThisTick = 0L

    private fun checkGlobalReset() {
        val serverTick = Bukkit.getCurrentTick()
        if (serverTick != currentTickId) {
            currentTickId = serverTick
            timeUsedInThisTick = 0L
        }
    }

    private fun calculateGlobalBudget(): Long {
        val tps = try { Bukkit.getTPS()[0] } catch (e: Exception) { 20.0 }
        val budgetMs = when {
            tps > 19.5 -> 35.0
            tps > 18.0 -> 20.0
            tps > 15.0 -> 5.0
            else -> 2.0
        }
        return (budgetMs * 1_000_000).toLong()
    }

    fun runTask(
        plugin: PanlingBasic,
        world: World,
        templateId: String,
        mode: Mode,
        origin: BlockVector3 = BlockVector3.at(0, 65, 0),
        onComplete: () -> Unit
    ) {
        val clipboard = SchematicManager.get(templateId)
        if (clipboard == null) {
            onComplete()
            return
        }

        val iterator = clipboard.region.iterator()
        val clipboardOrigin = clipboard.origin
        var taskId = -1

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            checkGlobalReset()
            val globalBudgetNs = calculateGlobalBudget()

            if (timeUsedInThisTick >= globalBudgetNs) {
                return@Runnable
            }

            // [修复2] 记录上一次检查的时间点，用于计算增量
            var lastCheckTime = System.nanoTime()
            var processed = 0

            while (iterator.hasNext()) {
                val vec = iterator.next()

                val x = vec.x() - clipboardOrigin.x() + origin.x()
                val y = vec.y() - clipboardOrigin.y() + origin.y()
                val z = vec.z() - clipboardOrigin.z() + origin.z()

                val baseBlock = clipboard.getBlock(vec)

                if (!baseBlock.blockType.material.isAir || mode == Mode.CLEAN) {

                    // [修复1] 关键修正：不要跳过未加载的区块，而是确保加载它！
                    // 在虚空世界，getBlockAt 会自动加载区块，或者手动 load 也可以。
                    // 为了保险，我们显式检查并加载 (对于 Void Generator，这非常快)
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) {
                        world.loadChunk(x shr 4, z shr 4)
                    }

                    val bukkitBlock = world.getBlockAt(x, y, z)

                    if (mode == Mode.BUILD) {
                        val newType = BukkitAdapter.adapt(baseBlock.blockType)
                        if (bukkitBlock.type != newType) {
                            val blockData = BukkitAdapter.adapt(baseBlock)
                            bukkitBlock.setBlockData(blockData, false)
                        }
                    } else {
                        if (bukkitBlock.type != Material.AIR) {
                            bukkitBlock.type = Material.AIR
                        }
                    }
                }

                processed++

                if (processed % CHECK_INTERVAL == 0) {
                    val now = System.nanoTime()
                    // [修复2] 只计算这一小段时间的增量，而不是总耗时
                    val delta = now - lastCheckTime
                    timeUsedInThisTick += delta
                    lastCheckTime = now // 更新基准时间

                    if (timeUsedInThisTick >= globalBudgetNs) {
                        return@Runnable
                    }
                }
            }

            if (!iterator.hasNext()) {
                Bukkit.getScheduler().cancelTask(taskId)
                onComplete()
            }
        }, 0L, 1L)
    }
}