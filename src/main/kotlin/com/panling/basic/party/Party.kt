package com.panling.basic.party

import org.bukkit.entity.Player
import java.util.UUID

// 队伍数据类
class Party(val leader: UUID) {
    // 包含队长在内的所有成员
    val members = mutableSetOf<UUID>(leader)

    val isFull: Boolean
        get() = members.size >= 16
}

// 标识 UI 的 Holder，方便我们在 InventoryClickEvent 中识别并区分状态
class PartyHolder(val type: PartyUIType) : org.bukkit.inventory.InventoryHolder {
    override fun getInventory(): org.bukkit.inventory.Inventory {
        throw UnsupportedOperationException("Not implemented")
    }
}

enum class PartyUIType {
    MANAGE_TEAM, // 队伍管理/拉人界面
    HANDLE_INVITES // 处理收到的邀请界面
}