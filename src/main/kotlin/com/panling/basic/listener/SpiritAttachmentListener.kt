package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.SpiritAttachmentManager
import com.panling.basic.ui.SpiritAttachmentUI
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.persistence.PersistentDataType

class SpiritAttachmentListener(
    private val manager: SpiritAttachmentManager
) : Listener {

    private val plugin = PanlingBasic.instance
    private val ui = SpiritAttachmentUI(manager)
    private val ACTION_KEY = NamespacedKey(plugin, "attach_action")
    private val SLOT_KEY = NamespacedKey(plugin, "attach_slot")
    private val BACK_KEY = NamespacedKey(plugin, "ui_back_target")

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? SpiritAttachmentUI.AttachmentHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.inventory) return

        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR || !clicked.hasItemMeta()) return
        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // 返回按钮
        val backTarget = pdc.get(BACK_KEY, PersistentDataType.STRING)
        if (backTarget == "MAIN") {
            plugin.menuManager?.openMenu(player)
            return
        }

        val action = pdc.get(ACTION_KEY, PersistentDataType.STRING) ?: return
        val slot = pdc.get(SLOT_KEY, PersistentDataType.INTEGER) ?: return

        if (action == "DETACH" && event.click == ClickType.SHIFT_LEFT) {
            val template = manager.detachAttachment(player, slot)
            if (template != null) {
                player.sendMessage("§a已拆卸附灵: ${template.name} §7(花费 ${template.tier * 500} 铜钱)")
                player.playSound(player.location, org.bukkit.Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            }
            ui.open(player)  // 刷新界面
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is SpiritAttachmentUI.AttachmentHolder) {
            event.isCancelled = true
        }
    }
}
