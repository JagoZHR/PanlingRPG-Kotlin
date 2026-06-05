package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance

/**
 * 可复用的标准等待阶段
 * 倒计时结束后执行回调（通常是进入玩法阶段）
 */
class StandardWaitingPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance,
    private val waitSeconds: Int,
    private val callback: () -> Unit
) : WaitingPhase(plugin, instance, waitSeconds) {

    override fun start() {
        super.start()
        instance.broadcast("§b副本将于 $waitSeconds 秒后开启...")
    }

    override fun onTimeout() {
        callback()
    }
}
