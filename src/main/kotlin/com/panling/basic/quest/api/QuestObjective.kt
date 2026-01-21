package com.panling.basic.quest.api

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

/**
 * 任务目标接口
 * 所有的任务条件（杀怪、采集、对话、升级）都必须实现此接口
 */
interface QuestObjective {

    // 目标的唯一ID (例如 "kill_zombie_10")
    val id: String

    // 目标的描述 (显示在记分板或聊天栏，例如 "击杀僵尸 0/10")
    val description: String

    // 目标需要的总进度 (例如 10)
    val requiredAmount: Int

    /**
     * 核心逻辑：处理事件
     * 当发生任何事件时，QuestManager 会询问这个目标：“这个事件跟你有关系吗？”
     *
     * @param player 触发事件的玩家
     * @param event  发生的事件 (可以是 Bukkit 事件，也可以是插件自定义事件)
     * @param currentProgress 当前该目标的进度值 (比如杀了几只)
     * @return 如果进度更新了，返回新的进度值；如果无关，返回 -1 或 currentProgress
     */
    fun onEvent(player: Player, event: Event, currentProgress: Int): Int

    // [NEW] 获取该目标的导航位置 (如果是杀怪，可以是怪物聚集地的坐标；如果是对话，就是NPC坐标)
    // 返回 null 表示该目标不支持导航
    val navigationLocation: Location?
        get() = null
}