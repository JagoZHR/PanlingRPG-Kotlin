package com.panling.basic.shop.feature

import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcFeature
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

class BarterNpcFeature : NpcFeature {

    override fun isAvailable(player: Player, npc: Npc): Boolean {
        // 当 NPC 配置了 barter_id 时，显示此按钮
        return npc.hasData("barter_id")
    }

    override fun getButton(player: Player, npc: Npc): Component {
        val barterId = npc.getData("barter_id") as String

        return Component.text("[ ⚖ 以物换物 ]")
            .color(NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true)
            .hoverEvent(HoverEvent.showText(Component.text("§a点击打开交易界面")))
            // 触发内部命令打开原版交易界面
            .clickEvent(ClickEvent.runCommand("/plbasic internal open_barter $barterId"))
    }
}