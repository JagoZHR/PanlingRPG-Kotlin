package com.panling.basic.dungeon.impl.trial.sacred

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.impl.trial.SacredMountainTrialLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.Chunk
import org.bukkit.event.entity.EntityDeathEvent
import java.util.UUID
import kotlin.random.Random

class QinglongJitanPhase(
    plugin: PanlingBasic, instance: DungeonInstance,
    private val beast: String,
    private val onReturn: () -> Unit
) : AbstractDungeonPhase(plugin, instance), Listener {

    private val qlTypes = linkedMapOf(
        "骷髅" to EntityType.SKELETON, "蜘蛛" to EntityType.SPIDER,
        "僵尸" to EntityType.ZOMBIE, "尸壳" to EntityType.HUSK,
        "溺尸" to EntityType.DROWNED, "流髑" to EntityType.STRAY,
        "洞穴蜘蛛" to EntityType.CAVE_SPIDER
    )

    private data class PointSpawn(val id: String, val loc: Location, var entity: LivingEntity? = null)

    private val points = mutableListOf<PointSpawn>()
    private val areaMobs = mutableListOf<LivingEntity>()
    private var areaTimer = 0
    private var quizActive = false; private var isFinished = false; private val loadedChunks = mutableSetOf<Chunk>()
 private var allFixedDead = false
    private val playerOptions = mutableMapOf<UUID, Map<String, String>>()
    private val playerCorrect = mutableMapOf<UUID, Set<String>>()
    private lateinit var jitanCenter: Location
    private var lastWarnTick = 0L
    private var spawnComplete = false; private var spawnDelay = 40

    // 问答追踪
    private var addKillCount = 0
    private var lastKilledPosition = ""
    private val deadGuardians = mutableSetOf<String>()
    private val pulseOffsets = mutableMapOf<String, Long>()

    override fun getReviveLocation() = jitanCenter

    override fun start() {
        val z = SacredMountainTrialLogic.JITAN_Z[beast] ?: 0
        jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, z.toDouble())
        instance.broadcast("§a青龙§e的幻境已然展开 —— 击败散落的守护者，抵达终点接受试炼！")
        instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

        points.add(PointSpawn("center", jitanCenter.clone().add(0.4, 0.0, -45.9)))
        points.add(PointSpawn("floor11", jitanCenter.clone().add(18.2, 5.0, -83.0)))
        points.add(PointSpawn("floor12", jitanCenter.clone().add(-17.8, 5.0, -83.6)))
        points.add(PointSpawn("floor2", jitanCenter.clone().add(0.3, 20.0, -78.6)))

        for (pt in points) {
            val m = plugin.mobManager.spawnMob(pt.loc, "dungeon_sacred_qinglong") ?: continue
            val s = 1.0 + (instance.players.size - 1) * 0.5
            m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
            if (m is Zombie) { m.setBaby(false); m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            pt.entity = m
            pulseOffsets[pt.id] = Random.nextLong(0, 80)
        }

        areaTimer = Random.nextInt(200, 360)

        // 强制加载幻境区块
        val w = instance.world
        for (dx in -5..5) for (dz in -5..5) {
            val chunk = w.getChunkAt(jitanCenter.chunk.x + dx, jitanCenter.chunk.z + dz)
            chunk.addPluginChunkTicket(plugin)
            loadedChunks.add(chunk)
        }

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun onTick() {
        if (isFinished) return
        if (!spawnComplete) { spawnDelay--; if (spawnDelay <= 0) spawnComplete = true }
        if (spawnComplete && !allFixedDead && points.all { it.entity == null || !it.entity!!.isValid || it.entity!!.isDead }) {
            allFixedDead = true; instance.broadcast("§a大殿中的守护者已全部被击败——前往终点接受最后的试炼吧。")
        }
        // 死亡跟踪已迁移至 onMobDeath()（独立事件，不做 onTick 轮询）
        val fx = jitanCenter.clone().add(0.5, 21.0, -92.7)
        if (!quizActive) for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (p.location.distanceSquared(fx) < 9.0) {
                if (!allFixedDead) {
                    if (instance.tickCount - lastWarnTick > 40) { p.sendMessage("§c先击败散落在大殿中的守护者再来。"); lastWarnTick = instance.tickCount }
                } else startQuiz(); break
            }
        }

        // 守护者技能：灵气脉冲（随机扰动防同步）
        for (pt in points) {
            val e = pt.entity
            val offset = pulseOffsets[pt.id] ?: 0L
            if (e != null && e.isValid && !e.isDead && (instance.tickCount + offset) % 160 == 0L) {
                val loc = e.location.clone()
                instance.broadcast("§a守护者释放了灵气脉冲！")
                for (j in 0 until 12) {
                    val angle = 2.0 * Math.PI * j / 12
                    loc.world.spawnParticle(Particle.ENCHANT, loc.x + kotlin.math.cos(angle) * 3, loc.y + 0.2, loc.z + kotlin.math.sin(angle) * 3, 3, 0.0, 0.0, 0.0, 0.0)
                }
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (!e.isValid || e.isDead) return@Runnable
                    for (uuid in instance.players) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        if (p.location.distance(loc) <= 3.0) p.damage(15.0)
                    }
                }, 20L)
            }
        }

        if (!quizActive) { areaTimer--; if (areaTimer <= 0) { spawnArea(); areaTimer = Random.nextInt(300, 500) } }
    }

    // 独立事件检测守护者死亡，不做 onTick 轮询 isDead
    override fun onMobDeath(event: EntityDeathEvent) {
        if (isFinished || quizActive) return
        for (pt in points) {
            if (event.entity == pt.entity && pt.id !in deadGuardians) {
                deadGuardians.add(pt.id)
                lastKilledPosition = pt.id
                instance.setPhaseTitle("§a守护者 §f${deadGuardians.size}/4")
                return
            }
        }
    }

    private fun spawnArea() {
        val count = Random.nextInt(4, 9)
        for (pt in points.shuffled().take(count.coerceAtMost(points.size))) {
            for (i in 0 until (count / points.size).coerceAtLeast(1)) {
                val loc = findSpawnNoGrass(pt.loc, 8.0) ?: continue
                val m = plugin.mobManager.spawnMob(loc, "dungeon_sacred_qinglong_add") ?: continue
                areaMobs.add(m)
            }
        }
        addKillCount += areaMobs.count { it.isValid && !it.isDead }
    }

    private fun findSpawnNoGrass(center: Location, radius: Double): Location? {
        val w = center.world ?: return null
        for (a in 0 until 15) {
            val ang = Random.nextDouble() * Math.PI * 2; val d = 2.0 + Random.nextDouble() * (radius - 2.0)
            val xb = (center.x + kotlin.math.cos(ang) * d).toInt(); val zb = (center.z + kotlin.math.sin(ang) * d).toInt()
            for (dy in 0..8) for (s in listOf(1, -1)) {
                val ay = center.blockY + dy * s; if (ay < w.minHeight || ay >= w.maxHeight) continue
                val loc = Location(w, xb + 0.5, ay.toDouble(), zb + 0.5)
                val below = loc.clone().subtract(0.0, 1.0, 0.0).block
                if (!below.type.isSolid || below.type.name.contains("GRASS_BLOCK")) continue
                if (below.type.name.let { it.contains("FENCE") || it.contains("WALL") }) continue
                if (loc.block.type.isOccluding || loc.clone().add(0.0, 1.0, 0.0).block.type.isOccluding) continue
                return loc.add(0.0, 0.2, 0.0)
            }
        }
        return null
    }

    // ---- Quiz ----
    private fun startQuiz() {
        quizActive = true; playerOptions.clear(); playerCorrect.clear()
        instance.broadcast("§e§l试炼之问：回答正确即可破解幻境，答错则将重置一切！")
        instance.broadcastSound(Sound.BLOCK_NOTE_BLOCK_CHIME)

        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            val (question, correct, options) = genQuestion()
            sendQuiz(p, question, options, correct)
        }
    }

    private fun genQuestion(): Triple<String, Set<String>, List<String>> {
        val all = qlTypes.keys.toList()
        return when (Random.nextInt(3)) {
            0 -> {
                // 数量题：杀了多少只腐化树苗
                val correct = addKillCount.toString()
                val wrongs = listOf(
                    (addKillCount + Random.nextInt(1, 4)).toString(),
                    (addKillCount - Random.nextInt(1, 3)).coerceAtLeast(0).toString(),
                    (addKillCount + Random.nextInt(3, 7)).toString()
                )
                Triple("在击败守护者的过程中，你一共消灭了多少只腐化树苗？", setOf(correct), (wrongs + correct).shuffled())
            }
            1 -> {
                // 顺序题：最后击败的守护者位置
                val posName = when (lastKilledPosition.ifEmpty { points.random().id }) {
                    "center" -> "大殿中心"; "floor11" -> "一层左侧"; "floor12" -> "一层右侧"; "floor2" -> "二层"; else -> "未知位置"
                }
                val otherNames = listOf("大殿中心", "一层左侧", "一层右侧", "二层").filter { it != posName }
                Triple("四只守护者中，你最后击败的是哪一个位置的？", setOf(posName), (otherNames.take(3) + posName).shuffled())
            }
            else -> {
                // 观察题：灵气脉冲范围
                val correct = "3格"
                val wrongs = listOf("2格", "4格", "5格")
                Triple("守护者释放灵气脉冲的范围是多少格？", setOf(correct), (wrongs + correct).shuffled())
            }
        }
    }

    private fun sendQuiz(p: Player, question: String, options: List<String>, correct: Set<String>) {
        val letters = listOf("A", "B", "C", "D"); p.sendMessage(""); p.sendMessage("§6══════════════════════"); p.sendMessage("§e$question"); p.sendMessage("")
        val map = mutableMapOf<String, String>()
        for (i in options.indices) {
            map[letters[i]] = options[i]
            p.sendMessage(Component.text("  §6[${letters[i]}] §a${options[i]}").clickEvent(ClickEvent.runCommand("/plbasic internal qanswer ${letters[i]}")))
        }
        p.sendMessage("§6══════════════════════")
        playerOptions[p.uniqueId] = map; playerCorrect[p.uniqueId] = correct
    }

    override fun onQAnswer(player: Player, letter: String) {
        if (!quizActive || isFinished) return
        if (!instance.players.contains(player.uniqueId)) return
        val up = letter.trim().uppercase()
        if (up !in listOf("A", "B", "C", "D")) return
        val chosen = playerOptions[player.uniqueId]?.get(up) ?: return
        val correct = playerCorrect[player.uniqueId] ?: return
        if (chosen in correct) { win(player) } else { fail(player) }
    }

    private fun win(p: Player) {
        isFinished = true; quizActive = false
        instance.broadcast("§a§l${p.name} 回答正确！青龙幻境已被破解！")
        instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
        val mc = instance.centerLocation.clone()
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
    }

    private fun fail(p: Player) {
        instance.broadcast("§c§l${p.name} 回答错误！所有人受到惩罚，幻境即将重置...")
        for (uuid in instance.players) {
            val pl = Bukkit.getPlayer(uuid) ?: continue; pl.damage(50.0)
            pl.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
        }
        resetAll()
    }

    private fun resetAll() {
        for (pt in points) { pt.entity?.let { if (it.isValid) it.remove() }; pt.entity = null }
        areaMobs.forEach { if (it.isValid) it.remove() }; areaMobs.clear()
        playerOptions.clear(); playerCorrect.clear(); quizActive = false; allFixedDead = false; spawnComplete = false; spawnDelay = 40
        addKillCount = 0; lastKilledPosition = ""
        deadGuardians.clear()
        pulseOffsets.clear()
        for (pt in points) {
            val m = plugin.mobManager.spawnMob(pt.loc, "dungeon_sacred_qinglong") ?: continue
            val s = 1.0 + (instance.players.size - 1) * 0.5
            m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
            if (m is Zombie) { m.setBaby(false); m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            pt.entity = m
            pulseOffsets[pt.id] = Random.nextLong(0, 80)
        }
        instance.setPhaseTitle("§a守护者 §f0/4")
        areaTimer = Random.nextInt(200, 360)
    }

    override fun end() {
        loadedChunks.forEach { it.removePluginChunkTicket(plugin) }; loadedChunks.clear()
        HandlerList.unregisterAll(this)
        for (pt in points) { pt.entity?.let { if (it.isValid) it.remove() } }; points.clear()
        areaMobs.forEach { if (it.isValid) it.remove() }; areaMobs.clear()
    }
}