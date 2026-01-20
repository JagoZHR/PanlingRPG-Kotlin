package com.panling.basic

import com.panling.basic.dungeon.DungeonManager
import org.bukkit.plugin.java.JavaPlugin

class PanlingBasic : JavaPlugin() {

    // Kotlin 风格的单例访问
    lateinit var dungeonManager: DungeonManager
        private set

    override fun onEnable() {
        logger.info("正在加载 PanlingBasic (Test Ver)...")

        // 1. 初始化精简版管理器
        dungeonManager = DungeonManager(this)

        // 2. 注册测试命令
        getCommand("dungeontest")?.setExecutor(DungeonTestCommand(this))

        // 注册之前的 NMS 检查命令 (保留着是个好习惯)
        getCommand("testnms")?.setExecutor { sender, _, _, _ ->
            if (sender is org.bukkit.entity.Player) {
                NMSVerifier.verify(sender)
            }
            true
        }

        logger.info("插件加载完成！")
    }

    override fun onDisable() {
        // 插件卸载时，自动清理所有测试世界
        if (::dungeonManager.isInitialized) {
            logger.info("正在清理测试副本...")
            dungeonManager.unloadAll()
        }
    }
}