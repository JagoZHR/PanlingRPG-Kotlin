package com.panling.basic.api

import org.bukkit.entity.Player

interface TriggerHandler {
    /**
     * 执行触发逻辑
     * @param player 触发的玩家
     * @param value 指令参数 (比如 "HUMAN" 或 "spawn")
     */
    fun execute(player: Player, value: String)
}