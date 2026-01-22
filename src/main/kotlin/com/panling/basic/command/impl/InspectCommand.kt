package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.command.SubCommand
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType

class InspectCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "inspect"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        val seenTarget = player.getTargetEntity(10)

        if (seenTarget is LivingEntity) {
            // 使用 Elvis 操作符处理可能的 null 值
            val seenMobId = seenTarget.persistentDataContainer.get(BasicKeys.MOB_ID, PersistentDataType.STRING)

            player.sendMessage("§e===== 生物信息检查 =====")
            // 优化名称获取逻辑
            player.sendMessage("§7名称: ${seenTarget.customName ?: seenTarget.name}")
            player.sendMessage("§7类型: ${seenTarget.type}")

            val mobIdText = seenMobId?.let { "§a$it" } ?: "§c无 (原版生物)"
            player.sendMessage("§7MobID: $mobIdText")

            // 使用安全调用避免潜在的 Attribute 为空导致的异常
            val maxHp = seenTarget.getAttribute(Attribute.MAX_HEALTH)?.value ?: 0.0
            val currentHp = seenTarget.health

            // 使用 Kotlin 字符串模板和格式化
            player.sendMessage("§b[原版硬属性] §f血量: §a${"%.1f".format(currentHp)} / ${"%.1f".format(maxHp)}")

            // 如果 seenMobId 不为空，则展示自定义属性
            seenMobId?.let {
                val stats = plugin.mobManager.getMobStats(seenTarget)
                player.sendMessage("§6[自定义 RPG 属性]")
                player.sendMessage("  §f物理防御: §e${stats.physicalDefense}")
                player.sendMessage("  §f魔法防御: §b${stats.magicDefense}")
                player.sendMessage("  §f暴击率: ${(stats.critRate * 100).toInt()}%")
            }
        } else {
            player.sendMessage("§c请看着一个生物！")
        }
    }
}