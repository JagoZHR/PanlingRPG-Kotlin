package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.BuffType
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ZidianPassiveSkill(private val plugin: PanlingBasic) :
    AbstractSkill("ZIDIAN", "风斩电刺", PlayerClass.WARRIOR), WarriorSkillStrategy {

    // 使用 ConcurrentHashMap 虽然在主线程运行通常 HashMap 也行，但作为类成员在 Kotlin 中显式声明更佳
    // 这里保持原逻辑的 HashMap 语义，但在 Kotlin 中直接用 mutableMapOf 或 HashMap 即可
    private val dataMap: MutableMap<UUID, ZidianData> = HashMap()

    override var castTime: Double = 0.0

    companion object {
        // 额外穿透的 Metadata Key
        private const val META_EXTRA_PEN = "pl_extra_pen"
    }

    init {
        // 启动清理任务
        startCleanupTask()
    }

    override fun cast(ctx: SkillContext): Boolean {
        return true
    }

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val p = ctx.player
        val uuid = p.uniqueId

        // 1. 获取或创建数据
        val data = dataMap.getOrPut(uuid) { ZidianData() }

        // 2. 更新数据
        data.addStack()
        data.lastAttackTime = System.currentTimeMillis()

        // 3. 应用移速 Buff
        // 每层 6% -> 1层=1.06, 5层=1.30
        val speedMult = 1.0 + (data.stacks * 0.06)

        // 调用 BuffManager，持续 6秒 (120 ticks)
        plugin.buffManager.addBuff(p, BuffType.SPEED_UP, 120L, speedMult, true)

        // 4. 应用 50% 穿甲 (满层时)
        if (data.stacks >= 5) {
            // 设置 Metadata
            p.setMetadata(META_EXTRA_PEN, FixedMetadataValue(plugin, 50.0))

            // 只有刚满层那一下提示
            if (data.stacks == 5 && !data.fullStackNotified) {
                p.world.playSound(p.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 2.0f)
                p.sendActionBar(Component.text("§e紫电层数已满！护甲穿透大幅提升！").color(NamedTextColor.YELLOW))
                data.fullStackNotified = true
            }
        }
    }

    private fun startCleanupTask() {
        // 转换为 Kotlin 风格的 Scheduler 调用，逻辑完全一致
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()

            // 遍历并移除符合条件的条目
            dataMap.entries.removeIf { (uuid, data) ->
                val p = Bukkit.getPlayer(uuid)
                var shouldRemove = false

                // 判定 1: 玩家离线 (Kotlin 中 p 为 null 或 !p.isOnline)
                if (p == null || !p.isOnline) {
                    return@removeIf true
                }

                // 判定 2: 5秒未攻击
                if (now - data.lastAttackTime > 5000) {
                    shouldRemove = true
                }

                // 3. [核心修复] 检查激活槽位物品
                val activeSlot = plugin.playerDataManager.getActiveSlot(p)

                val activeItem: ItemStack? = when {
                    activeSlot == 40 -> p.inventory.itemInOffHand
                    activeSlot >= 0 && activeSlot < p.inventory.size -> p.inventory.getItem(activeSlot)
                    else -> null
                }

                // 如果激活槽里的东西不是紫电，说明换流派或者下装备了
                if (!isZidianWeapon(activeItem)) {
                    shouldRemove = true
                }

                if (shouldRemove) {
                    // 清除 Buff 和 穿甲标记
                    plugin.buffManager.removeBuff(p, BuffType.SPEED_UP)
                    p.removeMetadata(META_EXTRA_PEN, plugin)
                    // 返回 true 以从 Map 中移除
                    return@removeIf true
                }

                false
            }
        }, 20L, 20L)
    }

    // 辅助检查：判断物品是否绑定了本技能
    private fun isZidianWeapon(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false //虽然 checked hasItemMeta, 但 Kotlin 智能转换有时需要 double check
        val pdc = meta.persistentDataContainer

        // 检查 ATTACK 触发器是否绑定了 ZIDIAN
        val triggerKey = BasicKeys.TRIGGER_KEYS[SkillTrigger.ATTACK]
        if (triggerKey != null) {
            val id = pdc.get(triggerKey, PersistentDataType.STRING)
            return "ZIDIAN" == id
        }
        return false
    }

    // 数据类
    private class ZidianData {
        var stacks = 0
        var lastAttackTime = System.currentTimeMillis()
        var fullStackNotified = false

        fun addStack() {
            if (stacks < 5) {
                stacks++
                fullStackNotified = false // 层数变化，重置通知状态
            }
        }
    }
}