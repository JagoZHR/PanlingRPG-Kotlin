package com.panling.basic.dungeon.impl.tomb

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.DungeonLogicProvider
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.WaitingPhase

/**
 * 古墓副本的逻辑入口
 * 自动被 Manager 扫描并注册
 */
class TombProvider(private val plugin: PanlingBasic) : DungeonLogicProvider {

    override val templateId: String = "tomb"

    override fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase {
        // 返回该副本的第一阶段
        return object : WaitingPhase(plugin, instance, 10) {
            override fun onTimeout() {
                instance.nextPhase(TombCombatPhase(plugin, instance))
            }
        }
    }
}