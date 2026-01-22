package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.script.WorldScript
import com.panling.basic.util.ClassScanner
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WorldScriptManager(private val plugin: PanlingBasic) : Listener {

    private val scripts = HashMap<String, WorldScript>()
    private val activeSessions = ConcurrentHashMap<UUID, String>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // 初始化时自动加载
        loadAllScripts()
    }

    /**
     * 自动扫描并注册所有 WorldScript 实现类
     */
    fun loadAllScripts() {
        scripts.clear()
        plugin.logger.info("开始扫描大世界脚本...")

        // 扫描 com.panling.basic 包下的所有 WorldScript 实现
        // 这样无论你把脚本放在 script.impl 还是 dungeon.impl 下，都能被找到
        val scriptClasses = ClassScanner.scanClasses(plugin, "com.panling.basic", WorldScript::class.java)

        var count = 0
        for (clazz in scriptClasses) {
            try {
                // 尝试查找带 plugin 参数的构造函数
                val constructor = clazz.getConstructor(PanlingBasic::class.java)
                val instance = constructor.newInstance(plugin)
                registerScript(instance)
                count++
            } catch (e: NoSuchMethodException) {
                // 如果没有带参构造，尝试无参构造
                try {
                    val constructor = clazz.getConstructor()
                    val instance = constructor.newInstance()
                    registerScript(instance)
                    count++
                } catch (ex: Exception) {
                    plugin.logger.severe("无法实例化脚本类 ${clazz.simpleName}: 缺少 constructor(plugin) 或无参构造函数")
                }
            } catch (e: Exception) {
                plugin.logger.severe("加载脚本 ${clazz.simpleName} 失败: ${e.message}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("共自动注册了 $count 个大世界脚本。")
    }

    // 保留手动注册方法，以备不时之需
    fun registerScript(script: WorldScript) {
        if (scripts.containsKey(script.id)) {
            plugin.logger.warning("发现重复的脚本 ID: ${script.id} (类: ${script.javaClass.simpleName})，将覆盖旧脚本。")
        }
        scripts[script.id] = script
        // plugin.logger.info("已注册脚本: ${script.id}") // 可选日志
    }

    // ... (原本的 startScript, stopScript, onMobDeath 等逻辑保持不变) ...

    fun startScript(player: Player, scriptId: String) {
        val script = scripts[scriptId]
        if (script == null) {
            player.sendMessage("§c错误: 找不到ID为 $scriptId 的脚本。")
            return
        }

        // ... 原有逻辑 ...
        if (activeSessions[player.uniqueId] == scriptId) return

        val oldScriptId = activeSessions[player.uniqueId]
        if (oldScriptId != null) stopScript(player)

        if (script.onStart(player)) {
            activeSessions[player.uniqueId] = scriptId
        }
    }

    fun stopScript(player: Player) {
        val scriptId = activeSessions.remove(player.uniqueId) ?: return
        val script = scripts[scriptId]
        script?.onStop(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMobDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val scriptId = activeSessions[killer.uniqueId] ?: return
        val script = scripts[scriptId]
        script?.onMobKill(killer, event)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        stopScript(event.player)
    }
}