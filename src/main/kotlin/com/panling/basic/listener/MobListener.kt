package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.manager.MobManager
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType
import java.util.*

class MobListener(private val mobManager: MobManager) : Listener {

    // 获取插件实例 (用于 hideEntity)
    private val plugin = PanlingBasic.getInstance()

    // ==================================================
    // 1. [NEW] 私有怪机制完善
    // ==================================================

    /**
     * 逻辑 A: 锁定仇恨 (AI 层面)
     * 私有怪只能把目标锁定为主人，不能看别人一眼
     */
    @EventHandler
    fun onMobTarget(event: EntityTargetLivingEntityEvent) {
        val mob = event.entity as? LivingEntity ?: return
        val target = event.target ?: return

        // 检查是否为私有怪
        val pdc = mob.persistentDataContainer
        if (pdc.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
            val ownerIdStr = pdc.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)

            // 核心判断：如果目标不是主人，直接取消索敌
            if (target.uniqueId.toString() != ownerIdStr) {
                event.isCancelled = true
            }
        }
    }

    /**
     * 逻辑 B: 新玩家进服时的视觉隐藏 (视觉层面)
     * 之前在 spawnPrivateMob 里只隐藏了当时在线的人，后来的得在这里补救
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val newPlayer = event.player

        // 遍历新玩家所在世界的实体
        for (entity in newPlayer.world.livingEntities) {
            val pdc = entity.persistentDataContainer
            if (pdc.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
                val ownerIdStr = pdc.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)

                // 如果这个怪的主人不是这位新玩家 -> 隐藏
                if (newPlayer.uniqueId.toString() != ownerIdStr) {
                    newPlayer.hideEntity(plugin, entity)
                }
            }
        }
    }

    /**
     * 逻辑 C: 伤害免疫 (物理层面)
     * 防止横扫之刃、爆炸等 AOE 误伤私有怪，或者私有怪误伤路人
     */
    @EventHandler
    fun onDamageLogic(event: EntityDamageByEntityEvent) {
        // [NEW] 0. 全局禁止怪物互殴
        // 获取真实的攻击源 (处理生物直接攻击和射箭情况)
        val damager = event.damager
        val realAttacker: LivingEntity? = when (damager) {
            is LivingEntity -> damager
            is AbstractArrow -> damager.shooter as? LivingEntity
            else -> null
        }

        // 只要攻击者存在，且攻击者不是玩家，且受害者也不是玩家 -> 判定为怪物互殴 -> 取消
        // 这将拦截：骷髅射僵尸、私有怪打私有怪、公共怪打私有怪等所有情况
        if (realAttacker != null && realAttacker !is Player && event.entity !is Player) {
            event.isCancelled = true
            return
        }

        // 1. 别人打私有怪 -> 取消
        val victim = event.entity as? LivingEntity
        if (victim != null && victim.persistentDataContainer.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
            val ownerId = victim.persistentDataContainer.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)
            val attackerPlayer = getAttackerPlayer(event.damager)

            // 如果攻击者是玩家，且不是主人 -> 禁止攻击
            if (attackerPlayer != null && attackerPlayer.uniqueId.toString() != ownerId) {
                event.isCancelled = true
            }
        }

        // 2. 私有怪打别人 -> 取消
        if (damager is LivingEntity && damager.persistentDataContainer.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
            val ownerId = damager.persistentDataContainer.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)

            // 如果被打的是玩家，且不是主人 -> 禁止伤害
            val victimPlayer = event.entity as? Player
            if (victimPlayer != null && victimPlayer.uniqueId.toString() != ownerId) {
                event.isCancelled = true
            }
        }
    }

    // 辅助方法：获取攻击来源玩家（处理射箭等情况）
    private fun getAttackerPlayer(damager: Entity): Player? {
        if (damager is Player) return damager
        if (damager is AbstractArrow) {
            val shooter = damager.shooter
            if (shooter is Player) return shooter
        }
        return null
    }

    // ==================================================
    // 2. 怪物射击逻辑 (NBT 写入)
    // ==================================================
    @EventHandler
    fun onMobShoot(event: EntityShootBowEvent) {
        if (event.entity is Player) return
        val mob = event.entity as? LivingEntity ?: return
        val arrow = event.projectile as? AbstractArrow ?: return

        // 假设 MobManager 有 getMobStats 方法
        val stats = mobManager.getMobStats(mob)
        if ("vanilla" == stats.id) return

        val baseDamage = stats.damage // 或 stats.phys()，取决于你的 MobStats 定义
        val force = event.force
        val finalDamage = baseDamage * force

        val pdc = arrow.persistentDataContainer
        pdc.set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, finalDamage)

        if (stats.armorPen > 0) pdc.set(BasicKeys.ATTR_ARMOR_PEN, PersistentDataType.DOUBLE, stats.armorPen)
        if (stats.critRate > 0) pdc.set(BasicKeys.ATTR_CRIT_RATE, PersistentDataType.DOUBLE, stats.critRate)
        if (stats.critDmg > 0) pdc.set(BasicKeys.ATTR_CRIT_DMG, PersistentDataType.DOUBLE, stats.critDmg)
        if (stats.lifeSteal > 0) pdc.set(BasicKeys.ATTR_LIFE_STEAL, PersistentDataType.DOUBLE, stats.lifeSteal)
    }

    // ==================================================
    // 3. 掉落逻辑
    // ==================================================
    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        val stats = mobManager.getMobStats(event.entity)
        if ("vanilla" == stats.id) return

        event.drops.clear()
        event.droppedExp = 0 // 禁止掉落原版经验球

        val killer = event.entity.killer

        // 如果有击杀者，且配置了经验值
        if (killer != null && stats.exp > 0) {
            // 直接给予经验值
            killer.giveExp(stats.exp)
            // 播放音效反馈
            killer.playSound(killer.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1f)
        }

        val dropsToGive = mobManager.generateLoot(event.entity, killer)

        if (dropsToGive.isEmpty()) return

        var ownerId: UUID? = null
        val entityPdc = event.entity.persistentDataContainer
        if (entityPdc.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
            try {
                val uuidStr = entityPdc.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)
                ownerId = UUID.fromString(uuidStr)
            } catch (ignored: Exception) {}
        }

        val owner = if (ownerId != null) Bukkit.getPlayer(ownerId) else null

        if (owner != null && owner.isOnline) {
            // 尝试放入背包
            val leftOver = owner.inventory.addItem(*dropsToGive.toTypedArray())

            if (leftOver.isNotEmpty()) {
                // 背包满了，掉在地上并保护
                for (drop in leftOver.values) {
                    val itemEntity = event.entity.world.dropItem(event.entity.location, drop)
                    itemEntity.owner = ownerId
                    itemEntity.persistentDataContainer.set(BasicKeys.ITEM_OWNER, PersistentDataType.STRING, ownerId.toString())
                }
                owner.sendMessage("§e背包已满，部分战利品掉落在地上。")
            } else {
                owner.sendActionBar(Component.text("§a获得战利品！"))
            }
            owner.playSound(owner.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
        } else {
            // 没有主人或主人不在线，正常掉落
            event.drops.addAll(dropsToGive)
        }
    }
}