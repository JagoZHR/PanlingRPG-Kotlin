package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.manager.*
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import com.panling.basic.skill.strategy.metal.MetalAttackT5FurnaceStrategy
import com.panling.basic.util.CombatUtil
import com.panling.basic.util.MageUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class PlayerCombatListener(
    private val plugin: PanlingBasic,
    private val dataManager: PlayerDataManager,
    private val statCalculator: StatCalculator,
    private val skillManager: SkillManager,
    private val mobManager: MobManager,
    private val subClassManager: SubClassManager,
    private val buffManager: BuffManager
) : Listener {

    private val skillArrowKey = NamespacedKey(plugin, "skill_arrow_id")
    private val random = Random()

    companion object {
        // [NEW] 元数据 Key 常量化
        private const val META_AGGRO_OWNER = "pl_aggro_owner"
        private const val META_DAMAGE_HOOK = "pl_skill_damage_hook"
        private const val META_SHOOT_LOCK = "pl_shoot_skill_lock"
        private const val META_THORNS_PROCESSING = "pl_thorns_processing"
        private const val META_CASTING_SKILL = "pl_casting_skill"
        private const val META_PASSIVE_DAMAGE = "pl_passive_damage"
    }

    init {
        // [优化] 散弹保护清理任务
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            CombatUtil.cleanUpCache()
        }, 200L, 200L)
    }

    // ==================================================
    // 1. 交互与射击
    // ==================================================

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerSwing(event: PlayerAnimationEvent) {
        if (event.animationType == PlayerAnimationType.ARM_SWING) {
            val p = event.player
            // 只有法师触发剑阵
            if (dataManager.getPlayerClass(p) == PlayerClass.MAGE) {
                // 假设这是静态方法
                try {
                    MetalAttackT5FurnaceStrategy.tryFireSword(p, PanlingBasic.instance)
                } catch (ignored: Throwable) {}
            }
        }
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        if (event.hand != EquipmentSlot.HAND) return

        val item = event.item
        val isBow = item != null && item.type.name.contains("BOW")

        // 1. [关键修正] 先计算 trigger
        val trigger = getTrigger(event.action, event.player.isSneaking)

        // 2. 智能过滤弓弩：如果是右键/蹲右键，跳过 (交由 onShoot 处理)
        if (isBow && (trigger == SkillTrigger.RIGHT_CLICK || trigger == SkillTrigger.SHIFT_RIGHT)) {
            return
        }

        processActiveTrigger(event.player, item, trigger, event, null)
    }

    @EventHandler
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val arrow = event.projectile as? AbstractArrow ?: return

        // 校验武器位
        val activeSlot = dataManager.getActiveSlot(player)
        val isOffHand = (event.hand == EquipmentSlot.OFF_HAND)
        val currentSlot = player.inventory.heldItemSlot

        if (!((isOffHand && activeSlot == 40) || (!isOffHand && activeSlot == currentSlot))) {
            event.isCancelled = true
            player.sendActionBar(Component.text("§c请使用激活位的武器！").color(NamedTextColor.RED))
            return
        }

        // =========================================================
        // [核心修复] 技能触发防抖锁
        // =========================================================
        if (!player.hasMetadata(META_SHOOT_LOCK)) {
            // 1. 动态判断触发类型
            val trigger = if (player.isSneaking) SkillTrigger.SHIFT_RIGHT else SkillTrigger.RIGHT_CLICK

            // 2. 触发技能
            processActiveTrigger(player, event.bow, trigger, event, arrow)

            // 3. 上锁 (1 tick 后自动解锁)
            player.setMetadata(META_SHOOT_LOCK, FixedMetadataValue(plugin, true))
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) player.removeMetadata(META_SHOOT_LOCK, plugin)
            })
        }

        subClassManager.getStrategy(player)?.onShoot(player, arrow, event.force)

        // =========================================================
        // [NEW] 3. 处理物品被动技能 (Item Passive Strategy)
        // =========================================================
        val bow = event.bow
        if (bow != null && bow.hasItemMeta()) {
            val pdc = bow.itemMeta!!.persistentDataContainer
            val passiveKey = BasicKeys.TRIGGER_KEYS[SkillTrigger.PASSIVE]

            if (passiveKey != null && pdc.has(passiveKey, PersistentDataType.STRING)) {
                val passiveSkillId = pdc.get(passiveKey, PersistentDataType.STRING)?:return
                val skill = skillManager.getSkill(passiveSkillId)

                // 检查 ArcherSkillStrategy 接口
                if (skill is ArcherSkillStrategy) {
                    // [核心修复] 职业检查
                    if (skill.checkClassRestriction(player, dataManager)) {
                        val ctx = SkillContext(
                            player, null, bow, arrow, player.location, 0.0, SkillTrigger.PASSIVE
                        )
                        if (skillManager.tryApplyCooldown(skill, ctx)) {
                            skill.onShoot(player, arrow, event)
                        }
                    }
                }
            }
        }

        // 计算属性
        val pc = dataManager.getPlayerClass(player)
        val phys = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val baseDmg = phys * pc.rangeMultiplier
        val speedMult = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_ARROW_VELOCITY)

        if (speedMult > 0) {
            arrow.velocity = arrow.velocity.multiply(speedMult)
        }

        // 基础伤害计算
        var finalDmg = 0.0
        if (baseDmg > 0) {
            val velocity = max(0.1, arrow.velocity.length())
            finalDmg = ceil(baseDmg * velocity)

            if (event.force >= 0.9 || arrow.isCritical) {
                arrow.isCritical = true
                finalDmg += random.nextInt((baseDmg * 0.5).toInt() + 1)
            }

            // [NEW] 通用伤害倍率接口 (读取 NBT)
            val arrowPdc = arrow.persistentDataContainer
            val multKey = NamespacedKey(plugin, "pl_arrow_dmg_mult")
            if (arrowPdc.has(multKey, PersistentDataType.DOUBLE)) {
                val mult = arrowPdc.get(multKey, PersistentDataType.DOUBLE) ?: 1.0
                finalDmg *= mult
            }
        }

        // 存入 PDC
        val pdc = arrow.persistentDataContainer
        pdc.set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, finalDmg)
        copyStatsToArrow(player, pdc)
    }

    // ==================================================
    // 2. 投射物命中
    // ==================================================

    @EventHandler(priority = EventPriority.LOWEST)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity as? Projectile ?: return

        // [修改] 通用检查：是否禁止击中玩家
        if (event.hitEntity is Player && CombatUtil.isNoPlayerDamage(projectile)) {
            event.isCancelled = true
            projectile.remove()
            return
        }

        // 1. 阵营拦截
        val victim = event.hitEntity as? LivingEntity
        val shooter = projectile.shooter as? Player

        if (victim != null && shooter != null) {
            if (!mobManager.canAttack(shooter, victim)) {
                event.isCancelled = true
                return
            }
        }

        // 2. 技能回调 (Core)
        val pdc = projectile.persistentDataContainer
        if (pdc.has(skillArrowKey, PersistentDataType.STRING)) {
            val skillId = pdc.get(skillArrowKey, PersistentDataType.STRING)?:return
            val skill = skillManager.getSkill(skillId)

            if (shooter != null) {
                val target = event.hitEntity as? LivingEntity
                val ctx = SkillContext(
                    shooter, target, null, projectile, projectile.location, 0.0, SkillTrigger.PROJECTILE_HIT
                )

                if (skill != null) skill.onProjectileHit(event, ctx)
                else skillManager.castSkill(skillId, ctx) // 兼容旧逻辑

                projectile.remove()
            }
        }

        // 3. 移除无敌帧
        if (victim != null && pdc.has(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE)) {
            victim.noDamageTicks = 0
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.isCancelled) return

        // [Recursion Guard] 防止反伤无限循环
        if (event.damager.hasMetadata(META_THORNS_PROCESSING)) {
            return
        }

        // [打断施法]
        val victim = event.entity as? LivingEntity ?: return
        if (victim is Player && victim.hasMetadata(META_CASTING_SKILL)) {
            // 这里为了获取 Plugin 实例比较麻烦，直接用 plugin 变量
            victim.removeMetadata(META_CASTING_SKILL, plugin)
            victim.sendActionBar(Component.text("§c受击！施法被打断！").color(NamedTextColor.RED))
            victim.playSound(victim.location, Sound.BLOCK_GLASS_BREAK, 1f, 0.5f)
        }

        val damagerEntity = event.damager
        var attackerPlayer: Player? = null
        var attackerMob: LivingEntity? = null
        var projectile: Projectile? = null

        // 解析攻击源
        when (damagerEntity) {
            is Player -> attackerPlayer = damagerEntity
            is LivingEntity -> attackerMob = damagerEntity
            is Projectile -> {
                projectile = damagerEntity
                // [修改] 二次拦截
                if (victim is Player && CombatUtil.isNoPlayerDamage(projectile)) {
                    event.isCancelled = true
                    return
                }

                val shooter = projectile.shooter
                if (shooter is Player) attackerPlayer = shooter
                else if (shooter is LivingEntity) attackerMob = shooter
            }
        }

        // 阵营检查
        if (attackerPlayer != null && !mobManager.canAttack(attackerPlayer, victim)) {
            event.isCancelled = true
            projectile?.remove()
            return
        }

        // === [优化] 散弹保护 (委托 CombatUtil) ===
        val isSkillDamage = (attackerPlayer != null) && CombatUtil.isSkillDamage(attackerPlayer)

        if (!isSkillDamage && CombatUtil.checkShotgunProtection(victim)) {
            event.isCancelled = true
            return
        }

        // 怪物触发主动技能
        if (attackerMob != null) {
            mobManager.triggerSkills(attackerMob, SkillTrigger.ATTACK, victim)
        }

        // === [阶段 1] 构建伤害快照 - 委托 CombatUtil ===
        val snapshot = CombatUtil.createSnapshot(damagerEntity, event, statCalculator, mobManager, dataManager)

        // 基础伤害为0则退出
        if (snapshot.totalRaw <= 0) {
            event.damage = 0.0
            return
        }

        // === [阶段 2] 暴击计算 ===
        if (snapshot.critRate > 0 && random.nextDouble() < snapshot.critRate) {
            val multiplier = 1.5 + snapshot.critDmg
            snapshot.physDamage *= multiplier
            snapshot.magicDamage *= multiplier

            victim.world.spawnParticle(Particle.CRIT, victim.location.add(0.0, 1.5, 0.0), 10)
            victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f)
        }

        // === [阶段 3] 防御减免 - 委托 CombatUtil ===
        var finalDamage = CombatUtil.calculateMitigation(victim, snapshot, statCalculator, mobManager)

        // === [阶段 4] 特殊效果处理 ===

        // 盾牌格挡
        if (victim is Player && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) != 0.0) {
            val mainHand = victim.inventory.itemInMainHand
            val offHand = victim.inventory.itemInOffHand
            val shield = if (mainHand.type == Material.SHIELD) mainHand else offHand

            if (shield.type == Material.SHIELD) {
                processActiveTrigger(victim, shield, SkillTrigger.BLOCK, event, null)
            }
        }

        // 2. 元素增益与反应
        if (attackerPlayer != null) {
            finalDamage += PanlingBasic.instance.elementalManager.applyAttackBuffs(attackerPlayer, victim)

            if (projectile != null) {
                PanlingBasic.instance.elementalManager.handleElementHit(attackerPlayer, victim, projectile, finalDamage)
            }
        }

        // 3. 触发防御特效
        val effectiveAttacker = attackerPlayer ?: attackerMob
        PanlingBasic.instance.elementalManager.handleDefenseTriggers(victim, effectiveAttacker, finalDamage)

        // 触发受击者技能 (怪物)
        if (victim !is Player) {
            mobManager.triggerSkills(victim, SkillTrigger.DAMAGED, effectiveAttacker)
        }

        // === [阶段 5] 应用伤害 & 吸血 ===
        event.damage = finalDamage

        val noProc = (attackerPlayer != null) && CombatUtil.isNoProc(attackerPlayer)

        if (attackerPlayer != null && !isSkillDamage && !noProc) {
            skillManager.triggerCharges(attackerPlayer, event)
            skillManager.triggerItemSkill(attackerPlayer, SkillTrigger.ATTACK, event)
            finalDamage = event.damage // 更新伤害
        }

        // 被动触发 & 吸血 & 仇恨锁定
        if (finalDamage > 0 && attackerPlayer != null) {
            // 1. 攻击被动
            if (!noProc && !attackerPlayer.hasMetadata(META_PASSIVE_DAMAGE)) {
                val power = statCalculator.getPlayerTotalStat(attackerPlayer, BasicKeys.ATTR_PHYSICAL_DAMAGE)
                val ctx = SkillContext(
                    attackerPlayer, victim, null, projectile, victim.location, power, SkillTrigger.PASSIVE
                )
                skillManager.handlePassives(attackerPlayer, PlayerDataManager.PassiveTrigger.ATTACK, ctx)
            }

            // 2. 吸血
            if (snapshot.lifeSteal > 0) {
                var heal = finalDamage * snapshot.lifeSteal
                heal = max(1.0, heal)
                // 严格保留 Attribute.MAX_HEALTH
                val maxHealth = attackerPlayer.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                attackerPlayer.health = min(maxHealth, attackerPlayer.health + heal)
            }

            // 3. 流派攻击特效
            subClassManager.getStrategy(attackerPlayer)?.onAttack(
                attackerPlayer, victim, dataManager.getSlotHoldDuration(attackerPlayer)
            )
        }

        if (finalDamage > 0 && victim is Player) {
            // 触发受击技能 (装备上的 DAMAGED 触发器)
            skillManager.triggerItemSkill(victim, SkillTrigger.DAMAGED, event)

            // 受击钩子 (Active Skill Hook)
            if (victim.hasMetadata(META_DAMAGE_HOOK)) {
                try {
                    val skillId = victim.getMetadata(META_DAMAGE_HOOK)[0].asString()
                    val skill = skillManager.getSkill(skillId)
                    if (skill != null) {
                        val ctx = SkillContext(
                            victim, effectiveAttacker, null, projectile, victim.location, 0.0, SkillTrigger.DAMAGED
                        )
                        skill.onDamaged(event, ctx)
                    }
                } catch (ignored: Exception) {}
            }

            // 触发受击被动
            val def = statCalculator.getPlayerTotalStat(victim, BasicKeys.ATTR_DEFENSE)
            val ctx = SkillContext(
                victim, effectiveAttacker, null, projectile, victim.location, def, SkillTrigger.PASSIVE
            )
            skillManager.handlePassives(victim, PlayerDataManager.PassiveTrigger.HIT, ctx)
        }
    }

    // ==================================================
    // 仇恨锁定逻辑
    // ==================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobTarget(event: EntityTargetEvent) {
        val mob = event.entity as? Mob ?: return
        if (!mob.hasMetadata(META_AGGRO_OWNER)) return

        val uuidStr = mob.getMetadata(META_AGGRO_OWNER)[0].asString()
        val owner = try {
            org.bukkit.Bukkit.getPlayer(UUID.fromString(uuidStr))
        } catch (e: Exception) { null } ?: return

        // 委托给流派策略
        subClassManager.getStrategy(owner)?.onMobTarget(owner, mob, event)
    }

    // === 辅助方法 ===

    private fun processActiveTrigger(
        player: Player,
        item: ItemStack?,
        trigger: SkillTrigger,
        eventHandle: Any,
        projectile: AbstractArrow?
    ) {
        if (item == null || trigger == SkillTrigger.NONE) return
        val key = BasicKeys.TRIGGER_KEYS[trigger] ?: return

        val pdc = item.itemMeta?.persistentDataContainer ?: return
        if (!pdc.has(key, PersistentDataType.STRING)) return

        // 状态校验
        val status = statCalculator.getValidationStatus(
            player, item, player.inventory.heldItemSlot,
            dataManager.getActiveSlot(player), dataManager.getPlayerClass(player)
        )
        if (status != StatCalculator.STATUS_ACTIVE && status != StatCalculator.STATUS_FABAO_ACTIVE) return

        val skillId = pdc.get(key, PersistentDataType.STRING) ?: return
        val power = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_SKILL_DAMAGE)

        val ctx = SkillContext(
            player, null, item, projectile, player.location, power, trigger
        )

        if (skillManager.castSkill(skillId, ctx)) {
            if (eventHandle is PlayerInteractEvent) eventHandle.isCancelled = true
        }
    }

    private fun getTrigger(action: Action, isSneaking: Boolean): SkillTrigger {
        if (action.name.contains("RIGHT")) return if (isSneaking) SkillTrigger.SHIFT_RIGHT else SkillTrigger.RIGHT_CLICK
        if (action.name.contains("LEFT")) return if (isSneaking) SkillTrigger.SHIFT_LEFT else SkillTrigger.LEFT_CLICK
        return SkillTrigger.NONE
    }

    private fun copyStatsToArrow(player: Player, pdc: PersistentDataContainer) {
        pdc.set(BasicKeys.ATTR_CRIT_RATE, PersistentDataType.DOUBLE, statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_RATE))
        pdc.set(BasicKeys.ATTR_CRIT_DMG, PersistentDataType.DOUBLE, statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_DMG))
        pdc.set(BasicKeys.ATTR_ARMOR_PEN, PersistentDataType.DOUBLE, statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_ARMOR_PEN))
        pdc.set(BasicKeys.ATTR_LIFE_STEAL, PersistentDataType.DOUBLE, statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_LIFE_STEAL))
    }
}