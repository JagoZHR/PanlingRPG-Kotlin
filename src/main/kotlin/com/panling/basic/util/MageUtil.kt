package com.panling.basic.util

import com.panling.basic.PanlingBasic
import com.panling.basic.api.ArrayStance
import com.panling.basic.api.BasicKeys
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

object MageUtil {

    // 核心标记 Key
    val NO_PLAYER_DAMAGE_KEY = NamespacedKey(PanlingBasic.instance, "pl_no_player_damage")

    /**
     * [重构] 统一初始化法师投射物
     * @param strategy 传入策略实例，通过多态获取是否允许PVP配置
     */
    fun configureProjectile(
        proj: Projectile?,
        plugin: Plugin,
        strategy: MageSkillStrategy,
        damage: Double,
        skillId: String?,
        element: String?
    ) {
        if (proj == null) return

        val pdc = proj.persistentDataContainer

        // 1. 基础数据
        pdc.set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, damage)
        if (skillId != null) {
            pdc.set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, skillId)
        }
        if (element != null) {
            pdc.set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, element)
        }

        // 2. [逻辑解耦] 读取策略的自我定义，写入通用 NBT
        // Listener 不需要认识 Strategy，只认这个 NBT 标签
        if (!strategy.canHitPlayers) {
            pdc.set(NO_PLAYER_DAMAGE_KEY, PersistentDataType.BYTE, 1.toByte())
        }
    }

    /**
     * 检查投射物是否携带"禁止伤害玩家"标记
     */
    fun isNoPlayerDamage(proj: Projectile?): Boolean {
        if (proj == null) return false
        return proj.persistentDataContainer.has(NO_PLAYER_DAMAGE_KEY, PersistentDataType.BYTE)
    }

    fun getMageWeapon(player: Player?): ItemStack? {
        return player?.inventory?.itemInOffHand
    }

    fun getWeaponRarity(item: ItemStack?): String {
        if (item == null || !item.hasItemMeta()) return "COMMON"
        // 使用 Elvis 操作符替代 getOrDefault
        return item.itemMeta!!.persistentDataContainer
            .get(BasicKeys.ITEM_RARITY, PersistentDataType.STRING) ?: "COMMON"
    }

    fun getWeaponType(item: ItemStack?): String {
        if (item == null || !item.hasItemMeta()) return "DEFAULT"
        return item.itemMeta!!.persistentDataContainer
            .get(BasicKeys.MAGE_WEAPON_TYPE, PersistentDataType.STRING) ?: "DEFAULT"
    }

    fun getTierValue(player: Player): Int {
        val item = getMageWeapon(player)
        val rarity = getWeaponRarity(item)
        return when (rarity) {
            "BROKEN" -> 0
            "COMMON" -> 1
            "UNCOMMON" -> 3
            "RARE" -> 4
            "EPIC" -> 5
            "LEGENDARY" -> 6
            "ETERNAL" -> 7
            else -> 1
        }
    }

    fun getDynamicLore(fabao: ItemStack?, weapon: ItemStack?, stance: ArrayStance): String? {
        if (fabao == null || !fabao.hasItemMeta()) return null

        val targetKey = if (stance == ArrayStance.OFFENSE) {
            BasicKeys.FABAO_LORE_OFFENSE
        } else {
            BasicKeys.FABAO_LORE_SUPPORT
        }

        val mapStr = fabao.itemMeta!!.persistentDataContainer.get(targetKey, PersistentDataType.STRING)
            ?: return null

        val rarity = getWeaponRarity(weapon)
        val type = getWeaponType(weapon)
        val key = "$rarity:$type"

        // 格式: rarity:type:lore;...
        for (entry in mapStr.split(";")) {
            val parts = entry.split(":", limit = 3)
            if (parts.size == 3) {
                if ("${parts[0]}:${parts[1]}" == key) {
                    return parts[2].replace("&", "§")
                }
            }
        }
        return null
    }

    fun dealSkillDamage(attacker: Player?, victim: LivingEntity?, damage: Double, isMagic: Boolean) {
        if (attacker == null || victim == null) return
        victim.noDamageTicks = 0
        val plugin = PanlingBasic.instance

        if (isMagic) {
            attacker.setMetadata("pl_magic_damage", FixedMetadataValue(plugin, true))
        }
        try {
            victim.damage(damage, attacker)
        } finally {
            if (isMagic) {
                attacker.removeMetadata("pl_magic_damage", plugin)
            }
        }
    }
}