package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 更新公告管理器。读取 changelogs/ 下所有 YAML 文件，按时间降序排列。
 *
 * 配置文件格式：
 *   changelogs/<任意名>.yml:
 *     date: "2024-06-15 14:30"
 *     content:
 *       - "§e新增: §f..."
 *       - "§e修复: §f..."
 */
class ChangelogManager(private val plugin: PanlingBasic) : Reloadable {

    data class Entry(
        val date: LocalDateTime,
        val dateStr: String,
        val content: List<String>
    )

    private val entries = mutableListOf<Entry>()

    init {
        plugin.reloadManager?.register(this)
        load()
    }

    override fun reload() = load()

    fun load() {
        entries.clear()
        val folder = File(plugin.dataFolder, "changelogs")
        if (!folder.exists()) { folder.mkdirs(); return }

        folder.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            val cfg = YamlConfiguration.loadConfiguration(file)
            val dateStr = cfg.getString("date") ?: return@forEach
            val date = try {
                LocalDateTime.parse(dateStr, FORMATTER)
            } catch (_: DateTimeParseException) {
                plugin.logger.warning("[ChangelogManager] 日期格式无效: $dateStr (${file.name})")
                return@forEach
            }
            val content = cfg.getStringList("content")
            if (content.isEmpty()) return@forEach

            entries.add(Entry(date, dateStr, content))
        }

        entries.sortByDescending { it.date }
        plugin.logger.info("已加载 ${entries.size} 条更新公告。")
    }

    fun getEntries(): List<Entry> = entries

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
