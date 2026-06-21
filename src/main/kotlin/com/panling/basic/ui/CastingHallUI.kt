package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class CastingHallUI(private val plugin: PanlingBasic) {

    private val regions = listOf("forest", "desert", "cave", "lake")
    private val regionNames = mapOf(
        "forest" to "§a青龙·森林", "desert" to "§c朱雀·沙漠",
        "cave" to "§f白虎·洞穴", "lake" to "§3玄武·湖边"
    )
    private val slots = listOf("weapon", "helmet", "chestplate", "leggings", "boots")
    private val slotNames = mapOf(
        "weapon" to "武器", "helmet" to "头盔", "chestplate" to "胸甲",
        "leggings" to "腿甲", "boots" to "靴子"
    )
    private val slotMats = mapOf(
        "weapon" to Material.IRON_SWORD, "helmet" to Material.IRON_HELMET,
        "chestplate" to Material.IRON_CHESTPLATE, "leggings" to Material.IRON_LEGGINGS,
        "boots" to Material.IRON_BOOTS
    )
    private val slotBonusDesc = mapOf(
        "weapon" to "§e攻击力 §6+5",
        "helmet" to "§e防御力 §6+3",
        "chestplate" to "§e防御力 §6+4",
        "leggings" to "§e防御力 §6+3",
        "boots" to "§e移动速度 §6+3%"
    )
    private val regionFullBonus = mapOf(
        "forest" to "§e攻击力 §6+8",
        "desert" to "§e暴击率 §6+5%",
        "cave" to "§e护甲穿透 §6+8",
        "lake" to "§e防御力 §6+8"
    )

    private fun materialToSlot(mat: Material): String? = when {
        mat.name.endsWith("_HELMET") || mat == Material.TURTLE_HELMET || mat == Material.CARVED_PUMPKIN -> "helmet"
        mat.name.endsWith("_CHESTPLATE") || mat == Material.ELYTRA -> "chestplate"
        mat.name.endsWith("_LEGGINGS") -> "leggings"
        mat.name.endsWith("_BOOTS") -> "boots"
        mat.name.endsWith("_SWORD") || mat.name.endsWith("_AXE") || mat == Material.BOW
            || mat == Material.CROSSBOW || mat == Material.TRIDENT || mat.name.endsWith("_HOE") -> "weapon"
        else -> null
    }

    fun open(player: Player) {
        val inv = Bukkit.createInventory(Holder(), 54, Component.text("§8❖ 铸 灵 殿"))
        val dm = plugin.playerDataManager
        val submitted = dm.getSubmittedItems(player)

        for (ri in regions.indices) {
            val region = regions[ri]
            val baseSlot = ri * 9
            val count = slots.count { "$region:$it" in submitted }

            // 地区标题
            val header = ItemStack(Material.PAPER)
            val hm = header.itemMeta
            val fullBonusText = regionFullBonus[region] ?: ""
            val headerLore = listOf(
                Component.text("§7提交全套五阶装备后额外获得：").decoration(TextDecoration.ITALIC, false),
                Component.text("  $fullBonusText").decoration(TextDecoration.ITALIC, false)
            )
            hm.displayName(Component.text("${regionNames[region]}  §7($count/5)").decoration(TextDecoration.ITALIC, false))
            hm.lore(headerLore)
            header.itemMeta = hm
            inv.setItem(baseSlot, header)

            // 5 个装备槽
            for (si in slots.indices) {
                val slot = slots[si]
                val submittedKey = "$region:$slot"
                val done = submittedKey in submitted

                val icon = ItemStack(if (done) Material.CYAN_DYE else slotMats[slot]!!)
                val im = icon.itemMeta
                val bonusDesc = slotBonusDesc[slot] ?: ""
                val iconLore = if (done) listOf(
                    Component.text("§a已提交").decoration(TextDecoration.ITALIC, false),
                    Component.text(bonusDesc).decoration(TextDecoration.ITALIC, false)
                ) else listOf(
                    Component.text("§7提交后获得：").decoration(TextDecoration.ITALIC, false),
                    Component.text("  $bonusDesc").decoration(TextDecoration.ITALIC, false),
                    Component.text("§8点击提交（自动搜索背包）").decoration(TextDecoration.ITALIC, false)
                )
                im.displayName(Component.text(
                    if (done) "§a${slotNames[slot]} §7(已提交)" else "§7${slotNames[slot]}"
                ).decoration(TextDecoration.ITALIC, false))
                im.lore(iconLore)
                if (!done) {
                    im.persistentDataContainer.set(
                        NamespacedKey(plugin, "cast_submit"), PersistentDataType.STRING, submittedKey
                    )
                }
                icon.itemMeta = im
                inv.setItem(baseSlot + 1 + si, icon)
            }

            // 全套指示器
            val full = dm.isRegionFullSet(player, region)
            val statusIcon = ItemStack(if (full) Material.NETHER_STAR else Material.GRAY_DYE)
            val sm = statusIcon.itemMeta
            sm.displayName(Component.text(
                if (full) "§e§l全套已提交" else "§7未成套"
            ).decoration(TextDecoration.ITALIC, false))
            sm.lore(if (full) listOf(
                Component.text("§a$fullBonusText 已激活").decoration(TextDecoration.ITALIC, false)
            ) else listOf(
                Component.text("§8提交全套获得：$fullBonusText").decoration(TextDecoration.ITALIC, false)
            ))
            statusIcon.itemMeta = sm
            inv.setItem(baseSlot + 7, statusIcon)
        }

        player.openInventory(inv)
    }

    fun handleClick(player: Player, inv: Inventory, uiSlot: Int, item: ItemStack) {
        val meta = item.itemMeta ?: return
        val key = meta.persistentDataContainer.get(
            NamespacedKey(plugin, "cast_submit"), PersistentDataType.STRING
        ) ?: return

        val (region, equipSlot) = key.split(":")
        val dm = plugin.playerDataManager
        if (dm.hasSubmitted(player, region, equipSlot)) {
            player.sendMessage("§c该装备已经提交过了！")
            return
        }

        var foundIdx = -1
        for (i in player.inventory.contents.indices) {
            val stack = player.inventory.contents[i] ?: continue
            if (!stack.hasItemMeta()) continue
            val stackMeta = stack.itemMeta ?: continue
            val stackRegion = stackMeta.persistentDataContainer.get(
                BasicKeys.ITEM_SET_REGION, PersistentDataType.STRING
            ) ?: continue
            if (stackRegion != "${region}_set") continue
            val rarity = stackMeta.persistentDataContainer.get(
                BasicKeys.ITEM_RARITY, PersistentDataType.STRING
            ) ?: continue
            if (rarity != "EPIC" && rarity != "LEGENDARY") continue
            val matSlot = materialToSlot(stack.type) ?: continue
            if (matSlot != equipSlot) continue
            foundIdx = i
            break
        }

        if (foundIdx < 0) {
            player.sendMessage("§c背包中没有找到 ${regionNames[region]} 的五阶${slotNames[equipSlot]}！")
            return
        }

        val found = player.inventory.contents[foundIdx]!!
        if (found.amount > 1) found.amount-- else player.inventory.setItem(foundIdx, null)

        dm.submitItem(player, region, equipSlot)
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1.5f)
        player.sendMessage("§a成功提交 ${regionNames[region]} §a的 ${slotNames[equipSlot]}！")

        if (dm.isRegionFullSet(player, region)) {
            player.sendMessage("§e§l${regionNames[region]} 全套收集完成！额外属性已激活！")
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }

        open(player)
    }

    class Holder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }
}
