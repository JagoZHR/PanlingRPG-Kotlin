package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import com.panling.basic.npc.Npc
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*

class NpcManager(private val plugin: PanlingBasic) : Listener, Reloadable {

    // ID -> Npc对象 (配置数据源)
    private val npcMap = HashMap<String, Npc>()

    // 实体UUID -> Npc对象 (用于交互查找)
    private val entityMap = HashMap<UUID, Npc>()

    private val KEY_IS_NPC: NamespacedKey = NamespacedKey(plugin, "is_panling_npc")

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // [NEW] 注册重载
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        loadConfig()
    }

    // === 1. 核心 API: 重载与修复 ===

    /**
     * [API] 彻底重载 NPC 系统
     * 适用场景：修改了 npcs.yml 后，或者想完全重置所有 NPC
     */
    override fun reload() {
        // 1. 清理实体
        despawnAll()
        // 2. 清空缓存
        npcMap.clear()
        entityMap.clear()
        // 3. 重新加载
        loadConfig()

        plugin.logger.info("NPC 系统已重载。")
    }

    /**
     * [API] 修复丢失的 NPC (误杀恢复)
     * 适用场景：玩家误杀 NPC，或者区块卸载导致实体消失，但不想重读配置文件
     * @return 成功修复/重生的 NPC 数量
     */
    fun restoreNpcs(): Int {
        var count = 0
        val fixedNpcs = ArrayList<String>()

        for (npc in npcMap.values) {
            var needRespawn = false

            // 情况A: 从未生成过
            if (npc.entityUuid == null) {
                needRespawn = true
            } else {
                // 情况B: 有UUID，但找不到实体(区块卸载/被清理) 或 实体已死
                val entity = Bukkit.getEntity(npc.entityUuid!!)
                if (entity == null || !entity.isValid || entity.isDead) {
                    needRespawn = true
                }
            }

            if (needRespawn) {
                // 如果之前有记录 UUID，先从 map 中移除，防止内存泄漏
                if (npc.entityUuid != null) {
                    entityMap.remove(npc.entityUuid!!)
                }

                spawnNpcEntity(npc)
                if (npc.entityUuid != null) { // 确保生成成功
                    count++
                    fixedNpcs.add(npc.name)
                }
            }
        }

        if (count > 0) {
            plugin.logger.info("已修复 $count 个 NPC: $fixedNpcs")
        }
        return count;
    }

    // === 2. 内部逻辑 ===

    private fun loadConfig() {
        val file = File(plugin.dataFolder, "npcs.yml")
        if (!file.exists()) {
            plugin.saveResource("npcs.yml", false)
        }
        val config = YamlConfiguration.loadConfiguration(file)

        for (id in config.getKeys(false)) {
            val sec = config.getConfigurationSection(id) ?: continue

            val name = sec.getString("name", "NPC")!!
            val typeStr = sec.getString("type", "VILLAGER")!!
            val type = try {
                EntityType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                EntityType.VILLAGER
            }

            val locStr = sec.getString("location")
            val loc = parseLoc(locStr)

            if (loc != null) {
                val npc = Npc(id, name, loc, type)

                // [NEW] 读取自定义对话
                if (sec.contains("dialog")) {
                    npc.dialogText = sec.getString("dialog")!!.replace("&", "§")
                }

                // [NEW] 读取额外数据 (比如 shop_id)
                if (sec.contains("data")) {
                    val dataSec = sec.getConfigurationSection("data")
                    if (dataSec != null) {
                        for (key in dataSec.getKeys(false)) {
                            npc.setData(key, dataSec.get(key)!!)
                        }
                    }
                }

                npcMap[id] = npc
            }
        }
        plugin.logger.info("已加载 ${npcMap.size} 个 NPC 配置。")
        spawnAll()
    }

    // 关服或重载时调用
    fun despawnAll() {
        // A. 移除我们内存中记录的
        for (uuid in entityMap.keys) {
            val entity = Bukkit.getEntity(uuid)
            entity?.remove()
        }
        entityMap.clear()

        // B. 扫描世界清理残留 (防止堆叠)
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity.persistentDataContainer.has(KEY_IS_NPC, PersistentDataType.BYTE)) {
                    entity.remove()
                }
            }
        }
    }

    private fun spawnAll() {
        for (npc in npcMap.values) {
            spawnNpcEntity(npc)
        }
    }

    private fun spawnNpcEntity(npc: Npc) {
        val loc = npc.location
        if (loc.world == null) return

        // 确保区块加载，否则 spawnEntity 可能返回 null 或生成失败
        if (!loc.chunk.isLoaded) {
            loc.chunk.load()
        }

        val entity = loc.world.spawnEntity(loc, npc.type) as LivingEntity

        entity.setAI(false)
        entity.isInvulnerable = true
        entity.isSilent = true
        entity.removeWhenFarAway = false
        entity.customName = npc.name
        entity.isCustomNameVisible = true

        // 打上标签
        entity.persistentDataContainer.set(KEY_IS_NPC, PersistentDataType.BYTE, 1.toByte())

        // 更新记录
        npc.entityUuid = entity.uniqueId
        entityMap[entity.uniqueId] = npc
    }

    // === 3. 交互处理 ===

    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val npc = entityMap[event.rightClicked.uniqueId]
        if (npc != null) {
            event.isCancelled = true

            // [MODIFIED] 高级交互：打开聚合对话框
            // 除非 NPC 配置了特殊逻辑，否则统一走 DialogManager
            plugin.dialogManager.openDialog(event.player, npc)

            // 旧的 Action 逻辑可以保留作为特殊触发（例如踩踏板触发），
            // 但右键点击建议由 Dialog 接管。
        }
    }

    fun getNpc(id: String): Npc? {
        return npcMap[id]
    }

    private fun parseLoc(str: String?): Location? {
        if (str == null) return null
        return try {
            val p = str.split(",")
            val w = Bukkit.getWorld(p[0]) ?: return null
            Location(
                w,
                p[1].toDouble(),
                p[2].toDouble(),
                p[3].toDouble(),
                p[4].toFloat(),
                p[5].toFloat()
            )
        } catch (e: Exception) {
            plugin.logger.warning("NPC 坐标解析失败: $str")
            null
        }
    }
}