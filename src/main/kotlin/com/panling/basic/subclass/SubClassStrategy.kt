package com.panling.basic.subclass

import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetEvent

interface SubClassStrategy {

    // 计算攻击加成 (返回修改后的伤害)
    fun modifyAttackDamage(player: Player, originalDamage: Double, holdSeconds: Double): Double = originalDamage

    // 处理攻击后的特效 (吸血、横扫、以及 金钟嘲讽等)
    fun onAttackEffect(player: Player, event: EntityDamageByEntityEvent, holdSeconds: Double) {}

    // 攻击完成后的逻辑 (仇恨处理)
    fun onAttack(attacker: Player, victim: LivingEntity, holdSeconds: Double) {}

    // 计算防御加成 (返回修改后的防御值)
    fun modifyDefense(player: Player, originalDefense: Double, holdSeconds: Double): Double = originalDefense

    // [NEW] 计算最大生命值加成 (返回修改后的生命值)
    fun modifyMaxHealth(player: Player, originalHealth: Double, holdSeconds: Double): Double = originalHealth

    // [NEW] 计算移动速度加成 (返回修改后的速度)
    fun modifyMovementSpeed(player: Player, originalSpeed: Double, holdSeconds: Double): Double = originalSpeed

    // 处理射击
    fun onShoot(player: Player, arrow: AbstractArrow, force: Float) {}

    // 处理装填
    fun getReloadReduction(player: Player, holdSeconds: Double): Int = 0

    // 计算吸血加成
    fun modifyLifeSteal(player: Player, originalLifeSteal: Double, holdSeconds: Double): Double = originalLifeSteal

    // 处理怪物仇恨事件
    fun onMobTarget(owner: Player, mob: Mob, event: EntityTargetEvent): Boolean = false

    // 获取游侠的附魔加成策略
    fun getRangerEnchantBonus(player: Player, holdSeconds: Double): IntArray = intArrayOf(0, 0, 0)
}