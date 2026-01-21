package com.panling.basic.quest

import com.panling.basic.quest.api.QuestObjective
import com.panling.basic.quest.api.QuestReward
import org.bukkit.configuration.ConfigurationSection
import java.util.HashMap

class QuestRegistry {

    // === 定义两个工厂接口 ===

    // 1. 目标工厂：给你配置，你给我产出一个 QuestObjective
    fun interface ObjectiveFactory {
        fun create(objectiveId: String, config: ConfigurationSection): QuestObjective
    }

    // 2. 奖励工厂：给你配置，你给我产出一个 QuestReward
    fun interface RewardFactory {
        fun create(config: ConfigurationSection): QuestReward
    }

    // === 存储工厂的 Map ===
    private val objectiveFactories = HashMap<String, ObjectiveFactory>()
    private val rewardFactories = HashMap<String, RewardFactory>()

    // === 注册方法 ===

    fun registerObjective(type: String, factory: ObjectiveFactory) {
        objectiveFactories[type.uppercase()] = factory
    }

    fun registerReward(type: String, factory: RewardFactory) {
        rewardFactories[type.uppercase()] = factory
    }

    // === 获取/构建方法 ===

    fun createObjective(type: String, id: String, config: ConfigurationSection): QuestObjective {
        val factory = objectiveFactories[type.uppercase()]
            ?: throw IllegalArgumentException("未知的任务目标类型: $type")
        return factory.create(id, config)
    }

    fun createReward(type: String, config: ConfigurationSection): QuestReward {
        val factory = rewardFactories[type.uppercase()]
            ?: throw IllegalArgumentException("未知的任务奖励类型: $type")
        return factory.create(config)
    }
}