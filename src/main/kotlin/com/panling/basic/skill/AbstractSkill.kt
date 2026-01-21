package com.panling.basic.skill

import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.manager.CooldownManager
import com.panling.basic.manager.PlayerDataManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.Random

abstract class AbstractSkill(
    val id: String,
    val name: String,
    // [核心新增] Getter，让外部能获取该技能要求的职业 -> 直接转为属性
    val requiredClass: PlayerClass
) {

    protected val random = Random()

    // Kotlin property backing field
    protected open var castTime: Double = 0.0

    // 为了保持 API 兼容性保留此方法签名，虽然参数 player 目前未被使用
    open fun getCastTime(player: Player): Double {
        return this.castTime
    }

    // [核心新增] 统一检查方法 (便捷入口)
    fun checkClassRestriction(player: Player, dataManager: PlayerDataManager): Boolean {
        // 如果技能没要求职业 (NONE)，或者玩家职业匹配，则通过
        return requiredClass == PlayerClass.NONE || dataManager.getPlayerClass(player) == requiredClass
    }

    fun execute(ctx: SkillContext, cdManager: CooldownManager, dataManager: PlayerDataManager): Boolean {
        val p = ctx.player

        if (requiredClass != PlayerClass.NONE && dataManager.getPlayerClass(p) != requiredClass) return false

        if (p.hasMetadata("pl_casting_skill")) {
            p.sendActionBar(Component.text("§c正在施法中...").color(NamedTextColor.RED))
            return false
        }

        if (ctx.triggerType.isCheckRequired) {
            val cdKey = getCooldownKey(ctx)
            val remaining = cdManager.getRemainingCooldown(p, cdKey)
            if (remaining > 0) {
                p.sendActionBar(Component.text("§c冷却中... ${"%.1f".format(remaining / 1000.0)}s"))
                return false
            }
            if (!checkCost(ctx, dataManager)) return false
        }

        if (getCastTime(p) > 0) {
            startCasting(ctx, cdManager, dataManager)
            return true
        } else {
            return doCast(ctx, cdManager, dataManager)
        }
    }

    private fun startCasting(ctx: SkillContext, cdManager: CooldownManager, dataManager: PlayerDataManager) {
        val p = ctx.player
        val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
        p.setMetadata("pl_casting_skill", FixedMetadataValue(plugin, true))

        val ticks = (getCastTime(p) * 20).toLong()
        val startLoc = p.location

        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                // 中断判定
                if (!p.isOnline || p.isDead) {
                    interrupt("§c施法中断")
                    return
                }
                if (!p.hasMetadata("pl_casting_skill")) {
                    this.cancel()
                    return
                }
                if (p.location.distanceSquared(startLoc) > 0.01) {
                    interrupt("§c移动打断施法！")
                    return
                }

                if (tick < ticks) {
                    p.world.spawnParticle(Particle.CRIT, p.location.add(0.0, 1.0, 0.0), 2, 0.3, 0.5, 0.3, 0.0)

                    val progress = tick / 2
                    val total = (ticks / 2).toInt()
                    // 构造进度条字符串
                    val bar = "§e[" + "=".repeat(progress) + ".".repeat((total - progress).coerceAtLeast(0)) + "]"

                    p.sendActionBar(Component.text("吟唱中 $bar"))
                    tick += 2
                } else {
                    p.removeMetadata("pl_casting_skill", plugin)
                    doCast(ctx, cdManager, dataManager)
                    this.cancel()
                }
            }

            private fun interrupt(reason: String) {
                p.removeMetadata("pl_casting_skill", plugin)
                p.sendActionBar(Component.text(reason).color(NamedTextColor.RED))
                p.playSound(p.location, Sound.BLOCK_GLASS_BREAK, 1f, 0.5f)
                this.cancel()
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun doCast(ctx: SkillContext, cdManager: CooldownManager, dataManager: PlayerDataManager): Boolean {
        if (onCast(ctx)) {
            if (ctx.triggerType.isCheckRequired) {
                payCost(ctx, dataManager)
                val cdKey = getCooldownKey(ctx)
                val cdMillis = calculateCooldown(ctx, dataManager)
                cdManager.setCooldown(ctx.player, cdKey, cdMillis)
                ctx.player.sendActionBar(Component.text("§a[战斗] 释放技能: $name").color(NamedTextColor.GREEN))
            }
            return true
        }
        return false
    }

    protected abstract fun onCast(ctx: SkillContext): Boolean

    // [新增] 蓄力/下一次攻击触发钩子
    // 默认实现为空
    open fun onNextAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {}

    // [核心修改] 重写获取冷却 Key 的逻辑
    open fun getCooldownKey(ctx: SkillContext): String {
        val trigger = ctx.triggerType

        // 判断是否为"主动动作"
        // 对于这些动作，我们希望所有使用该动作的技能共享同一个 CD
        return when (trigger) {
            SkillTrigger.RIGHT_CLICK,
            SkillTrigger.SHIFT_RIGHT,
            SkillTrigger.LEFT_CLICK,
            SkillTrigger.SHIFT_LEFT,
            SkillTrigger.SWAP_HAND,
            SkillTrigger.DROP,
            SkillTrigger.BLOCK -> {
                val p = ctx.player

                // 1. 获取当前主手槽位 (0-8)
                var slot = p.inventory.heldItemSlot

                // 2. [优化] 尝试检测是否为副手 (Slot 40)
                // 如果 Context 里的源物品存在，且它不像主手物品，但像副手物品，则判定为副手触发
                ctx.sourceItem?.let { source ->
                    val mainHand = p.inventory.itemInMainHand
                    val offHand = p.inventory.itemInOffHand

                    // 简单的判定逻辑：如果源物品和主手不一样，但和副手一样，那就是副手
                    if (!source.isSimilar(mainHand) && source.isSimilar(offHand)) {
                        slot = 40
                    }
                }

                // 3. 生成独立 Key
                "ACTION_${trigger.name}_SLOT_$slot"
            }
            // 对于被动技能 (ATTACK, HIT, PASSIVE, DAMAGED, PROJECTILE_HIT)
            // 依然使用技能 ID，互不影响
            else -> this.id
        }
    }

    open fun calculateCooldown(ctx: SkillContext, dataManager: PlayerDataManager): Long {
        var base = 2000L

        ctx.sourceItem?.itemMeta?.persistentDataContainer?.let { pdc ->
            // 1. 尝试读取该触发器特定的冷却
            val specificKey = BasicKeys.TRIGGER_COOLDOWN_KEYS[ctx.triggerType]
            val specificCd = if (specificKey != null) pdc.get(specificKey, PersistentDataType.LONG) else null

            if (specificCd != null) {
                base = specificCd
            } else {
                // 2. 回退到全局冷却
                val globalCd = pdc.get(BasicKeys.FEATURE_COOLDOWN, PersistentDataType.LONG)
                if (globalCd != null) base = globalCd
            }
        }

        val cdr = dataManager.getCachedStat(ctx.player, BasicKeys.ATTR_CDR)?:0.0
        // 使用 coerceAtMost 替代 Math.min
        return (base * (1.0 - cdr.coerceAtMost(0.8))).toLong()
    }

    protected fun checkCost(ctx: SkillContext, dataManager: PlayerDataManager): Boolean {
        // 使用 Kotlin 的安全调用链简化 null 检查
        val pdc = ctx.sourceItem?.itemMeta?.persistentDataContainer ?: return true

        val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)

        // [NEW] 1. 尝试读取独立消耗
        val costKey = BasicKeys.TRIGGER_COST_KEYS[ctx.triggerType]
        val skillCost = if (costKey != null && pdc.has(costKey, PersistentDataType.INTEGER)) {
            pdc.get(costKey, PersistentDataType.INTEGER)!!
        } else {
            // 回退到全局消耗
            pdc.getOrDefault(BasicKeys.ITEM_SKILL_COST, PersistentDataType.INTEGER, 0)
        }

        val elementVal = pdc.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0)
        val player = ctx.player

        if ("ELEMENT" == type) {
            if (elementVal <= 0) return true
            // 注意：这里保留了 sourceItem().amount 的检查逻辑
            if (dataManager.getElementPoints(player) < elementVal && (ctx.sourceItem?.amount ?: 0) < 1) {
                player.sendActionBar(Component.text("§c[提示] 灵力/媒介不足！").color(NamedTextColor.RED))
                return false
            }
        } else if (skillCost > 0) {
            val currentPoints = dataManager.getElementPoints(player)
            if (currentPoints < skillCost) {
                player.sendActionBar(Component.text("§c[提示] 灵力不足 ($currentPoints/$skillCost)").color(NamedTextColor.RED))
                return false
            }
        }
        return true
    }

    protected fun payCost(ctx: SkillContext, dataManager: PlayerDataManager) {
        val sourceItem = ctx.sourceItem ?: return
        val pdc = sourceItem.itemMeta?.persistentDataContainer ?: return

        val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)

        // [NEW] 1. 尝试读取独立消耗
        val costKey = BasicKeys.TRIGGER_COST_KEYS[ctx.triggerType]
        val skillCost = if (costKey != null && pdc.has(costKey, PersistentDataType.INTEGER)) {
            pdc.get(costKey, PersistentDataType.INTEGER)!!
        } else {
            pdc.getOrDefault(BasicKeys.ITEM_SKILL_COST, PersistentDataType.INTEGER, 0)
        }

        val elementVal = pdc.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0)

        val conserveRate = dataManager.getCachedStat(ctx.player, BasicKeys.ATTR_NO_CONSUME) ?: 0.0
        val isConserved = (conserveRate > 0) && (random.nextDouble() < conserveRate)

        if ("ELEMENT" == type) {
            if (elementVal <= 0) return
            if (dataManager.getElementPoints(ctx.player) >= elementVal) {
                if (!isConserved) {
                    dataManager.takeElementPoints(ctx.player, elementVal.toLong())
                } else {
                    ctx.player.sendActionBar(Component.text("§a[灵力] 元素保留生效！").color(NamedTextColor.GREEN))
                }
            } else {
                if (!isConserved) {
                    sourceItem.subtract(1)
                } else {
                    ctx.player.sendActionBar(Component.text("§a[灵力] 元素保留生效！").color(NamedTextColor.GREEN))
                }
            }
        } else if (skillCost > 0) {
            if (!isConserved) {
                dataManager.takeElementPoints(ctx.player, skillCost.toLong())
                ctx.player.sendActionBar(Component.text("§b[法宝] 消耗灵力: $skillCost").color(NamedTextColor.AQUA))
            } else {
                ctx.player.sendActionBar(Component.text("§a[法宝] 灵力保留生效！").color(NamedTextColor.GREEN))
            }
        }
    }

    open fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {}
    open fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {}
    // [NEW] 受击时钩子
    // 当技能通过 DAMAGED 触发器触发时，SkillManager 会调用此方法
    open fun onDamaged(event: EntityDamageByEntityEvent, ctx: SkillContext) {}
}