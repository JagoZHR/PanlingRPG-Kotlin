package com.panling.basic.quest

import java.util.HashMap

class QuestProgress(val quest: Quest) {

    val acceptTime: Long = System.currentTimeMillis()

    // 记录每个目标的进度: ObjectiveID -> Count
    // 例如: "kill_wolf" -> 3
    private val progressData = HashMap<String, Int>()

    var isCompleted: Boolean = false

    fun getProgress(objectiveId: String): Int {
        return progressData.getOrDefault(objectiveId, 0)
    }

    fun setProgress(objectiveId: String, amount: Int) {
        progressData[objectiveId] = amount
    }

    fun addProgress(objectiveId: String, amount: Int) {
        val current = getProgress(objectiveId)
        progressData[objectiveId] = current + amount
    }

    // 检查这个任务是否包含了某个目标ID (使用 Kotlin 的 any 集合操作)
    fun hasObjective(objId: String): Boolean {
        return quest.objectives.any { it.id == objId }
    }
}