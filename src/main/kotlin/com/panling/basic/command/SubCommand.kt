package com.panling.basic.command

import com.panling.basic.PanlingBasic
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

abstract class SubCommand(protected val plugin: PanlingBasic) {

    // === 必须实现的方法 ===

    /**
     * 子命令的名称 (例如 "reload", "race")
     * Kotlin 中抽象 getter 变成了抽象属性
     */
    abstract val name: String

    /**
     * 执行逻辑
     * @param sender 发送者
     * @param args 参数 (已去除子命令本身，args[0] 是子命令后的第一个参数)
     */
    abstract fun perform(sender: CommandSender, args: Array<out String>)

    // === 可选重写的方法 (使用 open 关键字) ===

    /**
     * 所需权限 (返回 null 代表无需权限)
     * 默认值可以直接赋值
     */
    open val permission: String? = "plbasic.admin" // 默认需要管理员权限

    /**
     * 是否只允许玩家执行 (默认 true)
     */
    open val isPlayerOnly: Boolean = true

    /**
     * Tab 补全逻辑 (默认返回空列表)
     * @param args 参数
     */
    open fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return emptyList()
    }

    // === 辅助方法 ===

    // 简化发送消息
    protected fun msg(sender: CommandSender, message: String) {
        sender.sendMessage(message)
    }

    // 检查并转换为玩家 (使用 Kotlin 的安全转换操作符 as?)
    protected fun asPlayer(sender: CommandSender): Player? {
        return sender as? Player
    }
}