package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class CloudShiftPassive(plugin: PanlingBasic) : AbstractSkill("CLOUD_SHIFT", "云衣", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        if (!item.hasItemMeta()) return
        if (!item.itemMeta!!.persistentDataContainer.has(com.panling.basic.api.BasicKeys.ITEM_ID, PersistentDataType.STRING)) return
        val passives = pl.playerDataManager.getCachedPassives(player, PlayerDataManager.PassiveTrigger.CONSTANT)
        if (passives.find { it.id == id } == null) return

        val cdKey = NamespacedKey(pl, "set_cd_cloud_shift")
        val now = System.currentTimeMillis()
        val last = player.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - last < 8000) return
        player.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)

        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 40, 0, false, true))
        player.setMetadata("pl_cloud_shield", FixedMetadataValue(pl, now))
    }

    @EventHandler
    fun onHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        if (!victim.hasMetadata("pl_cloud_shield")) return
        val triggered = victim.getMetadata("pl_cloud_shield").firstOrNull()?.asLong() ?: return
        if (System.currentTimeMillis() - triggered > 2000) { victim.removeMetadata("pl_cloud_shield", pl); return }
        event.damage *= 0.7
        victim.removeMetadata("pl_cloud_shield", pl)
        victim.sendMessage("§9[云衣] §7流云护体！减免30%")
        victim.playSound(victim.location, Sound.BLOCK_WOOL_BREAK, 0.5f, 2.0f)
    }
}
