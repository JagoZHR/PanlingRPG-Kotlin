package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.SchematicManager
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Blaze
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SacredMountainTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId = "sacred_mountain_trial"
    override val waitDuration = 5

    val clearedBeasts = mutableSetOf<String>()

    companion object {
        val CORE_OFFSETS = mapOf(
            "qinglong" to Triple(-46, -1, -36),
            "zhuque"  to Triple(-55, 19,  20),
            "xuanwu"  to Triple(-114, 43, 18),
            "baihu"   to Triple(-103, 12, -27)
        )
        val PANGU_CORE_OFFSET = Triple(-93, 2, 0)
        val RANGE1_OFFSET = Triple(-7, 7, 33)
        val RANGE2_OFFSET = Triple(-107, 13, -41)
        val XUANWU_WATER_OFFSET = Triple(-111, 39, 22)

        val REGION_MOBS = mapOf(
            "qinglong" to listOf("forest_spider", "forest_wolf", "forest_skeleton", "forest_zombie"),
            "zhuque"  to listOf("desert_husk", "desert_zombie", "desert_wither_skeleton", "desert_skeleton"),
            "xuanwu"  to listOf("tortoise_shell_warden", "abyssal_drowned", "abyssal_guardian", "abyssal_bogged"),
            "baihu"   to listOf("scorched_bone_warrior", "cave_zombie", "cave_skeleton", "cave_spider")
        )
        val REGION_BOSS = mapOf(
            "qinglong" to "forest_spider",
            "zhuque"  to "desert_husk",
            "xuanwu"  to "tortoise_shell_warden",
            "baihu"   to "scorched_bone_warrior"
        )
        val ALL_BOSSES = REGION_BOSS.values.toList()
        val ALL_MOBS = REGION_MOBS.values.flatten()

        val JITAN_LIST = listOf(
            "qinglongjitan" to 1000,
            "zhuquejitan"  to 2000,
            "xuanwujitan"  to 3000,
            "baihujitan"   to 4000,
            "zhenpangu"    to 5000
        )
        val JITAN_Z = mapOf(
            "qinglong" to 1000,
            "zhuque"  to 2000,
            "xuanwu"  to 3000,
            "baihu"   to 4000
        )

        val BEAST_NAME = mapOf(
            "qinglong" to "§a青龙",
            "zhuque"  to "§c朱雀",
            "xuanwu"  to "§3玄武",
            "baihu"   to "§f白虎"
        )
        val BEAST_ORDER = listOf("qinglong", "zhuque", "xuanwu", "baihu")
    }

    private fun difficultyScale(instance: DungeonInstance): Double =
        1.0 + (instance.players.size - 1) * 0.5

    // ---- jitan 粘贴已由 DungeonManager 在 tick 启动前完成，这里直接进标准流程 ----
    override fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase {
        return StandardWaitingPhase(plugin, instance, if (clearedBeasts.isEmpty()) waitDuration else 5) {
            instance.nextPhase(createGamePhase(instance))
        }
    }

    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase {
        clearedBeasts.clear()
        return MainPhase(instance, clearedBeasts)
    }

    override fun createRewardConfig(instance: DungeonInstance): RewardConfig? {
        return RewardConfig(
            title = "§6[圣山] 四圣终试通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            money = 2000.0
        )
    }

    // ==========================================
    // MainPhase
    // ==========================================
    inner class MainPhase(
        instance: DungeonInstance,
        private val clearedBeasts: MutableSet<String>
    ) : AbstractDungeonPhase(plugin, instance) {

        private val cores = mutableMapOf<String, Blaze>()
        private val coreTimers = mutableMapOf<String, Int>()
        private var rangeTimer = 40
        private var panguCore: Blaze? = null
        private var isPanguPhase = false
        private var isFinished = false
        private val spawnedMobs = mutableListOf<LivingEntity>()

        override fun start() {
            if (clearedBeasts.isEmpty()) {
                val (dx, dy, dz) = XUANWU_WATER_OFFSET
                instance.centerLocation.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block.type = Material.WATER
                instance.broadcast("§6════════════════════════════════")
                instance.broadcast("§e§l四圣兽合力召唤出了盘古的倒影作为最终的考验！")
                instance.broadcast("§7在盘古周围的柱子里，沉睡着对应圣兽的核心。")
                instance.broadcast("§7打破核心以进入圣兽的幻境 —— 只有破解全部四个幻境，")
                instance.broadcast("§7盘古的内核才会显露出来。")
                instance.broadcast("§6════════════════════════════════")
                instance.broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
            }
            spawnCores()
        }

        private fun spawnCores() {
            val center = instance.centerLocation
            val scale = difficultyScale(instance)
            for (beast in BEAST_ORDER) {
                if (clearedBeasts.contains(beast)) continue
                val (dx, dy, dz) = CORE_OFFSETS[beast] ?: continue
                val loc = center.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
                cores[beast] = spawnCoreAt(loc, "${BEAST_NAME[beast]}核心", 3000.0 * scale)
                coreTimers[beast] = Random.nextInt(60, 140)
            }
            val alive = cores.size
            if (alive > 0) {
                instance.broadcast("§e${alive} 个圣兽核心已激活 —— 打破它们以进入幻境。")
            } else if (clearedBeasts.size >= 4) {
                revealPanguCore()
            }
        }

        private fun spawnCoreAt(loc: Location, name: String, hp: Double): Blaze {
            val core = instance.world.spawnEntity(loc, EntityType.BLAZE) as Blaze
            core.customName(Component.text(name))
            core.isCustomNameVisible = true
            core.setAI(false)
            core.isSilent = true
            core.isGlowing = true
            core.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
            core.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            core.health = hp
            core.lootTable = null
            core.equipment?.let {
                it.helmetDropChance = 0f; it.chestplateDropChance = 0f
                it.leggingsDropChance = 0f; it.bootsDropChance = 0f
            }
            core.isPersistent = true
            core.removeWhenFarAway = false
            return core
        }

        private fun revealPanguCore() {
            isPanguPhase = true
            val center = instance.centerLocation
            val scale = difficultyScale(instance)
            val (dx, dy, dz) = PANGU_CORE_OFFSET
            val loc = center.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
            panguCore = spawnCoreAt(loc, "§6盘古内核", 7000.0 * scale)
            instance.broadcast("§6§l四象核心全部破解！盘古的内核显露了出来！")
            instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
        }

        override fun onTick() {
            if (isFinished) return

            val dying = mutableListOf<String>()
            for ((beast, core) in cores) {
                if (!core.isValid || core.isDead) dying.add(beast)
            }
            for (beast in dying) {
                cores.remove(beast); coreTimers.remove(beast)
                instance.broadcast("${BEAST_NAME[beast]}§e核心已破碎！幻境之门开启...")
                instance.broadcastSound(Sound.BLOCK_GLASS_BREAK)
                isFinished = true
                instance.nextPhase(JitanPhase(instance, beast) {
                    clearedBeasts.add(beast)
                    instance.nextPhase(MainPhase(instance, clearedBeasts))
                })
                return
            }

            if (isPanguPhase && panguCore != null && (!panguCore!!.isValid || panguCore!!.isDead)) {
                isFinished = true
                instance.broadcast("§6§l盘古内核已摧毁！远古的试炼终于结束。")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                goToRewardPhase(instance)
                return
            }

            for ((beast, timer) in coreTimers.toMap()) {
                val t = timer - 1
                if (t <= 0) { spawnNearCore(beast); coreTimers[beast] = Random.nextInt(600, 1500) }
                else coreTimers[beast] = t
            }

            rangeTimer--
            if (rangeTimer <= 0) { spawnInRange(); rangeTimer = Random.nextInt(150, 300) }

            if (!isPanguPhase && cores.isEmpty() && clearedBeasts.size >= 4) revealPanguCore()
        }

        private fun findSpawnLoc(center: Location, radius: Double, minDist: Double = 3.0): Location? {
            val world = center.world ?: return null
            for (attempt in 0 until 15) {
                val angle = Random.nextDouble() * Math.PI * 2
                val dist = minDist + Random.nextDouble() * (radius - minDist)
                val xBlock = (center.x + cos(angle) * dist).toInt()
                val zBlock = (center.z + sin(angle) * dist).toInt()
                val startY = center.blockY
                for (dy in 0..10) for (sign in listOf(1, -1)) {
                    val attemptY = startY + dy * sign
                    if (attemptY < world.minHeight || attemptY >= world.maxHeight) continue
                    val loc = Location(world, xBlock + 0.5, attemptY.toDouble(), zBlock + 0.5)
                    if (isSafeSpawn(loc)) return loc.add(0.0, 0.2, 0.0)
                }
            }
            return null
        }

        private fun isSafeSpawn(loc: Location): Boolean {
            val block = loc.block; val below = loc.clone().subtract(0.0, 1.0, 0.0).block
            val above = loc.clone().add(0.0, 1.0, 0.0).block
            if (!below.type.isSolid) return false
            if (below.type.name.let { it.contains("FENCE") || it.contains("WALL") || it.contains("TRAPDOOR") }) return false
            if (block.type.isOccluding || above.type.isOccluding) return false
            var n = 0
            if (block.getRelative(1,0,0).type.isSolid) n++
            if (block.getRelative(-1,0,0).type.isSolid) n++
            if (block.getRelative(0,0,1).type.isSolid) n++
            if (block.getRelative(0,0,-1).type.isSolid) n++
            return n < 3
        }

        private fun spawnNearCore(beast: String) {
            val core = cores[beast] ?: return
            val mobs = REGION_MOBS[beast] ?: return
            val boss = REGION_BOSS[beast] ?: return
            val scale = difficultyScale(instance)
            for (i in 0 until Random.nextInt(3, 7)) {
                val mobId = if (i == 0) boss else mobs.random()
                val loc = findSpawnLoc(core.location, 10.0, 2.0) ?: continue
                val mob = plugin.mobManager.spawnMob(loc, mobId) ?: continue
                scaleHp(mob, scale); spawnedMobs.add(mob)
            }
        }

        private fun spawnInRange() {
            val center = instance.centerLocation
            val (x1, y1, z1) = RANGE1_OFFSET; val (x2, y2, z2) = RANGE2_OFFSET
            val scale = difficultyScale(instance)
            for (i in 0 until Random.nextInt(6, 13)) {
                val mobId = if (isPanguPhase) ALL_BOSSES.random() else ALL_MOBS.random()
                val rx = minOf(x1,x2) + Random.nextDouble() * (maxOf(x1,x2) - minOf(x1,x2))
                val rz = minOf(z1,z2) + Random.nextDouble() * (maxOf(z1,z2) - minOf(z1,z2))
                val sc = center.clone().add(rx, ((y1+y2)/2).toDouble(), rz)
                val loc = findSpawnLoc(sc, 8.0, 1.0) ?: continue
                val mob = plugin.mobManager.spawnMob(loc, mobId) ?: continue
                scaleHp(mob, scale); spawnedMobs.add(mob)
            }
        }

        private fun scaleHp(mob: LivingEntity, scale: Double) {
            mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; mob.health = attr.baseValue }
        }

        override fun end() {
            cores.values.forEach { if (it.isValid) it.remove() }; cores.clear()
            panguCore?.let { if (it.isValid) it.remove() }
            spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear()
        }
    }

    // ==========================================
    // JitanPhase
    // ==========================================
    inner class JitanPhase(
        instance: DungeonInstance,
        private val beast: String,
        private val onReturn: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance) {

        private var boss: LivingEntity? = null
        private val adds = mutableListOf<LivingEntity>()
        private var isFinished = false

        override fun start() {
            val name = BEAST_NAME[beast] ?: beast
            instance.broadcast("${name}§e的幻境已然展开 —— 击败其中的守护者！")
            instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)

            val zOff = JITAN_Z[beast] ?: return
            val jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, zOff.toDouble())
            for (uuid in instance.players) {
                Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add(
                    (Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
            }

            val scale = difficultyScale(instance)
            val bossId = REGION_BOSS[beast] ?: return
            boss = plugin.mobManager.spawnMob(jitanCenter.clone().add(0.0, 1.0, 0.0), bossId)
            boss?.let { scaleHp(it, scale) }

            val pool = REGION_MOBS[beast]?.filter { it != bossId } ?: emptyList()
            repeat(Random.nextInt(2, 4)) {
                if (pool.isEmpty()) return@repeat
                val m = plugin.mobManager.spawnMob(jitanCenter.clone().add(
                    (Random.nextDouble()-0.5)*6, 1.0, (Random.nextDouble()-0.5)*6), pool.random()) ?: return@repeat
                scaleHp(m, scale); adds.add(m)
            }
        }

        override fun onTick() {
            if (isFinished) return
            if (boss == null || !boss!!.isValid || boss!!.isDead) {
                isFinished = true
                instance.broadcast("${BEAST_NAME[beast]}§e的幻境已被破解！")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                val mc = instance.centerLocation.clone()
                for (uuid in instance.players) {
                    Bukkit.getPlayer(uuid)?.teleport(mc.clone().add(
                        (Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                }
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
            }
        }

        override fun end() {
            boss?.let { if (it.isValid) it.remove() }
            adds.forEach { if (it.isValid) it.remove() }; adds.clear()
        }

        private fun scaleHp(mob: LivingEntity, scale: Double) {
            mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; mob.health = attr.baseValue }
        }
    }
}
