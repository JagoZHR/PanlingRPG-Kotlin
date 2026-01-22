package com.panling.basic.script

import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent

/**
 * 大世界脚本接口
 * 用于处理副本前置、区域解密等一次性或临时性的逻辑
 */
interface WorldScript {

    // 脚本的唯一ID，用于命令方块调用
    // 例如: "tomb_entrance"
    val id: String

    /**
     * 当命令方块触发激活该脚本时调用
     * @return Boolean 如果返回 false，说明启动失败（例如条件完全不满足），管理器将不会把玩家加入监听列表
     */
    fun onStart(player: Player): Boolean

    /**
     * 当脚本被停止或完成时调用（用于清理临时数据，如计数器）
     */
    fun onStop(player: Player)

    /**
     * 只有当玩家处于“激活”状态时，管理器才会把杀怪事件传给这个方法
     */
    fun onMobKill(player: Player, event: EntityDeathEvent)

    // 如果未来需要，可以扩展 fun onInteract(...) 等
}