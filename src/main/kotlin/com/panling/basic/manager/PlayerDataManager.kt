package com.panling.basic.manager

import com.panling.basic.api.ArrayStance
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.api.PlayerSubClass
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class PlayerDataManager(private val plugin: JavaPlugin) {

    // 缓存池：玩家UUID -> (属性Key -> 数值)
    private val statCache = HashMap<UUID, MutableMap<NamespacedKey, Double>>()

    // === [NEW] 高性能缓存：基础信息全内存缓存 ===
    private val classCache = HashMap<UUID, PlayerClass>()
    private val raceCache = HashMap<UUID, PlayerRace>()
    private val stanceCache = HashMap<UUID, ArrayStance>()
    private val activeSlotCache = HashMap<UUID, Int>()
    private val quiverCache = HashMap<UUID, Int>()

    // === [核心优化] 属性计算相关的瞬时缓存 ===
    // 1. 活跃物品缓存 (避免重复遍历背包)
    private val activeItemsCache = HashMap<UUID, List<ItemStack>>()
    // 2. 计算状态标记 (避免重复触发重算)
    private val statsCalculated = HashSet<UUID>()
    // 3. 流派缓存 (避免重复解析NBT)
    private val subClassCache = HashMap<UUID, PlayerSubClass>()

    // 记录：玩家ID -> (物品ID, 开始持有时间戳)
    private val activeItemIdCache = HashMap<UUID, String>()
    private val slotHoldStartTime = HashMap<UUID, Long>()

    // [NEW] 被动技能缓存
    private val passiveCache = HashMap<UUID, MutableMap<PassiveTrigger, MutableList<CachedPassive>>>()

    // === 核心：缓存清理 (脏标记) ===
    // 当背包变动、穿脱装备、属性变化时调用
    fun clearStatCache(player: Player) {
        val uuid = player.uniqueId
        statCache.remove(uuid)

        // [NEW] 清理衍生缓存
        activeItemsCache.remove(uuid)
        statsCalculated.remove(uuid)
        subClassCache.remove(uuid)

        // 职业/种族/ActiveSlot 缓存一般不随背包变动而变，不需要清理
        // 但被动技能可能随装备改变
        passiveCache.remove(uuid)
    }

    fun onPlayerQuit(player: Player) {
        clearStatCache(player)
        val uuid = player.uniqueId
        classCache.remove(uuid)
        raceCache.remove(uuid)
        stanceCache.remove(uuid)
        activeSlotCache.remove(uuid)
        quiverCache.remove(uuid)
        activeItemIdCache.remove(uuid)
        slotHoldStartTime.remove(uuid)
    }

    // === 属性缓存操作 ===

    fun areStatsCalculated(player: Player): Boolean {
        return statsCalculated.contains(player.uniqueId)
    }

    fun setStatsCalculated(player: Player) {
        statsCalculated.add(player.uniqueId)
    }

    fun getCachedStat(player: Player, key: NamespacedKey): Double? {
        return statCache[player.uniqueId]?.get(key)
    }

    fun cacheStat(player: Player, key: NamespacedKey, value: Double) {
        statCache.computeIfAbsent(player.uniqueId) { HashMap() }[key] = value
    }

    // === 活跃物品缓存 ===

    fun getCachedActiveItems(player: Player): List<ItemStack>? {
        return activeItemsCache[player.uniqueId]
    }

    fun cacheActiveItems(player: Player, items: List<ItemStack>) {
        activeItemsCache[player.uniqueId] = items
    }

    // === 动态流派获取 (带缓存) ===
    fun getPlayerSubClass(player: Player): PlayerSubClass {
        // [NEW] 优先读缓存
        val uuid = player.uniqueId
        if (subClassCache.containsKey(uuid)) {
            return subClassCache[uuid]!!
        }

        var subClass = PlayerSubClass.NONE
        val slot = getActiveSlot(player)

        if (slot != -1) {
            val item = player.inventory.getItem(slot)
            if (item != null && item.hasItemMeta()) {
                val pdc = item.itemMeta!!.persistentDataContainer

                // 检查职业限制
                val reqClassStr = pdc.get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)
                var classMatch = true
                if (reqClassStr != null) {
                    try {
                        val req = PlayerClass.valueOf(reqClassStr)
                        if (req != PlayerClass.NONE && req != getPlayerClass(player)) {
                            classMatch = false
                        }
                    } catch (ignored: Exception) {}
                }

                if (classMatch) {
                    val subName = pdc.get(BasicKeys.ITEM_SUB_CLASS, PersistentDataType.STRING)
                    if (subName != null) {
                        try {
                            subClass = PlayerSubClass.valueOf(subName)
                        } catch (ignored: Exception) {}
                    }
                }
            }
        }

        subClassCache[uuid] = subClass
        return subClass
    }

    // ... (以下方法保持原样或仅做微调) ...

    fun setPlayerClass(player: Player, playerClass: PlayerClass) {
        player.persistentDataContainer.set(BasicKeys.DATA_CLASS, PersistentDataType.STRING, playerClass.name)
        classCache[player.uniqueId] = playerClass
        clearStatCache(player) // 职业变了，流派判定可能变
    }

    fun getPlayerClass(player: Player): PlayerClass {
        val uuid = player.uniqueId
        if (classCache.containsKey(uuid)) return classCache[uuid]!!

        val className = player.persistentDataContainer.get(BasicKeys.DATA_CLASS, PersistentDataType.STRING)
        var pc = PlayerClass.NONE
        if (className != null) {
            try {
                pc = PlayerClass.valueOf(className)
            } catch (ignored: Exception) {}
        }
        classCache[uuid] = pc
        return pc
    }

    fun setActiveSlot(player: Player, slot: Int) {
        var finalSlot = slot
        if (getPlayerClass(player) == PlayerClass.MAGE) return
        if (finalSlot != 40) finalSlot = 0

        player.persistentDataContainer.set(BasicKeys.DATA_ACTIVE_SLOT, PersistentDataType.INTEGER, finalSlot)
        activeSlotCache[player.uniqueId] = finalSlot
        clearStatCache(player) // 切换槽位影响属性
    }

    fun getActiveSlot(player: Player): Int {
        if (getPlayerClass(player) == PlayerClass.MAGE) return -1

        val uuid = player.uniqueId
        if (activeSlotCache.containsKey(uuid)) return activeSlotCache[uuid]!!

        var slot = player.persistentDataContainer.get(BasicKeys.DATA_ACTIVE_SLOT, PersistentDataType.INTEGER) ?: 0
        if (slot != 0 && slot != 40) slot = 0

        activeSlotCache[uuid] = slot
        return slot
    }

    fun getSlotHoldDuration(player: Player): Double {
        val activeSlot = getActiveSlot(player)
        var currentId = "AIR"
        if (activeSlot >= 0) {
            val item = player.inventory.getItem(activeSlot)
            if (item != null && item.hasItemMeta()) {
                currentId = item.itemMeta!!.persistentDataContainer
                    .get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: "AIR"
            }
        }
        val uuid = player.uniqueId
        val lastId = activeItemIdCache.getOrDefault(uuid, "AIR")

        if (currentId != lastId) {
            activeItemIdCache[uuid] = currentId
            slotHoldStartTime[uuid] = System.currentTimeMillis()
            return 0.0
        }

        if ("AIR" == currentId) return 0.0
        val startTime = slotHoldStartTime.getOrDefault(uuid, System.currentTimeMillis())
        return (System.currentTimeMillis() - startTime) / 1000.0
    }

    fun setPlayerRace(player: Player, race: PlayerRace) {
        player.persistentDataContainer.set(BasicKeys.DATA_RACE, PersistentDataType.STRING, race.name)
        raceCache[player.uniqueId] = race
        clearStatCache(player)
    }

    fun getPlayerRace(player: Player): PlayerRace {
        val uuid = player.uniqueId
        if (raceCache.containsKey(uuid)) return raceCache[uuid]!!

        val raceName = player.persistentDataContainer.get(BasicKeys.DATA_RACE, PersistentDataType.STRING)
        var race = PlayerRace.HUMAN
        if (raceName != null) {
            try {
                race = PlayerRace.valueOf(raceName)
            } catch (ignored: Exception) {}
        }
        raceCache[uuid] = race
        return race
    }

    fun setArrayStance(player: Player, stance: ArrayStance) {
        player.persistentDataContainer.set(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING, stance.name)
        stanceCache[player.uniqueId] = stance
    }

    fun getArrayStance(player: Player): ArrayStance {
        val uuid = player.uniqueId
        if (stanceCache.containsKey(uuid)) return stanceCache[uuid]!!

        val name = player.persistentDataContainer.get(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING)
        var stance = ArrayStance.SUPPORT
        if (name != null) {
            try {
                stance = ArrayStance.valueOf(name)
            } catch (ignored: Exception) {}
        }
        stanceCache[uuid] = stance
        return stance
    }

    // === 解锁物品 ===
    fun getUnlockedItems(player: Player): MutableList<String> {
        val data = player.persistentDataContainer.get(BasicKeys.DATA_UNLOCKED_ITEMS, PersistentDataType.STRING)
        if (data.isNullOrEmpty()) return ArrayList()
        // 使用 toMutableList 确保返回可变列表
        return data.split(",").toMutableList()
    }

    fun addUnlockedItem(player: Player, itemId: String) {
        val list = getUnlockedItems(player)
        if (!list.contains(itemId)) {
            list.add(itemId)
            saveUnlockedItems(player, list)
        }
    }

    fun removeUnlockedItem(player: Player, itemId: String) {
        val list = getUnlockedItems(player)
        if (list.remove(itemId)) saveUnlockedItems(player, list)
    }

    private fun saveUnlockedItems(player: Player, list: List<String>) {
        if (list.isEmpty()) {
            player.persistentDataContainer.remove(BasicKeys.DATA_UNLOCKED_ITEMS)
        } else {
            player.persistentDataContainer.set(
                BasicKeys.DATA_UNLOCKED_ITEMS,
                PersistentDataType.STRING,
                list.joinToString(",")
            )
        }
    }

    // =========================================================
    // [NEW] 解锁锻造配方 (Forge Recipes) - 规范化版本
    // =========================================================
    fun getUnlockedRecipes(player: Player): MutableList<String> {
        val data = player.persistentDataContainer.get(BasicKeys.DATA_UNLOCKED_RECIPES, PersistentDataType.STRING)
        if (data.isNullOrEmpty()) return ArrayList()
        return data.split(",").toMutableList()
    }

    fun hasUnlockedRecipe(player: Player, recipeId: String): Boolean {
        return getUnlockedRecipes(player).contains(recipeId)
    }

    fun addUnlockedRecipe(player: Player, recipeId: String) {
        val list = getUnlockedRecipes(player)
        if (!list.contains(recipeId)) {
            list.add(recipeId)
            saveUnlockedRecipes(player, list)
        }
    }

    fun removeUnlockedRecipe(player: Player, recipeId: String) {
        val list = getUnlockedRecipes(player)
        if (list.remove(recipeId)) {
            saveUnlockedRecipes(player, list)
        }
    }

    private fun saveUnlockedRecipes(player: Player, list: List<String>) {
        if (list.isEmpty()) {
            player.persistentDataContainer.remove(BasicKeys.DATA_UNLOCKED_RECIPES)
        } else {
            player.persistentDataContainer.set(
                BasicKeys.DATA_UNLOCKED_RECIPES,
                PersistentDataType.STRING,
                list.joinToString(",")
            )
        }
    }
    // =========================================================

    // === 其他属性 ===
    fun getElementPoints(player: Player): Long {
        return player.persistentDataContainer
            .get(BasicKeys.DATA_ELEMENT_POINTS, PersistentDataType.LONG) ?: 0L
    }

    fun setElementPoints(player: Player, points: Long) {
        player.persistentDataContainer.set(BasicKeys.DATA_ELEMENT_POINTS, PersistentDataType.LONG, points)
    }

    fun addElementPoints(player: Player, amount: Long) {
        setElementPoints(player, getElementPoints(player) + amount)
    }

    fun takeElementPoints(player: Player, amount: Long): Boolean {
        val current = getElementPoints(player)
        if (current >= amount) {
            setElementPoints(player, current - amount)
            return true
        }
        return false
    }

    fun getMoney(player: Player): Double {
        return player.persistentDataContainer
            .get(BasicKeys.DATA_MONEY, PersistentDataType.DOUBLE) ?: 0.0
    }

    fun setMoney(player: Player, amount: Double) {
        player.persistentDataContainer.set(BasicKeys.DATA_MONEY, PersistentDataType.DOUBLE, amount)
    }

    fun giveMoney(player: Player, amount: Double) {
        setMoney(player, getMoney(player) + amount)
    }

    fun takeMoney(player: Player, amount: Double): Boolean {
        val current = getMoney(player)
        if (current >= amount) {
            setMoney(player, current - amount)
            return true
        }
        return false
    }

    // =========================================================
    // [NEW] 等级管理 (直接对接原版 XP 等级)
    // 封装在这里是为了代码风格统一，方便未来可能的修改
    // =========================================================

    fun getLevel(player: Player): Int {
        return player.level
    }

    fun setLevel(player: Player, level: Int) {
        player.level = level
    }

    fun addLevel(player: Player, amount: Int) {
        player.giveExpLevels(amount)
    }

    // 顺便加个判断方法，写逻辑时更方便
    fun hasLevel(player: Player, required: Int): Boolean {
        return player.level >= required
    }

    // === 被动技能 ===
    enum class PassiveTrigger { ATTACK, HIT, CONSTANT }

    // Kotlin Data Class (注意：sourceItem 可空，因为套装被动不绑定特定物品)
    data class CachedPassive(val id: String, val sourceItem: ItemStack?, val overrideCooldown: Long)

    fun addPassiveToCache(
        player: Player,
        type: PassiveTrigger,
        skillId: String,
        source: ItemStack?,
        overrideCd: Long
    ) {
        passiveCache.computeIfAbsent(player.uniqueId) { HashMap() }
            .computeIfAbsent(type) { ArrayList() }
            .add(CachedPassive(skillId, source, overrideCd))
    }

    fun getCachedPassives(player: Player, type: PassiveTrigger): List<CachedPassive> {
        return passiveCache[player.uniqueId]?.get(type) ?: emptyList()
    }

    // === 箭袋 ===
    fun getQuiverArrows(player: Player): Int {
        val uuid = player.uniqueId
        if (quiverCache.containsKey(uuid)) return quiverCache[uuid]!!

        val `val` = player.persistentDataContainer
            .get(BasicKeys.DATA_QUIVER_ARROWS, PersistentDataType.INTEGER) ?: 0
        quiverCache[uuid] = `val`
        return `val`
    }

    fun setQuiverArrows(player: Player, amount: Int) {
        val finalAmount = amount.coerceAtLeast(0)
        player.persistentDataContainer.set(BasicKeys.DATA_QUIVER_ARROWS, PersistentDataType.INTEGER, finalAmount)
        quiverCache[player.uniqueId] = finalAmount
    }

    fun addQuiverArrows(player: Player, amount: Int) {
        setQuiverArrows(player, getQuiverArrows(player) + amount)
    }

    fun takeQuiverArrows(player: Player, amount: Int): Boolean {
        val current = getQuiverArrows(player)
        if (current >= amount) {
            setQuiverArrows(player, current - amount)
            return true
        }
        return false
    }
}