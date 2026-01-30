package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.*
import com.panling.basic.util.MageUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

object LoreManager {

    private val SKILL_NAMES = mapOf(
        "FIRE_ARRAY" to "离火阵",
        "EARTH_SMASH" to "撼地猛击",
        "BACKSTEP" to "后撤步",
        "THUNDER_STRIKE" to "雷霆一击",
        "LASER" to "烈焰射线",
        "HEAVY_SLASH" to "重斩",
        "THORNS_SHIELD" to "反伤",
        "MARK_TARGET" to "狙击架势"
    )

    // 获取插件实例 (懒加载或直接获取)
    private val plugin by lazy { PanlingBasic.instance }

    fun updateItemLore(item: ItemStack?, player: Player?) {
        if (item == null || !item.hasItemMeta()) return
        val meta = item.itemMeta!! // 已检查 hasItemMeta
        val pdc = meta.persistentDataContainer

        val lore = ArrayList<Component>()

        val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING) ?: "WEAPON"
        val isFabao = "FABAO" == type
        val isElement = "ELEMENT" == type
        val isMaterial = "MATERIAL" == type

        val playerClassStr = player?.persistentDataContainer?.get(BasicKeys.DATA_CLASS, PersistentDataType.STRING)
        val isMage = "MAGE" == playerClassStr

        // 1. 头部信息
        if (!isElement) {
            val rarityName = pdc.get(BasicKeys.ITEM_RARITY, PersistentDataType.STRING)
            if (rarityName != null) {
                val rarity = Rarity.safeValueOf(rarityName)
                lore.add(
                    Component.text("稀有度:").color(rarity.color).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(getRarityStars(rarity)).color(rarity.color))
                )
            }
        }

        if (!isElement && !isMaterial) {
            val reqClassStr = pdc.get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)
            var reqClass = PlayerClass.NONE
            try {
                if (reqClassStr != null) reqClass = PlayerClass.valueOf(reqClassStr)
            } catch (ignored: Exception) {}

            val className = if (reqClass == PlayerClass.NONE) "无" else reqClass.displayName
            val classColor = if (reqClass == PlayerClass.NONE) NamedTextColor.GRAY else NamedTextColor.YELLOW
            lore.add(
                Component.text("限制职业:").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("[$className]").color(classColor))
            )
        }

        // [新增] 绑定信息显示
        // 建议放在职业限制/等级限制的下方
        val boundName = pdc.get(NamespacedKey(plugin, "bound_owner_name"), PersistentDataType.STRING)
        if (boundName != null) {
            lore.add(
                Component.text("灵魂绑定:").color(NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(" $boundName").color(NamedTextColor.RED))
            )
        }

        val reqLevel = pdc.getOrDefault(BasicKeys.FEATURE_REQ_LEVEL, PersistentDataType.INTEGER, 0)
        if (reqLevel > 0) {
            if (!isElement || isMage) {
                val levelMet = player != null && player.level >= reqLevel
                val levelColor = if (levelMet) NamedTextColor.GREEN else NamedTextColor.RED
                lore.add(
                    Component.text("限制等级:").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("[$reqLevel]").color(levelColor))
                )
            }
        }

        if ("ACCESSORY" == type) {
            val slotKey = NamespacedKey(plugin, "accessory_slot")
            val slotIndex = pdc.get(slotKey, PersistentDataType.INTEGER)
            if (slotIndex != null) {
                lore.add(
                    Component.text("限制栏位:").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(" 饰品槽 [${slotIndex + 1}]").color(NamedTextColor.YELLOW))
                )
            }
        }

        // 2. 描述
        val normalDesc = pdc.get(BasicKeys.ITEM_DESC, PersistentDataType.STRING)
        val supDesc = pdc.get(BasicKeys.ITEM_DESC_SUPPORT, PersistentDataType.STRING)
        val offDesc = pdc.get(BasicKeys.ITEM_DESC_OFFENSE, PersistentDataType.STRING)
        var finalDesc = normalDesc

        if (isMage && player != null) {
            val stanceStr = player.persistentDataContainer.get(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING)
            if ("OFFENSE" == stanceStr && offDesc != null) finalDesc = offDesc
            else if ("SUPPORT" == stanceStr && supDesc != null) finalDesc = supDesc
        }

        if (!finalDesc.isNullOrEmpty()) {
            finalDesc.split("|||").forEach { line ->
                lore.add(
                    Component.text(line.replace("&", "§"))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, true)
                )
            }
        }

        // 3. 属性
        var hasStats = false
        val statLines = ArrayList<Component>()
        for (key in BasicKeys.ALL_STATS) {
            val `val` = pdc.getOrDefault(key, PersistentDataType.DOUBLE, 0.0)
            if (`val` == 0.0) continue

            // 假设 BasicKeys.STAT_METADATA 是 Map<NamespacedKey, StatMeta>
            val metaData = BasicKeys.STAT_METADATA[key] ?: continue

            val valStr = if (metaData.isPercent) String.format("%.1f%%", `val` * 100) else String.format("%.1f", `val`)

            if (key == BasicKeys.ATTR_PHYSICAL_DAMAGE) {
                var atkSpeed = 0.0
                if (meta.hasAttributeModifiers()) {
                    // 使用 GENERIC_ATTACK_SPEED (1.21+)
                    val mods = meta.getAttributeModifiers(Attribute.ATTACK_SPEED)
                    if (mods != null && mods.isNotEmpty()) {
                        for (mod in mods) {
                            if (mod.operation == AttributeModifier.Operation.ADD_NUMBER) {
                                atkSpeed = 4.0 + mod.amount
                                break
                            }
                        }
                    }
                }
                var line = Component.text(metaData.displayName).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("  $valStr").color(NamedTextColor.WHITE))
                if (atkSpeed > 0) {
                    line = line.append(Component.text("  攻击速度 ").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                        .append(Component.text(String.format("%.1f", atkSpeed)).color(NamedTextColor.WHITE))
                }
                statLines.add(line)
            } else {
                statLines.add(
                    Component.text(metaData.displayName).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("  $valStr").color(NamedTextColor.WHITE))
                )
            }
            hasStats = true
        }

        if (hasStats && !isElement) {
            val title = when (type) {
                "FABAO" -> "法宝属性"
                "ARMOR" -> "装备属性"
                "ACCESSORY" -> "饰品属性"
                else -> "武器属性"
            }
            lore.add(createSeparator(title))
            lore.addAll(statLines)
        } else if (hasStats && isElement) {
            lore.addAll(statLines)
        }

        // 4. 技能
        var showSkills = true
        if (isElement && !isMage) showSkills = false

        if (showSkills) {
            var hasActive = false
            val activeLines = ArrayList<Component>()

            for ((trigger, key) in BasicKeys.TRIGGER_KEYS) {
                val skillId = pdc.get(key, PersistentDataType.STRING)
                if (skillId != null) {
                    hasActive = true

                    // [NEW] 1. 独立冷却和消耗
                    val cdKey = BasicKeys.TRIGGER_COOLDOWN_KEYS[trigger]
                    val cdMillis = if (cdKey != null) pdc.getOrDefault(cdKey, PersistentDataType.LONG, 0L) else 0L

                    val costKey = BasicKeys.TRIGGER_COST_KEYS[trigger]
                    val cost = if (costKey != null) pdc.getOrDefault(costKey, PersistentDataType.INTEGER, 0) else 0

                    val infoSuffix = StringBuilder()
                    if (cdMillis > 0 || cost > 0) {
                        infoSuffix.append(" §8(")
                        if (cdMillis > 0) infoSuffix.append(String.format("%.1fs", cdMillis / 1000.0))
                        if (cdMillis > 0 && cost > 0) infoSuffix.append(", ")
                        if (cost > 0) infoSuffix.append("消耗").append(cost)
                        infoSuffix.append(")")
                    }

                    // [NEW] 2. 独立描述逻辑 (支持杀伐/生息区分)
                    var customDesc: String? = null
                    if (isMage && player != null) {
                        val stanceStr = player.persistentDataContainer.get(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING)
                        if ("OFFENSE" == stanceStr) {
                            val offKey = BasicKeys.TRIGGER_LORE_OFFENSE_KEYS[trigger]
                            if (offKey != null) customDesc = pdc.get(offKey, PersistentDataType.STRING)
                        } else if ("SUPPORT" == stanceStr) {
                            val supKey = BasicKeys.TRIGGER_LORE_SUPPORT_KEYS[trigger]
                            if (supKey != null) customDesc = pdc.get(supKey, PersistentDataType.STRING)
                        }
                    }

                    // 如果没找到形态专属描述，回退到默认独立描述
                    if (customDesc == null) {
                        val loreKey = BasicKeys.TRIGGER_LORE_KEYS[trigger]
                        if (loreKey != null) customDesc = pdc.get(loreKey, PersistentDataType.STRING)
                    }

                    if (!customDesc.isNullOrEmpty()) {
                        // 有独立描述
                        var firstLine = true
                        for (line in customDesc.split("|||")) {
                            var textComp = Component.text(line.replace("&", "§"))
                                .color(NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)

                            // 仅在第一行追加冷却/消耗信息
                            if (firstLine && infoSuffix.isNotEmpty()) {
                                textComp = textComp.append(Component.text(infoSuffix.toString()))
                                firstLine = false
                            }
                            activeLines.add(textComp)
                        }
                    } else {
                        // 无独立描述 (默认格式)
                        val skillName = getSkillDisplayName(skillId)
                        val triggerName = trigger.displayName // 假设 SkillTrigger 有 displayName 属性
                        activeLines.add(
                            Component.text("[$triggerName] ").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                                .append(Component.text("[$skillName]").color(NamedTextColor.GREEN))
                                .append(Component.text(infoSuffix.toString()))
                        )
                    }
                }
            }

            val pId = pdc.get(BasicKeys.FEATURE_PASSIVE_ID, PersistentDataType.STRING)
            val pAtk = pdc.get(BasicKeys.PASSIVE_ON_ATTACK, PersistentDataType.STRING)
            val pHit = pdc.get(BasicKeys.PASSIVE_ON_HIT, PersistentDataType.STRING)

            var dynamicContent: String? = null
            if (isFabao && player != null) {
                // 假设 MageUtil 存在且是静态方法或 Object
                val offHandWeapon = MageUtil.getMageWeapon(player)
                val stanceStr = player.persistentDataContainer.getOrDefault(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING, "SUPPORT")
                val stance = try { ArrayStance.valueOf(stanceStr) } catch (e: Exception) { ArrayStance.SUPPORT }
                dynamicContent = MageUtil.getDynamicLore(item, offHandWeapon, stance)
            }

            val normalSkillLore = pdc.get(BasicKeys.ITEM_SKILL_LORE, PersistentDataType.STRING)
            val supSkillLore = pdc.get(BasicKeys.ITEM_SKILL_LORE_SUPPORT, PersistentDataType.STRING)
            val offSkillLore = pdc.get(BasicKeys.ITEM_SKILL_LORE_OFFENSE, PersistentDataType.STRING)

            var finalSkillLore = normalSkillLore
            if (isMage && player != null) {
                val stanceStr = player.persistentDataContainer.get(BasicKeys.DATA_ARRAY_STANCE, PersistentDataType.STRING)
                if ("OFFENSE" == stanceStr && offSkillLore != null) finalSkillLore = offSkillLore
                else if ("SUPPORT" == stanceStr && supSkillLore != null) finalSkillLore = supSkillLore
            }

            val hasPassive = (pId != null || pAtk != null || pHit != null)
            val hasContent = (hasActive || hasPassive || finalSkillLore != null)

            if (hasContent) {
                if (!isElement) {
                    val title = when (type) {
                        "FABAO" -> "法宝技能"
                        "ARMOR" -> "装备技能"
                        "ACCESSORY" -> "饰品技能"
                        else -> "武器技能"
                    }
                    lore.add(createSeparator(title))
                }

                lore.addAll(activeLines)

                // 全局信息
                if (hasActive) {
                    val cooldownMillis = pdc.getOrDefault(BasicKeys.FEATURE_COOLDOWN, PersistentDataType.LONG, 0L)
                    val cost = pdc.getOrDefault(BasicKeys.ITEM_SKILL_COST, PersistentDataType.INTEGER, 0)
                    if (cooldownMillis > 0 || cost > 0) {
                        val info = StringBuilder()
                        if (cooldownMillis > 0) info.append("冷却: ").append(cooldownMillis / 1000.0).append("秒 ")
                        if (cost > 0) info.append("消耗: ").append(cost).append("灵力")
                        lore.add(Component.text(info.toString()).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
                    }
                }

                addPassiveLines(lore, pId, "被动")
                addPassiveLines(lore, pAtk, "攻击")
                addPassiveLines(lore, pHit, "受击")

                if (!finalSkillLore.isNullOrEmpty()) {
                    if (finalSkillLore!!.contains("{DYNAMIC_DESC}")) {
                        val replacement = dynamicContent ?: "§8(暂无共鸣效果)"
                        finalSkillLore = finalSkillLore!!.replace("{DYNAMIC_DESC}", replacement)
                    }
                    finalSkillLore!!.split("|||").forEach { line ->
                        lore.add(Component.text(line.replace("&", "§")).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                    }
                }
            }
        }

        val setId = pdc.get(BasicKeys.ITEM_SET_ID, PersistentDataType.STRING)
        if (setId != null) {
            var setName = setId
            try {
                plugin.setManager?.let { setName = it.getSetName(setId) }
            } catch (ignored: Exception) {}
            lore.add(Component.text("套装: $setName").color(NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false))
        }

        meta.lore(lore)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
    }

    private fun createSeparator(title: String): Component {
        return Component.text("=========").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
            .append(Component.text(title).color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            .append(Component.text("=========").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false))
    }

    private fun getSkillDisplayName(skillId: String): String {
        try {
            val sm = plugin.skillManager // 假设为 public 属性
            val skill = sm.getSkill(skillId) // 如果 sm 是 Java 的 getSkillManager() 这里也是兼容的
            if (skill != null) return skill.name
        } catch (ignored: Exception) {}
        return SKILL_NAMES[skillId] ?: skillId
    }

    private fun addPassiveLines(lore: MutableList<Component>, raw: String?, tag: String) {
        if (raw.isNullOrEmpty()) return
        raw.split(",").forEach { pid ->
            if (pid.isNotEmpty()) {
                val skillName = getSkillDisplayName(pid)
                lore.add(
                    Component.text("[$tag] ").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(skillName).color(NamedTextColor.GRAY))
                )
            }
        }
    }

    private fun getRarityStars(rarity: Rarity): String {
        return when (rarity.name) {
            "BROKEN" -> "★"
            "COMMON" -> "★★"
            "UNCOMMON" -> "★★★"
            "RARE" -> "★★★★"
            "EPIC" -> "★★★★★"
            "LEGENDARY", "ETERNAL" -> "★★★★★★"
            else -> rarity.displayName
        }
    }

    fun refreshStatus(item: ItemStack?, status: Int, activeSlotIndex: Int, targetSlotIndex: Int) {
        if (item == null || !item.hasItemMeta()) return
        val meta = item.itemMeta!!
        val lore = meta.lore() ?: ArrayList()

        // 移除最后一行旧状态
        if (lore.isNotEmpty()) {
            val lastLine = PlainTextComponentSerializer.plainText().serialize(lore.last())
            if (lastLine.contains("✔") || lastLine.contains("✖")) {
                lore.removeAt(lore.size - 1)
            }
        }

        val type = meta.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
        if ("ELEMENT" == type || "MATERIAL" == type || "MENU" == type) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
            meta.lore(lore)
            item.itemMeta = meta
            return
        }

        if (status == -1) {
            meta.lore(lore)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS)
            item.itemMeta = meta
            return
        }

        val statusLine: Component = when (status) {
            1 -> Component.text("✔ 属性已激活").color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            2 -> Component.text("✖ 职业限定不匹配").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            3 -> Component.text("✖ 阵法师属性必须在副手生效").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            4 -> Component.text("✖ 你的职业无法使用此类型武器").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            5 -> Component.text("✖ 请穿戴装备以激活").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            6 -> Component.text("✖ 请放入饰品栏以激活").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            7 -> Component.text("✔ 法宝已激活").color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            8 -> {
                val slotName = if (targetSlotIndex == 40) "副手" else "栏位 ${targetSlotIndex + 1}"
                Component.text("✖ 须放入 $slotName 激活").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            }
            9 -> Component.text("✖ 未获得该物品的使用资格").color(NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            10 -> Component.text("✖ 此物品仅作为材料").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)
            11 -> Component.text("✖ 等级不足").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            else -> Component.text("✖ 请放入激活位 [${activeSlotIndex + 1}] 以激活").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
        }

        lore.add(statusLine)
        meta.lore(lore)
        item.itemMeta = meta
    }

    // 辅助扩展：简化 PDC 读取
    private fun <T : Any, Z : Any> PersistentDataContainer.getOrDefault(key: NamespacedKey, type: PersistentDataType<T, Z>, default: Z): Z {
        return this.get(key, type) ?: default
    }
}