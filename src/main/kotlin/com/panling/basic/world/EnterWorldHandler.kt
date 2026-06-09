package com.panling.basic.world

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/**
 * 处理 ENTER_WORLD 触发器：清空玩家数据 → 传送种族出生点 → 发放起始礼包 → 设置重生点。
 */
class EnterWorldHandler(private val plugin: PanlingBasic) {

    private val dataManager get() = plugin.playerDataManager
    private val itemKitManager get() = plugin.itemKitManager
    private val locationManager get() = plugin.locationManager

    fun execute(player: Player) {
        // ── 1. 校验 ──
        val race = dataManager.getPlayerRace(player)
        val playerClass = dataManager.getPlayerClass(player)

        if (race == PlayerRace.NONE) {
            player.sendMessage(Component.text("§c请先选择你的种族！").color(NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f)
            return
        }
        if (playerClass == PlayerClass.NONE) {
            player.sendMessage(Component.text("§c请先选择你的职业！").color(NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f)
            return
        }

        val savedRace = race
        val savedClass = playerClass

        // ── 2. 清空所有数据 ──
        dataManager.clearAllPlayerData(player)

        // ── 3. 恢复种族和职业 ──
        player.persistentDataContainer.set(BasicKeys.DATA_RACE, PersistentDataType.STRING, savedRace.name)
        player.persistentDataContainer.set(BasicKeys.DATA_CLASS, PersistentDataType.STRING, savedClass.name)
        dataManager.setPlayerClass(player, savedClass)
        dataManager.setActiveSlot(player, 0)

        // ── 4. 设置重生点 ──
        val world = Bukkit.getWorld("world") ?: player.world
        player.setRespawnLocation(Location(world, 179.5, 43.0, 63.5), false)

        // ── 5. 传送 ──
        val waypointName = "spawn_${savedRace.name}"
        val target = locationManager.getLocation(waypointName)
        if (target != null) {
            player.teleport(target)
        } else {
            plugin.logger.warning("[EnterWorld] 未找到出生点 $waypointName，玩家 ${player.name} 留在原地")
        }

        // ── 6. 发放礼包（职业 + 种族） ──
        itemKitManager.giveKits(player,
            "starter_${savedClass.name.lowercase()}",
            "race_${savedRace.name.lowercase()}"
        )

        // ── 7. 发放菜单物品 ──
        player.inventory.addItem(plugin.menuManager.menuItem.clone())

        // ── 8. 欢迎消息 ──
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        player.sendMessage(Component.text("§e§l========================================"))
        player.sendMessage(Component.text("§f  欢迎来到万象世界！").color(NamedTextColor.WHITE))
        player.sendMessage(Component.text("§f  种族: ${savedRace.coloredName}  §7|  §f职业: ${savedClass.displayName}")
            .color(NamedTextColor.WHITE))
        player.sendMessage(Component.text("§e§l========================================"))

        plugin.logger.info("[EnterWorld] ${player.name} 进入世界 — 种族=$savedRace 职业=$savedClass")
    }
}
