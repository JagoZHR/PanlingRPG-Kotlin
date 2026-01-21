package com.panling.basic.skill.impl.mage.baseArtifact

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector

class MageBaseArtifactAttackSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_BASE_ARTIFACT_ATTACK", "镇压", PlayerClass.MAGE) {

    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        // === 注册策略 (数值随稀有度提升) ===
        // 参数顺序: (最大目标数, 伤害倍率, 范围半径)

        // 1. 杀伐模式 (OFFENSE): 群攻
        registerStrategy("OFFENSE_BROKEN_FABAO",   ArtifactAttackStrategy(2, 0.25, 4.0))
        registerStrategy("OFFENSE_COMMON_FABAO",   ArtifactAttackStrategy(3, 0.30, 5.0))
        registerStrategy("OFFENSE_UNCOMMON_FABAO", ArtifactAttackStrategy(3, 0.35, 6.0))
        registerStrategy("OFFENSE_RARE_FABAO",     ArtifactAttackStrategy(4, 0.40, 7.0))
        registerStrategy("OFFENSE_EPIC_FABAO",     ArtifactAttackStrategy(5, 0.50, 8.0))

        // 2. 生息模式 (SUPPORT): 单体锁定
        registerStrategy("SUPPORT_BROKEN_FABAO",   ArtifactAttackStrategy(1, 0.25, 4.0))
        registerStrategy("SUPPORT_COMMON_FABAO",   ArtifactAttackStrategy(1, 0.30, 5.0))
        registerStrategy("SUPPORT_UNCOMMON_FABAO", ArtifactAttackStrategy(1, 0.35, 6.0))
        registerStrategy("SUPPORT_RARE_FABAO",     ArtifactAttackStrategy(1, 0.40, 7.0))
        registerStrategy("SUPPORT_EPIC_FABAO",     ArtifactAttackStrategy(1, 0.50, 8.0))
    }

    private fun registerStrategy(key: String, strategy: MageSkillStrategy) {
        strategies[key] = strategy
    }

    private fun getCurrentStrategy(p: Player): MageSkillStrategy? {
        val stance = PanlingBasic.instance.playerDataManager.getArrayStance(p)
        val weapon = p.inventory.itemInMainHand
        if (!weapon.hasItemMeta()) return null

        val type = weapon.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
            ?: return null

        val rarity = MageUtil.getWeaponRarity(weapon)
        val key = "${stance.name}_${rarity}_${type}"
        return strategies[key]
    }

    override fun getCastTime(player: Player): Double {
        val s = getCurrentStrategy(player)
        return s?.castTime ?: 0.0
    }

    override fun onCast(ctx: SkillContext): Boolean {
        val s = getCurrentStrategy(ctx.player) ?: return false
        return s.cast(ctx)
    }

    // === 内部策略实现 ===
    private class ArtifactAttackStrategy(
        private val maxTargets: Int,
        private val damageRatio: Double,
        private val range: Double
    ) : MageSkillStrategy {

        // 扇形判定角度限制 (60度半角 = 120度扇形)
        private val ANGLE_LIMIT = Math.toRadians(60.0)
        // [NEW] 高度限制 (上下各3格)
        private val HEIGHT_LIMIT = 3.0

        override val castTime: Double = 0.0

        override fun cast(ctx: SkillContext): Boolean {
            val p = ctx.player
            val damage = ctx.power * damageRatio

            // 获取玩家视线方向 (忽略Y轴，变为水平方向)
            val playerDir = p.location.direction.apply {
                setY(0)
                normalize()
            }

            // 使用 Kotlin 集合操作筛选和排序
            val targets = p.getNearbyEntities(range, range, range)
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { it != p }
                .filter { it !is Player } // 排除玩家 (保持原逻辑)
                .filter { !it.scoreboardTags.contains("pet") }
                .filter { le ->
                    // [核心修改 1] 高度判定
                    Math.abs(le.location.y - p.location.y) <= HEIGHT_LIMIT
                }
                .filter { le ->
                    // [核心修改 2] 扇形判定逻辑
                    val toTarget = le.location.toVector().subtract(p.location.toVector())
                    toTarget.setY(0) // 忽略Y轴进行角度计算

                    // 距离判定 (NearbyEntities 是方形，这里修正为圆形)
                    if (toTarget.lengthSquared() > range * range) return@filter false

                    // 角度判定 (如果在扇形外则跳过)
                    if (toTarget.lengthSquared() > 0.01 && toTarget.normalize().angle(playerDir) > ANGLE_LIMIT) {
                        return@filter false
                    }
                    true
                }
                .sortedBy { it.location.distanceSquared(p.location) }
                .take(maxTargets)
                .toList()

            var count = 0
            for (target in targets) {
                MageUtil.dealSkillDamage(p, target, damage, true)

                target.world.spawnParticle(Particle.CRIT, target.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.1)
                spawnLinkParticle(p, target)
                count++
            }

            if (count > 0) {
                p.playSound(p.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.5f)
                return true
            }
            return false
        }

        private fun spawnLinkParticle(from: LivingEntity, to: LivingEntity) {
            val start = from.location.add(0.0, 1.0, 0.0)
            val end = to.location.add(0.0, 1.0, 0.0)
            val dir = end.toVector().subtract(start.toVector())
            val dist = start.distance(end)

            if (dist > 0.1) {
                dir.normalize()
                // 步进循环
                var d = 0.0
                while (d <= dist) {
                    from.world.spawnParticle(
                        Particle.CRIT,
                        start.clone().add(dir.clone().multiply(d)),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                    d += 0.5
                }
            }
        }
    }
}