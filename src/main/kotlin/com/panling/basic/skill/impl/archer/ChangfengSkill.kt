package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import com.panling.basic.util.CombatUtil
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ChangfengSkill(private val plugin: PanlingBasic) :
    AbstractSkill("CHANGFENG", "群雁归巢", PlayerClass.ARCHER), ArcherSkillStrategy {

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    // -----------------------------------------------------------
    // 被动技能：长风万里 (修复反馈与伤害公式)
    // -----------------------------------------------------------
    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        if (event.force < 0.9) return
        event.isCancelled = true

        player.world.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.8f)
        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.5f)

        // 1. 获取面板物理伤害
        val phys = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val rangeMult = PlayerClass.ARCHER.rangeMultiplier
        val baseDmg = phys * rangeMult

        // 2. [伤害公式修复] 模拟 PlayerCombatListener 的逻辑
        // 模拟满弦箭矢速度 (通常约为 3.0)
        val simulatedVelocity = 3.0

        // 基础计算: base * velocity
        var calcDmg = ceil(baseDmg * simulatedVelocity)

        // 暴击/满弦随机增伤: random(base * 0.5)
        // 因为本技能触发条件就是 force >= 0.9，所以默认触发这个随机区间
        calcDmg += random.nextInt((baseDmg * 0.5).toInt() + 1)

        // NBT 倍率接口 (预留)
        val damageBeforeDist = calcDmg * 2.5

        val startLoc = player.eyeLocation
        val dir = startLoc.direction.normalize()

        object : BukkitRunnable() {
            var current = startLoc.clone()
            val step = dir.clone().multiply(5.0)
            val maxDist = 120

            override fun run() {
                val stepLen = step.length()
                val unitStep = step.clone().normalize()

                // 模拟步进
                var d = 0.0
                while (d < stepLen) {
                    current.add(unitStep)
                    current.world.spawnParticle(Particle.CLOUD, current, 1, 0.0, 0.0, 0.0, 0.01)
                    current.world.spawnParticle(Particle.CRIT, current, 1, 0.0, 0.0, 0.0, 0.0)

                    // [修复 1] 撞墙不给反馈，只销毁
                    if (current.block.type.isSolid) {
                        this.cancel()
                        return
                    }

                    // 实体检测
                    for (e in current.world.getNearbyEntities(current, 0.6, 0.6, 0.6)) {
                        if (e is LivingEntity && CombatUtil.isValidTarget(player, e)) {
                            val victim = e

                            // 3. 距离增伤 (独立乘区)
                            val dist = victim.location.distance(startLoc)
                            val distFactor = min(25.0, max(5.0, dist))
                            val bonusPct = (distFactor - 5.0) / 20.0

                            // 最终伤害 = (基础公式伤害) * (1 + 距离倍率)
                            val finalDmg = damageBeforeDist * (1.0 + bonusPct)

                            CombatUtil.dealPhysicalSkillDamage(player, victim, finalDmg, true)

                            // [修复 1] 只有命中实体才播放反馈
                            player.playSound(player.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.5f, 1.2f)

                            /*
                            if (bonusPct >= 0.8) {
                                player.sendActionBar(Component.text("§c⊕ 致命狙击! 距离: ${dist.toInt()}m | 伤害加成: ${(bonusPct*100).toInt()}%")
                                        .color(NamedTextColor.RED))
                                victim.world.spawnParticle(Particle.EXPLOSION_LARGE, victim.eyeLocation, 1)
                            }
                            */

                            this.cancel()
                            return
                        }
                    }
                    d += 1.0
                }

                if (startLoc.distance(current) > maxDist) {
                    this.cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // -----------------------------------------------------------
    // 主动技能入口：孤鹜齐飞 (SHIFT_RIGHT)
    // -----------------------------------------------------------
    override fun onCast(ctx: SkillContext): Boolean {
        if (ctx.triggerType == SkillTrigger.SHIFT_RIGHT) {
            startStagnantArrow(ctx)
            return true
        }
        return false
    }

    // -----------------------------------------------------------
    // 主动技能：孤鹜齐飞 (修复特效与钉墙逻辑)
    // -----------------------------------------------------------
    private fun startStagnantArrow(ctx: SkillContext) {
        val p = ctx.player
        val startLoc = p.eyeLocation
        val dir = startLoc.direction.normalize()

        // 慢速飞行
        val velocity = dir.clone().multiply(0.6)

        p.world.playSound(startLoc, Sound.ITEM_ELYTRA_FLYING, 1.0f, 0.5f)

        // =========================================================
        // [新增] 伤害预计算 (Snapshot)
        // 完全复刻被动技能的伤害公式，作为这次"孤鹜"的基础伤害
        // =========================================================
        val phys = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val rangeMult = PlayerClass.ARCHER.rangeMultiplier
        val baseDmg = phys * rangeMult

        // 模拟满弦 3.0 + 随机暴击
        var damageSnapshot = ceil(baseDmg * 3.0)
        damageSnapshot += random.nextInt((baseDmg * 0.5).toInt() + 1)

        // 读取 NBT 倍率 (从手中的弓读取)
        val sourceItem = ctx.sourceItem
        if (sourceItem != null && sourceItem.hasItemMeta()) {
            val pdc = sourceItem.itemMeta.persistentDataContainer
            val multKey = NamespacedKey(plugin, "pl_arrow_dmg_mult")
            if (pdc.has(multKey, PersistentDataType.DOUBLE)) {
                damageSnapshot *= pdc.get(multKey, PersistentDataType.DOUBLE)!!
            }
        }

        // 存入 final 变量供 Runnable 使用
        val damageBase = damageSnapshot * 3

        val capturedMobs = HashSet<UUID>()

        // [NEW] 永久黑名单：记录所有"已互动过"的怪物ID
        // 防止怪物钉墙后被移出 capturedMobs，下一帧又被重新扫描捕获
        val processedMobs = HashSet<UUID>()

        object : BukkitRunnable() {
            var current = startLoc.clone()
            var traveled = 0.0
            val maxRange = 40.0

            override fun run() {
                if (!p.isOnline) {
                    releaseMobs(capturedMobs)
                    this.cancel()
                    return
                }

                // [修复 3] 使用 RayTrace 提前检测碰撞，防止穿墙
                val hit = current.world.rayTraceBlocks(
                    current,
                    velocity.clone().normalize(),
                    velocity.length(),
                    FluidCollisionMode.NEVER,
                    true
                )

                if (hit != null && hit.hitBlock != null) {
                    // [核心修复] 传入碰撞面 (HitBlockFace) 以计算精确落点
                    pinMobsToWall(
                        capturedMobs,
                        hit.hitBlock!!,
                        hit.hitPosition.toLocation(current.world),
                        hit.hitBlockFace,
                        velocity
                    )
                    this.cancel()
                    return
                }

                // 没撞墙，正常移动
                current.add(velocity)
                traveled += velocity.length()

                // [修复 2] 特效升级：风洞效果
                // 使用柔和烟雾 + 云雾，向后喷射，模拟气流
                val back = velocity.clone().normalize().multiply(-0.2)
                current.world.spawnParticle(Particle.CLOUD, current, 3, 0.1, 0.1, 0.1, 0.02)

                // 循环 2 次
                for (i in 0 until 2) {
                    // 计算带随机抖动的速度向量
                    val vx = back.x + (Math.random() - 0.5) * 0.1
                    val vy = back.y + (Math.random() - 0.5) * 0.1
                    val vz = back.z + (Math.random() - 0.5) * 0.1

                    current.world.spawnParticle(
                        Particle.CAMPFIRE_COSY_SMOKE, // 粒子类型
                        current,                      // 位置
                        0,                            // [关键] Count=0 开启"速度模式"
                        vx, vy, vz,                   // 这里填速度向量 X, Y, Z
                        1.0                           // 速度倍率 (Extra)
                    )
                }

                if (traveled % 4 < 0.6) {
                    current.world.playSound(current, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 1.5f)
                }

                // 捕获与携带逻辑
                for (e in current.world.getNearbyEntities(current, 1.5, 1.5, 1.5)) {
                    if (e is LivingEntity && CombatUtil.isValidTarget(p, e)) {
                        val victim = e
                        if (!capturedMobs.contains(victim.uniqueId)) {
                            // [核心修复] 如果这个怪之前已经被这支箭处理过(无论捕获、钉墙、还是弹开)，直接跳过
                            if (processedMobs.contains(victim.uniqueId)) {
                                continue
                            }

                            capturedMobs.add(victim.uniqueId)
                            processedMobs.add(victim.uniqueId) // [关键] 加入黑名单

                            // =========================================================
                            // [核心修复] 捕获时禁用物理引擎，防止积攒动能炸开
                            // =========================================================
                            victim.setGravity(false)    // 1. 无重力
                            victim.isCollidable = false // 2. 无碰撞 (彻底解决挤压爆炸)
                            victim.velocity = Vector(0, 0, 0) // 3. 清空动能

                            victim.world.playSound(victim.location, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 0.8f)

                            // =========================================================
                            // [新增] 捕获伤害 (Capture Damage)
                            // =========================================================
                            // 1. 计算当前距离 (捕获点 <-> 玩家眼部)
                            val dist = victim.location.distance(startLoc)

                            // 2. 复刻被动的距离增伤算法
                            val distFactor = min(25.0, max(5.0, dist))
                            val bonusPct = (distFactor - 5.0) / 20.0

                            // 3. 结算最终伤害
                            val finalDmg = damageBase * (1.0 + bonusPct)

                            // 造成伤害
                            CombatUtil.dealPhysicalSkillDamage(p, victim, finalDmg, true)

                            // 命中反馈
                            victim.world.playSound(victim.location, Sound.ENTITY_ARROW_HIT, 0.8f, 1.0f)
                            victim.world.spawnParticle(Particle.CRIT, victim.eyeLocation, 5)
                        }
                    }
                }

                // 3. [核心修改] 携带怪物 & 提前碰撞检测
                capturedMobs.removeIf { uuid ->
                    val e = Bukkit.getEntity(uuid)
                    if (e == null || e.isDead) return@removeIf true

                    if (e is LivingEntity) {
                        val heightOffset = e.height / 2.0
                        // 计算目标脚部位置 (用于 isSafe 检测)
                        val carryLoc = current.clone().subtract(0.0, heightOffset, 0.0)

                        // [NEW] 检测：如果把怪移到这里会卡住吗？
                        if (!isSafe(e, carryLoc)) {
                            // !!! 发生碰撞 (比如大怪进小门，或擦到了墙) !!!

                            // 在当前位置(current)执行单体钉墙
                            // current 是中心点位置，pinSingleMob 会自己计算脚部
                            // face 传 null，会自动根据箭矢反方向钉墙
                            pinSingleMob(e, current, velocity, null)

                            // 播放一个撞击音效
                            e.world.playSound(e.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.5f)

                            return@removeIf true // 从列表中移除 (已钉墙)
                        }

                        // 安全，执行传送
                        carryLoc.yaw = e.location.yaw
                        carryLoc.pitch = e.location.pitch
                        e.teleport(carryLoc)

                        // 维持无重力
                        e.velocity = Vector(0, 0, 0)
                        e.fallDistance = 0f
                    }
                    false
                }

                // 距离/高度限制
                if (traveled >= maxRange || current.y > 300) {
                    releaseMobs(capturedMobs)
                    this.cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // 自然消散：温和放下怪物
    private fun releaseMobs(mobs: Set<UUID>) {
        for (uuid in mobs) {
            val e = Bukkit.getEntity(uuid)
            if (e is LivingEntity) {
                // [核心修复] 恢复重力和碰撞
                e.setGravity(true)
                e.isCollidable = true
                // 给予缓降，防止高空掉落摔死 (副本防卡死机制)
                e.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0)) // 5秒缓降
                e.velocity = Vector(0.0, -0.2, 0.0) // 轻轻落下
            }
        }
    }

    // -----------------------------------------------------------
    // 钉墙逻辑 (修改为调用单体方法)
    // -----------------------------------------------------------
    private fun pinMobsToWall(mobs: Set<UUID>, hitBlock: Block, hitLoc: Location, face: BlockFace?, arrowVelocity: Vector) {
        // 群体钉墙时的公共特效
        hitBlock.world.playSound(hitLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.5f)
        hitBlock.world.spawnParticle(Particle.BLOCK, hitLoc, 20, 0.5, 0.5, 0.5, hitBlock.blockData)

        for (uuid in mobs) {
            val e = Bukkit.getEntity(uuid)
            if (e is LivingEntity) {
                pinSingleMob(e, hitLoc, arrowVelocity, face)
            }
        }
    }

    /**
     * [提取] 单体钉墙逻辑
     * @param face 可为 null，若为 null 则自动使用箭矢反方向定身
     */
    private fun pinSingleMob(le: LivingEntity, hitLoc: Location, arrowVelocity: Vector, face: BlockFace?) {
        // 1. 伤害与状态
        CombatUtil.dealPhysicalSkillDamage(null, le, 10.0, false)
        le.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 255, false, false))
        le.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 60, 200, false, false))
        le.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 255, false, false))

        // 2. 初始定位 (怪物中心对齐命中点)
        val halfHeight = le.height / 2.0
        val startCheckLoc = hitLoc.clone().subtract(0.0, halfHeight, 0.0)

        // 3. 计算安全方向
        val safeDirection = arrowVelocity.clone().normalize().multiply(-1)

        // 4. [智能寻找落点]
        val safePinLoc = findSmartSafeSpot(le, startCheckLoc, safeDirection)

        // 5. 朝向修正
        if (face != null) {
            safePinLoc.direction = face.oppositeFace.direction
        } else {
            // 如果没有指定面(空中撞墙)，则让怪面向墙壁(即箭矢飞来的方向)
            safePinLoc.direction = arrowVelocity.clone().normalize()
        }

        // 6. 执行定身
        le.teleport(safePinLoc)
        le.setGravity(false)
        le.isCollidable = false
        le.velocity = Vector(0, 0, 0)

        // 7. 物理弹出
        object : BukkitRunnable() {
            override fun run() {
                if (le.isValid) {
                    le.setGravity(true)
                    le.isCollidable = true
                    le.velocity = safeDirection.clone().multiply(0.3).setY(0.1)
                }
            }
        }.runTaskLater(plugin, 60L)

        le.world.spawnParticle(Particle.CRIT, le.eyeLocation, 10)
    }

    /**
     * [核心算法] 智能扫描器
     * 沿着 direction 方向后退，每退一步都尝试进行垂直修正。
     * 只要找到一个"既不卡头也不卡脚"的位置，立刻停止。
     */
    private fun findSmartSafeSpot(entity: LivingEntity, startLoc: Location, direction: Vector): Location {
        val candidate = startLoc.clone()

        // 初始微退，避免浮点数重叠
        candidate.add(direction.clone().multiply(0.1))

        // 限制回退距离 2.5格 (防止退太远穿墙)
        for (i in 0 until 25) {
            // 尝试在这个 X/Z 坐标下，寻找一个合法的 Y 坐标
            val resolved = tryResolveCollision(entity, candidate)
            if (resolved != null) {
                return resolved // 找到了！完美位置！
            }
            // 如果当前位置卡住了且无法修正，继续往后退
            candidate.add(direction.clone().multiply(0.1))
        }

        // 如果实在找不到，就在起始点硬着陆 (至少比卡在无限远处好)
        return startLoc
    }

    /**
     * [核心算法] 垂直碰撞解算器
     * 检查当前位置是否有碰撞。如果有，尝试通过"抬高脚"或"降低头"来解决。
     * @return 修正后的安全位置，如果无法修正(比如被夹扁了)则返回 null
     */
    private fun tryResolveCollision(entity: LivingEntity, loc: Location): Location? {
        if (isSafe(entity, loc)) return loc // 当前位置本来就安全，直接返回

        // 如果不安全，说明有方块侵入了怪物的碰撞箱。
        // 我们需要判断是"脚踩到了台阶"还是"头撞到了天花板"。

        val w = entity.width
        val h = entity.height
        val halfW = w / 2.0

        // 构建当前位置的虚拟碰撞箱
        val entityBox = BoundingBox(
            loc.x - halfW, loc.y, loc.z - halfW,
            loc.x + halfW, loc.y + h, loc.z + halfW
        )

        var highestFloorY = -9999.0
        var lowestCeilingY = 9999.0

        // 扫描周围方块，寻找"最高的地板"和"最低的天花板"
        val minX = floor(entityBox.minX).toInt()
        val maxX = ceil(entityBox.maxX).toInt()
        val minY = floor(entityBox.minY).toInt()
        val maxY = ceil(entityBox.maxY).toInt()
        val minZ = floor(entityBox.minZ).toInt()
        val maxZ = ceil(entityBox.maxZ).toInt()

        for (x in minX until maxX) {
            for (y in minY until maxY) {
                for (z in minZ until maxZ) {
                    val block = loc.world.getBlockAt(x, y, z)
                    if (block.isPassable) continue

                    for (blockBox in block.collisionShape.boundingBoxes) {
                        val worldBox = blockBox.clone().shift(x.toDouble(), y.toDouble(), z.toDouble())

                        if (entityBox.overlaps(worldBox)) {
                            // 判断这个碰撞箱是在怪物的下半身(地板/台阶)还是上半身(天花板)
                            val boxCenterY = worldBox.centerY
                            val entityCenterY = entityBox.centerY

                            if (boxCenterY < entityCenterY) {
                                // 这是一个脚下的障碍物 (如半砖、楼梯台阶)
                                if (worldBox.maxY > highestFloorY) {
                                    highestFloorY = worldBox.maxY
                                }
                            } else {
                                // 这是一个头顶的障碍物 (如天花板)
                                if (worldBox.minY < lowestCeilingY) {
                                    lowestCeilingY = worldBox.minY
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 尝试修正 1: 抬脚 ---
        if (highestFloorY > -9999) {
            val lifted = loc.clone()
            lifted.y = highestFloorY + 0.01 // 抬到障碍物上方
            if (isSafe(entity, lifted)) return lifted // 抬高后安全了！
        }

        // --- 尝试修正 2: 低头 ---
        if (lowestCeilingY < 9999) {
            val lowered = loc.clone()
            lowered.y = lowestCeilingY - h - 0.01 // 压到障碍物下方
            if (isSafe(entity, lowered)) return lowered // 压低后安全了！
        }

        // 如果抬脚和低头都无法解决碰撞(比如在1格高的夹缝里)，返回 null，让主循环继续往后退
        return null
    }

    /**
     * [辅助] 精确检测位置是否安全 (无重叠)
     */
    private fun isSafe(entity: LivingEntity, loc: Location): Boolean {
        val w = entity.width
        val h = entity.height
        val halfW = w / 2.0

        val entityBox = BoundingBox(
            loc.x - halfW, loc.y, loc.z - halfW,
            loc.x + halfW, loc.y + h, loc.z + halfW
        )

        // 稍微缩小一点检测范围(-0.01)，允许"紧贴"但不允许"嵌入"
        val checkStore = entityBox.clone().expand(-0.01)

        val minX = floor(entityBox.minX).toInt()
        val maxX = ceil(entityBox.maxX).toInt()
        val minY = floor(entityBox.minY).toInt()
        val maxY = ceil(entityBox.maxY).toInt()
        val minZ = floor(entityBox.minZ).toInt()
        val maxZ = ceil(entityBox.maxZ).toInt()

        for (x in minX until maxX) {
            for (y in minY until maxY) {
                for (z in minZ until maxZ) {
                    val block = loc.world.getBlockAt(x, y, z)
                    if (block.isPassable) continue

                    for (blockBox in block.collisionShape.boundingBoxes) {
                        val worldBox = blockBox.clone().shift(x.toDouble(), y.toDouble(), z.toDouble())
                        if (checkStore.overlaps(worldBox)) return false
                    }
                }
            }
        }
        return true
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        // 不需要处理普攻逻辑
    }
}