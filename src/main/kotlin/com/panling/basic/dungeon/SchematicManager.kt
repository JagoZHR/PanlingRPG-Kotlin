package com.panling.basic.dungeon

import com.fastasyncworldedit.core.FaweAPI
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SchematicManager {

    // 缓存：文件名 -> WE 剪贴板对象 (纯内存数据)
    private val cache = ConcurrentHashMap<String, Clipboard>()
    private lateinit var folder: File

    fun init(dataFolder: File) {
        folder = File(dataFolder, "schematics")
        if (!folder.exists()) folder.mkdirs()
    }

    /**
     * 获取或加载 Schematic
     * 这是一个可能耗时的操作(解压文件)，但在 DungeonManager 初始化池子时调用即可
     */
    fun get(name: String): Clipboard? {
        if (cache.containsKey(name)) return cache[name]

        val file = File(folder, "$name.schem")
        if (!file.exists()) return null

        try {
            val format = ClipboardFormats.findByFile(file) ?: return null
            format.getReader(file.inputStream()).use { reader ->
                val clipboard = reader.read()
                cache[name] = clipboard
                return clipboard
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}