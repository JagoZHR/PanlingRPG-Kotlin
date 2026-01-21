package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class SniperT5Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_SNIPER_T5", "灭神", PlayerClass.ARCHER), ArcherSkillStrategy {

    // 只需要内存计数器
    companion object {
        private val shotCounts = HashMap<UUID, Int>()

        fun clearCache(uuid: UUID) {
            shotCounts.remove(uuid)
        }
    }

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    private val multKey = NamespacedKey(plugin, "pl_arrow_dmg_mult")

    override fun onCast(ctx: SkillContext): Boolean {
        return true
    }

    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        // [不需要写任何冷却检查代码]
        // 能进到这里，说明 PlayerCombatListener -> SkillManager 已经帮我们检查并通过了

        val uuid = player.uniqueId
        var count = shotCounts.getOrDefault(uuid, 0) + 1

        if (count >= 7) {
            count = 0
            // 贴标签
            arrow.persistentDataContainer.set(multKey, PersistentDataType.DOUBLE, 3.0)
            // 特效
            arrow.isCritical = true
            arrow.isGlowing = true
            player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2.0f)
            player.world.spawnParticle(Particle.CRIT, arrow.location, 10)
            player.sendActionBar(Component.text("§c[狙击] §a灭神 已发动").color(NamedTextColor.RED))
        } else {
            player.sendActionBar(Component.text("§e[灭神] 蓄能: $count / 7").color(NamedTextColor.YELLOW))
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.3f, 2.0f)
        }

        shotCounts[uuid] = count
    }
}