package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcFeature
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Sound
import org.bukkit.entity.Player

class DialogManager(private val plugin: PanlingBasic) {

    private val features = ArrayList<NpcFeature>()

    fun registerFeature(feature: NpcFeature) {
        features.add(feature)
    }

    fun openDialog(player: Player, npc: Npc) {
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        // 1. 头部装饰
        player.sendMessage(Component.text("§8§m--------------------------------"))

        // 2. NPC 信息
        player.sendMessage(
            Component.text(npc.name).color(NamedTextColor.YELLOW)
                .append(Component.text(": ${npc.dialogText}").color(NamedTextColor.WHITE))
        )
        player.sendMessage(Component.empty())

        // 3. 动态生成按钮
        var buttonsLine = Component.text("  ")
        // var hasOption = false // 暂时没用到，如果需要判断无选项的情况可以加回来

        for (feature in features) {
            if (feature.isAvailable(player, npc)) {
                val btn = feature.getButton(player, npc)
                buttonsLine = buttonsLine.append(btn).append(Component.text("   "))
                // hasOption = true
            }
        }

        // 4. 通用按钮 (离开)
        val byeBtn = Component.text("[ 再见 ]")
            .color(NamedTextColor.GRAY)
            .hoverEvent(HoverEvent.showText(Component.text("§7结束对话")))
            .clickEvent(ClickEvent.runCommand("/plbasic internal close_dialog")) // 对应 CommandHandler

        buttonsLine = buttonsLine.append(byeBtn)

        player.sendMessage(buttonsLine)
        player.sendMessage(Component.text("§8§m--------------------------------"))
    }
}