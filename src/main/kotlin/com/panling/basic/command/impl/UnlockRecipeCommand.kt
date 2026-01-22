package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class UnlockRecipeCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "unlockrecipe"

    // 原代码包含 sender.isOp() 检查，且未显式要求玩家执行，故设为 false
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 显式权限检查逻辑
        if (!sender.isOp) return

        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic unlockrecipe <玩家> <配方ID>")
            return
        }

        val target = Bukkit.getPlayer(args[0])
        val recipeId = args[1]

        if (target == null) {
            msg(sender, "§c玩家不在线")
            return
        }

        // 调用 ForgeManager 接口，使用属性访问语法
        plugin.forgeManager.unlockRecipe(target, recipeId)

        msg(sender, "§a已为 ${target.name} 解锁配方: $recipeId")
    }
}