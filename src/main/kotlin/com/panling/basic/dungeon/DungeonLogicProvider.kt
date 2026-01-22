package com.panling.basic.dungeon

import com.panling.basic.dungeon.phase.AbstractDungeonPhase

/**
 * 副本逻辑提供者接口
 * * 实现此接口的类将被 DungeonManager 自动扫描并注册
 * * 替代了手动在主类调用 registerLogic 的方式
 */
interface DungeonLogicProvider {

    /**
     * 绑定的副本模板 ID (必须与 yml 文件名一致)
     */
    val templateId: String

    /**
     * 创建该副本的初始阶段
     * @param instance 副本实例
     */
    fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase
}