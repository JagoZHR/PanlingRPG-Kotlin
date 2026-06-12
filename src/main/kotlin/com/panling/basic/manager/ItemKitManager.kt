package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * 通用礼包管理器。
 *
 * 配置文件：plugins/PanlingRPG/kits/<kit_name>.yml
 *
 * 支持三种物品条目格式：
 *   1. 引用插件物品：  { id: <item_id>, slot: 0, amount: 1 }
 *   2. 原版物品：      { id: vanilla:BREAD, amount: 32, slot: -1 }
 *   3. 完整自定义物品：{ material: STICK, name: "§f...", lore: [...], enchantments: [...], ... }
 *
 * 使用方式：
 *   plugin.itemKitManager.giveKits(player, "starter_warrior", "race_human")
 *   — 不存在的礼包静默跳过
 */
class ItemKitManager(private val plugin: PanlingBasic) {

    private val VANILLA_PREFIX = "vanilla:"

    /** 给玩家发放一个礼包，礼包不存在则静默跳过。 */
    fun giveKit(player: Player, kitName: String) {
        val file = File(plugin.dataFolder, "kits/$kitName.yml")
        if (!file.exists()) return

        val cfg = YamlConfiguration.loadConfiguration(file)
        val items = cfg.getList("items") ?: return
        val inv = player.inventory

        for (entry in items) {
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any> ?: continue
            val item = buildItem(map, player) ?: continue

            val amount = (map["amount"] as? Number)?.toInt() ?: 1
            val slot = (map["slot"] as? Number)?.toInt() ?: -1

            // 解锁使用资格（插件物品需要）
            val rawId = map["id"]?.toString()
            if (rawId != null && !rawId.startsWith(VANILLA_PREFIX)) {
                plugin.playerDataManager.addUnlockedItem(player, rawId)
            }

            item.amount = amount

            if (slot in 0..40) {
                val existing = inv.getItem(slot)
                if (existing == null || existing.type == Material.AIR) {
                    inv.setItem(slot, item)
                } else {
                    inv.addItem(item)
                }
            } else {
                inv.addItem(item)
            }
        }
    }

    /** 发放多个礼包（合并）。 */
    fun giveKits(player: Player, vararg kitNames: String) {
        for (name in kitNames) giveKit(player, name)
    }

    // ── 构建物品 ──

    private fun buildItem(map: Map<String, Any>, player: Player): ItemStack? {
        val rawId = map["id"]?.toString()

        // 格式1 & 2：有 id 字段
        if (rawId != null) {
            // 原版物品
            if (rawId.startsWith(VANILLA_PREFIX)) {
                val matName = rawId.removePrefix(VANILLA_PREFIX)
                return try {
                    ItemStack(Material.valueOf(matName.uppercase()))
                } catch (_: Exception) { null }
            }
            // 插件物品
            return plugin.itemManager.createItem(rawId, player)
        }

        // 格式3：完整自定义物品（material 字段必填）
        val matStr = map["material"]?.toString() ?: return null
        val mat = try { Material.valueOf(matStr.uppercase()) } catch (_: Exception) { return null }
        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item

        // 名称
        (map["name"] as? String)?.let {
            meta.displayName(Component.text(it).decoration(TextDecoration.ITALIC, false))
        }

        // Lore
        @Suppress("UNCHECKED_CAST")
        val loreList = map["lore"] as? List<String>
        if (loreList != null) {
            meta.lore(loreList.map { Component.text(it).decoration(TextDecoration.ITALIC, false) })
        }

        // 附魔（格式: "namespace:id:level" 或 "minecraft:sharpness:5"）
        @Suppress("UNCHECKED_CAST")
        val enchants = map["enchantments"] as? List<String>
        if (enchants != null) {
            for (enchStr in enchants) {
                val parts = enchStr.split(":")
                if (parts.size < 2) continue
                val key = NamespacedKey.fromString("${parts[0]}:${parts[1]}") ?: continue
                val level = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1 else 1
                val enchantment = getEnchantment(key)
                if (enchantment != null) {
                    meta.addEnchant(enchantment, level, true)
                }
            }
        }

        // ItemFlag
        @Suppress("UNCHECKED_CAST")
        val flags = map["flags"] as? List<String>
        if (flags != null) {
            for (flagStr in flags) {
                try { meta.addItemFlags(ItemFlag.valueOf(flagStr.uppercase())) } catch (_: Exception) {}
            }
        }

        // 不可损坏
        if (map["unbreakable"] == true) {
            meta.isUnbreakable = true
        }

        item.itemMeta = meta
        return item
    }

    private fun getEnchantment(key: NamespacedKey): Enchantment? {
        return try {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key)
        } catch (_: Exception) { null }
    }
}
