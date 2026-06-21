package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.impl.trial.sacred.*
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Blaze
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.sin

class SacredMountainTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId = "sacred_mountain_trial"
    override val waitDuration = 5
    val clearedBeasts = mutableSetOf<String>()

    companion object {
        val CORE_OFFSETS = mapOf(
            "qinglong" to Triple(-46, -1, -36), "zhuque" to Triple(-55, 19, 20),
            "xuanwu" to Triple(-114, 43, 18), "baihu" to Triple(-103, 12, -27)
        )
        val PANGU_CORE_OFFSET = Triple(-93, 2, 0)
        val RANGE1_OFFSET = Triple(-7, 7, 33); val RANGE2_OFFSET = Triple(-107, 13, -41)
        val XUANWU_WATER_OFFSET = Triple(-111, 39, 22)

        val REGION_MOBS = mapOf(
            "qinglong" to listOf("forest_boar", "forest_venom_spider", "forest_treant", "forest_alpha_wolf"),
            "zhuque"  to listOf("desert_fire_elemental", "desert_scorpion", "desert_ifrit", "desert_sun_priest"),
            "xuanwu"  to listOf("lake_frost_knight", "lake_frost_priest", "lake_guardian_spirit", "lake_eel"),
            "baihu"   to listOf("cave_rock_zombie", "cave_crystal_golem", "cave_armored_beetle", "cave_black_stone_guard")
        )
        val REGION_BOSS = mapOf(
            "qinglong" to "forest_alpha_wolf", "zhuque" to "desert_ifrit",
            "xuanwu" to "lake_frost_knight", "baihu" to "cave_rock_zombie"
        )
        val ALL_BOSSES = REGION_BOSS.values.toList(); val ALL_MOBS = REGION_MOBS.values.flatten()
        val JITAN_Z = mapOf("qinglong" to 1000, "zhuque" to 2000, "xuanwu" to 3000, "baihu" to 4000)
        val BEAST_NAME = mapOf("qinglong" to "§a青龙", "zhuque" to "§c朱雀", "xuanwu" to "§3玄武", "baihu" to "§f白虎")
        val BEAST_ORDER = listOf("qinglong", "zhuque", "xuanwu", "baihu")

        fun isSafeSpawn(loc: Location): Boolean {
            val block = loc.block
            val below = loc.clone().subtract(0.0, 1.0, 0.0).block
            val above = loc.clone().add(0.0, 1.0, 0.0).block
            if (!below.type.isSolid) return false
            if (block.type.isOccluding || above.type.isOccluding) return false
            if (below.type.name.let { it.contains("FENCE") || it.contains("WALL") || it.contains("TRAPDOOR") }) return false
            var n = 0
            if (block.getRelative(1, 0, 0).type.isSolid) n++
            if (block.getRelative(-1, 0, 0).type.isSolid) n++
            if (block.getRelative(0, 0, 1).type.isSolid) n++
            if (block.getRelative(0, 0, -1).type.isSolid) n++
            return n < 3
        }
    }

    private fun difficultyScale(instance: DungeonInstance) = 1.0 + (instance.players.size - 1) * 0.5

    override fun createInitialPhase(instance: DungeonInstance) = StandardWaitingPhase(plugin, instance, if (clearedBeasts.isEmpty()) waitDuration else 5) {
        instance.nextPhase(createGamePhase(instance))
    }

    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase { clearedBeasts.clear(); return MainPhase(instance, clearedBeasts) }
    override fun createRewardConfig(instance: DungeonInstance) = RewardConfig(title = "§6[圣山] 四圣终试通过！", autoReward = true, autoRewardDelay = 100L, money = 2000.0)

    private fun createJitanPhase(instance: DungeonInstance, beast: String, onReturn: () -> Unit): AbstractDungeonPhase =
        when (beast) {
            "qinglong" -> QinglongJitanPhase(plugin, instance, beast, onReturn)
            "zhuque" -> ZhuqueJitanPhase(plugin, instance, beast, onReturn)
            "baihu" -> BaihuJitanPhase(plugin, instance, beast, onReturn)
            "xuanwu" -> XuanwuJitanPhase(plugin, instance, beast, onReturn)
            else -> QinglongJitanPhase(plugin, instance, "qinglong", onReturn)
        }

    private fun scaleHp(mob: LivingEntity, scale: Double) {
        mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; mob.health = attr.baseValue }
    }

    // ==================== MainPhase ====================
    inner class MainPhase(instance: DungeonInstance, private val clearedBeasts: MutableSet<String>) : AbstractDungeonPhase(plugin, instance) {
        private val cores = mutableMapOf<String, Blaze>(); private val coreTimers = mutableMapOf<String, Int>()
        private var rangeTimer = 40; private var panguCore: Blaze? = null; private var isPanguPhase = false; private var isFinished = false; private var firstWave = false
        private val spawnedMobs = mutableListOf<LivingEntity>()

        override fun start() {
            if (clearedBeasts.isEmpty()) {
                val (dx, dy, dz) = XUANWU_WATER_OFFSET; instance.centerLocation.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block.type = Material.WATER
                instance.broadcast("§6════════════════════════════════"); instance.broadcast("§e§l四圣兽合力召唤出了盘古的倒影作为最终的考验！")
                instance.broadcast("§7在盘古周围的柱子里，沉睡着对应圣兽的核心。"); instance.broadcast("§7打破核心以进入圣兽的幻境 —— 只有破解全部四个幻境，")
                instance.broadcast("§7盘古的内核才会显露出来。"); instance.broadcast("§6════════════════════════════════"); instance.broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
            }
            spawnCores()
        }
        private fun spawnCores() {
            val c = instance.centerLocation; val s = difficultyScale(instance)
            for (b in BEAST_ORDER) { if (clearedBeasts.contains(b)) continue; val (dx, dy, dz) = CORE_OFFSETS[b] ?: continue; cores[b] = spawnCoreAt(c.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()), "${BEAST_NAME[b]}核心", 3000.0 * s); coreTimers[b] = Random.nextInt(60, 140) }
            val a = cores.size; if (a > 0) instance.broadcast("§e$a 个圣兽核心已激活 —— 打破它们以进入幻境。") else if (clearedBeasts.size >= 4) revealPanguCore()
        }
        private fun spawnCoreAt(loc: Location, name: String, hp: Double): Blaze {
            val c = instance.world.spawnEntity(loc, EntityType.BLAZE) as Blaze; c.customName(Component.text(name)); c.isCustomNameVisible = true
            c.setAI(false); c.isSilent = true; c.isGlowing = true
            c.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0; c.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp; c.health = hp
            c.lootTable = null; c.equipment?.let { it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f }
            c.isPersistent = true; c.removeWhenFarAway = false; return c
        }
        private fun revealPanguCore() {
            isPanguPhase = true; firstWave = true; val (dx, dy, dz) = PANGU_CORE_OFFSET
            panguCore = spawnCoreAt(instance.centerLocation.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()), "§6盘古内核", 7000.0 * difficultyScale(instance))
            instance.broadcast("§6§l四象核心全部破解！盘古的内核显露了出来！"); instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
        }
        override fun onTick() {
            if (isFinished) return
            val dying = mutableListOf<String>(); for ((b, c) in cores) { if (!c.isValid || c.isDead) dying.add(b) }
            for (b in dying) {
                cores.remove(b); coreTimers.remove(b); instance.broadcast("${BEAST_NAME[b]}§e核心已破碎！幻境之门开启..."); instance.broadcastSound(Sound.BLOCK_GLASS_BREAK)
                isFinished = true; instance.nextPhase(createJitanPhase(instance, b) { clearedBeasts.add(b); instance.nextPhase(MainPhase(instance, clearedBeasts)) }); return
            }
            if (isPanguPhase && panguCore != null && (!panguCore!!.isValid || panguCore!!.isDead)) {
                isFinished = true; instance.broadcast("§6§l盘古内核已摧毁！"); instance.broadcast("§e真·盘古的幻境正在展开..."); instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
                instance.nextPhase(ZhenpanguPhase(plugin, instance) { goToRewardPhase(instance) }); return
            }
            for ((b, t) in coreTimers.toMap()) { val nt = t - 1; if (nt <= 0) { spawnNearCore(b); coreTimers[b] = Random.nextInt(600, 1500) } else coreTimers[b] = nt }
            rangeTimer--; if (rangeTimer <= 0) { spawnInRange(); rangeTimer = Random.nextInt(150, 300) }
            if (!isPanguPhase && cores.isEmpty() && clearedBeasts.size >= 4) revealPanguCore()
        }
        private fun findSpawnLoc(center: Location, radius: Double, minDist: Double = 3.0): Location? {
            val w = center.world ?: return null
            for (a in 0 until 15) { val ang = Random.nextDouble() * Math.PI * 2; val d = minDist + Random.nextDouble() * (radius - minDist)
                val xb = (center.x + cos(ang) * d).toInt(); val zb = (center.z + sin(ang) * d).toInt()
                for (dy in 0..10) for (s in listOf(1, -1)) { val ay = center.blockY + dy * s; if (ay < w.minHeight || ay >= w.maxHeight) continue
                    val l = Location(w, xb + 0.5, ay.toDouble(), zb + 0.5); if (isSafeSpawn(l)) return l.add(0.0, 0.2, 0.0) }
            }; return null
        }
        private fun spawnNearCore(beast: String) {
            val c = cores[beast] ?: return; val ms = REGION_MOBS[beast] ?: return; val b = REGION_BOSS[beast] ?: return; val s = difficultyScale(instance)
            for (i in 0 until Random.nextInt(3, 7)) { val id = if (i == 0) b else ms.random()
                val l = findSpawnLoc(c.location, 10.0, 2.0) ?: continue; val m = plugin.mobManager.spawnMob(l, id) ?: continue
                scaleHp(m, s); spawnedMobs.add(m) }
        }
        private fun spawnInRange() {
            val c = instance.centerLocation; val (x1, y1, z1) = RANGE1_OFFSET; val (x2, y2, z2) = RANGE2_OFFSET; val s = difficultyScale(instance)
            val waveCount = if (firstWave) { firstWave = false; Random.nextInt(24, 53) } else Random.nextInt(6, 13)
            for (i in 0 until waveCount) { val id = if (isPanguPhase) ALL_BOSSES.random() else ALL_MOBS.random()
                val rx = minOf(x1, x2) + Random.nextDouble() * (maxOf(x1, x2) - minOf(x1, x2))
                val rz = minOf(z1, z2) + Random.nextDouble() * (maxOf(z1, z2) - minOf(z1, z2))
                val l = findSpawnLoc(c.clone().add(rx, ((y1 + y2) / 2).toDouble(), rz), 8.0, 1.0) ?: continue
                val m = plugin.mobManager.spawnMob(l, id) ?: continue; scaleHp(m, s); spawnedMobs.add(m) }
        }
        override fun end() { cores.values.forEach { if (it.isValid) it.remove() }; cores.clear(); panguCore?.let { if (it.isValid) it.remove() }; spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear() }
    }
}
