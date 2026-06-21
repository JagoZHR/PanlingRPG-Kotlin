package com.panling.basic.npc.impl

import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcFeature
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

class CastingHallFeature : NpcFeature {

    override fun isAvailable(player: Player, npc: Npc): Boolean {
        return npc.hasData("command") && npc.getData("command") == "castinghall"
    }

    override fun getButton(player: Player, npc: Npc): Component {
        return Component.text("[ ⚒ 铸灵殿 ]")
            .color(NamedTextColor.GOLD)
            .hoverEvent(HoverEvent.showText(Component.text("§e提交 T5 装备换取永久属性")))
            .clickEvent(ClickEvent.runCommand("/plbasic internal castinghall"))
    }
}
