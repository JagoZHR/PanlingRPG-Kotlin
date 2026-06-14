package com.panling.basic.quest

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerRace
import com.panling.basic.manager.QuestManager
import com.panling.basic.quest.api.QuestObjective
import com.panling.basic.quest.api.QuestReward
import com.panling.basic.quest.impl.DungeonCompleteObjective
import com.panling.basic.quest.impl.InteractBlockObjective
import com.panling.basic.quest.impl.ItemReward
import com.panling.basic.quest.impl.KillMobObjective
import com.panling.basic.quest.impl.MoneyReward
import com.panling.basic.quest.impl.QuestCompleteObjective
import com.panling.basic.quest.impl.TalkToNpcObjective
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class QuestLoader(
    private val plugin: PanlingBasic,
    private val questManager: QuestManager
) {
    // 初始化注册表
    private val registry = QuestRegistry()

    init {
        // 1. 注册核心能力
        registerDefaults()
    }

    // 注册核心功能 (杀怪、给钱...)
    private fun registerDefaults() {
        // --- 注册杀怪目标 ---
        registry.registerObjective("KILL_MOB") { id, config ->
            val mobId = config.getString("mob_id") ?: "UNKNOWN"
            val amount = config.getInt("amount")
            val desc = config.getString("description") ?: "击杀怪物"

            // 解析导航坐标 (可选)
            var navLoc: Location? = null
            if (config.contains("location")) {
                navLoc = parseLocation(config.getString("location"))
            }

            // 返回实现类实例
            KillMobObjective(id, mobId, amount, desc, navLoc)
        }

        // --- 注册金币奖励 ---
        registry.registerReward("MONEY") { config ->
            val amount = config.getDouble("amount")
            MoneyReward(amount)
        }

        // --- 注册物品奖励 ---
        registry.registerReward("ITEM") { config ->
            val itemId = config.getString("item_id") ?: "air"
            val amount = config.getInt("amount", 1)
            val display = config.getString("display", itemId) ?: itemId
            ItemReward(itemId, amount, display)
        }

        // --- 注册对话目标 ---
        registry.registerObjective("TALK_TO_NPC") { id, config ->
            val npcId = config.getString("npc_id") ?: "UNKNOWN"
            val desc = config.getString("description") ?: "与NPC对话"

            var navLoc: Location? = null
            if (config.contains("location")) {
                navLoc = parseLocation(config.getString("location"))
            }

            TalkToNpcObjective(id, npcId, desc, navLoc)
        }

        // --- 注册副本通关目标 ---
        registry.registerObjective("DUNGEON_COMPLETE") { id, config ->
            val dungeonId = config.getString("dungeon_id") ?: "UNKNOWN"
            val desc = config.getString("description") ?: "通关副本"

            var navLoc: Location? = null
            if (config.contains("location")) {
                navLoc = parseLocation(config.getString("location"))
            }

            DungeonCompleteObjective(id, dungeonId, desc, navLoc)
        }

        // --- 注册任务完成目标 ---
        registry.registerObjective("QUEST_COMPLETE") { id, config ->
            val questId = config.getString("quest_id") ?: "UNKNOWN"
            val desc = config.getString("description") ?: "完成任务"

            var navLoc: Location? = null
            if (config.contains("location")) {
                navLoc = parseLocation(config.getString("location"))
            }

            QuestCompleteObjective(id, questId, desc, navLoc, questManager)
        }

        // --- 注册方块交互目标 ---
        registry.registerObjective("INTERACT_BLOCK") { id, config ->
            val matStr = config.getString("material") ?: "CHEST"
            val mat = try { Material.valueOf(matStr.uppercase()) } catch (e: Exception) { Material.CHEST }
            val desc = config.getString("description") ?: "交互方块"

            var navLoc: Location? = null
            var targetLoc = Location(Bukkit.getWorlds().firstOrNull(), 0.0, 0.0, 0.0)
            if (config.contains("location")) {
                targetLoc = parseLocation(config.getString("location")) ?: targetLoc
                navLoc = targetLoc
            }

            InteractBlockObjective(id, mat, targetLoc, desc, navLoc)
        }
    }

    fun loadAll() {
        questManager.clearQuests()

        val questFolder = File(plugin.dataFolder, "quests")
        if (!questFolder.exists()) {
            questFolder.mkdirs()
            return
        }

        plugin.logger.info("开始加载任务文件...")

        // Kotlin 风格的文件遍历
        questFolder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadQuestFromFile(it) }

        plugin.logger.info("任务加载完成。")
    }

    private fun loadQuestFromFile(file: File) {
        try {
            val yaml = YamlConfiguration.loadConfiguration(file)

            // 1. 基础信息
            val id = yaml.getString("id")
            if (id == null) {
                plugin.logger.warning("跳过文件 ${file.name}: 缺少任务 ID")
                return
            }
            val name = yaml.getString("name", "未命名任务")!!
            val desc = yaml.getString("description", "")!!

            // 2. 接取条件
            val reqLevel = yaml.getInt("requirements.level", 0)
            val preQuest = yaml.getString("requirements.pre_quest")
            val preQuestList = yaml.getStringList("requirements.pre_quests")
            // 合并：pre_quest 单值 + pre_quests 列表 → 统一列表
            val allPreQuests = mutableListOf<String>()
            if (!preQuest.isNullOrEmpty()) allPreQuests.add(preQuest)
            allPreQuests.addAll(preQuestList.filter { it.isNotBlank() })
            val preQuestsAll = yaml.getStringList("requirements.pre_quests_all").filter { it.isNotBlank() }
            val startNpc = yaml.getString("start_npc")
            val reqRaceStr = yaml.getString("requirements.race")
            val reqRace = if (reqRaceStr != null) {
                try { PlayerRace.valueOf(reqRaceStr.uppercase()) } catch (e: Exception) { null }
            } else null

            // 3. 解析目标 (Objectives)
            val objectives = ArrayList<QuestObjective>()
            val objSec = yaml.getConfigurationSection("objectives")
            if (objSec != null) {
                for (objKey in objSec.getKeys(false)) {
                    val sec = objSec.getConfigurationSection(objKey) ?: continue
                    val type = sec.getString("type") ?: continue

                    // [核心调用] 委托给注册表创建对象
                    // 这里可能抛出异常，最好由 registry 内部处理或这里捕获
                    objectives.add(registry.createObjective(type, objKey, sec))
                }
            }

            // 4. 解析奖励 (Rewards)
            val rewards = ArrayList<QuestReward>()
            if (yaml.contains("rewards")) {
                val mapList = yaml.getMapList("rewards")
                for (map in mapList) {
                    // 技巧：把 Map 转回 ConfigurationSection
                    val tempConfig = YamlConfiguration()
                    map.forEach { (k, v) -> tempConfig.set(k.toString(), v) }

                    val type = tempConfig.getString("type")
                    if (type != null) {
                        rewards.add(registry.createReward(type, tempConfig))
                    }
                }
            }

            // 5. 构建并注册
            val dialogList = yaml.getStringList("accept_dialog")
            val completeDialogList = yaml.getStringList("complete_dialog")
            val autoComplete = yaml.getBoolean("auto_complete_npc", true)
            val autoAccept = yaml.getString("auto_accept") // 完成后自动接取的下一个任务
            val quest = Quest(id, name, desc, reqLevel, preQuest, allPreQuests, preQuestsAll, startNpc, reqRace, objectives, rewards, dialogList, completeDialogList, autoComplete, autoAccept)
            questManager.registerQuest(quest)
            plugin.logger.info(" - 已加载任务: $name ($id)")

        } catch (e: Exception) {
            plugin.logger.severe("加载任务文件失败: ${file.name}")
            e.printStackTrace()
        }
    }

    // 辅助: 解析坐标字符串 "world,x,y,z"
    private fun parseLocation(str: String?): Location? {
        if (str == null) return null
        return try {
            val p = str.split(",")
            val w = Bukkit.getWorld(p[0])
            Location(w, p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
        } catch (e: Exception) {
            null
        }
    }
}