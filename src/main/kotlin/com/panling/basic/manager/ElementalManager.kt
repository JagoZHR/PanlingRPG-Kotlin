package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.BuffType
import com.panling.basic.util.CombatUtil
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.min

class ElementalManager(
    private val plugin: JavaPlugin,
    private val dataManager: PlayerDataManager,
    private val buffManager: BuffManager,
    private val statCalculator: StatCalculator,
    private val mobManager: MobManager
) {

    enum class Element(val elementName: String, val color: NamedTextColor) {
        METAL("金", NamedTextColor.YELLOW),
        WOOD("木", NamedTextColor.GREEN),
        WATER("水", NamedTextColor.BLUE),
        FIRE("火", NamedTextColor.RED),
        EARTH("土", NamedTextColor.GOLD),
        NONE("无", NamedTextColor.WHITE)
    }

    // [NEW] 生息模式入口
    fun handleSupportHit(caster: Player, target: LivingEntity, current: Element, skillPower: Double) {
        handleLogic(caster, target, current, skillPower, false)
    }

    // 杀伐模式入口
    fun handleElementHit(attacker: Player, victim: LivingEntity, current: Element, damage: Double) {
        handleLogic(attacker, victim, current, damage, true)
    }

    fun handleElementHit(attacker: Player, victim: LivingEntity, projectile: Entity?, damage: Double) {
        if (projectile == null || !projectile.persistentDataContainer.has(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING)) return
        val elStr = projectile.persistentDataContainer.get(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING)
        try {
            val element = Element.valueOf(elStr!!)
            handleLogic(attacker, victim, element, damage, true)
        } catch (ignored: Exception) {
        }
    }

    // 统一逻辑处理
    private fun handleLogic(source: Player, target: LivingEntity, current: Element, value: Double, isAttackMode: Boolean) {
        var mark = Element.NONE
        val pdc = target.persistentDataContainer
        if (pdc.has(BasicKeys.ELEMENT_MARK, PersistentDataType.STRING)) {
            try {
                mark = Element.valueOf(pdc.get(BasicKeys.ELEMENT_MARK, PersistentDataType.STRING)!!)
            } catch (ignored: Exception) {
            }
        }

        val triggered: Boolean
        if (isAttackMode) {
            // 杀伐基础特效 + 反应
            applyAttackBaseEffect(target, current, source)
            triggered = checkAttackReaction(source, target, mark, current, value)
        } else {
            // 生息反应 (基础效果在技能类里直接写了，这里只处理相生)
            triggered = checkLifeReaction(source, target, mark, current, value)
        }

        // [核心要求] 触发反应后移除标签，否则更新标签
        if (triggered) {
            pdc.remove(BasicKeys.ELEMENT_MARK)
            target.world.playSound(target.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.5f)
        } else {
            pdc.set(BasicKeys.ELEMENT_MARK, PersistentDataType.STRING, current.name)
            if (isAttackMode) {
                target.world.spawnParticle(Particle.HAPPY_VILLAGER, target.eyeLocation, 3, 0.3, 0.3, 0.3, 0.0)
            }
        }
    }

    private fun applyAttackBaseEffect(victim: LivingEntity, element: Element, attacker: Player) {
        when (element) {
            Element.WOOD -> victim.addPotionEffect(PotionEffect(PotionEffectType.POISON, 100, 1))
            Element.EARTH -> {
                val v = victim.location.toVector().subtract(attacker.location.toVector()).normalize().multiply(1.2).setY(0.4)
                victim.velocity = v
            }
            Element.WATER -> victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 2))
            else -> {}
        }
    }

    private fun checkAttackReaction(attacker: Player, victim: LivingEntity, mark: Element, hit: Element, damage: Double): Boolean {
        if (mark == Element.NONE) return false

        val victimName = victim.customName ?: victim.name

        // 1. 金克木 (目标有木，被金打)
        if (mark == Element.WOOD && hit == Element.METAL) {
            val bonus = damage * 0.8
            victim.damage(bonus)
            // [音效] 金属撞击
            victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.2f, 1.5f)
            // [特效] 暴击星
            victim.world.spawnParticle(Particle.CRIT, victim.eyeLocation, 15, 0.5, 0.5, 0.5, 0.1)
            // [NEW] 范围广播
            broadcastReaction(attacker, victim, "§e[金克木] §f金锐破甲！对 §7$victimName §f造成额外贯穿伤害！")
            return true
        }

        // 2. 木克土 (目标有土，被木打)
        if (mark == Element.EARTH && hit == Element.WOOD) {
            val explode = damage * 1.5
            victim.damage(explode)
            val heal = explode * 0.1
            val maxHp = attacker.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            attacker.health = min(maxHp, attacker.health + heal)
            // [音效] 经验球/有机声音
            victim.world.playSound(victim.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            victim.world.playSound(victim.location, Sound.BLOCK_GRASS_BREAK, 1f, 0.5f)
            // [特效] 爱心
            victim.world.spawnParticle(Particle.HEART, attacker.location.add(0.0, 2.0, 0.0), 3, 0.3, 0.3, 0.3)
            broadcastReaction(attacker, victim, "§a[木克土] §f根须缠绕！从 §7$victimName §f身上汲取了生命！")
            return true
        }

        // 3. 土克水 (目标有水，被土打)
        if (mark == Element.WATER && hit == Element.EARTH) {
            buffManager.addBuff(victim, BuffType.ROOT, 60) // 3秒
            // [音效] 铁砧落地/石头重击
            victim.world.playSound(victim.location, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f)
            // [特效] 灰色方块碎屑 (岩石)
            victim.world.spawnParticle(Particle.BLOCK_CRUMBLE, victim.location, 20, 0.5, 1.0, 0.5, Material.STONE.createBlockData())
            broadcastReaction(attacker, victim, "§6[土克水] §f岩石禁锢！§7$victimName §f已被定身！")
            return true
        }

        // 4. 水克火 (目标有火，被水打)
        // 逻辑：目标先中了天劫(火)，身上有 FIRE 标记。然后被却邪(水)命中 -> 触发。
        if (mark == Element.FIRE && hit == Element.WATER) {
            victim.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 200, 1))
            buffManager.addBuff(victim, BuffType.WEAKNESS, 200) // 10秒
            broadcastReaction(attacker, victim, "§b[水克火] §f水汽弥漫！§7$victimName §f陷入致盲与虚弱！")
            // 播放个灭火的声音
            victim.world.playSound(victim.location, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f)
            // [特效] 大量烟雾
            victim.world.spawnParticle(Particle.LARGE_SMOKE, victim.location, 30, 0.5, 1.0, 0.5, 0.05)
            return true
        }

        // 5. 火克金 (目标有金，被火打)
        if (mark == Element.METAL && hit == Element.FIRE) {
            victim.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 200, 1))
            buffManager.addBuff(victim, BuffType.ARMOR_BREAK, 200, 0.7, true) // 10秒
            // [音效] 装备破碎声/熔岩声
            victim.world.playSound(victim.location, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 0.5f)
            victim.world.playSound(victim.location, Sound.BLOCK_LAVA_EXTINGUISH, 1f, 2f)
            // [特效] 熔岩滴落/火焰
            victim.world.spawnParticle(Particle.DRIPPING_LAVA, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3)
            victim.world.spawnParticle(Particle.FLAME, victim.location, 10, 0.3, 1.0, 0.3, 0.05)
            broadcastReaction(attacker, victim, "§c[火克金] §f烈火熔金！§7$victimName §f护甲已被熔毁！")
            return true
        }

        return false
    }

    /**
     * [核心] 五行相生逻辑
     * 金 -> 水 -> 木 -> 火 -> 土 -> 金
     */
    // === [NEW] 生息相生逻辑 ===
    private fun checkLifeReaction(caster: Player, target: LivingEntity, mark: Element, current: Element, skillPower: Double): Boolean {
        if (mark == Element.NONE) return false
        val tName = target.name

        // 1. 金生水: 移除所有负面
        if (mark == Element.METAL && current == Element.WATER) {
            for (effect in target.activePotionEffects) {
                if (isNegativeEffect(effect.type)) {
                    target.removePotionEffect(effect.type)
                }
            }
            buffManager.removeBuff(target, BuffType.WEAKNESS)
            buffManager.removeBuff(target, BuffType.ARMOR_BREAK)
            buffManager.removeBuff(target, BuffType.ROOT)
            buffManager.removeBuff(target, BuffType.SILENCE)
            buffManager.removeBuff(target, BuffType.POISON)
            target.fireTicks = 0
            broadcast(caster, target, "§b[金生水] §f净化！§7$tName §f负面状态已全清！")
            return true
        }

        // 2. 水生木: 额外回血 20% SP
        if (mark == Element.WATER && current == Element.WOOD) {
            val heal = skillPower * 0.20
            val max = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            target.health = min(max, target.health + heal)
            broadcast(caster, target, "§a[水生木] §f滋养！§7$tName §f额外回复大量生命！")
            return true
        }

        // 3. 木生火: 必定暴击 (10s)
        if (mark == Element.WOOD && current == Element.FIRE) {
            buffManager.addBuff(target, BuffType.CRIT_GUARANTEE, 200)
            broadcast(caster, target, "§c[木生火] §f燃魂！§7$tName §f下一次攻击必暴！")
            return true
        }

        // 4. 火生土: 护盾翻倍
        if (mark == Element.FIRE && current == Element.EARTH) {
            // [MODIFIED] 适配药水版护盾
            val absEffect = target.getPotionEffect(PotionEffectType.ABSORPTION)

            // 只有当目标身上有伤害吸收药水时才翻倍
            if (absEffect != null) {
                val currentAmp = absEffect.amplifier
                var newAmp = (currentAmp + 1) * 2 - 1 // (等级 * 4) * 2 / 4 - 1 -> 简化为翻倍逻辑

                if (newAmp > 255) newAmp = 255

                // 重新施加翻倍后的药水
                target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, absEffect.duration, newAmp, false, false, true))

                broadcast(caster, target, "§6[火生土] §f固若金汤！§7${target.name} §f护盾翻倍！")
                return true
            } else {
                // 如果没护盾，火生土无效 (不消耗标记，或者消耗但无效果)
                return false
            }
        }

        // 5. 土生金: 额外双抗 (+50% SP)
        if (mark == Element.EARTH && current == Element.METAL) {
            // 加法 Buff (false)
            buffManager.addBuff(target, BuffType.DEFENSE_UP, 200, skillPower * 0.5, false)
            broadcast(caster, target, "§e[土生金] §f金身！§7$tName §f获得巨额双抗！")
            return true
        }

        return false
    }

    private fun broadcast(source: Player, target: LivingEntity, msg: String) {
        source.sendMessage(msg)
        if (target is Player && target != source) {
            target.sendMessage(msg)
        }
    }

    // === [NEW] 范围广播系统 ===
    private fun broadcastReaction(source: Player, target: LivingEntity, message: String) {
        // 定义广播范围 (20格)
        val range = 20.0

        // 发送给攻击者
        source.sendMessage(message)

        // 如果受害者是玩家，也发给他
        if (target is Player) {
            target.sendMessage(message)
        }

        // 寻找周围的其他玩家
        val nearby = source.getNearbyEntities(range, range, range)
        for (e in nearby) {
            if (e is Player && e != source && e != target) {
                e.sendMessage(message)
            }
        }
    }

    // =========================================================
    // [NEW] 1. 攻击增益处理 (Fire Imbue)
    // 在计算最终伤害前调用，返回额外增加的伤害值
    // =========================================================
    fun applyAttackBuffs(attacker: Player, victim: LivingEntity): Double {
        var bonusDamage = 0.0

        // 火元素 T4: 烈火附魔 (单次消耗)
        if (attacker.hasMetadata("pl_fire_imbue_power")) {
            try {
                val power = attacker.getMetadata("pl_fire_imbue_power")[0].asDouble()
                bonusDamage += power

                // 视觉效果
                attacker.world.spawnParticle(Particle.FLAME, victim.location, 8, 0.2, 0.5, 0.2, 0.05)

                // 消耗状态
                attacker.removeMetadata("pl_fire_imbue_power", plugin)
            } catch (ignored: Exception) {
            }
        }

        return bonusDamage
    }

    // =========================================================
    // [NEW] 2. 防御触发处理 (Earth Shield, Metal Thorns)
    // 在伤害计算完成后调用
    // =========================================================
    fun handleDefenseTriggers(victim: LivingEntity, attacker: LivingEntity?, finalDamage: Double) {

        // 土元素 T4: 护盾破碎逻辑
        // 如果伤害超过了当前的护盾值(Absorption)，则触发破碎
        if (victim.hasMetadata("pl_earth_shield_t4")) {
            val currentShield = victim.absorptionAmount
            // 只有当有护盾且伤害足够打破它时
            if (currentShield > 0 && finalDamage >= currentShield) {
                triggerEarthShieldBreak(victim)
                victim.removeMetadata("pl_earth_shield_t4", plugin)
            }
        }

        // 金元素 T5: 荆棘反伤
        // 只有在造成实际伤害时才反伤
        if (finalDamage > 0 && victim.hasMetadata("pl_metal_thorns_t5")) {
            if (attacker != null) {
                // 计算防御力 (用于确定反伤伤害)
                val def: Double = if (victim is Player) {
                    statCalculator.getPlayerTotalStat(victim, BasicKeys.ATTR_DEFENSE)
                } else {
                    mobManager.getMobStats(victim).physicalDefense
                }

                // 委托 CombatUtil 进行安全反伤
                if (plugin is PanlingBasic) {
                    CombatUtil.handleThornsSafe(victim, attacker, def, plugin)
                }
            }
        }
    }

    // [Moved] 从 Listener 移来的私有方法
    private fun triggerEarthShieldBreak(center: LivingEntity) {
        center.world.spawnParticle(Particle.BLOCK_CRUMBLE, center.eyeLocation, 30, 0.5, 0.5, 0.5, Material.DIRT.createBlockData())
        center.world.playSound(center.location, Sound.ITEM_SHIELD_BREAK, 1f, 0.8f)

        for (e in center.getNearbyEntities(2.0, 2.0, 2.0)) {
            if (e is LivingEntity && e != center) {
                // 简单的击退向量
                val knock = e.location.toVector().subtract(center.location.toVector()).normalize()
                knock.multiply(1.5).setY(0.5)
                e.velocity = knock

                // 造成破碎伤害
                e.damage(5.0, center)
            }
        }
    }

    /**
     * 判断药水效果是否为负面效果
     */
    private fun isNegativeEffect(type: PotionEffectType): Boolean {
        // 原版常见的负面效果
        if (type == PotionEffectType.BLINDNESS) return true
        if (type == PotionEffectType.HUNGER) return true
        if (type == PotionEffectType.LEVITATION) return true
        if (type == PotionEffectType.POISON) return true
        if (type == PotionEffectType.SLOWNESS) return true
        if (type == PotionEffectType.WEAKNESS) return true
        if (type == PotionEffectType.WITHER) return true

        // 1.19+ 新增
        try {
            if (type == PotionEffectType.DARKNESS) return true
        } catch (ignored: NoSuchFieldError) {
        } catch (ignored: NoClassDefFoundError) {
        }

        // 1.21+ 新增 (如果是 Purpur 1.21，这些应该都有)
        try {
            // 还有 Oozing, Weaving, Infested, Wind Charged 等新效果
            // 视情况判定是否为负面
            if (type.key.key == "oozing") return true
            if (type.key.key == "infested") return true
            if (type.key.key == "weaving") return true
        } catch (ignored: Exception) {
        }

        return false
    }
}