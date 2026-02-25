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

    // 使用 Tick 计数，而不是秒数
    private var ticksRemaining: Int = 0

    override fun start() {
        // 将秒转换为 Tick (1秒 = 20 Tick)
        this.ticksRemaining = durationSeconds * 20
            //instance.broadcast("§b副本将于${durationSeconds}秒后开启...")
    }

    override fun onTick() {
        if (ticksRemaining > 0) {
            // 每 20 Tick (1秒) 提示一次
            if (ticksRemaining % 20 == 0) {
                val secondsLeft = ticksRemaining / 20
                // 只在特定时间点广播，防止刷屏
                if (secondsLeft <= 5 || secondsLeft % 10 == 0) {
                    instance.broadcast("§7距离开启还有 $secondsLeft 秒")
                }
            }
            ticksRemaining--
        } else {
            // 时间到
            onTimeout()
        }
    }

    /**
     * [必须实现] 等待结束后的逻辑
     * 通常在这里写: instance.nextPhase(NextPhase(plugin, instance))
     */
    abstract fun onTimeout()
}