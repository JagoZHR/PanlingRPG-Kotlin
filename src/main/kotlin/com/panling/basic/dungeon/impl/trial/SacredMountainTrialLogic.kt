package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
            "qinglong" to listOf("forest_spider","forest_wolf","forest_skeleton","forest_zombie"),
            "zhuque"  to listOf("desert_husk","desert_zombie","desert_wither_skeleton","desert_skeleton"),
            "xuanwu"  to listOf("tortoise_shell_warden","abyssal_drowned","abyssal_guardian","abyssal_bogged"),
            "baihu"   to listOf("scorched_bone_warrior","cave_zombie","cave_skeleton","cave_spider")
        )
        val REGION_BOSS = mapOf("qinglong" to "forest_spider","zhuque" to "desert_husk","xuanwu" to "tortoise_shell_warden","baihu" to "scorched_bone_warrior")
        val ALL_BOSSES = REGION_BOSS.values.toList(); val ALL_MOBS = REGION_MOBS.values.flatten()
        val JITAN_Z = mapOf("qinglong" to 1000, "zhuque" to 2000, "xuanwu" to 3000, "baihu" to 4000)
        val BEAST_NAME = mapOf("qinglong" to "§a青龙","zhuque" to "§c朱雀","xuanwu" to "§3玄武","baihu" to "§f白虎")
        val BEAST_ORDER = listOf("qinglong","zhuque","xuanwu","baihu")
    }

    private fun difficultyScale(instance: DungeonInstance) = 1.0 + (instance.players.size - 1) * 0.5

    override fun createInitialPhase(instance: DungeonInstance) = StandardWaitingPhase(plugin, instance, if (clearedBeasts.isEmpty()) waitDuration else 5) {
        instance.nextPhase(createGamePhase(instance))
    }

    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase { clearedBeasts.clear(); return MainPhase(instance, clearedBeasts) }
    override fun createRewardConfig(instance: DungeonInstance) = RewardConfig(title = "§6[圣山] 四圣终试通过！", autoReward = true, autoRewardDelay = 100L, money = 2000.0)

    private fun createJitanPhase(instance: DungeonInstance, beast: String, onReturn: () -> Unit): AbstractDungeonPhase =
        when (beast) { "qinglong" -> QinglongJitanPhase(instance, beast, onReturn); "zhuque" -> ZhuqueJitanPhase(instance, beast, onReturn); else -> JitanPhase(instance, beast, onReturn) }

    // ==================== MainPhase (unchanged) ====================
    inner class MainPhase(instance: DungeonInstance, private val clearedBeasts: MutableSet<String>) : AbstractDungeonPhase(plugin, instance) {
        private val cores = mutableMapOf<String, Blaze>(); private val coreTimers = mutableMapOf<String, Int>()
        private var rangeTimer = 40; private var panguCore: Blaze? = null; private var isPanguPhase = false; private var isFinished = false
        private val spawnedMobs = mutableListOf<LivingEntity>()

        override fun start() {
            if (clearedBeasts.isEmpty()) {
                val (dx,dy,dz)=XUANWU_WATER_OFFSET; instance.centerLocation.clone().add(dx.toDouble(),dy.toDouble(),dz.toDouble()).block.type=Material.WATER
                instance.broadcast("§6════════════════════════════════"); instance.broadcast("§e§l四圣兽合力召唤出了盘古的倒影作为最终的考验！")
                instance.broadcast("§7在盘古周围的柱子里，沉睡着对应圣兽的核心。"); instance.broadcast("§7打破核心以进入圣兽的幻境 —— 只有破解全部四个幻境，")
                instance.broadcast("§7盘古的内核才会显露出来。"); instance.broadcast("§6════════════════════════════════"); instance.broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
            }
            spawnCores()
        }
        private fun spawnCores() {
            val c=instance.centerLocation; val s=difficultyScale(instance)
            for(b in BEAST_ORDER){if(clearedBeasts.contains(b))continue;val(dx,dy,dz)=CORE_OFFSETS[b]?:continue;cores[b]=spawnCoreAt(c.clone().add(dx.toDouble(),dy.toDouble(),dz.toDouble()),"${BEAST_NAME[b]}核心",3000.0*s);coreTimers[b]= Random.nextInt(60,140)}
            val a=cores.size;if(a>0)instance.broadcast("§e${a} 个圣兽核心已激活 —— 打破它们以进入幻境。") else if(clearedBeasts.size>=4)revealPanguCore()
        }
        private fun spawnCoreAt(loc:Location,name:String,hp:Double):Blaze{val c=instance.world.spawnEntity(loc,EntityType.BLAZE) as Blaze;c.customName(Component.text(name));c.isCustomNameVisible=true;c.setAI(false);c.isSilent=true;c.isGlowing=true;c.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue=1.0;c.getAttribute(Attribute.MAX_HEALTH)?.baseValue=hp;c.health=hp;c.lootTable=null;c.equipment?.let{it.helmetDropChance=0f;it.chestplateDropChance=0f;it.leggingsDropChance=0f;it.bootsDropChance=0f};c.isPersistent=true;c.removeWhenFarAway=false;return c}
        private fun revealPanguCore(){isPanguPhase=true;val(dx,dy,dz)=PANGU_CORE_OFFSET;panguCore=spawnCoreAt(instance.centerLocation.clone().add(dx.toDouble(),dy.toDouble(),dz.toDouble()),"§6盘古内核",7000.0*difficultyScale(instance));instance.broadcast("§6§l四象核心全部破解！盘古的内核显露了出来！");instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)}
        override fun onTick(){
            if(isFinished)return
            val dying= mutableListOf<String>();for((b,c)in cores){if(!c.isValid||c.isDead)dying.add(b)}
            for(b in dying){cores.remove(b);coreTimers.remove(b);instance.broadcast("${BEAST_NAME[b]}§e核心已破碎！幻境之门开启...");instance.broadcastSound(Sound.BLOCK_GLASS_BREAK);isFinished=true;instance.nextPhase(createJitanPhase(instance,b){clearedBeasts.add(b);instance.nextPhase(MainPhase(instance,clearedBeasts))});return}
            if(isPanguPhase&&panguCore!=null&&(!panguCore!!.isValid||panguCore!!.isDead)){isFinished=true;instance.broadcast("§6§l盘古内核已摧毁！远古的试炼终于结束。");instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE);goToRewardPhase(instance);return}
            for((b,t)in coreTimers.toMap()){val nt=t-1;if(nt<=0){spawnNearCore(b);coreTimers[b]= Random.nextInt(600,1500)}else coreTimers[b]=nt}
            rangeTimer--;if(rangeTimer<=0){spawnInRange();rangeTimer= Random.nextInt(150,300)}
            if(!isPanguPhase&&cores.isEmpty()&&clearedBeasts.size>=4)revealPanguCore()
        }
        private fun findSpawnLoc(center:Location,radius:Double,minDist:Double=3.0):Location?{val w=center.world?:return null;for(a in 0 until 15){val ang=Random.nextDouble()*Math.PI*2;val d=minDist+Random.nextDouble()*(radius-minDist);val xb=(center.x+cos(ang)*d).toInt();val zb=(center.z+sin(ang)*d).toInt();for(dy in 0..10)for(s in listOf(1,-1)){val ay=center.blockY+dy*s;if(ay<w.minHeight||ay>=w.maxHeight)continue;val l=Location(w,xb+0.5,ay.toDouble(),zb+0.5);if(isSafeSpawn(l))return l.add(0.0,0.2,0.0)}};return null}
        private fun isSafeSpawn(loc:Location):Boolean{val b=loc.block;val below=loc.clone().subtract(0.0,1.0,0.0).block;val above=loc.clone().add(0.0,1.0,0.0).block;if(!below.type.isSolid)return false;if(below.type.name.let{it.contains("FENCE")||it.contains("WALL")||it.contains("TRAPDOOR")})return false;if(b.type.isOccluding||above.type.isOccluding)return false;var n=0;if(b.getRelative(1,0,0).type.isSolid)n++;if(b.getRelative(-1,0,0).type.isSolid)n++;if(b.getRelative(0,0,1).type.isSolid)n++;if(b.getRelative(0,0,-1).type.isSolid)n++;return n<3}
        private fun spawnNearCore(beast:String){val c=cores[beast]?:return;val ms=REGION_MOBS[beast]?:return;val b=REGION_BOSS[beast]?:return;val s=difficultyScale(instance);for(i in 0 until Random.nextInt(3,7)){val id=if(i==0)b else ms.random();val l=findSpawnLoc(c.location,10.0,2.0)?:continue;val m=plugin.mobManager.spawnMob(l,id)?:continue;scaleHp(m,s);spawnedMobs.add(m)}}
        private fun spawnInRange(){val c=instance.centerLocation;val(x1,y1,z1)=RANGE1_OFFSET;val(x2,y2,z2)=RANGE2_OFFSET;val s=difficultyScale(instance);for(i in 0 until Random.nextInt(6,13)){val id=if(isPanguPhase)ALL_BOSSES.random() else ALL_MOBS.random();val rx=minOf(x1,x2)+Random.nextDouble()*(maxOf(x1,x2)-minOf(x1,x2));val rz=minOf(z1,z2)+Random.nextDouble()*(maxOf(z1,z2)-minOf(z1,z2));val l=findSpawnLoc(c.clone().add(rx,((y1+y2)/2).toDouble(),rz),8.0,1.0)?:continue;val m=plugin.mobManager.spawnMob(l,id)?:continue;scaleHp(m,s);spawnedMobs.add(m)}}
        private fun scaleHp(mob:LivingEntity,scale:Double){mob.getAttribute(Attribute.MAX_HEALTH)?.let{attr->attr.baseValue*=scale;mob.health=attr.baseValue}}
        override fun end(){cores.values.forEach{if(it.isValid)it.remove()};cores.clear();panguCore?.let{if(it.isValid)it.remove()};spawnedMobs.forEach{if(it.isValid)it.remove()};spawnedMobs.clear()}
    }

    // ==================== JitanPhase (generic) ====================
    inner class JitanPhase(instance: DungeonInstance, private val beast: String, private val onReturn: () -> Unit) : AbstractDungeonPhase(plugin, instance) {
        private var boss:LivingEntity?=null;private val adds= mutableListOf<LivingEntity>();private var isFinished=false
        override fun start(){instance.broadcast("${BEAST_NAME[beast]}§e的幻境已然展开 —— 击败其中的守护者！");instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT);val jc=instance.centerLocation.clone().add(0.0,0.0,(JITAN_Z[beast]?:0).toDouble());for(uuid in instance.players)Bukkit.getPlayer(uuid)?.teleport(jc.clone().add((Random.nextDouble()-0.5)*4,2.0,(Random.nextDouble()-0.5)*4));val s=difficultyScale(instance);val bid=REGION_BOSS[beast]?:return;boss=plugin.mobManager.spawnMob(jc.clone().add(0.0,1.0,0.0),bid);boss?.let{scaleHp(it,s)};val pool=REGION_MOBS[beast]?.filter{it!=bid}?:emptyList();repeat(Random.nextInt(2,4)){if(pool.isEmpty())return@repeat;val m=plugin.mobManager.spawnMob(jc.clone().add((Random.nextDouble()-0.5)*6,1.0,(Random.nextDouble()-0.5)*6),pool.random())?:return@repeat;scaleHp(m,s);adds.add(m)}}
        override fun onTick(){if(isFinished)return;if(boss==null||!boss!!.isValid||boss!!.isDead){isFinished=true;instance.broadcast("${BEAST_NAME[beast]}§e的幻境已被破解！");instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE);val mc=instance.centerLocation.clone();for(uuid in instance.players)Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble()-0.5)*4,2.0,(Random.nextDouble()-0.5)*4));Bukkit.getScheduler().runTaskLater(plugin,Runnable{onReturn()},40L)}}
        override fun end(){boss?.let{if(it.isValid)it.remove()};adds.forEach{if(it.isValid)it.remove()};adds.clear()}
        private fun scaleHp(mob:LivingEntity,s:Double){mob.getAttribute(Attribute.MAX_HEALTH)?.let{it.baseValue*=s;mob.health=it.baseValue}}
    }

    // ==================== QinglongJitanPhase ====================
    // PointSpawn 不能在 inner class 里定义，提到外层
    private data class PointSpawn(val id: String, val loc: Location, var mobType: String = "", var entity: LivingEntity? = null)

    inner class QinglongJitanPhase(
        instance: DungeonInstance, private val beast: String, private val onReturn: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance), Listener {

        // qlTypes 不能在 inner class companion 中，改为实例属性
        private val qlTypes = linkedMapOf(
            "骷髅" to EntityType.SKELETON, "蜘蛛" to EntityType.SPIDER,
            "僵尸" to EntityType.ZOMBIE, "尸壳" to EntityType.HUSK,
            "溺尸" to EntityType.DROWNED, "流髑" to EntityType.STRAY,
            "洞穴蜘蛛" to EntityType.CAVE_SPIDER
        )
        private val qlRegion = listOf("forest_spider","forest_wolf","forest_skeleton","forest_zombie")

        private val points = mutableListOf<PointSpawn>()
        private val areaMobs = mutableListOf<LivingEntity>()
        private var areaTimer = 0
        private var quizActive = false; private var isFinished = false; private var allFixedDead = false
        // UUID → (letter → optionText)
        private val playerOptions = mutableMapOf<UUID, Map<String, String>>()
        private val playerCorrect = mutableMapOf<UUID, String>()
        private lateinit var jitanCenter: Location

        private var lastWarnTick = 0L

        private var spawnComplete = false
        private var spawnDelay = 40

        override fun start() {
            jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 1000.0)
            instance.broadcast("§a青龙§e的幻境已然展开 —— 击败散落的守护者，抵达终点接受试炼！")
            instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))

            points.add(PointSpawn("center",  jitanCenter.clone().add(0.4, 0.0, -45.9)))
            points.add(PointSpawn("floor11", jitanCenter.clone().add(18.2, 5.0, -83.0)))
            points.add(PointSpawn("floor12", jitanCenter.clone().add(-17.8, 5.0, -83.6)))
            points.add(PointSpawn("floor2",  jitanCenter.clone().add(0.3, 20.0, -78.6)))
            for (pt in points) { val (name, type) = qlTypes.entries.random(); pt.mobType = name; pt.entity = spawnFixed(pt.loc, type) }
            areaTimer = Random.nextInt(200, 360)
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        private fun spawnFixed(loc: Location, type: EntityType): LivingEntity {
            val m = instance.world.spawnEntity(loc, type) as LivingEntity
            m.customName(Component.text("§a守护者")); m.isCustomNameVisible = true
            m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 2500.0; m.health = 2500.0
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 50.0
            if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            if (m is Zombie) { m.setBaby(false); m.equipment?.let { it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f } }
            return m
        }

        override fun onTick() {
            if (isFinished) return
            // 前 40 tick 不判断全死——避免刚 spawn 还没初始化完就判定为死
            if (!spawnComplete) { spawnDelay--; if (spawnDelay <= 0) spawnComplete = true }
            if (spawnComplete && !allFixedDead && points.all { it.entity == null || !it.entity!!.isValid || it.entity!!.isDead }) {
                allFixedDead = true; instance.broadcast("§a大殿中的守护者已全部被击败——前往终点接受最后的试炼吧。")
            }
            val fx = jitanCenter.clone().add(0.5, 21.0, -92.7)
            if (!quizActive) for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(fx) < 9.0) {
                    if (!allFixedDead) {
                        if (instance.tickCount - lastWarnTick > 40) { p.sendMessage("§c先击败散落在大殿中的守护者再来。"); lastWarnTick = instance.tickCount }
                    } else startQuiz(); break
                }
            }
            if (!quizActive) { areaTimer--; if (areaTimer <= 0) { spawnArea(); areaTimer = Random.nextInt(300, 500) } }
        }

        private fun spawnArea() {
            val count = Random.nextInt(4, 9)
            for (pt in points.shuffled().take(count.coerceAtMost(points.size))) {
                for (i in 0 until (count / points.size).coerceAtLeast(1)) {
                    val loc = findSpawnNoGrass(pt.loc, 8.0) ?: continue
                    val m = plugin.mobManager.spawnMob(loc, qlRegion.random()) ?: continue; areaMobs.add(m)
                }
            }
        }

        private fun findSpawnNoGrass(center: Location, radius: Double): Location? {
            val w = center.world ?: return null
            for (a in 0 until 15) {
                val ang = Random.nextDouble() * Math.PI * 2; val d = 2.0 + Random.nextDouble() * (radius - 2.0)
                val xb = (center.x + cos(ang) * d).toInt(); val zb = (center.z + sin(ang) * d).toInt()
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

            // 收集所有出现过的怪物类型名
            val spawnedNames = mutableSetOf<String>()
            for (pt in points) spawnedNames.add(pt.mobType)
            // areaMobs 简化为全 qlTypes 出现（因为 area 随机刷，保守认为都可能出现）
            spawnedNames.addAll(qlTypes.keys)

            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                val (question, correct, options) = genQuestion(spawnedNames)
                sendQuiz(p, question, options, correct)
            }
        }

        private fun genQuestion(spawned: Set<String>): Triple<String, String, List<String>> {
            val all = qlTypes.keys.toList()
            if (Random.nextBoolean()) {
                // A: 某区域 boss
                val areaLabel: String; val ptId: String
                when (Random.nextInt(3)) {
                    0 -> { areaLabel = "中心"; ptId = "center" }
                    1 -> { areaLabel = "一层"; ptId = listOf("floor11", "floor12").random() }
                    else -> { areaLabel = "二层"; ptId = "floor2" }
                }
                val pt = points.find { it.id == ptId } ?: points.first()
                val correct = pt.mobType; val wrongs = all.filter { it != correct }.shuffled().take(3)
                return Triple("你在§e大殿${areaLabel}§r杀死的小boss是什么怪物？", correct, (wrongs + correct).shuffled())
            } else {
                // B: 没杀死什么
                val missing = all.filter { it !in spawned }
                val correct = if (missing.isNotEmpty()) missing.random() else all.random()
                val wrongs = all.filter { it != correct }.shuffled().take(3)
                return Triple("你在本次幻境中§c没有§r杀死什么怪物？", correct, (wrongs + correct).shuffled())
            }
        }

        private fun sendQuiz(p: Player, question: String, options: List<String>, correct: String) {
            val letters = listOf("A", "B", "C", "D"); p.sendMessage(""); p.sendMessage("§6══════════════════════"); p.sendMessage("§e$question"); p.sendMessage("")
            val map = mutableMapOf<String, String>()
            for (i in options.indices) {
                map[letters[i]] = options[i]
                p.sendMessage(Component.text("  §6[${letters[i]}] §a${options[i]}").clickEvent(ClickEvent.runCommand("/qanswer ${letters[i]}")))
            }
            p.sendMessage("§6══════════════════════")
            playerOptions[p.uniqueId] = map; playerCorrect[p.uniqueId] = correct
        }

        @EventHandler fun onCmd(event: PlayerCommandPreprocessEvent) {
            if (!quizActive || isFinished) return; val msg = event.message.trim()
            if (!msg.startsWith("/qanswer ")) return; event.isCancelled = true
            val p = event.player; if (!instance.players.contains(p.uniqueId)) return
            val letter = msg.removePrefix("/qanswer ").trim().uppercase()
            if (letter !in listOf("A", "B", "C", "D")) return
            val chosen = playerOptions[p.uniqueId]?.get(letter) ?: return
            val correct = playerCorrect[p.uniqueId] ?: return
            if (chosen == correct) { win(p) } else { fail(p) }
        }

        private fun win(p: Player) {
            isFinished = true; quizActive = false
            instance.broadcast("§a§l${p.name} 回答正确！青龙幻境已被破解！")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
            val mc = instance.centerLocation.clone()
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
        }

        private fun fail(p: Player) {
            instance.broadcast("§c§l${p.name} 回答错误！所有人受到惩罚，幻境即将重置...")
            for (uuid in instance.players) {
                val pl = Bukkit.getPlayer(uuid) ?: continue; pl.damage(70.0)
                pl.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
            }
            resetAll()
        }

        private fun resetAll() {
            for (pt in points) { pt.entity?.let { if (it.isValid) it.remove() }; pt.entity = null }
            areaMobs.forEach { if (it.isValid) it.remove() }; areaMobs.clear()
            playerOptions.clear(); playerCorrect.clear(); quizActive = false; allFixedDead = false; spawnComplete = false; spawnDelay = 40
            for (pt in points) { val (name, type) = qlTypes.entries.random(); pt.mobType = name; pt.entity = spawnFixed(pt.loc, type) }
            areaTimer = Random.nextInt(200, 360)
        }

        override fun end() { HandlerList.unregisterAll(this); for (pt in points) { pt.entity?.let { if (it.isValid) it.remove() } }; points.clear(); areaMobs.forEach { if (it.isValid) it.remove() }; areaMobs.clear() }
    }

    // ==================== ZhuqueJitanPhase ====================
    inner class ZhuqueJitanPhase(
        instance: DungeonInstance, private val beast: String, private val onReturn: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance), Listener {

        // 点位偏移 (相对 zhuquejitan center = Z+2000)
        private val archerLocs = listOf(
            Location(null, -12.5, 113.0, 3.7),
            Location(null, -28.5, 116.0, -4.5),
            Location(null, -48.5, 125.0, -17.6),
            Location(null, -44.5, 131.0, -39.3),
            Location(null, -56.5, 137.0, -44.6)
        )
        private val guardLocs = listOf(Location(null, 1.3, 149.0, -78.1), Location(null, 1.5, 149.0, -38.6))
        private val bigGuardLoc = Location(null, -8.6, 149.0, -56.5)
        private val sourceLoc = Location(null, 26.4, 153.0, -56.5)
        private val targetLocs = listOf(Location(null, 16.5, 190.9, -56.3), Location(null, 23.3, 178.1, -24.3), Location(null, 23.5, 178.9, -88.3))

        private val guards = mutableListOf<LivingEntity>()
        private val archers = mutableListOf<LivingEntity>()
        private var bigGuard: LivingEntity? = null
        private var source: Blaze? = null
        private var ghast: Ghast? = null
        private var ghastTimer = 0
        private var ghastHits = 0
        private var ghastHitCooldown = 0
        private var phase2 = false
        private var isFinished = false
        private val hasTrident = mutableSetOf<UUID>()
        private lateinit var jitanCenter: Location
        private var spawnDelay = 40

        override fun start() {
            jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 2000.0)
            instance.broadcast("§c朱雀§e的幻境已然展开 —— 击败守卫，夺取三叉戟击落恶魂！")
            instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))

            // 弓箭手：骷髅，∞血，20攻，速0，可推动
            for (loc in archerLocs) {
                val sk = instance.world.spawnEntity(toWorld(loc), EntityType.SKELETON) as Skeleton
                sk.customName(Component.text("§c朱雀弓手")); sk.isCustomNameVisible = true
                sk.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; sk.health = 999999.0
                sk.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 20.0
                sk.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
                sk.lootTable = null; sk.isPersistent = true; sk.removeWhenFarAway = false
                sk.equipment?.let { it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f }
                // 可推动：不设置 KNOCKBACK_RESISTANCE
                archers.add(sk)
            }

            // 守卫：僵尸，3000血，60攻，速略快，铁套+铁斧
            for (loc in guardLocs) {
                val z = instance.world.spawnEntity(toWorld(loc), EntityType.ZOMBIE) as Zombie
                z.customName(Component.text("§c朱雀守卫")); z.isCustomNameVisible = true; z.setBaby(false)
                z.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 3000.0; z.health = 3000.0
                z.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 60.0
                z.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.28  // 普通僵尸 0.23
                z.lootTable = null; z.isPersistent = true; z.removeWhenFarAway = false
                z.equipment?.let { it.helmet = ItemStack(Material.IRON_HELMET); it.chestplate = ItemStack(Material.IRON_CHESTPLATE); it.leggings = ItemStack(Material.IRON_LEGGINGS); it.boots = ItemStack(Material.IRON_BOOTS); it.setItemInMainHand(ItemStack(Material.IRON_AXE)); it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f; it.itemInMainHandDropChance = 0f }
                guards.add(z)
            }

            // 大守卫：卫道士，5000血，80攻，速略慢
            val v = instance.world.spawnEntity(toWorld(bigGuardLoc), EntityType.VINDICATOR) as Vindicator
            v.customName(Component.text("§c朱雀统领")); v.isCustomNameVisible = true
            v.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 5000.0; v.health = 5000.0
            v.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 80.0
            v.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.28  // 普通 0.35
            v.lootTable = null; v.isPersistent = true; v.removeWhenFarAway = false
            bigGuard = v

            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        private fun toWorld(loc: Location) = Location(instance.world,
            jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

        override fun onTick() {
            if (isFinished) return

            // 坠落检测：Y<90 → 免摔伤 + tp起点 + 扣50血
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.y < 90.0 && !p.isDead) {
                    p.fallDistance = 0f
                    p.damage(50.0)
                    p.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                    p.sendMessage("§c你坠入了熔岩深渊！")
                }
            }

            if (!phase2) {
                // 前 40 tick 不判断——避免 spawn 竞态
                if (spawnDelay > 0) { spawnDelay--; return }
                // 检查守卫是否全死
                val guardsAlive = guards.any { it.isValid && !it.isDead }
                val bigAlive = bigGuard != null && bigGuard!!.isValid && !bigGuard!!.isDead
                if (!guardsAlive && !bigAlive) {
                    phase2 = true
                    instance.broadcast("§c守卫已全部倒下！§e烈焰之源显现，夺取三叉戟击落恶魂！")
                    instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
                    // spawn source blaze
                    source = instance.world.spawnEntity(toWorld(sourceLoc), EntityType.BLAZE) as Blaze
                    source!!.customName(Component.text("§c烈焰之源")); source!!.isCustomNameVisible = true
                    source!!.setAI(false); source!!.isSilent = true
                    source!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; source!!.health = 999999.0
                    source!!.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                    source!!.lootTable = null; source!!.isPersistent = true; source!!.removeWhenFarAway = false
                    // spawn ghast
                    spawnGhast()
                }
            } else {
                // 恶魂瞬移计时
                ghastTimer--
                if (ghastHitCooldown > 0) ghastHitCooldown--
                if (ghastTimer <= 0 && ghast != null && ghast!!.isValid) {
                    spawnGhast()
                }
            }
        }

        private fun spawnGhast() {
            ghast?.let { if (it.isValid) it.remove() }
            moveGhast()
        }

        private fun moveGhast() {
            val current = ghast?.location
            val others = targetLocs.filter { tl ->
                val wl = toWorld(tl); current == null || wl.distanceSquared(current) > 1.0
            }
            val target = if (others.isNotEmpty()) others.random() else targetLocs.random()
            val wLoc = toWorld(target)
            if (ghast == null || !ghast!!.isValid) {
                ghast = instance.world.spawnEntity(wLoc, EntityType.GHAST) as Ghast
                ghast!!.customName(Component.text("§c朱雀幻影")); ghast!!.isCustomNameVisible = true
                ghast!!.setAI(false); ghast!!.isSilent = true
                ghast!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; ghast!!.health = 999999.0
                try { ghast!!.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
                ghast!!.lootTable = null; ghast!!.isPersistent = true; ghast!!.removeWhenFarAway = false
            } else {
                ghast!!.teleport(wLoc)
            }
            ghastTimer = 100
        }

        @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
            if (isFinished) return
            val damager = event.damager

            // 烈焰之源被攻击 → 给三叉戟
            if (event.entity == source && damager is Player && instance.players.contains(damager.uniqueId)) {
                if (!hasTrident.contains(damager.uniqueId)) {
                    val trident = ItemStack(Material.TRIDENT)
                    val meta = trident.itemMeta; meta.persistentDataContainer.set(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE, 1)
                    meta.displayName(Component.text("§c朱雀之戟"))
                    trident.itemMeta = meta
                    damager.inventory.addItem(trident)
                    hasTrident.add(damager.uniqueId)
                    damager.sendMessage("§c你获得了一把 §e朱雀之戟§c！用它击落恶魂！")
                }
            }

            // 三叉戟命中恶魂 (通过投掷物检测)
            if (event.entity == ghast && ghastHitCooldown <= 0) {
                val shooter = when (damager) {
                    is Player -> if (hasTrident.contains(damager.uniqueId)) damager else null
                    is org.bukkit.entity.Projectile -> damager.shooter as? Player
                    else -> null
                }
                if (shooter != null && hasTrident.contains(shooter.uniqueId)) {
                    ghastHits++; ghastHitCooldown = 20
                    instance.broadcast("§e朱雀之戟命中恶魂！($ghastHits/3)")
                    instance.broadcastSound(Sound.ENTITY_GHAST_HURT)
                    if (ghastHits >= 3) { win(); return }
                    // 命中后立刻闪现到另外两个点之一
                    moveGhast()
                }
            }
        }

        private fun win() {
            isFinished = true
            instance.broadcast("§c§l恶魂已被击落！朱雀幻境破解！")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
            // 清除所有朱雀三叉戟
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                p.inventory.forEachIndexed { i, item ->
                    if (item != null && item.type == Material.TRIDENT && item.itemMeta.persistentDataContainer.has(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE)) {
                        p.inventory.setItem(i, null)
                    }
                }
            }
            val mc = instance.centerLocation.clone()
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
        }

        override fun end() {
            HandlerList.unregisterAll(this)
            archers.forEach { if (it.isValid) it.remove() }; archers.clear()
            guards.forEach { if (it.isValid) it.remove() }; guards.clear()
            bigGuard?.let { if (it.isValid) it.remove() }
            source?.let { if (it.isValid) it.remove() }
            ghast?.let { if (it.isValid) it.remove() }
            // 清除残留三叉戟
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                p.inventory.forEachIndexed { i, item ->
                    if (item != null && item.type == Material.TRIDENT && item.itemMeta.persistentDataContainer.has(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE)) {
                        p.inventory.setItem(i, null)
                    }
                }
            }
        }
    }
}
