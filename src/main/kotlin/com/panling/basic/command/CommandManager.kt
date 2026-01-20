package com.panling.basic.command

import com.panling.basic.PanlingBasic
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

class CommandManager(private val plugin: PanlingBasic) : CommandExecutor, TabCompleter {

    // 存储子命令的映射表
    private val commands = HashMap<String, SubCommand>()

    init {
        loadCommands()
    }
    // 下面是真正的 Kotlin 写法

    private fun loadCommands() {
        val packageName = "com.panling.basic.command.impl"
        val packagePath = packageName.replace('.', '/')

        try {
            // [优化] 使用 toURI() 自动处理路径中的空格和特殊字符，替代 URLDecoder
            val src = plugin::class.java.protectionDomain.codeSource
            if (src == null) return

            val jarFile = File(src.location.toURI())

            // [优化] use 块会自动关闭 JarFile，防止文件被占用
            JarFile(jarFile).use { jar ->
                val entries = jar.entries()

                // 遍历 Jar 包内的文件
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // 筛选：必须是 class 文件且位于指定包路径下
                    if (name.startsWith(packagePath) && name.endsWith(".class")) {
                        // 转换路径为类名 (移除 .class 后缀，将 / 替换为 .)
                        val className = name.replace('/', '.').substringBeforeLast(".class")

                        try {
                            val clazz = Class.forName(className)

                            // 筛选：必须继承自 SubCommand，且不是抽象类
                            if (SubCommand::class.java.isAssignableFrom(clazz) &&
                                !Modifier.isAbstract(clazz.modifiers)) {

                                // 实例化：假设都有一个 (PanlingBasic) 的构造函数
                                val constructor = clazz.getConstructor(PanlingBasic::class.java)
                                val cmd = constructor.newInstance(plugin) as SubCommand

                                commands[cmd.name.lowercase()] = cmd
                                plugin.logger.info("自动加载指令: ${cmd.name}")
                            }
                        } catch (e: Exception) {
                            plugin.logger.warning("跳过无法加载的类: $className (${e.message})")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("指令管理器自动扫描失败: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false

        val subName = args[0].lowercase()
        val cmd = commands[subName]

        if (cmd != null) {
            // 1. 权限检查
            if (cmd.permission != null && !sender.hasPermission(cmd.permission!!)) {
                sender.sendMessage("§c你没有权限使用此指令。")
                return true
            }

            // 2. 玩家检查
            if (cmd.isPlayerOnly && sender !is Player) {
                sender.sendMessage("§c只有玩家可以使用此指令。")
                return true
            }

            // 3. 执行 (切片获取剩余参数)
            // args.sliceArray 对应 Arrays.copyOfRange
            val subArgs = args.sliceArray(1 until args.size)
            cmd.perform(sender, subArgs)
            return true
        }

        sender.sendMessage("§c未知子命令。输入 /plbasic help 查看帮助。")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        // 第一层：补全子命令
        if (args.size == 1) {
            return commands.values.asSequence()
                .filter { cmd -> cmd.permission == null || sender.hasPermission(cmd.permission!!) }
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
                .toMutableList()
        }

        // 第二层及以后：交给子命令自己处理
        if (args.size > 1) {
            val subName = args[0].lowercase()
            val cmd = commands[subName]
            if (cmd != null) {
                // 只有当玩家有权使用该子命令时，才进行参数补全
                if (cmd.permission == null || sender.hasPermission(cmd.permission!!)) {
                    val subArgs = args.sliceArray(1 until args.size)
                    // 桥接 Java List 和 Kotlin MutableList
                    return cmd.getTabComplete(sender, subArgs)?.toMutableList() ?: mutableListOf()
                }
            }
        }

        return mutableListOf()
    }
}