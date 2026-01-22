package com.panling.basic.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 当核心插件执行重载操作时触发此事件。
 * 下游插件（如 PanlingForge）应监听此事件来重载自己的配置。
 */
class PanlingReloadEvent : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        /**
         * Bukkit 事件系统需要此静态方法来注册监听器。
         * 使用 @JvmStatic 确保编译后的字节码中存在该静态方法。
         */
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}