package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance

/**
 * 通用等待阶段
 * * 用法：继承此类并实现 onTimeout() 来指定下一阶段
 */
abstract class WaitingPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance,
    private val durationSeconds: Int
) : AbstractDungeonPhase(plugin, instance) {

    private var timer: Int = 0

    override fun start() {
        this.timer = durationSeconds
        instance.broadcast("§b[集结] 进入等待区，${durationSeconds}秒后开启...")
    }

    override fun onTick() {
        if (timer > 0) {
            // 每10秒或最后5秒倒计时提示
            if (timer <= 5 || timer % 10 == 0) {
                instance.broadcast("§7距离开启还有 $timer 秒")
            }
            timer--
        } else {
            // 时间到，触发抽象方法
            onTimeout()
        }
    }

    /**
     * [必须实现] 等待结束后的逻辑
     * 通常在这里写: instance.nextPhase(NextPhase(plugin, instance))
     */
    abstract fun onTimeout()
}