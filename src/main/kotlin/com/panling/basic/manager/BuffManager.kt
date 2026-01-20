package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

class BuffManager(private val plugin: PanlingBasic) {

    private val activeBuffs = ConcurrentHashMap<UUID, MutableMap<BuffType, BuffInstance>>()

    // [NEW] 需要引用 Calculator 来触发同步
    // 使用 var 并设为可空，避免构造函数循环依赖
    var statCalculator: StatCalculator? = null

    data class BuffInstance(
        val expireTime: Long,
        val value: Double,
        val isMultiplier: Boolean
    )

    init {
        startTickTask()
    }

    fun addBuff(target: LivingEntity, type: BuffType, durationTicks: Long) {
        addBuff(target, type, durationTicks, type.defaultMultiplier, true)
    }

    /**
     * 添加 Buff (带智能覆盖逻辑)
     */
    fun addBuff(
        target: LivingEntity,
        type: BuffType,
        durationTicks: Long,
        newValue: Double,
        isMultiplier: Boolean
    ) {
        val id = target.uniqueId
        // 使用 ConcurrentHashMap 的 computeIfAbsent 保证线程安全
        val entityBuffs = activeBuffs.computeIfAbsent(id) { ConcurrentHashMap() }

        val newExpireTime = System.currentTimeMillis() + (durationTicks * 50)

        // [核心修改] 检查是否已存在同类 Buff
        if (entityBuffs.containsKey(type)) {
            val old = entityBuffs[type]!!

            // 1. 判断新旧 Buff 的"强度"
            // 倍率型：偏离 1.0 越远越强 (0.5 比 0.8 强，1.5 比 1.2 强)
            // 数值型：绝对值越大越强 (100 比 50 强)
            val newIsStronger = if (isMultiplier) {
                abs(1.0 - newValue) > abs(1.0 - old.value)
            } else {
                abs(newValue) > abs(old.value)
            }

            // 2. 强度判断逻辑
            val valuesEqual = abs(newValue - old.value) < 0.001

            if (valuesEqual) {
                // 情况 A: 强度相同 -> 刷新时间 (取最长)
                val finalExpire = max(old.expireTime, newExpireTime)
                entityBuffs[type] = BuffInstance(finalExpire, newValue, isMultiplier)
            } else if (newIsStronger) {
                // 情况 B: 新的更强 -> 强制覆盖
                entityBuffs[type] = BuffInstance(newExpireTime, newValue, isMultiplier)
            } else {
                // 情况 C: 旧的更强 -> 忽略新的
            }
        } else {
            // 不存在 -> 直接添加
            entityBuffs[type] = BuffInstance(newExpireTime, newValue, isMultiplier)
        }

        // 特殊处理与通知
        if (type == BuffType.ROOT) applyVanillaSpeed(target, 0.0)

        // [可选优化] 每次加 Buff 立即刷新一次属性
        if (target is Player) {
            statCalculator?.syncPlayerAttributes(target)
        }
    }

    fun removeBuff(target: LivingEntity, type: BuffType) {
        val uuid = target.uniqueId
        activeBuffs[uuid]?.let { buffs ->
            buffs.remove(type)
            // [可选优化] 移除时也刷新
            if (target is Player) {
                statCalculator?.syncPlayerAttributes(target)
            }
        }
    }

    fun hasBuff(entity: LivingEntity, type: BuffType): Boolean {
        val buffs = activeBuffs[entity.uniqueId] ?: return false
        val instance = buffs[type] ?: return false
        return System.currentTimeMillis() <= instance.expireTime
    }

    fun getBuffValue(entity: LivingEntity, type: BuffType): Double {
        if (!hasBuff(entity, type)) return 0.0
        return activeBuffs[entity.uniqueId]?.get(type)?.value ?: 0.0
    }

    fun applyBuffsToValue(entity: LivingEntity, attrKey: NamespacedKey, baseValue: Double): Double {
        val buffs = activeBuffs[entity.uniqueId] ?: return baseValue

        var finalValue = baseValue
        val now = System.currentTimeMillis()

        // 1. 先做加法 (利用 Sequence 避免多次遍历)
        buffs.asSequence()
            .filter { it.value.expireTime >= now } // 过滤过期
            .filter { !it.value.isMultiplier }    // 筛选加法
            .forEach { (type, inst) ->
                // 假设 BuffType.affectedAttributes 是 Collection<NamespacedKey>
                if (type.affectedAttributes.contains(attrKey)) {
                    finalValue += inst.value
                }
            }

        // 2. 后做乘法
        buffs.asSequence()
            .filter { it.value.expireTime >= now } // 过滤过期
            .filter { it.value.isMultiplier }     // 筛选乘法
            .forEach { (type, inst) ->
                if (type.affectedAttributes.contains(attrKey)) {
                    finalValue *= inst.value
                }
            }

        return finalValue
    }

    private fun startTickTask() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            val it = activeBuffs.iterator()

            while (it.hasNext()) {
                val entry = it.next()
                val uuid = entry.key
                val buffs = entry.value

                // 注意：这里用 Bukkit.getEntity 获取实体可能为空 (区块未加载/离线)
                val entity = Bukkit.getEntity(uuid) as? LivingEntity

                val rootedBefore = buffs.containsKey(BuffType.ROOT)

                if (entity != null && !entity.isDead) {
                    // 处理回血 (REGEN)
                    val regenInst = buffs[BuffType.REGEN]
                    if (regenInst != null && now <= regenInst.expireTime) {
                        val maxHp = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                        val newHp = minOf(maxHp, entity.health + regenInst.value)

                        if (newHp > entity.health) {
                            entity.health = newHp
                            entity.world.spawnParticle(
                                Particle.HEART,
                                entity.location.add(0.0, 2.0, 0.0),
                                1, 0.2, 0.2, 0.2, 0.0
                            )
                        }
                    }

                    // [核心新增] 定时同步属性
                    // 确保 Buff 过期后，属性值能自动回退 (比如霸体结束，击退抗性归零)
                    if (entity is Player) {
                        statCalculator?.syncPlayerAttributes(entity)
                    }
                }

                // 清理过期 Buff (Kotlin 的 MutableMap.values.removeIf)
                buffs.values.removeIf { now > it.expireTime }

                // 检查定身状态解除
                val rootedAfter = buffs.containsKey(BuffType.ROOT)
                if (rootedBefore && !rootedAfter && entity != null) {
                    applyVanillaSpeed(entity, 0.2)
                }

                // 如果该实体的所有 Buff 都清理完了，移除实体记录
                if (buffs.isEmpty()) {
                    it.remove()
                }
            }
        }, 20L, 20L)
    }

    private fun applyVanillaSpeed(entity: LivingEntity, `val`: Double) {
        if (entity is Player) {
            entity.walkSpeed = `val`.toFloat()
        } else {
            val attr = entity.getAttribute(Attribute.MOVEMENT_SPEED)
            attr?.baseValue = `val`
        }
    }
}