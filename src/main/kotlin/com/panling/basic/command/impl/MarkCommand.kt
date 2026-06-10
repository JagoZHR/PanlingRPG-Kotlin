package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MarkCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "mark"

    private val file: File = File(plugin.dataFolder, "marked_points.yml")
    private var config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        // 确保最新
        if (!file.exists()) {
            config = YamlConfiguration()
        }

        if (args.isEmpty()) {
            listMarks(player)
            return
        }

        val action = args[0].lowercase()

        when (action) {
            "del", "delete", "remove" -> {
                if (args.size < 2) {
                    msg(sender, "§c用法: /plbasic mark del <名称>")
                    return
                }
                deleteMark(player, args[1])
            }
            "list" -> listMarks(player)
            else -> recordMark(player, args[0])
        }
    }

    private fun recordMark(player: org.bukkit.entity.Player, name: String) {
        val loc = player.location
        val path = "marks.$name"

        config.set("$path.world", loc.world.name)
        config.set("$path.x", round1(loc.x))
        config.set("$path.y", round1(loc.y))
        config.set("$path.z", round1(loc.z))
        config.set("$path.yaw", round1(loc.yaw.toDouble()))
        config.set("$path.pitch", round1(loc.pitch.toDouble()))
        config.set("$path.timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))

        save()
        msg(player, "§a已标记坐标点 §e$name §7(§f${round1(loc.x)}, ${round1(loc.y)}, ${round1(loc.z)}§7) §7→ §8marked_points.yml")
    }

    private fun listMarks(player: org.bukkit.entity.Player) {
        val marks = config.getConfigurationSection("marks")
        if (marks == null || marks.getKeys(false).isEmpty()) {
            msg(player, "§7暂无标记点。使用 §e/plbasic mark <名称> §7记录当前坐标。")
            return
        }

        msg(player, "§6§l=== 已标记坐标点 (${marks.getKeys(false).size}个) ===§r")
        for (name in marks.getKeys(false).sorted()) {
            val w = config.getString("marks.$name.world", "?")
            val x = config.getDouble("marks.$name.x")
            val y = config.getDouble("marks.$name.y")
            val z = config.getDouble("marks.$name.z")
            val ts = config.getString("marks.$name.timestamp", "")
            msg(player, " §e$name §7→ §f$w §7(§f${round1(x)}, ${round1(y)}, ${round1(z)}§7) §8$ts")
        }
    }

    private fun deleteMark(player: org.bukkit.entity.Player, name: String) {
        if (!config.contains("marks.$name")) {
            msg(player, "§c标记点 '$name' 不存在。")
            return
        }
        config.set("marks.$name", null)
        save()
        msg(player, "§a已删除标记点 §e$name")
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val actions = listOf("del", "list")
            val marks = config.getConfigurationSection("marks")?.getKeys(false) ?: emptySet()
            return (actions + marks).filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in listOf("del", "delete", "remove")) {
            val marks = config.getConfigurationSection("marks")?.getKeys(false) ?: emptySet()
            return marks.filter { it.startsWith(args[1], ignoreCase = true) }.toList()
        }
        return emptyList()
    }

    private fun save() {
        try {
            config.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("§c保存 marked_points.yml 失败: ${e.message}")
        }
    }

    private fun round1(d: Double): Double = Math.round(d * 10.0) / 10.0
}
