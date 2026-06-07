package com.panling.basic.quest

import com.panling.basic.api.PlayerRace
import com.panling.basic.quest.api.QuestObjective
import com.panling.basic.quest.api.QuestReward

class Quest(
    val id: String,
    val name: String,
    val description: String,
    val requiredLevel: Int,
    val preQuestId: String?, // [兼容] 单个前置 (已废弃，请用 preQuestIds)
    val preQuestIds: List<String>, // 多个前置 (OR: 满足任意一个即可，空列表 = 无前置)
    val preQuestIdsAll: List<String>, // 多个前置 (AND: 必须全部完成，空列表 = 无前置)
    var startNpcId: String?, // 允许为空 (无起始NPC)，且可变 (Setter)
    val requiredRace: PlayerRace?, // 种族限制 (null = 无限制)
    val objectives: List<QuestObjective>,
    val rewards: List<QuestReward>,
    val acceptDialog: List<String> = emptyList(), // 接取任务时的多轮对话
    val completeDialog: List<String> = emptyList(), // 完成任务时的多轮对话
    val autoCompleteNpc: Boolean = true, // 接取后是否自动完成当前 NPC 的对话目标 (默认 true)
    val autoAcceptNext: String? = null // 完成后自动接取的下一个任务 ID
) {

    // === [兼容性] 7 参数构造函数 (无 startNpcId, 无 requiredRace)
    constructor(
        id: String,
        name: String,
        description: String,
        requiredLevel: Int,
        preQuestId: String?,
        objectives: List<QuestObjective>,
        rewards: List<QuestReward>
    ) : this(id, name, description, requiredLevel, preQuestId, emptyList(), emptyList(), null, null, objectives, rewards)

    /**
     * 检查任务是否已完成
     */
    fun isCompleted(progress: QuestProgress): Boolean {
        // 使用 Kotlin 的 all 集合操作符，语义更清晰
        // 只要有一个目标没达到要求，就返回 false
        return objectives.all { obj ->
            val current = progress.getProgress(obj.id)
            current >= obj.requiredAmount
        }
    }
}