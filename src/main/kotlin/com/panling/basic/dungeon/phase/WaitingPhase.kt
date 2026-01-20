package com.panling.basic.dungeon.phase

import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.configuration.ConfigurationSection

class WaitingPhase : AbstractDungeonPhase("WAITING") {

    private var waitTime: Int = 60
    private var timer: Int = 0

    override fun load(config: ConfigurationSection) {
        // 从配置读取参数，保持通用性
        this.waitTime = config.getInt("duration", 60)
    }

    override fun onStart(instance: DungeonInstance) {
        this.timer = waitTime
        instance.broadcast("§b[集结] 进入等待区，${waitTime}秒后开启...")
    }

    override fun onTick(instance: DungeonInstance) {
        if (timer > 0) {
            if (timer <= 5 || timer % 10 == 0) {
                instance.broadcast("§7距离开启还有 $timer 秒")
            }
            timer--
        } else {
            instance.broadcast("§a集结完毕，副本开启！")
            instance.nextPhase() // 核心：控制权转交
        }
    }

    override fun onEnd(instance: DungeonInstance) {
        // 清理逻辑 (如果有空气墙可以在这里移除)
    }
}