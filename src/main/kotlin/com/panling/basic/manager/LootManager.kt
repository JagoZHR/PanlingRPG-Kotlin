package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.api.Reloadable
import com.panling.basic.loot.GlobalLootTable
import com.panling.basic.loot.LootCondition
import com.panling.basic.loot.LootContext
import com.panling.basic.loot.LootEntry
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.concurrent.ThreadLocalRandom

class LootManager(
    private val plugin: PanlingBasic,
    private val itemManager: ItemManager
) : Reloadable {

    // 战利品表缓存
    private val lootTableMap = HashMap<String, GlobalLootTable>()

    // 条件工厂: 输入配置字符串 -> 输出条件对象
    // 假设 LootCondition 是一个函数式接口 (LootContext) -> Boolean
    private val conditionFactories = HashMap<String, (String) -> LootCondition>()

    init {
        registerConditions()
        loadLootTables()

        // 自动注册
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}
    }

    override fun reload() {
        loadLootTables()
        plugin.logger.info("战利品表已重载。")
    }

    // === 核心业务：计算指定表的掉落 ===
    fun rollDrops(tableId: String, context: LootContext): List<ItemStack> {
        val table = lootTableMap[tableId]
        if (table != null && table.canDrop(context)) {
            return table.rollDrops(itemManager)
        }
        return emptyList()
    }

    // === 加载逻辑 ===
    private fun loadLootTables() {
        lootTableMap.clear()
        val folder = File(plugin.dataFolder, "loot_tables")
        if (!folder.exists()) folder.mkdirs()

        // Kotlin 风格的文件遍历
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleLootTableFile(it) }

        plugin.logger.info("已加载 ${lootTableMap.size} 个战利品表。")
    }

    private fun loadSingleLootTableFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (tableId in config.getKeys(false)) {
            try {
                val table = GlobalLootTable(tableId)
                val sec = config.getConfigurationSection(tableId) ?: continue

                table.isWeightedMode = sec.getBoolean("weighted", false)

                // 解析条件
                val condSec = sec.getConfigurationSection("conditions")
                if (condSec != null) {
                    for (condKey in condSec.getKeys(false)) {
                        val factory = conditionFactories[condKey]
                        if (factory != null) {
                            val configVal = condSec.getString(condKey) ?: ""
                            table.addCondition(factory(configVal))
                        }
                    }
                }

                // 解析掉落项
                // 格式: itemId:chanceOrWeight:amount OR itemId:chanceOrWeight:min-max
                for (line in sec.getStringList("drops")) {
                    val parts = line.split(":")
                    if (parts.size < 2) continue

                    val itemId = parts[0]
                    val `val` = parts[1].toDoubleOrNull() ?: 0.0
                    var min = 1
                    var max = 1

                    if (parts.size > 2) {
                        val amountPart = parts[2]
                        if (amountPart.contains("-")) {
                            val range = amountPart.split("-")
                            if (range.size >= 2) {
                                min = range[0].toIntOrNull() ?: 1
                                max = range[1].toIntOrNull() ?: 1
                            }
                        } else {
                            val amount = amountPart.toIntOrNull() ?: 1
                            min = amount
                            max = amount
                        }
                    }

                    // 根据模式添加条目
                    if (table.isWeightedMode) {
                        // 权重模式: val = weight (int), chance = 0
                        table.addEntry(LootEntry(itemId, 0.0, `val`.toInt(), min, max))
                    } else {
                        // 几率模式: val = chance (double), weight = 0
                        table.addEntry(LootEntry(itemId, `val`, 0, min, max))
                    }
                }
                lootTableMap[tableId] = table

            } catch (e: Exception) {
                plugin.logger.warning("战利品表 $tableId 加载错误: ${file.name}")
                e.printStackTrace()
            }
        }
    }

    // === 条件注册 ===
    private fun registerConditions() {
        // 职业限制
        conditionFactories["required_class"] = { valStr ->
            LootCondition { context ->
                // [修复] 去掉括号，并使用 ?. 安全调用
                val killer = context?.killer ?: return@LootCondition false
                try {
                    plugin.playerDataManager.getPlayerClass(killer) == PlayerClass.valueOf(valStr.uppercase())
                } catch (e: Exception) { false }
            }
        }

        // 种族限制
        conditionFactories["required_race"] = { valStr ->
            LootCondition { context ->
                // [修复] 去掉括号，并使用 ?. 安全调用
                val killer = context?.killer ?: return@LootCondition false
                try {
                    plugin.playerDataManager.getPlayerRace(killer) == PlayerRace.valueOf(valStr.uppercase())
                } catch (e: Exception) { false }
            }
        }

        // 环境限制
        conditionFactories["required_world_env"] = { valStr ->
            LootCondition { context ->
                try {
                    // [修复] context 和 world 都可能是 null，使用 ?.
                    context?.world?.environment == World.Environment.valueOf(valStr.uppercase())
                } catch (e: Exception) { false }
            }
        }

        // 额外几率 (用于覆盖全局几率或子条件)
        conditionFactories["chance"] = { valStr ->
            LootCondition { _ ->
                ThreadLocalRandom.current().nextDouble() < (valStr.toDoubleOrNull() ?: 0.0)
            }
        }

        // 怪物名包含
        conditionFactories["mob_name_contains"] = { valStr ->
            LootCondition { context ->
                // [修复] 使用 ?. 访问 mobName
                context?.mobName?.contains(valStr) == true
            }
        }
    }
}