package com.panling.basic.npc

import com.panling.basic.npc.api.NpcAction
import org.bukkit.Location
import org.bukkit.entity.EntityType
import java.util.UUID
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class Npc(
    val id: String,
    val name: String,
    val location: Location,
    val type: EntityType
) {
    // [NEW] 自定义对话内容 (支持颜色代码)
    var dialogText: String = "你好，有什么可以帮你的吗？"

    // [NEW] 通用数据存储 (用来存 shop_id, skin_texture 等额外数据，保持高扩展性)
    // Java 的 Object 对应 Kotlin 的 Any
    private val data = HashMap<String, Any>()

    var entityUuid: UUID? = null
    val actions = ArrayList<NpcAction>()

    fun setData(key: String, value: Any) {
        data[key] = value
    }

    fun getData(key: String): Any? {
        return data[key]
    }

    fun hasData(key: String): Boolean {
        return data.containsKey(key)
    }

    fun addAction(action: NpcAction) {
        actions.add(action)
    }
}