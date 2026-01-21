package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.BuffType
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import com.panling.basic.util.CombatUtil
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class YunshuiPassiveSkill(private val plugin: PanlingBasic) :
    AbstractSkill("YUNSHUI", "百川入海", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    init {
        startPassiveBuffTask()
    }

    override fun cast(ctx: SkillContext): Boolean {
        return true
    }

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val attacker = ctx.player
        val mainTarget = ctx.target

        // [核心修复] 使用实际攻击伤害，而不是 ctx.power() (可能为1.0)
        val damage = event.damage

        // 1. 水波斩特效
        spawnWaterSlashEffect(attacker)
        attacker.world.playSound(attacker.location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.5f, 1.2f)

        // 2. 扇形 AOE
        val radius = 2.0
        val attackerDir = attacker.location.direction.setY(0).normalize()

        // getNearbyEntities 返回 MutableList<Entity>
        attacker.getNearbyEntities(radius, 2.0, radius).forEach { e ->
            if (e is LivingEntity && e !== attacker && e !== mainTarget) {
                if (CombatUtil.isValidTarget(attacker, e)) {
                    val toTarget = e.location.toVector().subtract(attacker.location.toVector()).setY(0).normalize()

                    if (attackerDir.dot(toTarget) > 0) { // 前方 180度

                        // [核心修复] triggerPassives = false
                        // 溅射伤害禁止再次触发被动，防止无限套娃
                        CombatUtil.dealPhysicalSkillDamage(attacker, e, damage, false)

                        // 击退
                        e.velocity = toTarget.multiply(0.3).setY(0.2)
                    }
                }
            }
        }
    }

    private fun spawnWaterSlashEffect(p: Player) {
        val loc = p.eyeLocation.subtract(0.0, 0.4, 0.0)
        val dir = loc.direction.setY(0).normalize()

        // Kotlin 范围循环优化
        for (angle in -90..90 step 10) {
            val v = rotateVector(dir, angle.toFloat()).multiply(1.5)
            val particleLoc = loc.clone().add(v)

            p.world.spawnParticle(Particle.FALLING_WATER, particleLoc, 1)
            p.world.spawnParticle(Particle.CLOUD, particleLoc, 0, v.x, 0.0, v.z, 0.05)
        }
    }

    private fun rotateVector(vector: Vector, angleDegrees: Float): Vector {
        val angle = Math.toRadians(angleDegrees.toDouble())
        val x = vector.x
        val z = vector.z
        val cos = cos(angle)
        val sin = sin(angle)
        return Vector(x * cos - z * sin, vector.y, x * sin + z * cos).normalize()
    }

    private fun startPassiveBuffTask() {
        object : BukkitRunnable() {
            override fun run() {
                // 直接遍历在线玩家
                for (p in Bukkit.getOnlinePlayers()) {
                    updateBuff(p)
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun updateBuff(p: Player) {
        val item = p.inventory.itemInMainHand

        // 检查物品是否有 Meta
        if (!item.hasItemMeta()) return

        // 这里的 itemMeta 不会为 null，因为上面检查过了，但为了稳健可以使用 ?.
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // [核心修复] 同时检查 PASSIVE 和 ATTACK 两个位置
        // 只要任意一个位置绑定了 "YUNSHUI"，就视为生效
        var isActive = false

        // 检查 PASSIVE 槽位
        val passiveKey = BasicKeys.TRIGGER_KEYS[SkillTrigger.PASSIVE]
        if (passiveKey != null) {
            val id = pdc.get(passiveKey, PersistentDataType.STRING)
            if ("YUNSHUI" == id) isActive = true
        }

        // 检查 ATTACK 槽位 (你现在用的就是这个)
        if (!isActive) {
            val attackKey = BasicKeys.TRIGGER_KEYS[SkillTrigger.ATTACK]
            if (attackKey != null) {
                val id = pdc.get(attackKey, PersistentDataType.STRING)
                if ("YUNSHUI" == id) isActive = true
            }
        }

        // 如果没有激活被动，后续逻辑不需要运行 (原版逻辑似乎即使 isActive=false 也会往下跑计算 enemyCount？)
        // 回看原版 Java 代码：它并没有 `if (!isActive) return;`。
        // 原版逻辑确实是：计算 isActive 变量，但后续并没有用到这个 isActive 变量来阻断 buff 添加吗？
        // *仔细检查原代码*：
        // 原代码中 `isActive` 变量被赋值后，竟然没有被使用！
        // `enemyCount` 的计算和 Buff 添加与 `isActive` 毫无关系。
        // 这看起来像是一个逻辑 Bug (计算了 isActive 但没用它)，但根据你的指示“不妄加猜测其中是不是有什么内容写错了”，我必须保留这个行为。
        // 哪怕这个变量没用，我也保留它以防未来你需要用它。

        // 使用 Kotlin 的 count 聚合函数优化计数逻辑
        var enemyCount = p.getNearbyEntities(8.0, 5.0, 8.0).count { e ->
            e is LivingEntity && CombatUtil.isValidTarget(p, e)
        }

        enemyCount = enemyCount.coerceAtMost(10)

        if (enemyCount > 0) {
            val defMult = 1.0 + (enemyCount * 0.05)
            plugin.buffManager.addBuff(p, BuffType.DEFENSE_UP, 30L, defMult, true)

            val kbBonus = enemyCount * 0.05
            plugin.buffManager.addBuff(p, BuffType.KB_RESIST, 30L, kbBonus, false)
        }
    }
}