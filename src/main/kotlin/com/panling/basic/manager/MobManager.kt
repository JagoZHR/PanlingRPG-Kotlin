package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.Reloadable
import com.panling.basic.api.SkillTrigger
import com.panling.basic.loot.LootContext
import com.panling.basic.mob.skill.MobSkill
import com.panling.basic.mob.skill.MobSkillRegistry
import com.panling.basic.mob.skill.impl.LeapSkill
import com.panling.basic.mob.skill.impl.MessageSkill
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scoreboard.Team
import java.io.File
import java.util.*
import java.util.concurrent.ThreadLocalRandom

class MobManager(
    private val plugin: PanlingBasic,
    private val itemManager: ItemManager,
    private val lootManager: LootManager
) : Reloadable {

    // 可选依赖，通过 Setter 注入
    var buffManager: BuffManager? = null

    private val mobCache = HashMap<String, MobStats>()
    private val skillRegistry = MobSkillRegistry()

    // [NEW] 用于处理碰撞规则的队伍
    private var collisionTeam: Team? = null

    // === 数据记录定义 ===
    data class DropConfig(val itemId: String, val chance: Double, val min: Int, val max: Int)

    data class EquipmentConfig(
        val mainHand: String?, val offHand: String?,
        val helmet: String?, val chest: String?,
        val leggings: String?, val boots: String?
    )

    data class SkillEntry(val skill: MobSkill, val trigger: SkillTrigger, val chance: Double, val cooldownMs: Long)

    // 增强版怪物属性模板
    data class MobStats(
        val id: String,
        val name: String,
        val entityType: EntityType,
        val hp: Double,
        val damage: Double,
        val magicDamage: Double,
        val physicalDefense: Double,
        val magicDefense: Double,
        val moveSpeed: Double,
        val kbResist: Double,
        val critRate: Double,
        val critDmg: Double,
        val armorPen: Double,
        val magicPen: Double,
        val lifeSteal: Double,
        val vanillaArmor: Double,
        val vanillaArmorToughness: Double,
        val exp: Int,
        val drops: List<DropConfig>,
        val equipment: EquipmentConfig?,
        val skills: List<SkillEntry>,
        val lootTableIds: List<String>
    )

    init {
        registerDefaultSkills()
        initCollisionTeam()

        // 注册到重载管理器
        // 假设 ReloadManager 已经存在于 PanlingBasic
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        loadConfig()
        startMobTimerTask()
    }

    override fun reload() {
        loadConfig()
        plugin.logger.info("生物库已重载。")
    }

    // [NEW] 初始化队伍逻辑
    private fun initCollisionTeam() {
        val sb = Bukkit.getScoreboardManager().mainScoreboard
        // 尝试获取，没有则创建
        collisionTeam = sb.getTeam("PL_PrivateMobs") ?: sb.registerNewTeam("PL_PrivateMobs")

        // [核心修复] 设置碰撞规则为 NEVER
        // 效果：队伍内的实体没有物理体积碰撞 (不会互相推挤，也不会挡路)，但依然保留 Hitbox (可以被箭射中)
        collisionTeam?.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)

        // 确保名字标签可见性正常
        collisionTeam?.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS)
    }

    // ==========================================================
    // 1. 初始化注册
    // ==========================================================

    private fun registerDefaultSkills() {
        // Kotlin Lambda 简化
        skillRegistry.register("MESSAGE") { MessageSkill() }
        skillRegistry.register("LEAP") { LeapSkill() }
    }

    // ==========================================================
    // 2. 核心：文件加载
    // ==========================================================

    fun loadConfig() {
        mobCache.clear()
        val folder = File(plugin.dataFolder, "mobs")
        if (!folder.exists()) {
            folder.mkdirs()
            val legacyFile = File(plugin.dataFolder, "mobs.yml")
            if (legacyFile.exists()) loadSingleMobFile(legacyFile)
            return
        }

        // Kotlin 风格的文件遍历
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleMobFile(it) }

        plugin.logger.info("Loaded ${mobCache.size} custom mobs.")
    }

    private fun loadSingleMobFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (key in config.getKeys(false)) {
            if ("global_loot_tables" == key) continue
            try {
                // 安全解包，确保 ConfigurationSection 存在
                config.getConfigurationSection(key)?.let { loadOneMob(key, it) }
            } catch (e: Exception) {
                plugin.logger.warning("Error loading mob $key in ${file.name}")
                e.printStackTrace()
            }
        }
    }

    private fun loadOneMob(id: String, sec: ConfigurationSection) {
        val typeStr = sec.getString("type", "ZOMBIE")!!
        val type = try { EntityType.valueOf(typeStr.uppercase()) } catch (e: Exception) { EntityType.ZOMBIE }

        val eqConfig = sec.getConfigurationSection("equipment")?.let { eqSec ->
            EquipmentConfig(
                eqSec.getString("main_hand"), eqSec.getString("off_hand"),
                eqSec.getString("helmet"), eqSec.getString("chestplate"),
                eqSec.getString("leggings"), eqSec.getString("boots")
            )
        }

        val dropList = ArrayList<DropConfig>()
        sec.getMapList("drops").forEach { map ->
            dropList.add(DropConfig(
                map["item"] as String,
                (map["chance"] as? Number)?.toDouble() ?: 1.0,
                (map["min"] as? Number)?.toInt() ?: 1,
                (map["max"] as? Number)?.toInt() ?: 1
            ))
        }

        val skillList = ArrayList<SkillEntry>()
        sec.getMapList("skills").forEach { map ->
            try {
                val tempConfig = YamlConfiguration()
                map.forEach { (k, v) -> tempConfig.set(k.toString(), v) }

                val skillType = tempConfig.getString("type")
                val triggerStr = tempConfig.getString("trigger", "ATTACK")!!
                val chance = tempConfig.getDouble("chance", 1.0)
                val cooldown = (tempConfig.getDouble("cooldown", 0.0) * 1000).toLong()

                if (skillType != null) {
                    val skill = skillRegistry.create(skillType, tempConfig)
                    if (skill != null) {
                        val trigger = try { SkillTrigger.valueOf(triggerStr.uppercase()) } catch(e:Exception) { SkillTrigger.ATTACK }
                        skillList.add(SkillEntry(skill, trigger, chance, cooldown))
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("Invalid skill config for mob: $id")
            }
        }

        val linkedTables = sec.getStringList("loot_tables")

        val stats = MobStats(
            id = id,
            name = sec.getString("name", "Unknown Mob")!!,
            entityType = type,
            hp = sec.getDouble("hp", 20.0),
            damage = sec.getDouble("phys", 5.0),
            magicDamage = sec.getDouble("skill", 0.0),
            physicalDefense = sec.getDouble("def", 0.0),
            magicDefense = sec.getDouble("mdef", 0.0),
            moveSpeed = sec.getDouble("speed", 0.23),
            kbResist = sec.getDouble("kb", 0.0),
            critRate = sec.getDouble("crit", 0.0),
            critDmg = sec.getDouble("crit_dmg", 0.0),
            armorPen = sec.getDouble("pen", 0.0),
            magicPen = sec.getDouble("mpen", 0.0),
            lifeSteal = sec.getDouble("lifesteal", 0.0),
            vanillaArmor = sec.getDouble("vanilla_armor", 0.0),
            vanillaArmorToughness = sec.getDouble("vanilla_armor_toughness", 0.0),
            exp = sec.getInt("exp", 0),
            drops = dropList,
            equipment = eqConfig,
            skills = skillList,
            lootTableIds = linkedTables
        )
        mobCache[id] = stats
    }

    // ==========================================================
    // 3. 逻辑核心：生成与属性
    // ==========================================================

    fun spawnMob(loc: Location, mobId: String): LivingEntity? {
        val template = mobCache[mobId] ?: return null
        val entity = loc.world.spawnEntity(loc, template.entityType) as? LivingEntity ?: return null

        entity.persistentDataContainer.set(BasicKeys.MOB_ID, PersistentDataType.STRING, mobId)
        // 假设 template.name 已经包含颜色代码或者用 &
        entity.customName = template.name.replace("&", "§")
        entity.isCustomNameVisible = true

        // 设置属性 (使用 GENERIC_ 前缀适配新版本)
        setAttr(entity, Attribute.MAX_HEALTH, template.hp)
        entity.health = template.hp
        setAttr(entity, Attribute.ATTACK_DAMAGE, template.damage)
        setAttr(entity, Attribute.MOVEMENT_SPEED, template.moveSpeed)
        setAttr(entity, Attribute.KNOCKBACK_RESISTANCE, template.kbResist)
        setAttr(entity, Attribute.ARMOR, template.vanillaArmor)
        setAttr(entity, Attribute.ARMOR_TOUGHNESS, template.vanillaArmorToughness)

        template.equipment?.let { eq ->
            entity.equipment?.let { ee ->
                ee.itemInMainHand = createVisualItem(eq.mainHand)
                ee.itemInOffHand = createVisualItem(eq.offHand)
                ee.helmet = createVisualItem(eq.helmet)
                ee.chestplate = createVisualItem(eq.chest)
                ee.leggings = createVisualItem(eq.leggings)
                ee.boots = createVisualItem(eq.boots)
                EquipmentSlot.values().forEach { ee.setDropChance(it, 0f) }
            }
        }

        triggerSkills(entity, SkillTrigger.SPAWN, null)
        return entity
    }

    /**
     * [API] 生成私有怪物 (优化版)
     */
    fun spawnPrivateMob(loc: Location, mobId: String, owner: Player): LivingEntity? {
        val entity = spawnMob(loc, mobId) ?: return null

        // 1. 标记归属权
        entity.persistentDataContainer.set(BasicKeys.MOB_OWNER, PersistentDataType.STRING, owner.uniqueId.toString())

        // 2. [核心修复] 使用队伍规则代替 setCollidable(false)
        collisionTeam?.addEntry(entity.uniqueId.toString())

        // 3. [发包优化] 对其他玩家隐形
        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.uniqueId != owner.uniqueId) {
                onlinePlayer.hideEntity(plugin, entity)
            }
        }

        // 4. [AI 优化] 出生瞬间强制锁定主人
        if (entity is Mob) {
            entity.target = owner
        }
        return entity
    }

    /**
     * [NEW] 移除私有怪物时的清理逻辑
     */
    fun unregisterPrivateMob(uuid: UUID?) {
        if (uuid != null) {
            try {
                collisionTeam?.removeEntry(uuid.toString())
            } catch (ignored: Exception) {}
        }
    }

    fun getMobStats(entity: LivingEntity): MobStats {
        val id = entity.persistentDataContainer.get(BasicKeys.MOB_ID, PersistentDataType.STRING)
        val base = if (id != null && mobCache.containsKey(id)) mobCache[id]!! else createVanillaStats(entity)

        val bm = buffManager ?: return base

        val finalDmg = bm.applyBuffsToValue(entity, BasicKeys.ATTR_PHYSICAL_DAMAGE, base.damage)
        val finalPhyDef = bm.applyBuffsToValue(entity, BasicKeys.ATTR_DEFENSE, base.physicalDefense)
        val finalMagDef = bm.applyBuffsToValue(entity, BasicKeys.ATTR_MAGIC_DEFENSE, base.magicDefense)

        return base.copy(
            damage = finalDmg,
            physicalDefense = finalPhyDef,
            magicDefense = finalMagDef
        )
    }

    // ==========================================================
    // 4. 技能触发系统
    // ==========================================================

    fun triggerSkills(mob: LivingEntity, trigger: SkillTrigger, target: LivingEntity?) {
        val stats = getMobStats(mob)
        // stats.skills 不能为空
        if (stats.skills.isEmpty()) return

        for (i in stats.skills.indices) {
            val entry = stats.skills[i]
            if (entry.trigger != trigger) continue
            if (entry.chance < 1.0 && ThreadLocalRandom.current().nextDouble() > entry.chance) continue

            val cdKey = NamespacedKey(plugin, "mob_skill_cd_$i")
            val lastUse = mob.persistentDataContainer.getOrDefault(cdKey, PersistentDataType.LONG, 0L)
            val now = System.currentTimeMillis()
            if (now - lastUse < entry.cooldownMs) continue

            if (entry.skill.cast(mob, target)) {
                if (entry.cooldownMs > 0) {
                    mob.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)
                }
            }
        }
    }

    private fun startMobTimerTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (world in Bukkit.getWorlds()) {
                val players = world.players
                if (players.isEmpty()) continue

                for (entity in world.livingEntities) {
                    if (entity.isValid && entity.persistentDataContainer.has(BasicKeys.MOB_ID, PersistentDataType.STRING)) {

                        if (entity.persistentDataContainer.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
                            handlePrivateMobAI(entity, players)
                            continue
                        }

                        var target: Player? = null
                        var minDistanceSq = 16.0 * 16.0
                        for (p in players) {
                            if (p.world != entity.world) continue
                            val distSq = p.location.distanceSquared(entity.location)
                            if (distSq < minDistanceSq) {
                                minDistanceSq = distSq
                                target = p
                            }
                        }
                        if (target != null) {
                            triggerSkills(entity, SkillTrigger.TIMER, target)
                            if (entity is Mob) {
                                if (entity.target == null || entity.target!!.isDead) {
                                    entity.target = target
                                }
                            }
                        }
                    }
                }
            }
        }, 20L, 20L)
    }

    private fun handlePrivateMobAI(entity: LivingEntity, players: List<Player>) {
        if (entity !is Mob) return

        val ownerUUIDStr = entity.persistentDataContainer.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING) ?: return
        val ownerUUID = try { UUID.fromString(ownerUUIDStr) } catch (e: Exception) { return }

        val owner = Bukkit.getPlayer(ownerUUID)

        if (owner == null || !owner.isOnline || owner.world != entity.world || owner.isDead) {
            if (entity.target != null) entity.target = null
            return
        }

        if (entity.target != owner) {
            entity.target = owner
        }

        triggerSkills(entity, SkillTrigger.TIMER, owner)
    }

    // ==========================================================
    // 5. 掉落生成 (委托给 LootManager)
    // ==========================================================

    fun generateLoot(mob: LivingEntity, killer: Player?): List<ItemStack> {
        val allDrops = ArrayList<ItemStack>()
        val stats = getMobStats(mob)

        stats.drops.forEach { dc ->
            if (ThreadLocalRandom.current().nextDouble() < dc.chance) {
                val item = createVisualItem(dc.itemId)
                if (item != null) {
                    var amount = dc.min
                    if (dc.max > dc.min) {
                        amount = ThreadLocalRandom.current().nextInt(dc.min, dc.max + 1)
                    }
                    item.amount = amount
                    allDrops.add(item)
                }
            }
        }

        if (stats.lootTableIds.isNotEmpty()) {
            val context = LootContext(mob, killer)
            for (tableId in stats.lootTableIds) {
                // 假设 LootManager.rollDrops 返回 Collection<ItemStack>
                allDrops.addAll(lootManager.rollDrops(tableId, context))
            }
        }

        return allDrops
    }

    // ==========================================================
    // 6. 辅助方法
    // ==========================================================

    fun canAttack(attacker: Player, target: LivingEntity): Boolean {
        if (!target.persistentDataContainer.has(BasicKeys.MOB_OWNER, PersistentDataType.STRING)) {
            return true
        }
        val ownerUUID = target.persistentDataContainer.get(BasicKeys.MOB_OWNER, PersistentDataType.STRING)
        return ownerUUID != null && ownerUUID == attacker.uniqueId.toString()
    }

    val mobIds: Set<String>
        get() = mobCache.keys

    private fun createVanillaStats(entity: LivingEntity): MobStats {
        val maxHp = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val dmg = entity.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 0.0

        val armor = entity.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val toughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS)?.value ?: 0.0

        return MobStats(
            id = "vanilla",
            name = entity.name,
            entityType = entity.type,
            hp = maxHp,
            damage = dmg,
            magicDamage = 0.0,
            physicalDefense = 0.0,
            magicDefense = 0.0,
            moveSpeed = 0.23,
            kbResist = 0.0,
            critRate = 0.0,
            critDmg = 0.0,
            armorPen = 0.0,
            magicPen = 0.0,
            lifeSteal = 0.0,
            vanillaArmor = armor,
            vanillaArmorToughness = toughness,
            exp = 0,
            drops = emptyList(),
            equipment = null,
            skills = emptyList(),
            lootTableIds = emptyList()
        )
    }

    private fun setAttr(le: LivingEntity, attr: Attribute, `val`: Double) {
        val inst = le.getAttribute(attr)
        inst?.baseValue = `val`
    }

    private fun createVisualItem(id: String?): ItemStack? {
        if (id.isNullOrEmpty()) return null
        val customItem = this.itemManager.createItem(id, null)
        if (customItem != null) return customItem
        return try {
            ItemStack(Material.valueOf(id.uppercase()))
        } catch (e: Exception) {
            null
        }
    }

    // PDC 扩展
    private fun <T : Any, Z : Any> org.bukkit.persistence.PersistentDataContainer.getOrDefault(key: NamespacedKey, type: PersistentDataType<T, Z>, default: Z): Z {
        return this.get(key, type) ?: default
    }
}