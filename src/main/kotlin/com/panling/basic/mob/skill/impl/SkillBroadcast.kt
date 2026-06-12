package com.panling.basic.mob.skill.impl

import com.panling.basic.api.BasicKeys
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** 向与怪物作战的玩家发送消息（独立怪的主人） */
internal fun broadcast(caster: LivingEntity, text: String) {
    val msg = Component.text(text.replace("&", "§"))
    val ownerStr = caster.persistentDataContainer.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)
    if (ownerStr != null) {
        try {
            val owner = Bukkit.getPlayer(UUID.fromString(ownerStr))
            owner?.sendMessage(msg)
        } catch (ignored: Exception) {}
    }
}
