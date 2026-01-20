package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LoreManager
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.persistence.PersistentDataType

class BindCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "bind"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用 SubCommand 中的辅助方法 (Kotlin版会自动识别为 nullable)
        val p = asPlayer(sender) ?: return

        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic bind <技能ID>")
            return
        }

        // 访问物品属性
        val item = p.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            msg(sender, "§c请手持物品。")
            return
        }

        val sid = args[0].uppercase()

        // 假设 SkillManager 还是 Java 版，可以直接调用 .getSkill()
        // 如果 IDE 提示 getSkillManager() 找不到，请确保主类有这个方法
        if (plugin.skillManager.getSkill(sid) != null) {
            val meta = item.itemMeta

            // 使用 Kotlin 的空安全调用 ?.
            // BasicKeys 现在是 Kotlin Object，直接访问属性
            meta?.persistentDataContainer?.set(BasicKeys.FEATURE_ABILITY_ID, PersistentDataType.STRING, sid)
            meta?.persistentDataContainer?.set(BasicKeys.FEATURE_TRIGGER, PersistentDataType.STRING, "RIGHT_CLICK") // 默认右键

            item.itemMeta = meta

            // LoreManager 尚未迁移，如果是静态方法直接调用
            LoreManager.updateItemLore(item, p)
            msg(sender, "§a绑定技能: $sid")
        } else {
            msg(sender, "§c未知技能 ID: $sid")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            // 假设 SkillManager.getSkillIds() 返回 Collection<String>
            // 使用 Kotlin 的 filter 和 toList 简化流操作
            return plugin.skillManager.skillIds
                .filter { it.startsWith(args[0], true) } // true 表示忽略大小写
                .toList()
        }
        return emptyList()
    }
}