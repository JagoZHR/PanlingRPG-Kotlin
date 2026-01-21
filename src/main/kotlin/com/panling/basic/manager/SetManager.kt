package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.Reloadable
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.ArrayList
import java.util.HashMap

class SetManager(private val plugin: PanlingBasic) : Reloadable {

    // 套装ID -> 套装定义
    private val sets = HashMap<String, SetDefinition>()
    // 套装ID -> 包含的物品ID列表 (缓存，用于快速查找)
    private val setItemsCache = HashMap<String, MutableList<String>>()

    init {
        // 自动注册重载
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        loadSets()

        // 初始构建缓存 (尝试获取 ItemManager)
        plugin.itemManager?.let { rebuildItemCache(it) }
    }

    override fun reload() {
        loadSets()
        // 重载时必须重建反向索引缓存
        plugin.itemManager?.let { rebuildItemCache(it) }
        plugin.logger.info("套装库已重载，重建了 ${setItemsCache.size} 个套装的物品索引。")
    }

    // ==========================================================
    // 1. 核心加载逻辑
    // ==========================================================

    fun loadSets() {
        sets.clear()
        val folder = File(plugin.dataFolder, "sets")

        // 1. 如果文件夹不存在，检查是否有旧版单文件
        if (!folder.exists()) {
            folder.mkdirs()
            val legacyFile = File(plugin.dataFolder, "sets.yml")
            if (legacyFile.exists()) {
                plugin.logger.info("检测到旧版 sets.yml，正在加载...")
                loadSingleFile(legacyFile)
            }
            return
        }

        // 2. 扫描文件夹 (Kotlin 风格)
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleFile(it) }

        plugin.logger.info("已加载 ${sets.size} 个套装定义。")
    }

    private fun loadSingleFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (id in config.getKeys(false)) {
            try {
                val section = config.getConfigurationSection(id) ?: continue

                val name = section.getString("name", "Unknown Set")!!

                val tiers = HashMap<Int, MutableMap<NamespacedKey, Double>>()
                val descriptions = HashMap<Int, List<String>>()

                val passiveConst = HashMap<Int, List<SetPassive>>()
                val passiveAttack = HashMap<Int, List<SetPassive>>()
                val passiveHit = HashMap<Int, List<SetPassive>>()

                val tiersSec = section.getConfigurationSection("tiers")

                if (tiersSec != null) {
                    for (countStr in tiersSec.getKeys(false)) {
                        val count = countStr.toIntOrNull() ?: continue
                        val tierNode = tiersSec.getConfigurationSection(countStr) ?: continue

                        // A. 解析属性
                        val stats = HashMap<NamespacedKey, Double>()
                        val statsSec = tierNode.getConfigurationSection("stats")
                        if (statsSec != null) {
                            for (shortKey in statsSec.getKeys(false)) {
                                val key = BasicKeys.SHORT_NAME_MAP[shortKey.lowercase()]
                                if (key != null) {
                                    stats[key] = statsSec.getDouble(shortKey)
                                }
                            }
                        }
                        tiers[count] = stats

                        // B. 解析描述
                        var descList = tierNode.getStringList("desc")
                        if (descList.isEmpty()) descList = tierNode.getStringList("description")
                        descriptions[count] = descList

                        // C. 解析被动
                        passiveConst[count] = parsePassiveList(tierNode, "passive")
                        passiveAttack[count] = parsePassiveList(tierNode, "passive_attack")
                        passiveHit[count] = parsePassiveList(tierNode, "passive_hit")
                    }
                }

                sets[id] = SetDefinition(id, name, tiers, descriptions, passiveConst, passiveAttack, passiveHit)

            } catch (e: Exception) {
                plugin.logger.warning("Error loading set '$id' in ${file.name}")
                e.printStackTrace()
            }
        }
    }

    private fun parsePassiveList(section: ConfigurationSection, key: String): List<SetPassive> {
        val result = ArrayList<SetPassive>()
        if (!section.contains(key)) return result

        if (section.isList(key)) {
            // 列表格式：无冷却
            section.getStringList(key).forEach { pid ->
                result.add(SetPassive(pid, 0))
            }
        } else if (section.isConfigurationSection(key)) {
            // 键值对格式：ID: 冷却(秒)
            val sec = section.getConfigurationSection(key) ?: return result
            for (pid in sec.getKeys(false)) {
                val cd = sec.getDouble(pid)
                result.add(SetPassive(pid, (cd * 1000).toLong()))
            }
        }
        return result
    }

    // ==========================================================
    // 2. 缓存构建与查询
    // ==========================================================

    /**
     * 重建套装物品缓存 (反向索引)
     * 需要在 ItemManager 加载完物品后调用
     */
    fun rebuildItemCache(itemManager: ItemManager) {
        setItemsCache.clear()
        // 遍历所有物品，检查其是否属于某个套装
        for (itemId in itemManager.itemIds) {
            val setId = itemManager.getItemSetId(itemId)
            if (setId != null && sets.containsKey(setId)) {
                setItemsCache.computeIfAbsent(setId) { ArrayList() }.add(itemId)
            }
        }
    }

    fun getSet(id: String): SetDefinition? = sets[id]

    fun getItemsInSet(setId: String): List<String> = setItemsCache[setId] ?: emptyList()

    fun getAllSets(): List<SetDefinition> = ArrayList(sets.values)

    // 给 LoreManager 用的辅助方法
    fun getSetName(setId: String): String {
        return sets[setId]?.displayName ?: setId
    }

    // ==========================================================
    // 3. 数据结构定义
    // ==========================================================

    data class SetPassive(val id: String, val cooldownMillis: Long)

    enum class PassiveType { CONSTANT, ATTACK, HIT }

    data class SetDefinition(
        val id: String,
        val displayName: String,
        val tiers: Map<Int, Map<NamespacedKey, Double>>,
        val descriptions: Map<Int, List<String>>,
        val passiveConst: Map<Int, List<SetPassive>>,
        val passiveAttack: Map<Int, List<SetPassive>>,
        val passiveHit: Map<Int, List<SetPassive>>
    ) {
        fun getBonuses(activeCount: Int): Map<NamespacedKey, Double> {
            val totalBonus = HashMap<NamespacedKey, Double>()
            tiers.forEach { (count, stats) ->
                if (activeCount >= count) {
                    stats.forEach { (key, `val`) ->
                        totalBonus.merge(key, `val`) { a, b -> a + b }
                    }
                }
            }
            return totalBonus
        }

        fun getActivePassives(activeCount: Int, type: PassiveType): List<SetPassive> {
            val result = ArrayList<SetPassive>()
            val targetMap = when (type) {
                PassiveType.CONSTANT -> passiveConst
                PassiveType.ATTACK -> passiveAttack
                PassiveType.HIT -> passiveHit
            }

            targetMap.forEach { (count, list) ->
                if (activeCount >= count) {
                    result.addAll(list)
                }
            }
            return result
        }
    }
}