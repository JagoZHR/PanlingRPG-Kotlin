package com.panling.basic.skill.impl.archer

import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.inventory.ItemStack
import kotlin.math.min

object QuiverSkills {

    class Store(private val dataManager: PlayerDataManager) :
        AbstractSkill("QUIVER_STORE", "箭矢回收", PlayerClass.NONE) {

        override fun onCast(ctx: SkillContext): Boolean {
            val p = ctx.player
            var count = 0

            // 遍历背包移除所有箭矢
            // getContents() 可能包含 null，需要安全处理
            for (item in p.inventory.contents) {
                if (item != null && item.type == Material.ARROW) {
                    count += item.amount
                    item.amount = 0
                }
            }

            if (count > 0) {
                dataManager.addQuiverArrows(p, count)
                p.sendMessage("§a[箭袋] 已存入 $count 支箭矢。当前库存: ${dataManager.getQuiverArrows(p)}")
                p.playSound(p.location, Sound.ITEM_BUNDLE_INSERT, 1f, 1f)
                return true
            } else {
                p.sendMessage("§c背包内没有箭矢可存。")
                return false
            }
        }
    }

    class Withdraw(private val dataManager: PlayerDataManager) :
        AbstractSkill("QUIVER_WITHDRAW", "箭矢取出", PlayerClass.NONE) {

        override fun onCast(ctx: SkillContext): Boolean {
            val p = ctx.player
            val current = dataManager.getQuiverArrows(p)

            if (current <= 0) {
                p.sendMessage("§c箭袋内没有箭矢。")
                return false
            }

            // 取出数量：最多 64 或者 全部库存
            val amount = 64.coerceAtMost(current)

            if (dataManager.takeQuiverArrows(p, amount)) {
                p.inventory.addItem(ItemStack(Material.ARROW, amount))
                p.sendMessage("§e[箭袋] 取出 $amount 支箭矢。剩余: ${dataManager.getQuiverArrows(p)}")
                p.playSound(p.location, Sound.ITEM_BUNDLE_REMOVE_ONE, 1f, 1f)
                return true
            }
            return false
        }
    }

    /**
     * 自动补给被动 (批量补给模式)
     */
    class Supply(private val dataManager: PlayerDataManager) :
        AbstractSkill("QUIVER_SUPPLY", "箭袋补给", PlayerClass.NONE) {

        companion object {
            // 当背包箭矢少于 32 时触发补给
            private const val TRIGGER_THRESHOLD = 32
            // 每次尝试补给 32 支 (半组)
            private const val REFILL_AMOUNT = 32
        }

        override fun onCast(ctx: SkillContext): Boolean {
            val p = ctx.player

            // 1. 统计当前背包内的箭矢数量
            var currentArrows = 0
            for (item in p.inventory.contents) {
                if (item != null && item.type == Material.ARROW) {
                    currentArrows += item.amount
                }
            }

            // 2. 只有当箭矢变得很少时 (防止还没用完就一直补，导致背包塞满)
            if (currentArrows < TRIGGER_THRESHOLD) {
                val storage = dataManager.getQuiverArrows(p)

                if (storage > 0) {
                    // 尝试补给一组固定数量
                    val toAdd = REFILL_AMOUNT.coerceAtMost(storage)

                    if (dataManager.takeQuiverArrows(p, toAdd)) {
                        p.inventory.addItem(ItemStack(Material.ARROW, toAdd))

                        // [NEW] 只在补给发生时提示
                        p.sendActionBar(Component.text("§e[箭袋] 自动补给 $toAdd 支箭矢 (剩余: ${storage - toAdd})")
                            .color(NamedTextColor.YELLOW))

                        // 可选：极轻微的音效
                        // p.playSound(p.location, Sound.ITEM_PICKUP, 0.3f, 2.0f)
                    }
                } else if (currentArrows == 0) {
                    // 箭袋也没了，背包也没了
                    p.sendActionBar(Component.text("§c[箭袋] 弹尽粮绝!").color(NamedTextColor.RED))
                }
            }
            return true
        }
    }
}