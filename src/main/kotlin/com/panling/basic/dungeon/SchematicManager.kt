package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
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
    private const val BATCH_SIZE = 5000

    fun init(dataFolder: File) {
        folder = File(dataFolder, "schematics")
        if (!folder.exists()) folder.mkdirs()
    }

    fun pasteAsync(plugin: PanlingBasic, center: Location, schematicName: String, onComplete: () -> Unit) {
        val world = center.world!!

        CompletableFuture.runAsync {
            try {
                val clipboard = get(schematicName)
                if (clipboard == null) {
                    plugin.logger.warning("Schematic not found: $schematicName")
                    return@runAsync
                }

                val min = clipboard.region.minimumPoint
                val max = clipboard.region.maximumPoint
                val dx = max.x() - min.x() + 1
                val dy = max.y() - min.y() + 1
                val dz = max.z() - min.z() + 1
                val totalBlocks = dx * dy * dz

                // 计算世界坐标范围
                val origin = clipboard.origin
                val minWorldX = center.blockX + (min.x() - origin.x())
                val minWorldZ = center.blockZ + (min.z() - origin.z())
                val maxWorldX = center.blockX + (max.x() - origin.x())
                val maxWorldZ = center.blockZ + (max.z() - origin.z())

                if (totalBlocks == 0) {
                    Bukkit.getScheduler().runTask(plugin, Runnable { onComplete() })
                    return@runAsync
                }

                plugin.logger.info("[Schematic] $schematicName: ${dx}x${dy}x${dz} = ${totalBlocks} 方块, ~${totalBlocks / BATCH_SIZE + 1} 批")

                // 主线程：加载区块
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val minChunkX = minWorldX shr 4
                    val maxChunkX = maxWorldX shr 4
                    val minChunkZ = minWorldZ shr 4
                    val maxChunkZ = maxWorldZ shr 4

                    val ticketedChunks = ArrayList<Long>()
                    val futures = ArrayList<CompletableFuture<Chunk>>()

                    for (cx in minChunkX..maxChunkX) {
                        for (cz in minChunkZ..maxChunkZ) {
                            world.addPluginChunkTicket(cx, cz, plugin)
                            ticketedChunks.add((cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL))
                            futures.add(world.getChunkAtAsync(cx, cz))
                        }
                    }

                    CompletableFuture.allOf(*futures.toTypedArray()).thenAccept {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            addToQueue(plugin, center, clipboard, ticketedChunks, onComplete)
                        })
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addToQueue(plugin: PanlingBasic, center: Location, clipboard: Clipboard, tickets: List<Long>, callback: () -> Unit) {
        renderQueue.add(RenderTask(plugin, center, clipboard, tickets, callback))
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

    private class RenderTask(
        val plugin: PanlingBasic,
        val center: Location,
        val clipboard: Clipboard,
        val tickets: List<Long>,
        val callback: () -> Unit
    ) {
        private var index = 0
        private val world = checkNotNull(center.world)
        private val origin = clipboard.origin
        private val min = clipboard.region.minimumPoint
        private val max = clipboard.region.maximumPoint
        private val dz = max.z() - min.z() + 1
        private val dy = max.y() - min.y() + 1
        private val totalBlocks = (max.x() - min.x() + 1) * dy * dz
        private val faweWorld = BukkitAdapter.adapt(world)
        private val editSession: EditSession =
            WorldEdit.getInstance().newEditSession(faweWorld)

        fun processBatch(): Boolean {
            var count = 0
            while (index < totalBlocks && count < BATCH_SIZE) {
                val z = min.z() + (index % dz)
                val temp = index / dz
                val y = min.y() + (temp % dy)
                val x = min.x() + (temp / dy)

                val blockState = clipboard.getBlock(x, y, z)
                if (!blockState.blockType.material.isAir) {
                    val relX = x - origin.x()
                    val relY = y - origin.y()
                    val relZ = z - origin.z()
                    try {
                        editSession.setBlock(
                            center.blockX + relX,
                            center.blockY + relY,
                            center.blockZ + relZ,
                            blockState
                        )
                    } catch (_: Exception) {}
                }

                index++
                count++
            }
            editSession.flushQueue()
            return index >= totalBlocks
        }

        fun finish() {
            editSession.close()
            if (world != null) {
                for (packed in tickets) {
                    val cx = (packed shr 32).toInt()
                    val cz = packed.toInt()
                    world.removePluginChunkTicket(cx, cz, plugin)
                }
            }
            callback()
        }
    }
}
