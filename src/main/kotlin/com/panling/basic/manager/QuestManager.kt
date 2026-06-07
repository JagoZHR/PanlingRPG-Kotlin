package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerRace
import com.panling.basic.api.Reloadable
import com.panling.basic.dungeon.DungeonCompleteEvent
import com.panling.basic.quest.Quest
import com.panling.basic.quest.QuestLoader
import com.panling.basic.quest.QuestProgress
import com.panling.basic.quest.impl.TalkToNpcObjective
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class QuestManager(private val plugin: PanlingBasic) : Listener, Reloadable {

    // 任务注册表 (QuestID -> Quest对象)
    private val questRegistry = HashMap<String, Quest>()

    // 玩家运行时数据 (UUID -> 任务进度列表)
    // 这里只存“进行中”和“已完成”的任务
    private val playerQuests = HashMap<UUID, MutableList<QuestProgress>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 尝试注册到 ReloadManager
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        registerTestQuests()
    }

    override fun reload() {
        // 重新调用加载器
        // 假设 QuestLoader 已迁移或兼容
        try {
            QuestLoader(plugin, this).loadAll()
        } catch (e: NoClassDefFoundError) {
            plugin.logger.warning("QuestLoader class not found yet.")
        }
    }

    // === 1. 注册与初始化 ===

    fun registerQuest(quest: Quest) {
        questRegistry[quest.id] = quest
    }

    fun getQuest(questId: String): Quest? {
        return questRegistry[questId]
    }

    private fun registerTestQuests() {
        // 占位: 实际逻辑由 QuestLoader 处理
    }

    fun clearQuests() {
        questRegistry.clear()
        // 注意：不要清空 playerQuests (玩家进度)，因为那是运行时数据
    }

    // === 2. 核心：事件分发总线 ===

    private fun dispatchEvent(player: Player, event: Event) {
        val progressList = playerQuests[player.uniqueId] ?: return
        if (progressList.isEmpty()) return

        var saved = false

        for (progress in progressList) {
            // 如果任务已经完成，就不再处理事件
            if (progress.isCompleted) continue

            val quest = progress.quest
            var updated = false

            for (obj in quest.objectives) {
                val current = progress.getProgress(obj.id)
                if (current >= obj.requiredAmount) continue

                // 核心调用：询问目标 "这个事件跟你有关系吗？"
                // 假设 QuestObjective 有 onEvent 方法
                val newVal = obj.onEvent(player, event, current)

                // 如果返回值 != -1 且 != 原值，说明进度更新了
                if (newVal != -1 && newVal != current) {
                    progress.setProgress(obj.id, newVal)
                    updated = true

                    // 提示玩家
                    player.sendMessage("§a[任务进度] ${obj.description}: $newVal/${obj.requiredAmount}")
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f)
                }
            }

            if (updated) {
                checkCompletion(player, progress)
                saved = true
            }
        }

        if (saved) {
            savePlayerData(player)
        }
    }

    // 检查任务是否完成 (供外部调用，如 GiveQuestAction 自动完成 NPC 对话任务)
    fun checkCompletion(player: Player, progress: QuestProgress) {
        if (progress.quest.isCompleted(progress)) {
            progress.isCompleted = true
            player.sendMessage("§6§l[任务完成] ${progress.quest.name}")
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

            // 发放奖励
            for (reward in progress.quest.rewards) {
                reward.give(player)
                player.sendMessage("§e获得奖励: ${reward.display}")
            }

            // 播放交任务对话
            if (progress.quest.completeDialog.isNotEmpty()) {
                showDialog(player, progress.quest.completeDialog)
            }

            // 自动接取下一环任务
            val nextId = progress.quest.autoAcceptNext
            if (nextId != null) {
                val nextQuest = questRegistry[nextId]
                if (nextQuest != null && isQuestAvailable(player, nextQuest)) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        acceptQuest(player, nextId)
                        // 显示接取对话
                        if (nextQuest.acceptDialog.isNotEmpty()) {
                            showDialog(player, nextQuest.acceptDialog)
                        }
                    }, 40L) // 2 秒延迟，让完成提示先显示
                }
            }
        }
    }

    /** 逐行延迟显示对话 */
    private fun showDialog(player: Player, lines: List<String>) {
        val scheduler = plugin.server.scheduler
        for ((i, line) in lines.withIndex()) {
            scheduler.runTaskLater(plugin, Runnable {
                player.sendMessage(line.replace("&", "§"))
            }, (i * 40L) + 60L)
        }
    }

    // === 3. 监听器 ===

    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        event.entity.killer?.let { killer ->
            dispatchEvent(killer, event)
        }
    }

    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        // 检查被点击的实体是否是 NPC
        val npcId = event.rightClicked.persistentDataContainer
            .get(BasicKeys.NPC_ID, PersistentDataType.STRING) ?: return
        // NPC 交互也走统一的事件分发
        dispatchEvent(event.player, event)
    }

    @EventHandler
    fun onDungeonComplete(event: DungeonCompleteEvent) {
        dispatchEvent(event.player, event)
    }

    @EventHandler
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.clickedBlock == null) return
        dispatchEvent(event.player, event)
    }

    // === 4. 玩家数据管理 ===

    fun acceptQuest(player: Player, questId: String) {
        val quest = questRegistry[questId] ?: return

        val list = playerQuests.getOrPut(player.uniqueId) { ArrayList() }

        // 检查是否已接取
        if (list.any { it.quest.id == questId }) {
            player.sendMessage("§c你已经接取过这个任务了！")
            return
        }

        val newProgress = QuestProgress(quest)
        list.add(newProgress)

        player.sendMessage("§a[接受任务] ${quest.name}")
        player.sendMessage("§7${quest.description}")
        player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f)

        savePlayerData(player)
    }

    // === 查询接口 ===

    fun getActiveQuests(player: Player): List<QuestProgress> {
        return playerQuests[player.uniqueId]?.filter { !it.isCompleted } ?: emptyList()
    }

    fun getCompletedQuests(player: Player): List<QuestProgress> {
        return playerQuests[player.uniqueId]?.filter { it.isCompleted } ?: emptyList()
    }

    /**
     * [NEW] 获取玩家正在进行的某个任务的进度数据
     */
    fun getActiveProgress(player: Player, questId: String): QuestProgress? {
        return playerQuests[player.uniqueId]?.find { it.quest.id == questId && !it.isCompleted }
    }

    /**
     * [NEW] 判断任务是否对玩家“可接取”
     */
    fun isQuestAvailable(player: Player, quest: Quest): Boolean {
        // 1. 检查是否已经接取 (在进行中)
        if (getActiveProgress(player, quest.id) != null) return false

        // 2. 检查是否已完成
        if (hasCompleted(player, quest.id)) return false

        // 3. 检查等级要求
        if (player.level < quest.requiredLevel) return false

        // 4. 检查前置任务
        // OR: preQuestId 或 preQuestIds 中任意一个完成即可
        val preIds = quest.preQuestIds
        val hasSinglePre = !quest.preQuestId.isNullOrEmpty()
        if (preIds.isNotEmpty() || hasSinglePre) {
            var anyCompleted = false
            if (hasSinglePre && hasCompleted(player, quest.preQuestId!!)) anyCompleted = true
            if (!anyCompleted && preIds.any { hasCompleted(player, it) }) anyCompleted = true
            if (!anyCompleted) return false
        }
        // AND: preQuestIdsAll 中的全部必须完成
        val preAllIds = quest.preQuestIdsAll
        if (preAllIds.isNotEmpty()) {
            if (!preAllIds.all { hasCompleted(player, it) }) return false
        }

        // 5. 检查种族限制
        val requiredRace = quest.requiredRace
        if (requiredRace != null && requiredRace != PlayerRace.NONE) {
            val playerRace = plugin.playerDataManager.getPlayerRace(player)
            if (playerRace != requiredRace) return false
        }

        return true
    }

    /**
     * 接取任务后自动完成与该 NPC 的对话目标
     * 用于接取即完成的场景 (如 "找到族长并对话")
     * @return true 如果触发了自动完成
     */
    fun tryAutoCompleteNpc(player: Player, progress: QuestProgress, npcId: String): Boolean {
        // 如果任务不允许自动完成 NPC 对话，直接跳过
        if (!progress.quest.autoCompleteNpc) return false

        var updated = false
        for (obj in progress.quest.objectives) {
            if (obj is TalkToNpcObjective && obj.matchesNpc(npcId)) {
                val current = progress.getProgress(obj.id)
                if (current < obj.requiredAmount) {
                    progress.setProgress(obj.id, current + 1)
                    updated = true
                }
            }
        }
        if (updated) {
            checkCompletion(player, progress)
            savePlayerData(player)
        }
        return updated
    }

    // [NEW] 获取所有可接取任务
    fun getAvailableQuests(player: Player): List<Quest> {
        return questRegistry.values.filter { isQuestAvailable(player, it) }
    }

    fun hasCompleted(player: Player, questId: String): Boolean {
        val list = playerQuests[player.uniqueId] ?: return false
        return list.any { it.quest.id == questId && it.isCompleted }
    }

    // === 5. 数据持久化 ===

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        loadPlayerData(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        savePlayerData(event.player)
        playerQuests.remove(event.player.uniqueId)
    }

    private fun getPlayerFile(uuid: UUID): File {
        return File(plugin.dataFolder, "quest_data/$uuid.yml")
    }

    private fun loadPlayerData(player: Player) {
        val file = getPlayerFile(player.uniqueId)
        if (!file.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(file)
        val list = ArrayList<QuestProgress>()

        for (questId in yaml.getKeys(false)) {
            val quest = getQuest(questId) ?: continue

            val qp = QuestProgress(quest)
            qp.isCompleted = yaml.getBoolean("$questId.completed")

            val progSec = yaml.getConfigurationSection("$questId.progress")
            if (progSec != null) {
                for (objId in progSec.getKeys(false)) {
                    val count = progSec.getInt(objId)
                    qp.setProgress(objId, count)
                }
            }
            list.add(qp)
        }
        playerQuests[player.uniqueId] = list
    }

    private fun savePlayerData(player: Player) {
        val list = playerQuests[player.uniqueId] ?: return
        val file = getPlayerFile(player.uniqueId)
        val yaml = YamlConfiguration()

        for (qp in list) {
            val qid = qp.quest.id
            yaml.set("$qid.completed", qp.isCompleted)

            for (obj in qp.quest.objectives) {
                yaml.set("$qid.progress.${obj.id}", qp.getProgress(obj.id))
            }
        }

        try {
            yaml.save(file)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}