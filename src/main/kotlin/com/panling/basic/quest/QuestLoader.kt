package com.panling.basic.quest

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.QuestManager
import com.panling.basic.quest.api.QuestObjective
import com.panling.basic.quest.api.QuestReward
import com.panling.basic.quest.impl.KillMobObjective
import com.panling.basic.quest.impl.MoneyReward
import org.bukkit.Bukkit
import org.bukkit.Location
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
            val startNpc = yaml.getString("start_npc")

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
            // 这里的 Quest 构造函数应匹配 Quest.kt 中的主构造函数
            val quest = Quest(id, name, desc, reqLevel, preQuest, startNpc, objectives, rewards)
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