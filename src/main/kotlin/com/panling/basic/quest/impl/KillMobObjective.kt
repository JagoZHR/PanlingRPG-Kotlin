package com.panling.basic.quest.impl

import com.panling.basic.api.BasicKeys
import com.panling.basic.quest.api.QuestObjective
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType

class KillMobObjective(
    override val id: String,
    private val targetMobId: String,
    private val amount: Int,
    override val description: String,
    override val navigationLocation: Location?
) : QuestObjective {

    override val requiredAmount: Int
        get() = amount

    override fun onEvent(player: Player, event: Event, currentProgress: Int): Int {
        // 1. 事件类型检查
        if (event !is EntityDeathEvent) return -1

        // 2. 判定击杀者 (虽然 QuestManager 分发时已经检查过 killer，但这里再确认一下也无妨，或者由 Manager 保证)
        // Manager 的 dispatchEvent 是基于 player 调用的，所以这里的 player 就是 killer

        val victim = event.entity
        val pdc = victim.persistentDataContainer

        // 3. 获取怪物 ID
        // 优先尝试读取自定义 ID (MobManager 写入的)
        val mobId = pdc.get(BasicKeys.MOB_ID, PersistentDataType.STRING) ?: victim.type.name

        // 4. 比对 ID (忽略大小写)
        if (mobId.equals(targetMobId, ignoreCase = true)) {
            return currentProgress + 1
        }

        return -1
    }
}