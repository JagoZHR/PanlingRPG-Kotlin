package com.panling.basic.world

import com.panling.basic.PanlingBasic
import com.panling.basic.util.ClassScanner

/**
 * 世界装饰物管理器。
 * 在插件启动时自动扫描 com.panling.basic 包下所有 WorldDecoration 实现类，
 * 实例化并调用 ensure()，实现零耦合的装饰物注册。
 *
 * 新增装饰物只需三步：
 * 1. 创建类实现 WorldDecoration
 * 2. 放在 com.panling.basic 包树下
 * 3. 提供一个 (PanlingBasic) 构造函数
 *
 * 无需修改任何现有代码。
 */
class DecorationManager(private val plugin: PanlingBasic) {

    private val decorations = ArrayList<WorldDecoration>()

    fun loadAll() {
        decorations.clear()

        val classes = ClassScanner.scanClasses(plugin, "com.panling.basic", WorldDecoration::class.java)

        for (clazz in classes) {
            try {
                val instance = try {
                    clazz.getConstructor(PanlingBasic::class.java).newInstance(plugin)
                } catch (e: NoSuchMethodException) {
                    plugin.logger.warning("${clazz.simpleName} 缺少构造函数(PanlingBasic)，跳过")
                    continue
                }
                decorations.add(instance)
                instance.ensure()
                plugin.logger.info("已加载世界装饰物: ${clazz.simpleName}")
            } catch (e: Exception) {
                plugin.logger.warning("初始化装饰物 ${clazz.simpleName} 失败: ${e.message}")
            }
        }

        plugin.logger.info("共加载 ${decorations.size} 个世界装饰物")
    }
}
