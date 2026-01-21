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
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import kotlin.math.min

class MageBaseArtifactHealSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_BASE_ARTIFACT_HEAL", "回春", PlayerClass.MAGE) {

    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        // === 注册策略 (数值随稀有度提升) ===
        // 参数顺序: (最大目标数, 是否仅自己, 治疗倍率, 范围半径)

        // 1. 生息模式 (SUPPORT): 群体治疗
        registerStrategy("SUPPORT_BROKEN_FABAO",   ArtifactHealStrategy(2, false, 0.15, 4.0)) // 15%倍率
        registerStrategy("SUPPORT_COMMON_FABAO",   ArtifactHealStrategy(3, false, 0.20, 5.0)) // 20%倍率
        registerStrategy("SUPPORT_UNCOMMON_FABAO", ArtifactHealStrategy(3, false, 0.25, 6.0)) // 25%倍率
        registerStrategy("SUPPORT_RARE_FABAO",     ArtifactHealStrategy(4, false, 0.30, 7.0)) // 30%倍率
        registerStrategy("SUPPORT_EPIC_FABAO",     ArtifactHealStrategy(4, false, 0.40, 8.0)) // 40%倍率

        // 2. 杀伐模式 (OFFENSE): 自我治疗 (数值也随之提升)
        registerStrategy("OFFENSE_BROKEN_FABAO",   ArtifactHealStrategy(1, true, 0.15, 4.0))
        registerStrategy("OFFENSE_COMMON_FABAO",   ArtifactHealStrategy(1, true, 0.20, 5.0))
        registerStrategy("OFFENSE_UNCOMMON_FABAO", ArtifactHealStrategy(1, true, 0.25, 6.0))
        registerStrategy("OFFENSE_RARE_FABAO",     ArtifactHealStrategy(1, true, 0.30, 7.0))
        registerStrategy("OFFENSE_EPIC_FABAO",     ArtifactHealStrategy(1, true, 0.40, 8.0))
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
    private class ArtifactHealStrategy(
        private val maxTargets: Int,
        private val selfOnly: Boolean,
        private val healRatio: Double, // [NEW] 动态治疗倍率
        private val range: Double      // [NEW] 动态范围
    ) : MageSkillStrategy {

        override val castTime: Double = 0.0

        override fun cast(ctx: SkillContext): Boolean {
            val p = ctx.player
            val healAmount = ctx.power * healRatio // 使用动态倍率

            if (selfOnly) {
                healPlayer(p, healAmount)
                p.world.spawnParticle(Particle.HAPPY_VILLAGER, p.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.5, 0.5)
                p.playSound(p.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f)
                return true
            }

            // 收集目标
            val targets = ArrayList<Player>()
            targets.add(p)

            // 使用动态范围查找周围玩家
            p.getNearbyEntities(range, range, range)
                .filterIsInstance<Player>()
                .forEach { targets.add(it) }

            // 排序：按血量百分比 (当前/最大) 升序排列，优先治疗血少的
            // 使用 sortedBy 和 safe call
            val sortedTargets = targets.sortedBy { player ->
                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                player.health / maxHealth
            }

            var count = 0
            for (target in sortedTargets) {
                if (count >= maxTargets) break

                healPlayer(target, healAmount)

                target.world.spawnParticle(Particle.HAPPY_VILLAGER, target.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.5, 0.5)
                if (target != p) {
                    spawnLinkParticle(p, target)
                }
                count++
            }

            if (count > 0) {
                p.playSound(p.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f)
                return true
            }
            return false
        }

        private fun healPlayer(p: Player, amount: Double) {
            val max = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            p.health = min(max, p.health + amount)
        }

        private fun spawnLinkParticle(from: LivingEntity, to: LivingEntity) {
            val start = from.location.add(0.0, 1.0, 0.0)
            val end = to.location.add(0.0, 1.0, 0.0)
            val dir = end.toVector().subtract(start.toVector())
            val dist = start.distance(end)

            if (dist > 0.1) {
                dir.normalize()
                var d = 0.0
                while (d <= dist) {
                    from.world.spawnParticle(
                        Particle.COMPOSTER,
                        start.clone().add(dir.clone().multiply(d)),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                    d += 0.5
                }
            }
        }
    }
}