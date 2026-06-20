package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PatchTemplate
import com.panling.basic.api.Reloadable
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PatchSlot(val stat: String, val pct: Double)

class PatchManager(
    private val plugin: PanlingBasic,
    private val itemManager: ItemManager
) : Reloadable {

    private val templates = LinkedHashMap<String, PatchTemplate>()
    private val statCaps = HashMap<String, Double>()

    // 玩家级贴片数据: playerUUID → itemId → list of patches
    private val playerPatches = ConcurrentHashMap<java.util.UUID, MutableMap<String, MutableList<PatchSlot>>>()

    companion object {
        const val MAX_PATCH_SLOTS = 6
    }

    init {
        plugin.reloadManager.register(this)
        loadAll()
    }

    override fun reload() {
        loadAll()
        // 重新加载所有在线玩家的贴片数据（覆盖内存 → 后台改 YAML 后 reload 即生效）
        for (player in Bukkit.getOnlinePlayers()) {
            playerPatches.remove(player.uniqueId)
            loadPlayerData(player)
        }
    }

    // =========================================================
    // 加载配置
    // =========================================================
    private fun loadAll() {
        templates.clear(); statCaps.clear()
        loadConfig(); loadPatches()
        plugin.logger.info("[PatchManager] ${templates.size} templates, ${statCaps.size} caps")
    }

    private fun loadConfig() {
        val f = File(plugin.dataFolder, "patches/patch_config.yml")
        if (!f.exists()) return
        val s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("stat_caps") ?: return
        s.getKeys(false).forEach { statCaps[it] = s.getDouble(it, 0.3) }
    }

    private fun loadPatches() {
        val dir = File(plugin.dataFolder, "patches")
        if (!dir.exists()) { dir.mkdirs(); return }
        dir.walk().filter { it.extension == "yml" && it.name != "patch_config.yml" }
            .sortedBy { it.name }.forEach { file ->
                val cfg = YamlConfiguration.loadConfiguration(file)
                for (k in cfg.getKeys(false)) {
                    try { templates[k] = PatchTemplate.load(k, cfg.getConfigurationSection(k)!!) }
                    catch (e: Exception) { plugin.logger.warning("[PatchManager] bad $k: ${e.message}") }
                }
            }
    }

    fun getTemplate(id: String) = templates[id]
    fun getAllTemplates() = templates.values
    fun getStatCaps() = statCaps

    /** 根据 stat+pct 反查贴片模板（用于 UI 显示对应材质） */
    fun findTemplate(stat: String, pct: Double): PatchTemplate? {
        return templates.values.firstOrNull { it.stat == stat && kotlin.math.abs(it.perPatchPct - pct) < 0.001 }
    }
    fun getPatchesForPlayer(player: Player): Map<String, List<PatchSlot>> = playerPatches[player.uniqueId] ?: emptyMap()
    fun getPatchesForItem(player: Player, itemId: String): List<PatchSlot> = playerPatches[player.uniqueId]?.get(itemId) ?: emptyList()

    private fun getOrCreatePatches(player: Player, itemId: String): MutableList<PatchSlot> {
        return playerPatches.getOrPut(player.uniqueId) { ConcurrentHashMap() }
            .getOrPut(itemId) { java.util.Collections.synchronizedList(mutableListOf()) }
    }

    // =========================================================
    // 镶嵌 / 覆盖 (操作玩家级 map)
    // =========================================================
    fun embedPatch(player: Player, equipmentId: String, patchStat: String, patchPct: Double): Boolean {
        val slots = getPatchSlots(player, equipmentId)
        val patches = getOrCreatePatches(player, equipmentId)
        val used = patches.size

        if (used >= slots) return false
        val cap = statCaps[patchStat] ?: 0.3
        val current = patches.filter { it.stat == patchStat }.sumOf { it.pct }
        if (current + patchPct > cap) return false

        patches.add(PatchSlot(patchStat, patchPct))
        savePlayerData(player)
        return true
    }

    fun overwritePatch(player: Player, equipmentId: String, slotIndex: Int, newStat: String, newPct: Double): Boolean {
        val patches = getOrCreatePatches(player, equipmentId)
        if (slotIndex < 0 || slotIndex >= patches.size) return false

        val old = patches[slotIndex]
        val cap = statCaps[newStat] ?: 0.3
        val currentNew = if (newStat == old.stat) patches.filter { it.stat == newStat }.sumOf { it.pct } - old.pct
                         else patches.filter { it.stat == newStat }.sumOf { it.pct }
        if (currentNew + newPct > cap) return false

        patches[slotIndex] = PatchSlot(newStat, newPct)
        savePlayerData(player)
        return true
    }

    fun getPatchSlots(player: Player, equipmentId: String): Int {
        // 1. 查物品配置中是否显式声明 patch_slots
        val tpl = plugin.itemManager.getTemplate(equipmentId)
        val configured = tpl?.patchSlots
        if (configured != null) return configured

        // 2. 没配置 → 走默认（稀有度）
        val item = plugin.itemManager.createItem(equipmentId, player) ?: return 1
        val rw = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_RARITY_WEIGHT, PersistentDataType.INTEGER) ?: 1
        return (rw - 1).coerceIn(1, MAX_PATCH_SLOTS)
    }

    // =========================================================
    // 持久化
    // =========================================================
    fun savePlayerData(player: Player) {
        val data = playerPatches[player.uniqueId] ?: return
        val yaml = YamlConfiguration()
        for ((itemId, patches) in data) {
            val list = ArrayList<Map<String, Any>>()
            for (p in patches) list.add(mapOf("stat" to p.stat, "pct" to p.pct))
            yaml.set("patches.$itemId", list)
        }
        val file = File(plugin.dataFolder, "playerdata/${player.uniqueId}_patches.yml")
        file.parentFile.mkdirs()
        yaml.save(file)
    }

    fun loadPlayerData(player: Player) {
        val file = File(plugin.dataFolder, "playerdata/${player.uniqueId}_patches.yml")
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("patches") ?: return
        for (itemId in section.getKeys(false)) {
            @Suppress("UNCHECKED_CAST")
            val list = section.getList(itemId) as? List<Map<String, Any>> ?: continue
            val patches = list.mapNotNull { entry ->
                val stat = entry["stat"] as? String ?: return@mapNotNull null
                val pct = (entry["pct"] as? Number)?.toDouble() ?: return@mapNotNull null
                PatchSlot(stat, pct)
            }.toMutableList()
            if (patches.isNotEmpty()) {
                playerPatches.getOrPut(player.uniqueId) { ConcurrentHashMap() }[itemId] =
                    java.util.Collections.synchronizedList(patches)
            }
        }
    }

    fun unloadPlayerData(player: Player) {
        playerPatches.remove(player.uniqueId)
    }

}
