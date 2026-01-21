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

    // 处理攻击后的特效 (吸血、横扫、以及 [NEW] 金钟嘲讽等)
    fun onAttackEffect(player: Player, event: EntityDamageByEntityEvent, holdSeconds: Double) {}

    // [NEW] 攻击完成后的逻辑 (用于处理如 金钟嘲讽 这种不修改伤害但有副作用的逻辑)
    // 此时伤害已结算，适合做仇恨处理
    fun onAttack(attacker: Player, victim: LivingEntity, holdSeconds: Double) {}

    // 计算防御加成 (返回修改后的防御值)
    fun modifyDefense(player: Player, originalDefense: Double, holdSeconds: Double): Double = originalDefense

    // 处理射击
    fun onShoot(player: Player, arrow: AbstractArrow, force: Float) {}

    // 处理装填
    fun getReloadReduction(player: Player, holdSeconds: Double): Int = 0

    // 计算吸血加成
    fun modifyLifeSteal(player: Player, originalLifeSteal: Double, holdSeconds: Double): Double = originalLifeSteal

    // [NEW] 处理怪物仇恨事件 (返回 true 表示策略已处理该事件，Listener 应直接 return)
    fun onMobTarget(owner: Player, mob: Mob, event: EntityTargetEvent): Boolean = false

    // [NEW] 获取游侠的附魔加成策略
    // 返回值数组: [0]=装填加成, [1]=穿透加成, [2]=是否允许多重射击(1=yes, 0=no)
    fun getRangerEnchantBonus(player: Player, holdSeconds: Double): IntArray = intArrayOf(0, 0, 0)
}