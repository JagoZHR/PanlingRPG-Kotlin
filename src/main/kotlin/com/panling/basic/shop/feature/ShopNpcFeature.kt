package com.panling.basic.shop.feature

import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcFeature
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

class ShopNpcFeature : NpcFeature {

    override fun isAvailable(player: Player, npc: Npc): Boolean {
        // 核心判断：只有当 NPC 配置里有 "shop_id" 时，才显示商店按钮
        return npc.hasData("shop_id")
    }

    override fun getButton(player: Player, npc: Npc): Component {
        // 强转逻辑保持不变，如果类型不对会抛出 ClassCastException
        val shopId = npc.getData("shop_id") as String

        // 点击后执行: /plbasic internal open_shop <shop_id>
        return Component.text("[ 💰 交易 ]")
            .color(NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true)
            .hoverEvent(HoverEvent.showText(Component.text("§e点击打开商店: $shopId")))
            .clickEvent(ClickEvent.runCommand("/plbasic internal open_shop $shopId"))
    }
}