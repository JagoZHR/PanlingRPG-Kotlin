package com.panling.basic.quest.feature

import com.panling.basic.manager.QuestManager
import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcFeature
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

class QuestNpcFeature(private val questManager: QuestManager) : NpcFeature {

    override fun isAvailable(player: Player, npc: Npc): Boolean {
        // 简单的逻辑：只有当 NPC 配置过 start_npc 时，才视为任务NPC
        // 进阶逻辑：检查是否有该 NPC 发布的、且玩家当前可接的任务
        // 这里为了演示，只要它能发任务，就显示按钮
        return true
    }

    override fun getButton(player: Player, npc: Npc): Component {
        // 点击后执行内部指令: /plbasic internal quest_dialog <npc_id>
        // 注意：这里假设 Npc 类有 id 属性
        return Component.text("[ 📜 任务委托 ]")
            .color(NamedTextColor.GOLD)
            .hoverEvent(HoverEvent.showText(Component.text("§e点击查看该 NPC 发布的任务")))
            .clickEvent(ClickEvent.runCommand("/plbasic internal quest_dialog ${npc.id}"))
    }
}