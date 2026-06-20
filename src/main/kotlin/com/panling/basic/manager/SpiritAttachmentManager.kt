package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.AttachmentType
import com.panling.basic.api.Reloadable
import com.panling.basic.api.SpiritAttachment
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.*

class SpiritAttachmentManager(
    private val plugin: PanlingBasic,
    private val dataManager: PlayerDataManager
) : Reloadable {

    private val templates = LinkedHashMap<String, SpiritAttachment>()

    companion object {
        const val MAX_SLOTS = 5
    }

    init {
        plugin.reloadManager.register(this)
        loadAttachments()
    }

    override fun reload() {
        loadAttachments()
    }

    // =========================================================
    // 模板加载
    // =========================================================
    private fun loadAttachments() {
        templates.clear()
        val dir = File(plugin.dataFolder, "attachments")
        if (!dir.exists()) {
            dir.mkdirs()
            // 写入默认配置文件
            val defaults = mapOf(
                "efficiency.yml" to javaClass.getResourceAsStream("/attachments/efficiency.yml"),
                "special.yml" to javaClass.getResourceAsStream("/attachments/special.yml"),
                "combat.yml" to javaClass.getResourceAsStream("/attachments/combat.yml"),
                "fun.yml" to javaClass.getResourceAsStream("/attachments/fun.yml")
            )
            for ((name, stream) in defaults) {
                if (stream != null) {
                    File(dir, name).outputStream().use { stream.copyTo(it) }
                    stream.close()
                }
            }
        }
        dir.walk().filter { it.extension == "yml" }.sortedBy { it.name }.forEach { file ->
            loadSingleFile(file)
        }
        plugin.logger.info("[SpiritAttachment] Loaded ${templates.size} attachment templates")
    }

    private fun loadSingleFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (key in config.getKeys(false)) {
            val section = config.getConfigurationSection(key) ?: continue
            try {
                val attachment = SpiritAttachment.load(key, section)
                templates[key] = attachment
            } catch (e: Exception) {
                plugin.logger.warning("[SpiritAttachment] Failed to load '$key': ${e.message}")
            }
        }
    }

    fun getTemplate(id: String): SpiritAttachment? = templates[id]
    fun getAllTemplates(): Collection<SpiritAttachment> = templates.values

    // =========================================================
    // 槽位 CRUD
    // =========================================================
    fun getPlayerAttachments(player: Player): Array<Pair<Int, SpiritAttachment?>> {
        val ids = dataManager.getAttachments(player)
        return Array(ids.size) { i ->
            val template = ids[i]?.let { templates[it] }
            Pair(i, template)
        }
    }

    fun equipAttachment(player: Player, slot: Int, attachmentId: String): Boolean {
        val template = templates[attachmentId] ?: return false
        val slots = dataManager.getAttachmentSlotCount(player)
        if (slot < 0 || slot >= slots) return false
        dataManager.setAttachment(player, slot, attachmentId)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f)
        return true
    }

    fun detachAttachment(player: Player, slot: Int): SpiritAttachment? {
        val ids = dataManager.getAttachments(player)
        if (slot < 0 || slot >= ids.size) return null
        val id = ids[slot] ?: return null
        val template = templates[id] ?: return null

        // 花铜钱拆卸
        val cost = template.tier * 500.0
        if (!plugin.playerDataManager.takeMoney(player, cost)) {
            player.sendMessage("§c拆卸费用不足！需要 §e${cost.toInt()} 铜钱")
            return null
        }

        dataManager.removeAttachment(player, slot)
        player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 1.0f, 1.0f)
        return template
    }

    // =========================================================
    // 效果查询 (供 StatCalculator 和 被动系统 使用)
    // =========================================================
    fun getStatEffects(player: Player): Map<String, Double> {
        val merged = HashMap<String, Double>()
        val ids = dataManager.getAttachments(player)
        for (id in ids) {
            if (id == null) continue
            val template = templates[id] ?: continue
            for ((stat, value) in template.effects) {
                merged.merge(stat, value) { a, b -> a + b }
            }
        }
        return merged
    }

    fun getSpecialMonsters(player: Player): Set<String> {
        val monsters = HashSet<String>()
        val ids = dataManager.getAttachments(player)
        for (id in ids) {
            if (id == null) continue
            val template = templates[id] ?: continue
            if (template.type == AttachmentType.SPECIAL) {
                monsters.addAll(template.specialMonsters)
            }
        }
        return monsters
    }

    fun getActivePassiveIds(player: Player): List<String> {
        val passives = ArrayList<String>()
        val ids = dataManager.getAttachments(player)
        for (id in ids) {
            if (id == null) continue
            val template = templates[id] ?: continue
            if (template.type == AttachmentType.COMBAT && template.passiveId != null) {
                passives.add(template.passiveId)
            }
        }
        return passives
    }

    // =========================================================
    // 被动缓存注入 (供 StatCalculator 调用)
    // =========================================================
    fun registerPassivesToCache(player: Player) {
        val passives = getActivePassiveIds(player)
        for (pid in passives) {
            dataManager.addPassiveToCache(
                player,
                PlayerDataManager.PassiveTrigger.CONSTANT,
                pid,
                null,
                0
            )
        }
    }
}
