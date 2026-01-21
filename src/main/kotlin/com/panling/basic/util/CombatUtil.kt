package com.panling.basic.util

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.manager.MobManager
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.manager.StatCalculator
import com.panling.basic.skill.strategy.SkillStrategy
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 全职业通用的战斗/技能工具类
 * [重构] 包含了伤害快照、防御计算、散弹保护等核心逻辑
 */
object CombatUtil {

    val NO_PLAYER_DAMAGE_KEY = NamespacedKey(PanlingBasic.instance, "pl_no_player_damage")
    private const val META_THORNS_PROCESSING = "pl_thorns_processing"

    // 散弹/多段伤害保护 (UUID -> LastHitTime)
    private val shotgunProtection = ConcurrentHashMap<UUID, Long>()

    // [核心重构] 使用静态 Map 代替 Metadata 传递瞬时数据
    // 这种方式在同步事件处理中绝对可靠，不会出现读取失败的情况
    private val pendingSkillDamage = HashMap<UUID, Double>()
    private val pendingNoProc = HashSet<UUID>()

    // === 内部类：伤害快照 ===
    class DamageSnapshot {
        var physDamage: Double = 0.0
        var magicDamage: Double = 0.0
        var armorPen: Double = 0.0
        var magicPen: Double = 0.0
        var critRate: Double = 0.0
        var critDmg: Double = 0.0
        var lifeSteal: Double = 0.0
        var isProjectile: Boolean = false

        val totalRaw: Double
            get() = physDamage + magicDamage
    }

    // === 散弹保护逻辑 ===
    fun cleanUpCache() {
        val now = System.currentTimeMillis()
        shotgunProtection.entries.removeIf { (now - it.value) > 2000 }
    }

    fun checkShotgunProtection(victim: LivingEntity): Boolean {
        val now = System.currentTimeMillis()
        val lastHit = shotgunProtection.getOrDefault(victim.uniqueId, 0L)
        // 50ms 阈值
        if (now - lastHit < 50) return true // 应被拦截
        shotgunProtection[victim.uniqueId] = now
        return false
    }

    // === 快照工厂方法 ===

    fun createSnapshot(
        damager: Entity,
        event: EntityDamageByEntityEvent,
        statCalc: StatCalculator,
        mobMgr: MobManager,
        dataMgr: PlayerDataManager
    ): DamageSnapshot {
        val snap = DamageSnapshot()
        val projectile = damager as? Projectile
        snap.isProjectile = (projectile != null)

        var attackerPlayer: Player? = null
        var attackerMob: LivingEntity? = null

        when (damager) {
            is Player -> attackerPlayer = damager
            is LivingEntity -> attackerMob = damager
            is Projectile -> {
                val shooter = damager.shooter
                if (shooter is Player) attackerPlayer = shooter
                else if (shooter is LivingEntity) attackerMob = shooter
            }
        }

        if (attackerPlayer != null) {
            fillPlayerSnapshot(attackerPlayer, event, projectile, snap, statCalc, dataMgr)
        } else if (attackerMob != null) {
            fillMobSnapshot(attackerMob, snap, projectile, mobMgr)
        } else {
            // 普通原版伤害
            snap.physDamage = event.damage
        }

        return snap
    }

    private fun fillPlayerSnapshot(
        player: Player,
        event: EntityDamageByEntityEvent,
        projectile: Entity?,
        snap: DamageSnapshot,
        statCalc: StatCalculator,
        dataMgr: PlayerDataManager
    ) {
        // 1. 魔法伤害优先
        if (player.hasMetadata("pl_casting_skill") || player.hasMetadata("pl_magic_damage")) {
            snap.magicDamage = event.damage
            snap.magicPen = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_MAGIC_PEN)
            snap.critRate = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_RATE)
            snap.critDmg = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_DMG)
            snap.lifeSteal = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_LIFE_STEAL)
        }
        // 2. 物理技能伤害 (如金钟反震、剑阵反击) - 这里的伤害是从 pendingSkillDamage 读取的
        else if (pendingSkillDamage.containsKey(player.uniqueId)) {
            // 直接读取我们存入的伤害值，不再受普攻逻辑干扰
            snap.physDamage = pendingSkillDamage[player.uniqueId] ?: 0.0

            // [Debug] 读取
            // PanlingBasic.getInstance().logger.info("[Debug-Combat] 成功读取缓存伤害: ${snap.physDamage} (原事件伤害: ${event.damage})")

            snap.armorPen = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_ARMOR_PEN)
            snap.critRate = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_RATE)
            snap.critDmg = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_DMG)
            snap.lifeSteal = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_LIFE_STEAL)

            if (player.hasMetadata("pl_extra_pen")) {
                try {
                    snap.armorPen += player.getMetadata("pl_extra_pen")[0].asDouble()
                } catch (ignored: Exception) {}
            }
        }
        // 3. 投射物
        else if (projectile != null) {
            val pdc = projectile.persistentDataContainer
            // getOrDefault 扩展函数假设已在其他地方定义，或者使用 Elvis
            val dmg = pdc.get(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE) ?: 0.0

            if (pdc.has(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING) && !pdc.has(BasicKeys.IS_PHYSICAL_SKILL, PersistentDataType.BYTE)) {
                snap.magicDamage = dmg
                snap.magicPen = pdc.get(BasicKeys.ATTR_EXTRA_PEN, PersistentDataType.DOUBLE) ?: 0.0
            } else {
                snap.physDamage = dmg
                snap.armorPen = pdc.get(BasicKeys.ATTR_EXTRA_PEN, PersistentDataType.DOUBLE) ?: 0.0
            }
            snap.critRate = pdc.get(BasicKeys.ATTR_CRIT_RATE, PersistentDataType.DOUBLE) ?: 0.0
            snap.critDmg = pdc.get(BasicKeys.ATTR_CRIT_DMG, PersistentDataType.DOUBLE) ?: 0.0
            snap.lifeSteal = pdc.get(BasicKeys.ATTR_LIFE_STEAL, PersistentDataType.DOUBLE) ?: 0.0
            snap.armorPen += pdc.get(BasicKeys.ATTR_ARMOR_PEN, PersistentDataType.DOUBLE) ?: 0.0
        }
        // 4. 物理近战
        else {
            val pc = dataMgr.getPlayerClass(player)
            // [Debug]
            // PanlingBasic.getInstance().logger.info("[Debug-Combat] 判定为普攻，进入重算流程")
            if (pc == PlayerClass.WARRIOR && player.inventory.heldItemSlot == dataMgr.getActiveSlot(player)) {
                val phys = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
                snap.physDamage = phys * pc.meleeMultiplier * player.attackCooldown
                snap.armorPen = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_ARMOR_PEN)
            } else {
                snap.physDamage = event.damage
            }
            snap.critRate = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_RATE)
            snap.critDmg = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_CRIT_DMG)
            snap.lifeSteal = statCalc.getPlayerTotalStat(player, BasicKeys.ATTR_LIFE_STEAL)
        }

        // 额外穿透 (Metadata)
        if (player.hasMetadata("pl_extra_pen")) {
            try {
                val extra = player.getMetadata("pl_extra_pen")[0].asDouble()
                if (snap.magicDamage > 0) snap.magicPen += extra else snap.armorPen += extra
            } catch (ignored: Exception) {}
        }
    }

    private fun fillMobSnapshot(mob: LivingEntity, snap: DamageSnapshot, projectile: Entity?, mobMgr: MobManager) {
        if (projectile != null && projectile.persistentDataContainer.has(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE)) {
            val pdc = projectile.persistentDataContainer
            snap.physDamage = pdc.get(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE) ?: 0.0
            snap.armorPen = pdc.get(BasicKeys.ATTR_ARMOR_PEN, PersistentDataType.DOUBLE) ?: 0.0
            snap.critRate = pdc.get(BasicKeys.ATTR_CRIT_RATE, PersistentDataType.DOUBLE) ?: 0.0
            snap.critDmg = pdc.get(BasicKeys.ATTR_CRIT_DMG, PersistentDataType.DOUBLE) ?: 0.0
            snap.lifeSteal = pdc.get(BasicKeys.ATTR_LIFE_STEAL, PersistentDataType.DOUBLE) ?: 0.0
        } else {
            val stats = mobMgr.getMobStats(mob)
            snap.physDamage = stats.damage
            snap.magicDamage = stats.magicDamage
            snap.armorPen = stats.armorPen
            snap.magicPen = stats.magicPen
            snap.critRate = stats.critRate
            snap.critDmg = stats.critDmg
            snap.lifeSteal = stats.lifeSteal
        }
    }

    // === 防御计算 ===
    fun calculateMitigation(victim: LivingEntity, snap: DamageSnapshot, statCalc: StatCalculator, mobMgr: MobManager): Double {
        var finalPhys = snap.physDamage
        var finalMagic = snap.magicDamage

        var def = 0.0
        var mDef = 0.0

        if (victim is Player) {
            def = statCalc.getPlayerTotalStat(victim, BasicKeys.ATTR_DEFENSE)
            mDef = statCalc.getPlayerTotalStat(victim, BasicKeys.ATTR_MAGIC_DEFENSE)
        } else {
            val stats = mobMgr.getMobStats(victim)
            def = stats.physicalDefense
            mDef = stats.magicDefense
        }

        if (finalPhys > 0) {
            val effDef = max(0.0, def - snap.armorPen)
            if (effDef > 0) finalPhys *= (100.0 / (100.0 + effDef))
        }
        if (finalMagic > 0) {
            val effMDef = max(0.0, mDef - snap.magicPen)
            if (effMDef > 0) finalMagic *= (100.0 / (100.0 + effMDef))
        }

        return finalPhys + finalMagic
    }

    // === 反伤逻辑 ===
    fun handleThornsSafe(victim: LivingEntity, attacker: LivingEntity?, def: Double, plugin: PanlingBasic) {
        if (attacker == null) return
        if (attacker.hasMetadata(META_THORNS_PROCESSING)) return // 递归保护

        val thornsDmg = def * 0.2
        if (thornsDmg > 0.5) {
            attacker.setMetadata(META_THORNS_PROCESSING, FixedMetadataValue(plugin, true))
            try {
                attacker.damage(thornsDmg, victim)
            } finally {
                attacker.removeMetadata(META_THORNS_PROCESSING, plugin)
            }
            victim.world.playSound(victim.location, Sound.ENCHANT_THORNS_HIT, 1f, 1.5f)
            victim.world.spawnParticle(Particle.CRIT, victim.eyeLocation, 5, 0.3, 0.3, 0.3, 0.1)
        }
    }

    // === 其他辅助方法 ===

    fun bindStrategy(proj: Projectile?, strategy: SkillStrategy?) {
        if (proj == null || strategy == null) return
        if (!strategy.canHitPlayers) {
            proj.persistentDataContainer.set(NO_PLAYER_DAMAGE_KEY, PersistentDataType.BYTE, 1.toByte())
        }
    }

    fun isNoPlayerDamage(proj: Projectile?): Boolean {
        if (proj == null) return false
        return proj.persistentDataContainer.has(NO_PLAYER_DAMAGE_KEY, PersistentDataType.BYTE)
    }

    fun getNearbyEnemies(caster: Player, rangeX: Double, rangeY: Double, rangeZ: Double): List<LivingEntity> {
        return filterEnemies(caster, caster.getNearbyEntities(rangeX, rangeY, rangeZ))
    }

    fun getNearbyEnemies(caster: Player, range: Double): List<LivingEntity> {
        return getNearbyEnemies(caster, range, range, range)
    }

    fun getEnemiesAround(center: Location, radius: Double, caster: Player): List<LivingEntity> {
        if (center.world == null) return emptyList()
        return filterEnemies(caster, center.world!!.getNearbyEntities(center, radius, radius, radius))
    }

    private fun filterEnemies(caster: Player, entities: Collection<Entity>): List<LivingEntity> {
        val targets = ArrayList<LivingEntity>()
        for (e in entities) {
            if (e !is LivingEntity) continue
            if (e === caster) continue
            if (e is Player) continue // 排除玩家
            if (e.scoreboardTags.contains("pet")) continue
            if (e.isInvulnerable || !e.isValid || e.isDead) continue
            targets.add(e)
        }
        return targets
    }

    /**
     * [通用] 判定是否为合法的技能锁定目标
     * 整合了：存活检测、无敌检测、宠物过滤、阵营判定(MobManager)
     */
    fun isValidTarget(attacker: Player, target: LivingEntity?): Boolean {
        // 1. 基础存在性检查
        if (target == null || !target.isValid || target.isDead) return false
        if (target === attacker) return false

        // 2. 排除非怪物实体
        if (target is Player) return false
        // if (target is ArmorStand) return false;

        // 3. 状态检查
        if (target.isInvulnerable) return false
        if (target.scoreboardTags.contains("pet")) return false
        if (target.scoreboardTags.contains("shop")) return false

        // 4. 阵营/队伍检查 (核心)
        if (!PanlingBasic.instance.mobManager.canAttack(attacker, target)) return false

        return true
    }

    /**
     * [重构] 造成物理技能伤害
     * @param damage 真实伤害数值
     * @param triggerPassives 是否允许触发攻击被动 (false = 禁止套娃)
     */
    fun dealPhysicalSkillDamage(attacker: Player?, victim: LivingEntity?, damage: Double, triggerPassives: Boolean = true) {
        if (attacker == null || victim == null) return
        victim.noDamageTicks = 0

        val id = attacker.uniqueId

        // [Debug]
        // PanlingBasic.getInstance().logger.info("[Debug-Combat] 存入伤害: Player=${attacker.name}, Dmg=$damage, Proc=$triggerPassives")

        // 1. 将数据压入静态内存
        pendingSkillDamage[id] = damage
        if (!triggerPassives) {
            pendingNoProc.add(id)
        }

        try {
            // 2. 发起伤害 -> 立即触发 PlayerCombatListener
            victim.damage(damage, attacker)
        } finally {
            // [Debug]
            // PanlingBasic.getInstance().logger.info("[Debug-Combat] 清理伤害缓存: ${attacker.name}")
            // 3. 无论成功失败，立即清理内存，防止污染下一次攻击
            pendingSkillDamage.remove(id)
            pendingNoProc.remove(id)
        }
    }

    fun dealSkillDamage(attacker: Player?, victim: LivingEntity?, damage: Double, isMagic: Boolean) {
        if (attacker == null || victim == null) return
        victim.noDamageTicks = 0
        val plugin = PanlingBasic.instance
        if (isMagic) attacker.setMetadata("pl_magic_damage", FixedMetadataValue(plugin, true))
        try {
            victim.damage(damage, attacker)
        } finally {
            if (isMagic) attacker.removeMetadata("pl_magic_damage", plugin)
        }
    }

    // === 辅助查询方法 (供 Listener 使用) ===
    fun isSkillDamage(player: Player): Boolean {
        return pendingSkillDamage.containsKey(player.uniqueId)
                || player.hasMetadata("pl_magic_damage")
                || player.hasMetadata("pl_casting_skill")
    }

    fun isNoProc(player: Player): Boolean {
        return pendingNoProc.contains(player.uniqueId)
    }
}