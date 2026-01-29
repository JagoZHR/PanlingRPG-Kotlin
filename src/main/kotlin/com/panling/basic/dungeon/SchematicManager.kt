package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object SchematicManager {

    private val cache = ConcurrentHashMap<String, Clipboard>()
    private lateinit var folder: File

    private val renderQueue = ConcurrentLinkedQueue<RenderTask>()
    private var isRunning = false

    private const val BASE_NANO_BUDGET = 15_000_000L
    private const val MIN_NANO_BUDGET = 1_000_000L
    private const val PAUSE_TPS_THRESHOLD = 12.0

    fun init(dataFolder: File) {
        folder = File(dataFolder, "schematics")
        if (!folder.exists()) folder.mkdirs()
    }

    fun pasteAsync(plugin: PanlingBasic, center: Location, schematicName: String, onComplete: () -> Unit) {
        val world = center.world!!

        // 1. 异步：解析文件 & 预计算数据
        CompletableFuture.runAsync {
            try {
                val clipboard = get(schematicName)
                if (clipboard == null) {
                    plugin.logger.warning("Schematic not found: $schematicName")
                    return@runAsync
                }

                val actions = ArrayList<PasteAction>()
                val origin = clipboard.origin
                val min = clipboard.region.minimumPoint
                val max = clipboard.region.maximumPoint

                var minWorldX = Int.MAX_VALUE
                var minWorldZ = Int.MAX_VALUE
                var maxWorldX = Int.MIN_VALUE
                var maxWorldZ = Int.MIN_VALUE

                // 遍历剪贴板
                for (x in min.x()..max.x()) {
                    for (y in min.y()..max.y()) {
                        for (z in min.z()..max.z()) {
                            val vec = BlockVector3.at(x, y, z)
                            val baseBlock = clipboard.getFullBlock(vec)
                            if (!baseBlock.blockType.id().contains("air")) {
                                val material = BukkitAdapter.adapt(baseBlock.blockType)
                                val relX = x - origin.x()
                                val relY = y - origin.y()
                                val relZ = z - origin.z()
                                actions.add(PasteAction(relX, relY, relZ, material))

                                val absX = center.blockX + relX
                                val absZ = center.blockZ + relZ
                                if (absX < minWorldX) minWorldX = absX
                                if (absX > maxWorldX) maxWorldX = absX
                                if (absZ < minWorldZ) minWorldZ = absZ
                                if (absZ > maxWorldZ) maxWorldZ = absZ
                            }
                        }
                    }
                }

                if (actions.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, Runnable { onComplete() })
                    return@runAsync
                }

                // 2. 回到主线程：加载区块并锁定 (Add Ticket)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val futures = ArrayList<CompletableFuture<Chunk>>()

                    val minChunkX = minWorldX shr 4
                    val maxChunkX = maxWorldX shr 4
                    val minChunkZ = minWorldZ shr 4
                    val maxChunkZ = maxWorldZ shr 4

                    // 用于记录我们锁定了哪些区块，以便任务完成后释放
                    val ticketedChunks = ArrayList<Long>()

                    for (cx in minChunkX..maxChunkX) {
                        for (cz in minChunkZ..maxChunkZ) {
                            // [核心修复] 添加 Plugin Ticket，强制保持区块加载
                            // 这会阻止服务器在粘贴过程中卸载这些区块
                            world.addPluginChunkTicket(cx, cz, plugin)

                            // 将坐标编码为 Long 存储 (高32位x, 低32位z)，方便后续释放
                            ticketedChunks.add((cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL))

                            // 依然调用 getChunkAtAsync 确保它们尽快就绪
                            futures.add(world.getChunkAtAsync(cx, cz))
                        }
                    }

                    // 3. 确保全部就绪后加入管线
                    CompletableFuture.allOf(*futures.toTypedArray()).thenAccept {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            // 将 ticketedChunks 传递给 RenderTask，任务结束时负责清理
                            addToQueue(plugin, center, actions, ticketedChunks, onComplete)
                        })
                    }
                })

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addToQueue(plugin: PanlingBasic, center: Location, actions: List<PasteAction>, tickets: List<Long>, callback: () -> Unit) {
        // RenderTask 接收 tickets 参数
        renderQueue.add(RenderTask(plugin, center, actions, tickets, callback))
        startGlobalPipeline(plugin)
    }

    private fun startGlobalPipeline(plugin: PanlingBasic) {
        if (isRunning) return
        isRunning = true

        object : BukkitRunnable() {
            override fun run() {
                if (renderQueue.isEmpty()) {
                    isRunning = false
                    cancel()
                    return
                }

                val currentTps = Bukkit.getTPS()[0]
                if (currentTps < PAUSE_TPS_THRESHOLD) return

                val loadFactor = ((currentTps - 15.0) / 5.0).coerceIn(0.0, 1.0)
                val currentTickBudget = (MIN_NANO_BUDGET + (BASE_NANO_BUDGET - MIN_NANO_BUDGET) * loadFactor).toLong()

                val startTime = System.nanoTime()

                while (System.nanoTime() - startTime < currentTickBudget) {
                    val task = renderQueue.poll() ?: break
                    val isFinished = task.processBatch()
                    if (isFinished) {
                        // 任务完成，触发清理和回调
                        try { task.finish() } catch (e: Exception) { e.printStackTrace() }
                    } else {
                        renderQueue.add(task)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun get(name: String): Clipboard? {
        if (cache.containsKey(name)) return cache[name]
        val file = File(folder, "$name.schem")
        if (!file.exists()) return null
        return try {
            val format = ClipboardFormats.findByFile(file) ?: return null
            format.getReader(file.inputStream()).use { reader ->
                val c = reader.read()
                cache[name] = c
                c
            }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    private data class PasteAction(val relX: Int, val relY: Int, val relZ: Int, val material: Material)

    private class RenderTask(
        val plugin: PanlingBasic, // 需要 plugin 实例来移除 ticket
        val center: Location,
        val actions: List<PasteAction>,
        val tickets: List<Long>, // 待释放的票据列表
        val callback: () -> Unit
    ) {
        private var index = 0
        private val world = center.world

        fun processBatch(): Boolean {
            var count = 0
            while (index < actions.size && count < 50) {
                val action = actions[index]
                if (world != null) {
                    val targetX = center.blockX + action.relX
                    val targetY = center.blockY + action.relY
                    val targetZ = center.blockZ + action.relZ

                    // 此时 isChunkLoaded 几乎100%为 true，因为有 Ticket 撑腰
                    // 但保留检查是个好习惯
                    if (world.isChunkLoaded(targetX shr 4, targetZ shr 4)) {
                        world.getBlockAt(targetX, targetY, targetZ).setType(action.material, false)
                    }
                }
                index++
                count++
            }
            return index >= actions.size
        }

        /**
         * 任务结束时的清理工作
         */
        fun finish() {
            // [核心修复] 释放所有区块的 Ticket
            // 释放后，如果周围没有玩家，这些区块将在之后的 Tick 中被自动卸载，释放内存
            if (world != null) {
                for (packed in tickets) {
                    val cx = (packed shr 32).toInt()
                    val cz = packed.toInt()
                    world.removePluginChunkTicket(cx, cz, plugin)
                }
            }
            // 执行回调
            callback()
        }
    }
}