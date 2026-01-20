package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import java.util.ArrayList

class ReloadManager(private val plugin: PanlingBasic) {

    // 存储所有实现了 Reloadable 接口的管理器
    // 使用 MutableList 接口，底层仍是 ArrayList
    private val reloadables: MutableList<Reloadable> = ArrayList()

    /**
     * [API] 注册一个可重载模块
     * 通常在 Manager 的构造函数里调用
     */
    fun register(reloadable: Reloadable) {
        reloadables.add(reloadable)
    }

    /**
     * [API] 执行一键重载
     */
    fun reloadAll() {
        plugin.logger.info("=== 开始执行全局重载 ===")

        // 1. 重载主配置文件 (config.yml)
        plugin.reloadConfig()

        // 2. 遍历所有注册的模块进行重载
        var count = 0
        for (r in reloadables) {
            try {
                // val start = System.currentTimeMillis()
                r.reload() // 调用接口方法

                // Kotlin 中获取类名推荐使用 javaClass.simpleName
                // plugin.logger.info("已重载: ${r.javaClass.simpleName} (${System.currentTimeMillis() - start}ms)")

                count++
            } catch (e: Exception) {
                plugin.logger.severe("重载模块 ${r.javaClass.simpleName} 时发生错误!")
                e.printStackTrace()
            }
        }

        plugin.logger.info("=== 全局重载完成，共处理 $count 个模块 ===")
    }
}