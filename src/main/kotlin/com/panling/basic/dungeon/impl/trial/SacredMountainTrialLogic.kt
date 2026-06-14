package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.SchematicManager
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
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

        /** 安全刷怪判定：脚下实心、身体/头部不窒息、不卡墙 */
        fun isSafeSpawn(loc: Location): Boolean {
            val block = loc.block
            val below = loc.clone().subtract(0.0, 1.0, 0.0).block
            val above = loc.clone().add(0.0, 1.0, 0.0).block
            if (!below.type.isSolid) return false
            if (block.type.isOccluding) return false
            if (above.type.isOccluding) return false
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
        when (beast) { "qinglong" -> QinglongJitanPhase(instance, beast, onReturn); "zhuque" -> ZhuqueJitanPhase(instance, beast, onReturn); "baihu" -> BaihuJitanPhase(instance, beast, onReturn); "xuanwu" -> XuanwuJitanPhase(instance, beast, onReturn); else -> JitanPhase(instance, beast, onReturn) }

    // ==================== MainPhase (unchanged) ====================
    inner class MainPhase(instance: DungeonInstance, private val clearedBeasts: MutableSet<String>) : AbstractDungeonPhase(plugin, instance) {
        private val cores = mutableMapOf<String, Blaze>(); private val coreTimers = mutableMapOf<String, Int>()
        private var rangeTimer = 40; private var panguCore: Blaze? = null; private var isPanguPhase = false; private var isFinished = false; private var firstWave = false
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
        private fun revealPanguCore(){isPanguPhase=true;firstWave=true;val(dx,dy,dz)=PANGU_CORE_OFFSET;panguCore=spawnCoreAt(instance.centerLocation.clone().add(dx.toDouble(),dy.toDouble(),dz.toDouble()),"§6盘古内核",7000.0*difficultyScale(instance));instance.broadcast("§6§l四象核心全部破解！盘古的内核显露了出来！");instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)}
        override fun onTick(){
            if(isFinished)return
            val dying= mutableListOf<String>();for((b,c)in cores){if(!c.isValid||c.isDead)dying.add(b)}
            for(b in dying){cores.remove(b);coreTimers.remove(b);instance.broadcast("${BEAST_NAME[b]}§e核心已破碎！幻境之门开启...");instance.broadcastSound(Sound.BLOCK_GLASS_BREAK);isFinished=true;instance.nextPhase(createJitanPhase(instance,b){clearedBeasts.add(b);instance.nextPhase(MainPhase(instance,clearedBeasts))});return}
            if(isPanguPhase&&panguCore!=null&&(!panguCore!!.isValid||panguCore!!.isDead)){isFinished=true;instance.broadcast("§6§l盘古内核已摧毁！");instance.broadcast("§e真·盘古的幻境正在展开...");instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN);instance.nextPhase(ZhenpanguJitanPhase(instance){goToRewardPhase(instance)});return}
            for((b,t)in coreTimers.toMap()){val nt=t-1;if(nt<=0){spawnNearCore(b);coreTimers[b]= Random.nextInt(600,1500)}else coreTimers[b]=nt}
            rangeTimer--;if(rangeTimer<=0){spawnInRange();rangeTimer= Random.nextInt(150,300)}
            if(!isPanguPhase&&cores.isEmpty()&&clearedBeasts.size>=4)revealPanguCore()
        }
        private fun findSpawnLoc(center:Location,radius:Double,minDist:Double=3.0):Location?{val w=center.world?:return null;for(a in 0 until 15){val ang=Random.nextDouble()*Math.PI*2;val d=minDist+Random.nextDouble()*(radius-minDist);val xb=(center.x+cos(ang)*d).toInt();val zb=(center.z+sin(ang)*d).toInt();for(dy in 0..10)for(s in listOf(1,-1)){val ay=center.blockY+dy*s;if(ay<w.minHeight||ay>=w.maxHeight)continue;val l=Location(w,xb+0.5,ay.toDouble(),zb+0.5);if(isSafeSpawn(l))return l.add(0.0,0.2,0.0)}};return null}
        private fun isSafeSpawn(loc:Location):Boolean{val b=loc.block;val below=loc.clone().subtract(0.0,1.0,0.0).block;val above=loc.clone().add(0.0,1.0,0.0).block;if(!below.type.isSolid)return false;if(below.type.name.let{it.contains("FENCE")||it.contains("WALL")||it.contains("TRAPDOOR")})return false;if(b.type.isOccluding||above.type.isOccluding)return false;var n=0;if(b.getRelative(1,0,0).type.isSolid)n++;if(b.getRelative(-1,0,0).type.isSolid)n++;if(b.getRelative(0,0,1).type.isSolid)n++;if(b.getRelative(0,0,-1).type.isSolid)n++;return n<3}
        private fun spawnNearCore(beast:String){val c=cores[beast]?:return;val ms=REGION_MOBS[beast]?:return;val b=REGION_BOSS[beast]?:return;val s=difficultyScale(instance);for(i in 0 until Random.nextInt(3,7)){val id=if(i==0)b else ms.random();val l=findSpawnLoc(c.location,10.0,2.0)?:continue;val m=plugin.mobManager.spawnMob(l,id)?:continue;scaleHp(m,s);spawnedMobs.add(m)}}
        private fun spawnInRange(){val c=instance.centerLocation;val(x1,y1,z1)=RANGE1_OFFSET;val(x2,y2,z2)=RANGE2_OFFSET;val s=difficultyScale(instance);val waveCount = if(firstWave){firstWave=false;Random.nextInt(24,53)}else Random.nextInt(6,13);for(i in 0 until waveCount){val id=if(isPanguPhase)ALL_BOSSES.random() else ALL_MOBS.random();val rx=minOf(x1,x2)+Random.nextDouble()*(maxOf(x1,x2)-minOf(x1,x2));val rz=minOf(z1,z2)+Random.nextDouble()*(maxOf(z1,z2)-minOf(z1,z2));val l=findSpawnLoc(c.clone().add(rx,((y1+y2)/2).toDouble(),rz),8.0,1.0)?:continue;val m=plugin.mobManager.spawnMob(l,id)?:continue;scaleHp(m,s);spawnedMobs.add(m)}}
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
        private val playerCorrect = mutableMapOf<UUID, Set<String>>()
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

            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                val (question, correct, options) = genQuestion(spawnedNames)
                sendQuiz(p, question, options, correct)
            }
        }

        private fun genQuestion(spawned: Set<String>): Triple<String, Set<String>, List<String>> {
            val all = qlTypes.keys.toList()
            if (Random.nextBoolean()) {
                // A: 某区域 boss
                val areaLabel: String; val correctSet: Set<String>
                when (Random.nextInt(3)) {
                    0 -> { areaLabel = "中心"; correctSet = setOf(points.find { it.id == "center" }!!.mobType) }
                    1 -> { areaLabel = "一层"; correctSet = setOf(points.find { it.id == "floor11" }!!.mobType, points.find { it.id == "floor12" }!!.mobType) }
                    else -> { areaLabel = "二层"; correctSet = setOf(points.find { it.id == "floor2" }!!.mobType) }
                }
                val wrongs = all.filter { it !in correctSet }.shuffled().take(3)
                return Triple("你在§e大殿${areaLabel}§r杀死的小boss是什么怪物？", correctSet, (wrongs + correctSet.random()).shuffled())
            } else {
                // B: 没杀死什么
                val missing = all.filter { it !in spawned }
                val correct = if (missing.isNotEmpty()) missing.random() else all.random()
                val wrongs = all.filter { it != correct }.shuffled().take(3)
                return Triple("你在本次幻境中§c没有§r杀死什么怪物？", setOf(correct), (wrongs + correct).shuffled())
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

            // 烈焰之源被攻击 → 每次攻击给三叉戟
            if (event.entity == source && damager is Player && instance.players.contains(damager.uniqueId)) {
                val trident = ItemStack(Material.TRIDENT)
                val meta = trident.itemMeta; meta.persistentDataContainer.set(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE, 1)
                meta.displayName(Component.text("§c朱雀之戟"))
                trident.itemMeta = meta
                damager.inventory.addItem(trident)
                hasTrident.add(damager.uniqueId)
                damager.sendMessage("§c你获得了一把 §e朱雀之戟§c！用它击落恶魂！")
            }

            // 三叉戟命中恶魂 (通过投掷物检测)
            if (event.entity == ghast && ghastHitCooldown <= 0) {
                val shooter = when (damager) {
                    is Player -> if (hasTrident.contains(damager.uniqueId)) damager else null
                    is org.bukkit.entity.Trident -> (damager as org.bukkit.entity.Trident).shooter as? Player
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

    // ==================== BaihuJitanPhase ====================
    inner class BaihuJitanPhase(
        instance: DungeonInstance, private val beast: String, private val onReturn: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance), Listener {

        // 点位偏移 (相对 baihujitan center = Z+4000)
        private val tp1Loc = Location(null, -119.4, 111.0, 46.6)
        private val tp2Loc = Location(null, -91.7, 100.0, 41.9)
        private val finalLoc = Location(null, -91.5, 114.0, 133.5)
        private val guardLocs = listOf(
            Location(null, -82.1, 108.0, 68.2), Location(null, -82.2, 108.0, 81.7),
            Location(null, -83.2, 108.0, 99.9), Location(null, -99.8, 108.0, 99.5),
            Location(null, -99.6, 108.0, 83.9), Location(null, -100.2, 108.0, 69.3)
        )
        private val archerLocs = listOf(Location(null, -67.4, 113.0, 122.0), Location(null, -115.5, 113.0, 122.1))

        private val zombies = mutableListOf<LivingEntity>()
        private val guards = mutableListOf<Zombie>()
        private val chunkTickets = mutableListOf<Pair<Int, Int>>()
        private var phase2 = false; private var isFinished = false
        private lateinit var jitanCenter: Location

        override fun start() {
            jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 4000.0)
            instance.broadcast("§f白虎§e的幻境已然展开 —— 杀出一条血路，直奔终点！")
            instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))

            // 阶段一：从 clipboard 找所有楼梯，每 4 个选 1 个刷僵尸
            val clipboard = SchematicManager.getClipboard("baihujitan") ?: return
            val orig = clipboard.origin; val min = clipboard.region.minimumPoint; val max = clipboard.region.maximumPoint
            var stairCount = 0
            for (cx in min.x..max.x) for (cy in min.y..max.y) for (cz in min.z..max.z) {
                val bs = clipboard.getBlock(cx, cy, cz)
                val id = bs.blockType.id.lowercase()
                if (id.contains("slab") || id.contains("smooth_stone")) {
                    if (stairCount++ % 4 == 0) {
                        val worldLoc = Location(instance.world,
                            jitanCenter.x + cx - orig.x + 0.5,
                            jitanCenter.y + cy - orig.y + 1.0,
                            jitanCenter.z + cz - orig.z + 0.5)
                        val z = instance.world.spawnEntity(worldLoc, EntityType.ZOMBIE) as Zombie
                        z.customName(Component.text("§f白虎兵卒")); z.setBaby(false)
                        z.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 40.0; z.health = 40.0
                        z.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 10.0
                        z.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.15
                        z.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                        z.lootTable = null; z.isPersistent = true; z.removeWhenFarAway = false
                        z.equipment?.let { it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f }
                        // 锁仇恨到随机玩家
                        val target = Bukkit.getPlayer(instance.players.random())
                        if (target != null) z.target = target
                        zombies.add(z)
                        // 保持区块加载
                        val cx = worldLoc.blockX shr 4; val cz = worldLoc.blockZ shr 4
                        if (Pair(cx, cz) !in chunkTickets) { chunkTickets.add(Pair(cx, cz)); instance.world.addPluginChunkTicket(cx, cz, plugin) }
                    }
                }
            }
            instance.broadcast("§e${zombies.size} 个白虎兵卒拦住了去路！")

            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

        @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
            if (isFinished) return
            val damager = event.damager
            if (damager is Player && instance.players.contains(damager.uniqueId) && event.entity is Mob) {
                (event.entity as Mob).target = damager
            }
        }

        override fun onTick() {
            if (isFinished) return

            // 坠落检测
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.y < 90.0 && !p.isDead) { p.fallDistance = 0f; p.damage(50.0); p.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4)); p.sendMessage("§c你坠入了深渊！") }
            }

            if (!phase2) {
                val tp1 = toWorld(tp1Loc)
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distanceSquared(tp1) < 9.0) {
                        phase2 = true
                        instance.broadcast("§f传送阵已激活！§e白虎禁卫苏醒了——走位绕过它们！")
                        instance.broadcastSound(Sound.ENTITY_ZOMBIE_AMBIENT)
                        val tp2 = toWorld(tp2Loc)
                        for (id in instance.players) Bukkit.getPlayer(id)?.teleport(tp2.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                        // 缩放僵尸禁卫
                        for (loc in guardLocs) {
                            val g = instance.world.spawnEntity(toWorld(loc), EntityType.ZOMBIE) as Zombie
                            g.customName(Component.text("§f白虎禁卫")); g.isCustomNameVisible = true; g.setBaby(false)
                            g.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; g.health = 999999.0
                            g.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 100.0
                            g.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.14
                            g.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                            try { g.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
                            g.equipment?.let { it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f; it.setItemInMainHand(ItemStack(Material.GOLDEN_SWORD)); it.itemInMainHandDropChance = 0f }
                            g.lootTable = null; g.isPersistent = true; g.removeWhenFarAway = false
                            val t = Bukkit.getPlayer(instance.players.random())
                            if (t != null) g.target = t
                            guards.add(g)
                        }
                        // 击退骷髅
                        for (loc in archerLocs) {
                            val sk = instance.world.spawnEntity(toWorld(loc), EntityType.SKELETON) as Skeleton
                            sk.customName(Component.text("§f白虎弓手")); sk.isCustomNameVisible = true
                            sk.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; sk.health = 999999.0
                            sk.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 5.0
                            sk.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
                            sk.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                            val bow = ItemStack(Material.BOW); val meta = bow.itemMeta
                            try { meta.addEnchant(Enchantment.PUNCH, 5, true) } catch (_: Exception) {}
                            bow.itemMeta = meta; sk.equipment?.setItemInMainHand(bow)
                            sk.lootTable = null; sk.isPersistent = true; sk.removeWhenFarAway = false
                            sk.equipment?.let { it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f }
                            val t = Bukkit.getPlayer(instance.players.random())
                            if (t != null) sk.target = t
                        }
                        break
                    }
                }
            }

            // 阶段三：半数人到达终点
            val final = toWorld(finalLoc)
            var atFinal = 0
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(final) < 9.0) atFinal++
            }
            val needed = (instance.players.size + 1) / 2
            if (phase2 && atFinal >= needed) {
                isFinished = true
                instance.broadcast("§f§l半数勇士已抵达终点！白虎幻境破解！")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                val mc = instance.centerLocation.clone()
                for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
            }
        }

        override fun end() {
            HandlerList.unregisterAll(this)
            // 先释放区块票再清实体
            for ((cx, cz) in chunkTickets) { try { instance.world.removePluginChunkTicket(cx, cz, plugin) } catch (_: Exception) {} }
            chunkTickets.clear()
            zombies.forEach { if (it.isValid) it.remove() }; zombies.clear()
            guards.forEach { if (it.isValid) it.remove() }; guards.clear()
        }
    }

    // ==================== XuanwuJitanPhase ====================
    // data classes for XuanwuJitanPhase
    private data class LineTurtle(val entity: LivingEntity, val a: Location, val b: Location, var toB: Boolean = true)
    private data class BouncyTurtle(val entity: LivingEntity, var vel: Vector)

    inner class XuanwuJitanPhase(
        instance: DungeonInstance, private val beast: String, private val onReturn: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance) {

        private val lineTurtles = mutableListOf<LineTurtle>()
        private var bouncy: BouncyTurtle? = null
        private val silverfish = mutableListOf<LivingEntity>()
        private val spawnedMobs = mutableListOf<LivingEntity>()
        private var isFinished = false
        private lateinit var jitanCenter: Location
        private lateinit var center: Location

        // 6 对线坐标
        private val linePairs = listOf(
            (Location(null, 135.5, 102.0, -108.2) to Location(null, 135.5, 102.0, -92.2)),
            (Location(null, 125.9, 100.5, -109.5) to Location(null, 125.5, 100.0, -91.7)),
            (Location(null, 116.1, 99.0, -92.2) to Location(null, 116.5, 99.0, -110.3)),
            (Location(null, 106.3, 98.0, -84.3) to Location(null, 105.6, 98.0, -116.6)),
            (Location(null, 133.3, 102.0, -92.1) to Location(null, 138.7, 102.0, -91.8)),
            (Location(null, 133.3, 102.0, -108.8) to Location(null, 138.7, 102.0, -108.7))
        )
        private val centerLoc = Location(null, 149.7, 102.0, -100.5)

        override fun start() {
            jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 3000.0)
            center = toWorld(centerLoc)
            instance.broadcast("§3玄武§e的幻境已然展开 —— 穿越玄龟之阵，抵达中心！")
            instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))

            // 从 marked_points 读取 xuanwupoint 点位附近刷怪
            val waterMobs = listOf("abyssal_drowned", "abyssal_guardian", "abyssal_bogged", "tortoise_shell_warden")
            // 从 marked_points 读取的 xuanwupoint 点位，硬编码在此
            val xuanwuPoints = listOf(
                Location(null, 37.5, 67.0, -7.3),
                Location(null, 33.2, 57.6, -34.6),
                Location(null, 48.6, 73.3, -63.1),
                Location(null, 64.7, 68.1, -43.2),
                Location(null, 79.5, 76.0, -23.2)
            )
            for (loc in xuanwuPoints) {
                val pointLoc = toWorld(loc)
                val count = 10 + Random.nextInt(6) // 10-15
                for (j in 0 until count) {
                    val off = Location(null, (Random.nextDouble()-0.5)*12, 0.0, (Random.nextDouble()-0.5)*12)
                    val spawnLoc = pointLoc.clone().add(off.x, off.y, off.z)
                    val m = plugin.mobManager.spawnMob(spawnLoc, waterMobs.random()) ?: continue
                    spawnedMobs.add(m)
                }
            }

            // 6 对线龟
            for ((a, b) in linePairs) {
                val wa = toWorld(a); val wb = toWorld(b)
                val t = spawnTurtle(wa)
                lineTurtles.add(LineTurtle(t, wa, wb))
            }
            // 弹球龟
            val bt = spawnTurtle(center)
            bouncy = BouncyTurtle(bt, Vector(Random.nextDouble()-0.5, 0.0, Random.nextDouble()-0.5).normalize().multiply(1.2))

            // 蠹虫守卫（有 AI，慢速）
            val count = instance.players.size * 2
            for (i in 0 until count) {
                val angle = Random.nextDouble() * Math.PI * 2; val dist = Random.nextDouble() * 10
                val loc = center.clone().add(cos(angle) * dist, 0.0, sin(angle) * dist)
                val sf = instance.world.spawnEntity(loc, EntityType.SILVERFISH) as Silverfish
                sf.customName(Component.text("§3玄龟幼体")); sf.isCustomNameVisible = true
                sf.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; sf.health = 999999.0
                sf.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 0.0
                sf.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.1
                sf.lootTable = null; sf.isPersistent = true; sf.removeWhenFarAway = false
                silverfish.add(sf)
            }
        }

        private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

        private fun spawnTurtle(loc: Location): LivingEntity {
            val t = instance.world.spawnEntity(loc, EntityType.TURTLE) as Turtle
            t.customName(Component.text("§3玄龟")); t.isCustomNameVisible = true
            t.setAI(false); t.isSilent = true
            t.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; t.health = 999999.0
            try { t.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
            t.lootTable = null; t.isPersistent = true; t.removeWhenFarAway = false
            return t
        }

        override fun onTick() {
            if (isFinished) return

            val hitSet = mutableSetOf<UUID>()
            val knockStrength = 4.0; val hitDamage = 20.0

            // 移动线龟 + 碰撞
            for (lt in lineTurtles) {
                if (!lt.entity.isValid) continue
                val target = if (lt.toB) lt.b else lt.a
                val dir = target.toVector().subtract(lt.entity.location.toVector())
                if (dir.lengthSquared() < 1.0) { lt.toB = !lt.toB; continue }
                lt.entity.teleport(lt.entity.location.add(dir.normalize().multiply(0.72)))
                // 碰撞检测
                for (uuid in instance.players) {
                    if (uuid in hitSet) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distanceSquared(lt.entity.location) < 4.0) {
                        hitSet.add(uuid); p.damage(hitDamage)
                        val away = p.location.toVector().subtract(center.toVector()).normalize()
                        p.velocity = away.multiply(knockStrength).setY(0.3)
                    }
                }
            }

            // 弹球龟
            val bt = bouncy
            if (bt != null && bt.entity.isValid) {
                val next = bt.entity.location.clone().add(bt.vel)
                // 碰到方块就反射
                if (next.block.type.isSolid || next.clone().add(0.0, 1.0, 0.0).block.type.isSolid) {
                    bt.vel = bt.vel.multiply(-1.0)
                }
                bt.entity.teleport(bt.entity.location.add(bt.vel))
                // 碰撞
                for (uuid in instance.players) {
                    if (uuid in hitSet) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distanceSquared(bt.entity.location) < 4.0) {
                        hitSet.add(uuid); p.damage(hitDamage)
                        val away = p.location.toVector().subtract(center.toVector()).normalize()
                        p.velocity = away.multiply(knockStrength).setY(0.3)
                    }
                }
            }

            // 胜利条件：半数人到中心
            var atCenter = 0
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(center) < 9.0) atCenter++
            }
            if (atCenter >= (instance.players.size + 1) / 2) {
                isFinished = true
                instance.broadcast("§3§l半数勇士已抵达中心！玄武幻境破解！")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                val mc = instance.centerLocation.clone()
                for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
            }
        }

        override fun end() {
            lineTurtles.forEach { if (it.entity.isValid) it.entity.remove() }; lineTurtles.clear()
            bouncy?.let { if (it.entity.isValid) it.entity.remove() }; bouncy = null
            silverfish.forEach { if (it.isValid) it.remove() }; silverfish.clear()
            spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear()
        }
    }

    // ==================== ZhenpanguJitanPhase ====================
    inner class ZhenpanguJitanPhase(
        instance: DungeonInstance, private val onComplete: () -> Unit
    ) : AbstractDungeonPhase(plugin, instance), Listener {

        // 青龙守护者候选类型
        private val qlTypes = linkedMapOf(
            "骷髅" to EntityType.SKELETON, "蜘蛛" to EntityType.SPIDER,
            "僵尸" to EntityType.ZOMBIE, "尸壳" to EntityType.HUSK,
            "溺尸" to EntityType.DROWNED, "流髑" to EntityType.STRAY,
            "洞穴蜘蛛" to EntityType.CAVE_SPIDER
        )
        // 各地区 boss 池
        private val regionBossPool = listOf("forest_spider", "desert_husk", "tortoise_shell_warden", "scorched_bone_warrior")
        private val regionMobPool = listOf("forest_spider","forest_wolf","forest_skeleton","forest_zombie",
            "desert_husk","desert_zombie","desert_wither_skeleton","desert_skeleton",
            "tortoise_shell_warden","abyssal_drowned","abyssal_guardian","abyssal_bogged",
            "scorched_bone_warrior","cave_zombie","cave_skeleton","cave_spider")

        // 硬编码点位
        private val zhenpanPoints = listOf(
            Location(null, -0.9, 112.0, -78.9), Location(null, -48.7, 96.0, -142.8),
            Location(null, -81.4, 87.0, -156.4), Location(null, -103.6, 81.0, -121.8),
            Location(null, -119.5, 96.0, -26.5), Location(null, -87.1, 70.0, 78.4),
            Location(null, -51.6, 63.0, 98.6),  Location(null, 26.0, 60.0, 42.3)
        )
        private val zhenpanBoss1 = Location(null, -19.6, 109.0, -114.6)
        private val zhenpanBoss2 = Location(null, -113.6, 88.0, -80.1)
        private val zhenpanBoss3 = Location(null, -108.4, 88.0, 19.2)
        private val zhenpanBoss4 = Location(null, -6.6, 57.0, 63.4)
        private val zhenpanEnter = Location(null, 22.8, 45.0, -1.3)
        private val zhenpanBigBoss = Location(null, 56.1, 48.0, -58.9)

        // 精英怪追踪
        private val qinglongElites = mutableListOf<LivingEntity>()
        private val zhuqueElites = mutableListOf<LivingEntity>()
        private var baihuElite: LivingEntity? = null
        private var xuanwuElite: LivingEntity? = null
        private val spawnedMobs = mutableListOf<LivingEntity>()

        // 大boss
        private var bigBoss: Evoker? = null
        private var bigBossActive = false

        // 技能冷却
        private var skill1Cd = 0  // 开天辟地
        private var skill2Cd = 0  // 混沌召唤
        private var skill3Cd = 0  // 地脉冲击
        private var skill4Cd = 0  // 万钧之势（HP<50%解锁）
        private var skill4Unlocked = false
        private var spawnTimer = 0
        private var isFinished = false
        private var allElitesDead = false
        private var spawnComplete = false
        private var spawnDelay = 40
        private lateinit var jitanCenter: Location

        private fun toWorld(loc: Location) = Location(instance.world,
            jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

        /** 在指定位置画一个水平粒子环（半径=range） */
        private fun drawRing(center: Location, range: Double, count: Int = 48) {
            val w = center.world ?: return
            for (i in 0 until count) {
                val a = 2.0 * Math.PI * i / count
                w.spawnParticle(Particle.ENCHANT, center.clone().add(cos(a) * range, 0.5, sin(a) * range), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }

        override fun start() {
            jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 5000.0)
            instance.broadcast("§5§l真·盘古§e的幻境已然展开 —— 击败四方守护者，直面盘古幻影！")
            instance.broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
            for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(
                jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))

            // zhenpanpoint: 每点 2-3 地区boss + 5-8 地区小怪
            for (pt in zhenpanPoints) {
                val wpt = toWorld(pt)
                val bossCount = 2 + Random.nextInt(2)
                var bi = 0; var br = 0
                while (bi < bossCount && br < bossCount * 10) {
                    val off = Location(null, (Random.nextDouble()-0.5)*8, 0.0, (Random.nextDouble()-0.5)*8)
                    val sl = wpt.clone().add(off.x, off.y, off.z)
                    br++
                    if (!isSafeSpawn(sl)) continue
                    val m = plugin.mobManager.spawnMob(sl, regionBossPool.random()) ?: continue
                    spawnedMobs.add(m)
                    bi++
                }
                val mobCount = 5 + Random.nextInt(4)
                var mi = 0; var mr = 0
                while (mi < mobCount && mr < mobCount * 10) {
                    val off = Location(null, (Random.nextDouble()-0.5)*8, 0.0, (Random.nextDouble()-0.5)*8)
                    val sl = wpt.clone().add(off.x, off.y, off.z)
                    mr++
                    if (!isSafeSpawn(sl)) continue
                    val m = plugin.mobManager.spawnMob(sl, regionMobPool.random()) ?: continue
                    spawnedMobs.add(m)
                    mi++
                }
            }

            // zhenpanboss1: 青龙精英×3（随机选3种不同类型）
            val chosen = qlTypes.entries.shuffled().take(3)
            for ((name, type) in chosen) {
                val m = instance.world.spawnEntity(toWorld(zhenpanBoss1).clone().add(
                    (Random.nextDouble()-0.5)*3, 0.0, (Random.nextDouble()-0.5)*3), type) as LivingEntity
                m.customName(Component.text("§a真·青龙试炼者")); m.isCustomNameVisible = true
                m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 2500.0; m.health = 2500.0
                m.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 50.0
                if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
                if (m is Zombie) { m.setBaby(false); m.equipment?.let {
                    it.helmetDropChance = 0f; it.chestplateDropChance = 0f
                    it.leggingsDropChance = 0f; it.bootsDropChance = 0f } }
                val t = Bukkit.getPlayer(instance.players.random())
                if (t != null && m is Mob) (m as Mob).target = t
                qinglongElites.add(m)
            }

            // zhenpanboss2: 朱雀守卫×3
            for (i in 0 until 3) {
                val z = instance.world.spawnEntity(toWorld(zhenpanBoss2).clone().add(
                    (Random.nextDouble()-0.5)*3, 0.0, (Random.nextDouble()-0.5)*3), EntityType.ZOMBIE) as Zombie
                z.customName(Component.text("§c真·朱雀守卫")); z.isCustomNameVisible = true; z.setBaby(false)
                z.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 3000.0; z.health = 3000.0
                z.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 60.0
                z.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.28
                z.lootTable = null; z.isPersistent = true; z.removeWhenFarAway = false
                z.equipment?.let {
                    it.helmet = ItemStack(Material.IRON_HELMET); it.chestplate = ItemStack(Material.IRON_CHESTPLATE)
                    it.leggings = ItemStack(Material.IRON_LEGGINGS); it.boots = ItemStack(Material.IRON_BOOTS)
                    it.setItemInMainHand(ItemStack(Material.IRON_AXE))
                    it.helmetDropChance = 0f; it.chestplateDropChance = 0f
                    it.leggingsDropChance = 0f; it.bootsDropChance = 0f; it.itemInMainHandDropChance = 0f
                }
                val t = Bukkit.getPlayer(instance.players.random())
                if (t != null) z.target = t
                zhuqueElites.add(z)
            }

            // zhenpanboss3: 白虎禁卫（3000血,50攻,30防,scale=2,皮帽+金剑）
            val g = instance.world.spawnEntity(toWorld(zhenpanBoss3), EntityType.ZOMBIE) as Zombie
            g.customName(Component.text("§f真·白虎斗士")); g.isCustomNameVisible = true; g.setBaby(false)
            g.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 3000.0; g.health = 3000.0
            g.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 50.0  // 降低到50
            g.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.14
            g.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
            try { g.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
            g.equipment?.let {
                it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f
                it.setItemInMainHand(ItemStack(Material.GOLDEN_SWORD)); it.itemInMainHandDropChance = 0f
            }
            g.lootTable = null; g.isPersistent = true; g.removeWhenFarAway = false
            val t3 = Bukkit.getPlayer(instance.players.random())
            if (t3 != null) g.target = t3
            baihuElite = g

            // zhenpanboss4: 玄武装甲溺尸（30攻,2000血,40防,三叉戟,不移动但可攻击/可推动）
            val d = instance.world.spawnEntity(toWorld(zhenpanBoss4), EntityType.DROWNED) as Drowned
            d.customName(Component.text("§3真·玄武守卫")); d.isCustomNameVisible = true
            d.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 2000.0; d.health = 2000.0
            d.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 30.0
            d.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0  // 不能移动
            d.equipment?.let { it.setItemInMainHand(ItemStack(Material.TRIDENT)); it.itemInMainHandDropChance = 0f }
            d.lootTable = null; d.isPersistent = true; d.removeWhenFarAway = false
            val t4 = Bukkit.getPlayer(instance.players.random())
            if (t4 != null) d.target = t4
            xuanwuElite = d

            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
            if (isFinished) return
            val damager = event.damager
            if (damager is Player && instance.players.contains(damager.uniqueId) && event.entity is Mob) {
                (event.entity as Mob).target = damager
            }
        }

        override fun onTick() {
            if (isFinished) return

            // 前 40 tick 不判断精英死亡——避免刚 spawn 还没初始化完就误判
            if (!spawnComplete) { spawnDelay--; if (spawnDelay <= 0) spawnComplete = true }

            // 坠落检测
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.y < 10.0 && !p.isDead) {
                    p.fallDistance = 0f; p.damage(30.0)
                    p.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                    p.sendMessage("§c你坠入了深渊！")
                }
            }
            // 怪物坠落杀死
            val allElites = qinglongElites + zhuqueElites + listOfNotNull(baihuElite, xuanwuElite)
            for (m in allElites) {
                if (m.isValid && !m.isDead && m.location.y < 10.0) m.remove()
            }
            if (bigBoss?.isValid == true && !bigBoss!!.isDead && bigBoss!!.location.y < 10.0) bigBoss!!.remove()
            spawnedMobs.removeAll { m ->
                if (!m.isValid || m.isDead) true
                else if (m.location.y < 10.0) { m.remove(); true }
                else false
            }

            // 检测所有精英死亡
            if (spawnComplete && !allElitesDead) {
                val qlAlive = qinglongElites.any { it.isValid && !it.isDead }
                val zqAlive = zhuqueElites.any { it.isValid && !it.isDead }
                val bhAlive = baihuElite?.let { it.isValid && !it.isDead } == true
                val xwAlive = xuanwuElite?.let { it.isValid && !it.isDead } == true
                if (!qlAlive && !zqAlive && !bhAlive && !xwAlive) {
                    allElitesDead = true
                    instance.broadcast("§5§l四方守护者已全部倒下！前往盘古斧上直面盘古幻影！")
                    instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
                }
            }

            // 进入中心触发大boss
            if (allElitesDead && !bigBossActive) {
                val enter = toWorld(zhenpanEnter)
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distanceSquared(enter) < 25.0) {  // 5格内
                        bigBossActive = true
                        instance.broadcast("§5§l盘古幻影苏醒了！")
                        instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
                        val bbLoc = toWorld(zhenpanBigBoss)
                        val ev = instance.world.spawnEntity(bbLoc, EntityType.EVOKER) as Evoker
                        ev.customName(Component.text("§5§l盘古幻影")); ev.isCustomNameVisible = true
                        ev.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 7500.0; ev.health = 7500.0
                        ev.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 50.0
                        ev.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0  // 不移动
                        ev.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0  // 不可推动
                        try { ev.getAttribute(Attribute.SCALE)?.baseValue = 2.5 } catch (_: Exception) {}
                        ev.lootTable = null; ev.isPersistent = true; ev.removeWhenFarAway = false
                        ev.equipment?.let {
                            it.helmetDropChance = 0f; it.chestplateDropChance = 0f
                            it.leggingsDropChance = 0f; it.bootsDropChance = 0f
                        }
                        bigBoss = ev
                        skill1Cd = 100; skill2Cd = 60; skill3Cd = 140; skill4Cd = 9999; skill4Unlocked = false
                        spawnTimer = 300
                        break
                    }
                }
            }
            // 精英未全死但到了enter点 → 传送回起点
            if (!allElitesDead && !bigBossActive) {
                val enter = toWorld(zhenpanEnter)
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distanceSquared(enter) < 25.0) {
                        p.teleport(jitanCenter.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                        p.sendMessage("§c四方守护者尚未全部击败！先击败它们再来。")
                    }
                }
            }

            // 大boss阶段
            if (bigBossActive && bigBoss != null) {
                val b = bigBoss!!
                if (!b.isValid || b.isDead) {
                    isFinished = true
                    instance.broadcast("§5§l盘古幻影已被击败！真·盘古的试炼终于完成！")
                    instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                    for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(
                        instance.centerLocation.clone().add((Random.nextDouble()-0.5)*4, 2.0, (Random.nextDouble()-0.5)*4))
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { onComplete() }, 60L)
                    return
                }

                // 技能1: 开天辟地 (cd 22s = 440tick)
                skill1Cd--
                if (skill1Cd <= 0) {
                    skill1Cd = 440
                    instance.broadcast("§5盘古幻影§e施展 §c开天辟地§e——退开！")
                    val bc = b.location.clone()
                    drawRing(bc, 8.0)
                    // 1秒后刷新环
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 8.0) }, 20L)
                    // 2秒后爆炸
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        drawRing(bc, 8.0)
                        bc.world.spawnParticle(Particle.EXPLOSION, bc.add(0.0, 1.0, 0.0), 20, 1.5, 2.0, 1.5, 0.0)
                        for (uuid in instance.players) {
                            val p = Bukkit.getPlayer(uuid) ?: continue
                            if (p.location.distanceSquared(bc) < 64.0) {  // 8格内
                                val away = p.location.toVector().subtract(bc.toVector()).normalize()
                                p.velocity = away.multiply(3.0).setY(0.5)
                                p.damage(15.0)
                            }
                        }
                    }, 40L)  // 2s预警
                }

                // 技能2: 混沌召唤 (cd 18s = 360tick)
                skill2Cd--
                if (skill2Cd <= 0) {
                    skill2Cd = 360
                    instance.broadcast("§5盘古幻影§e施展 §a混沌召唤§e——仆从涌现！")
                    for (i in 0 until 3) {
                        val off = Location(null, (Random.nextDouble()-0.5)*16, 0.0, (Random.nextDouble()-0.5)*16)
                        val sl = b.location.clone().add(off.x, off.y, off.z)
                        val m = plugin.mobManager.spawnMob(sl, regionMobPool.random()) ?: continue
                        sl.world.spawnParticle(Particle.PORTAL, sl, 30, 1.0, 1.0, 1.0, 0.02)
                        spawnedMobs.add(m)
                    }
                }

                // 技能3: 地脉冲击 (cd 25s = 500tick) — 拉向boss，距离越远伤害越高
                skill3Cd--
                if (skill3Cd <= 0) {
                    skill3Cd = 500
                    instance.broadcast("§5盘古幻影§e施展 §6地脉冲击§e——大地在拖动你！")
                    val bc = b.location.clone()
                    drawRing(bc, 10.0, 64)  // 10格外受伤
                    // 1.5秒刷新环
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 10.0, 64) }, 30L)
                    // 3秒后执行
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        drawRing(bc, 10.0, 64)
                        bc.world.spawnParticle(Particle.CLOUD, bc.add(0.0, 0.5, 0.0), 40, 2.0, 0.5, 2.0, 0.05)
                        for (uuid in instance.players) {
                            val p = Bukkit.getPlayer(uuid) ?: continue
                            val dist = p.location.distance(bc)
                            val pull = bc.toVector().subtract(p.location.toVector()).normalize().multiply(2.5).setY(0.3)
                            p.velocity = pull
                            if (dist > 10.0) {
                                val dmg = (minOf(dist, 20.0) - 10.0) / 10.0 * 80.0
                                p.damage(dmg)
                                p.world.spawnParticle(Particle.EXPLOSION, p.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.5, 0.5, 0.0)
                            }
                            p.world.spawnParticle(Particle.CLOUD, p.location.add(0.0, 0.2, 0.0), 10, 0.5, 0.0, 0.5, 0.02)
                        }
                    }, 60L)  // 3s前摇
                }

                // 技能4: 万钧之势 (HP<50%解锁, cd 20s = 400tick)
                if (b.health / b.getAttribute(Attribute.MAX_HEALTH)!!.baseValue < 0.5) {
                    if (!skill4Unlocked) { skill4Unlocked = true; skill4Cd = 200; instance.broadcast("§5盘古幻影§c生命垂危——万钧之势即将降临！") }
                    skill4Cd--
                    if (skill4Cd <= 0) {
                        skill4Cd = 400
                        instance.broadcast("§5盘古幻影§e蓄力 §c万钧之势§e——快跑！")
                        val bc = b.location.clone()
                        drawRing(bc, 8.0)
                        for (uuid in instance.players) {
                            val p = Bukkit.getPlayer(uuid) ?: continue
                            p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, false))
                            p.world.spawnParticle(Particle.ENCHANT, p.location.add(0.0, 1.5, 0.0), 20, 0.5, 1.5, 0.5, 0.0)
                        }
                        // 1秒后刷新环
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 8.0) }, 20L)
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            drawRing(bc, 8.0)
                            // boss脚下大爆炸
                            b.world.spawnParticle(Particle.EXPLOSION, b.location.add(0.0, 1.0, 0.0), 15, 1.0, 1.0, 1.0, 0.0)
                            b.world.spawnParticle(Particle.CLOUD, b.location.add(0.0, 0.5, 0.0), 30, 2.0, 0.5, 2.0, 0.05)
                            for (uuid in instance.players) {
                                val p = Bukkit.getPlayer(uuid) ?: continue
                                if (p.location.distanceSquared(b.location) < 64.0) {
                                    p.damage(25.0)
                                    p.velocity = p.location.toVector().subtract(b.location.toVector()).normalize().multiply(2.5).setY(0.6)
                                    p.world.spawnParticle(Particle.EXPLOSION, p.location.add(0.0, 1.0, 0.0), 10, 0.8, 1.2, 0.8, 0.0)
                                    p.world.spawnParticle(Particle.CLOUD, p.location.add(0.0, 0.3, 0.0), 15, 0.5, 0.3, 0.5, 0.04)
                                }
                            }
                        }, 40L)  // 2秒后爆炸
                    }
                }

                // 周围定时刷怪 (每15-20秒)
                spawnTimer--
                if (spawnTimer <= 0) {
                    spawnTimer = 300 + Random.nextInt(100)
                    val cnt = 2 + Random.nextInt(3)
                    for (i in 0 until cnt) {
                        val angle = Random.nextDouble() * Math.PI * 2
                        val dist = 10.0 + Random.nextDouble() * 10.0
                        val sl = b.location.clone().add(cos(angle) * dist, 0.0, sin(angle) * dist)
                        val m = plugin.mobManager.spawnMob(sl, regionMobPool.random()) ?: continue
                        spawnedMobs.add(m)
                    }
                }
            }
        }

        override fun end() {
            HandlerList.unregisterAll(this)
            qinglongElites.forEach { if (it.isValid) it.remove() }; qinglongElites.clear()
            zhuqueElites.forEach { if (it.isValid) it.remove() }; zhuqueElites.clear()
            baihuElite?.let { if (it.isValid) it.remove() }; baihuElite = null
            xuanwuElite?.let { if (it.isValid) it.remove() }; xuanwuElite = null
            bigBoss?.let { if (it.isValid) it.remove() }; bigBoss = null
            spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear()
        }
    }
}
