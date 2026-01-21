package com.panling.basic.mob.skill

import org.bukkit.configuration.ConfigurationSection
import java.util.HashMap

class MobSkillRegistry {

    // 工厂函数：接收配置段，返回一个构造好的技能对象
    private val factories = HashMap<String, (ConfigurationSection) -> MobSkill>()

    fun register(type: String, factory: (ConfigurationSection) -> MobSkill) {
        factories[type.uppercase()] = factory
    }

    fun create(type: String, config: ConfigurationSection): MobSkill? {
        val factory = factories[type.uppercase()] ?: return null
        return factory(config)
    }
}