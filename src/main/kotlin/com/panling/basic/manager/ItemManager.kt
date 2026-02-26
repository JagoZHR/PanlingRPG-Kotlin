package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ItemManager(private val plugin: JavaPlugin) : Reloadable {

    private val templates = HashMap<String, ItemTemplate>()

    init {
        // 自动注册到 ReloadManager (如果插件实例匹配)
        if (plugin is PanlingBasic) {
            try {
                plugin.reloadManager.register(this)
            } catch (ignored: Exception) {
                // 忽略未初始化时的错误
            }
        }
        loadItems()
    }

    override fun reload() {
        loadItems()
        var count = 0
        for (p in Bukkit.getOnlinePlayers()) {
            // 遍历玩家背包同步物品
            for (item in p.inventory.contents) {
                if (item != null) syncItem(item, p)
            }
            count++
        }
        plugin.logger.info("物品重载完成，已刷新 $count 名在线玩家的背包。")
    }

    val itemIds: Set<String>
        get() = templates.keys

    fun loadItems() {
        templates.clear()
        val folder = File(plugin.dataFolder, "items")
        if (!folder.exists()) {
            folder.mkdirs()
            plugin.logger.info("已创建 items 文件夹，请在其中放入配置文件。")
            return
        }

        // Kotlin 风格的文件遍历
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleConfigFile(it) }

        plugin.logger.info("Loaded ${templates.size} items.")
    }

    private fun loadSingleConfigFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (id in config.getKeys(false)) {
            try {
                val section = config.getConfigurationSection(id) ?: continue
                if (templates.containsKey(id)) {
                    plugin.logger.warning("重复的物品ID: $id (位于 ${file.name})")
                }
                val template = ItemTemplate(id, section)
                templates[id] = template
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load item: $id in ${file.name}")
                e.printStackTrace()
            }
        }
    }

    fun getTemplate(id: String): ItemTemplate? = templates[id]

    fun syncItem(item: ItemStack?, player: Player?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val pdc = item.itemMeta!!.persistentDataContainer
        val id = pdc.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: return false

        val template = templates[id] ?: return false
        template.applyTo(item, player)
        return true
    }

    fun createItem(id: String, player: Player?): ItemStack? {
        val template = templates[id] ?: return null
        val item = ItemStack(template.material)

        // 先设置 ID，防止 applyTo 里丢失（虽然 applyTo 也会覆盖 meta）
        val meta = item.itemMeta ?: return null
        meta.persistentDataContainer.set(BasicKeys.ITEM_ID, PersistentDataType.STRING, id)
        item.itemMeta = meta

        template.applyTo(item, player)
        return item
    }

    fun getItemSetId(itemId: String): String? {
        return templates[itemId]?.setId
    }

    fun isItemAllowedForClass(itemId: String, pc: PlayerClass): Boolean {
        val tmpl = templates[itemId] ?: return false
        if (!pc.isAllowedWeapon(tmpl.material)) return false

        if (tmpl.reqClass != null) {
            try {
                val req = PlayerClass.valueOf(tmpl.reqClass!!)
                if (req != PlayerClass.NONE && req != pc) return false
            } catch (ignored: Exception) {}
        }
        return true
    }

    fun getAllElementItems(): List<ItemStack> {
        val elements = templates.values
            .filter { "ELEMENT" == it.itemType }
            .mapNotNull { createItem(it.id, null) }
            .toMutableList()

        elements.sortWith { i1, i2 ->
            val v1 = i1.itemMeta?.persistentDataContainer?.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0) ?: 0
            val v2 = i2.itemMeta?.persistentDataContainer?.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0) ?: 0
            v1.compareTo(v2)
        }
        return elements
    }

    /**
     * [API] 将物品绑定给特定玩家
     * 会写入 UUID 和 Name，并刷新 Lore
     */
    fun bindItem(item: ItemStack, player: Player): ItemStack {
        val meta = item.itemMeta ?: return item
        val pdc = meta.persistentDataContainer

        // 写入 NBT
        pdc.set(NamespacedKey(plugin, "bound_owner_uuid"), PersistentDataType.STRING, player.uniqueId.toString())
        pdc.set(NamespacedKey(plugin, "bound_owner_name"), PersistentDataType.STRING, player.name)

        item.itemMeta = meta

        // 刷新 Lore 以显示绑定信息
        LoreManager.updateItemLore(item, player)

        return item
    }

    /**
     * [新增] 检查物品绑定状态
     * @return true = 属于该玩家 或 无绑定; false = 已绑定给其他人
     */
    fun checkItemOwner(item: ItemStack?, player: Player?): Boolean {
        if (item == null || !item.hasItemMeta() || player == null) return true
        val pdc = item.itemMeta!!.persistentDataContainer

        // 假设绑定的 Key 是 "bound_owner_uuid"
        // 你需要确保和 binditem 指令存入的 Key 一致
        val boundKey = NamespacedKey(plugin, "bound_owner_uuid")
        val boundUuidStr = pdc.get(boundKey, PersistentDataType.STRING)

        // 1. 如果没有绑定信息，视为验证通过
        if (boundUuidStr.isNullOrEmpty()) return true

        // 2. 如果有绑定信息，比较 UUID
        // 可选：允许 OP 无视绑定 (如果不想要此功能可删去 || player.isOp)
        return boundUuidStr == player.uniqueId.toString() || player.isOp
    }

    // === 内部类：物品模板 ===
    inner class ItemTemplate(val id: String, section: ConfigurationSection) {
        val material: Material = Material.valueOf(section.getString("material", "STONE")!!.uppercase())
        val name: String = section.getString("name", "Unknown Item")!!
        val itemType: String? = section.getString("type")

        val stats = HashMap<NamespacedKey, Double>()
        val reqClass: String? = section.getString("class")

        var fabaoSlot: Int? = if (section.contains("fabao_slot")) section.getInt("fabao_slot") - 1 else null
        var accessorySlot: Int? = if (section.contains("accessory_slot")) section.getInt("accessory_slot") - 1 else null

        val attackSpeed: Double? = if (section.contains("attack_speed")) section.getDouble("attack_speed") else null
        val rarity: Rarity? = Rarity.parse(section.get("rarity"))
        val description: List<String> = section.getStringList("description")
        val customModelData: Int? = if (section.contains("model_data")) section.getInt("model_data") else null
        val cooldown: Double? = if (section.contains("cooldown")) section.getDouble("cooldown") else null
        val setId: String? = section.getString("set")

        val elementValue: Int? = if (section.contains("element_value")) section.getInt("element_value") else null
        val skillCost: Int? = if (section.contains("skill_cost")) section.getInt("skill_cost") else null

        val descSupport: List<String> = section.getStringList("description_support")
        val descOffense: List<String> = section.getStringList("description_offense")

        val skillLore: List<String> = section.getStringList("skill_lore")
        val skillLoreSupport: List<String> = section.getStringList("skill_lore_support")
        val skillLoreOffense: List<String> = section.getStringList("skill_lore_offense")

        val itemModel: String? = section.getString("item_model")
        val equippableSection: ConfigurationSection? = section.getConfigurationSection("equippable")
        val moneyValue: Double? = if (section.contains("money_value")) section.getDouble("money_value") else null
        val level: Int? = if (section.contains("level")) section.getInt("level") else null
        val mageWeaponType: String? = section.getString("mage_weapon_type")?.uppercase()

        val fabaoLoreOffenseSection: ConfigurationSection? = section.getConfigurationSection("fabao_lore.offense")
        val fabaoLoreSupportSection: ConfigurationSection? = section.getConfigurationSection("fabao_lore.support")
        val subClass: String? = section.getString("sub_class")?.uppercase()

        // 技能相关
        val activeSkills = HashMap<SkillTrigger, String>()
        val activeCooldowns = HashMap<SkillTrigger, Double>()
        val activeCosts = HashMap<SkillTrigger, Int>()
        val activeSkillDescriptions = HashMap<SkillTrigger, String>()
        val activeSkillDescriptionsOffense = HashMap<SkillTrigger, String>()
        val activeSkillDescriptionsSupport = HashMap<SkillTrigger, String>()

        val passiveIdList: List<String> = readStringOrList(section, "passive")
        val passiveAttackList: List<String> = readStringOrList(section, "passive_attack")
        val passiveHitList: List<String> = readStringOrList(section, "passive_hit")

        init {
            // 解析基础属性 (SHORT_NAME_MAP)
            BasicKeys.SHORT_NAME_MAP.forEach { (key, nsKey) ->
                if (section.contains(key)) {
                    stats[nsKey] = section.getDouble(key)
                }
            }

            // 解析主动技能 (Complex logic)
            if (section.contains("abilities")) {
                section.getMapList("abilities").forEach { map ->
                    val sid = map["id"]?.toString()
                    if (sid != null) {
                        val trigStr = map["trigger"]?.toString() ?: "RIGHT_CLICK"
                        try {
                            val trig = SkillTrigger.valueOf(trigStr.uppercase())
                            activeSkills[trig] = sid

                            (map["cooldown"] as? Number)?.let { activeCooldowns[trig] = it.toDouble() }
                            (map["cost"] as? Number)?.let { activeCosts[trig] = it.toInt() }

                            // 描述
                            val descObj = map["description"] ?: map["lore"]
                            if (descObj != null) activeSkillDescriptions[trig] = parseDesc(descObj)

                            val offDesc = map["description_offense"]
                            if (offDesc != null) activeSkillDescriptionsOffense[trig] = parseDesc(offDesc)

                            val supDesc = map["description_support"]
                            if (supDesc != null) activeSkillDescriptionsSupport[trig] = parseDesc(supDesc)

                        } catch (ignored: Exception) {
                            activeSkills[SkillTrigger.RIGHT_CLICK] = sid
                        }
                    }
                }
            } else if (section.contains("ability")) {
                // 旧版兼容
                val sid = section.getString("ability")!!
                val trigStr = section.getString("trigger", "RIGHT_CLICK")!!
                try {
                    activeSkills[SkillTrigger.valueOf(trigStr.uppercase())] = sid
                } catch (e: Exception) {
                    activeSkills[SkillTrigger.RIGHT_CLICK] = sid
                }
            }
        }

        private fun parseDesc(obj: Any): String {
            if (obj is List<*>) {
                return obj.joinToString("|||") { it.toString() }
            }
            return obj.toString()
        }

        private fun readStringOrList(sec: ConfigurationSection, path: String): List<String> {
            if (sec.isList(path)) return sec.getStringList(path)
            val str = sec.getString(path)
            return if (str != null) listOf(str) else emptyList()
        }

        fun applyTo(item: ItemStack, player: Player?) {
            item.type = this.material
            val meta = item.itemMeta ?: return

            if (this.customModelData != null) meta.setCustomModelData(this.customModelData)
            meta.displayName(Component.text(name).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            meta.isUnbreakable = true
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS)

            // 清理原生属性
            meta.removeAttributeModifier(Attribute.ARMOR)
            meta.removeAttributeModifier(Attribute.ARMOR_TOUGHNESS)
            meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE)
            meta.removeAttributeModifier(Attribute.ATTACK_SPEED)

            // 覆盖原生属性显示 (Hack)
            if (isEquipment(this.material)) {
                val key = NamespacedKey(plugin, "pl_vanilla_override")
                val dummy = AttributeModifier(key, 0.0, AttributeModifier.Operation.ADD_NUMBER, getEquipmentSlotGroup(this.material))
                // 使用 GENERIC_ 前缀适配新版本
                meta.addAttributeModifier(Attribute.ARMOR, dummy)
                meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, dummy)
                meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, dummy)
            }

            val pdc = meta.persistentDataContainer

            // 1. 写入主动技能
            BasicKeys.TRIGGER_KEYS.values.forEach { pdc.remove(it) }
            activeSkills.forEach { (trigger, skillId) ->
                val key = BasicKeys.TRIGGER_KEYS[trigger]
                if (key != null) pdc.set(key, PersistentDataType.STRING, skillId)
            }

            if (activeSkills.isNotEmpty()) {
                val first = activeSkills.entries.first()
                pdc.set(BasicKeys.FEATURE_ABILITY_ID, PersistentDataType.STRING, first.value)
                pdc.set(BasicKeys.FEATURE_TRIGGER, PersistentDataType.STRING, first.key.name)
            } else {
                pdc.remove(BasicKeys.FEATURE_ABILITY_ID)
                pdc.remove(BasicKeys.FEATURE_TRIGGER)
            }

            // [NEW] 2. 写入独立冷却和消耗
            BasicKeys.TRIGGER_COOLDOWN_KEYS.values.forEach { pdc.remove(it) }
            activeCooldowns.forEach { (trigger, cd) ->
                val key = BasicKeys.TRIGGER_COOLDOWN_KEYS[trigger]
                if (key != null) pdc.set(key, PersistentDataType.LONG, (cd * 1000).toLong())
            }

            BasicKeys.TRIGGER_COST_KEYS.values.forEach { pdc.remove(it) }
            activeCosts.forEach { (trigger, cost) ->
                val key = BasicKeys.TRIGGER_COST_KEYS[trigger]
                if (key != null) pdc.set(key, PersistentDataType.INTEGER, cost)
            }

            // [NEW] 3. 写入独立描述
            writeMapToPdc(pdc, BasicKeys.TRIGGER_LORE_KEYS, activeSkillDescriptions)
            writeMapToPdc(pdc, BasicKeys.TRIGGER_LORE_OFFENSE_KEYS, activeSkillDescriptionsOffense)
            writeMapToPdc(pdc, BasicKeys.TRIGGER_LORE_SUPPORT_KEYS, activeSkillDescriptionsSupport)

            pdc.setOrRemove(BasicKeys.ITEM_SUB_CLASS, PersistentDataType.STRING, this.subClass)
            pdc.setOrRemove(BasicKeys.MAGE_WEAPON_TYPE, PersistentDataType.STRING, this.mageWeaponType)

            // 法宝 Lore 展开
            if (this.fabaoLoreOffenseSection != null) {
                val mapStr = flattenLoreMap(this.fabaoLoreOffenseSection)
                pdc.setOrRemove(BasicKeys.FABAO_LORE_OFFENSE, PersistentDataType.STRING, mapStr)
            }
            if (this.fabaoLoreSupportSection != null) {
                val mapStr = flattenLoreMap(this.fabaoLoreSupportSection)
                pdc.setOrRemove(BasicKeys.FABAO_LORE_SUPPORT, PersistentDataType.STRING, mapStr)
            }

            writeListToPdc(pdc, BasicKeys.FEATURE_PASSIVE_ID, passiveIdList)
            writeListToPdc(pdc, BasicKeys.PASSIVE_ON_ATTACK, passiveAttackList)
            writeListToPdc(pdc, BasicKeys.PASSIVE_ON_HIT, passiveHitList)

            pdc.setOrRemove(BasicKeys.ITEM_SKILL_LORE, PersistentDataType.STRING, joinList(skillLore))
            pdc.setOrRemove(BasicKeys.ITEM_SKILL_LORE_SUPPORT, PersistentDataType.STRING, joinList(skillLoreSupport))
            pdc.setOrRemove(BasicKeys.ITEM_SKILL_LORE_OFFENSE, PersistentDataType.STRING, joinList(skillLoreOffense))

            // 写入属性
            BasicKeys.ALL_STATS.forEach { pdc.remove(it) }
            this.stats.forEach { (key, `val`) ->
                pdc.set(key, PersistentDataType.DOUBLE, `val`)
            }

            pdc.setOrRemove(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING, reqClass)
            pdc.setOrRemove(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING, itemType)
            pdc.setOrRemove(BasicKeys.ITEM_SET_ID, PersistentDataType.STRING, setId)

            if ((this.moneyValue ?: 0.0) > 0) pdc.set(BasicKeys.ITEM_MONEY_VALUE, PersistentDataType.DOUBLE, this.moneyValue!!)
            if ((this.level ?: 0) > 0) pdc.set(BasicKeys.FEATURE_REQ_LEVEL, PersistentDataType.INTEGER, this.level!!) else pdc.remove(BasicKeys.FEATURE_REQ_LEVEL)
            if ((this.elementValue ?: 0) > 0) pdc.set(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, this.elementValue!!)
            if ((this.skillCost ?: 0) > 0) pdc.set(BasicKeys.ITEM_SKILL_COST, PersistentDataType.INTEGER, this.skillCost!!)

            pdc.setOrRemove(BasicKeys.ITEM_DESC_SUPPORT, PersistentDataType.STRING, joinList(descSupport))
            pdc.setOrRemove(BasicKeys.ITEM_DESC_OFFENSE, PersistentDataType.STRING, joinList(descOffense))

            if (this.fabaoSlot != null) pdc.set(BasicKeys.FEATURE_FABAO_SLOT, PersistentDataType.INTEGER, this.fabaoSlot!!)
            if (this.accessorySlot != null) pdc.set(NamespacedKey(plugin, "accessory_slot"), PersistentDataType.INTEGER, this.accessorySlot!!)

            if (this.cooldown != null) pdc.set(BasicKeys.FEATURE_COOLDOWN, PersistentDataType.LONG, (this.cooldown * 1000).toLong())
            else pdc.remove(BasicKeys.FEATURE_COOLDOWN)

            if (this.rarity != null) {
                pdc.set(BasicKeys.ITEM_RARITY, PersistentDataType.STRING, this.rarity.name)
                pdc.set(BasicKeys.ITEM_RARITY_WEIGHT, PersistentDataType.INTEGER, this.rarity.weight)
            } else {
                pdc.remove(BasicKeys.ITEM_RARITY)
                pdc.remove(BasicKeys.ITEM_RARITY_WEIGHT)
            }

            pdc.setOrRemove(BasicKeys.ITEM_DESC, PersistentDataType.STRING, joinList(description))

            // 攻击速度修正
            if (this.attackSpeed != null) {
                meta.removeAttributeModifier(Attribute.ATTACK_SPEED)
                val modifier = AttributeModifier(
                    NamespacedKey(plugin, "base_attack_speed"),
                    this.attackSpeed - 4.0,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.MAINHAND
                )
                meta.addAttributeModifier(Attribute.ATTACK_SPEED, modifier)
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }

            // 物品模型 (1.21+)
            if (this.itemModel != null) {
                try {
                    val modelKey = parseNamespacedKey(this.itemModel)
                    if (modelKey != null) {
                        meta.setItemModel(modelKey)
                        pdc.set(BasicKeys.ITEM_MODEL, PersistentDataType.STRING, this.itemModel)
                    }
                } catch (ignored: Throwable) {}
            }

            // Equippable Component (1.21+)
            if (this.equippableSection != null) {
                try {
                    val equippable = meta.equippable
                    val slotStr = this.equippableSection.getString("slot", "HEAD")
                    try {
                        equippable.slot = EquipmentSlot.valueOf(slotStr!!.uppercase())
                    } catch (ignored: Exception) {}

                    val modelStr = this.equippableSection.getString("model")
                    if (modelStr != null) {
                        val modelKey = parseNamespacedKey(modelStr)
                        if (modelKey != null) equippable.model = modelKey
                    }

                    val cameraOverlay = this.equippableSection.getString("camera_overlay")
                    if (cameraOverlay != null) {
                        val overlayKey = parseNamespacedKey(cameraOverlay)
                        if (overlayKey != null) equippable.cameraOverlay = overlayKey
                    }

                    if (this.equippableSection.contains("allowed_entities")) {
                        val allowedList = this.equippableSection.getStringList("allowed_entities")
                        if (allowedList.isNotEmpty()) {
                            val types = HashSet<EntityType>()
                            for (typeStr in allowedList) {
                                try {
                                    types.add(EntityType.valueOf(typeStr.uppercase()))
                                } catch (ignored: Exception) {}
                            }
                            if (types.isNotEmpty()) equippable.allowedEntities = types
                        }
                    }
                    meta.setEquippable(equippable)
                } catch (ignored: Throwable) {}
            }

            item.itemMeta = meta
            LoreManager.updateItemLore(item, player)
        }

        // === 辅助方法 ===

        private fun parseNamespacedKey(keyStr: String): NamespacedKey? {
            return try {
                NamespacedKey.fromString(keyStr)
            } catch (e: Exception) {
                null
            }
        }

        private fun joinList(list: List<String>?): String? {
            if (list.isNullOrEmpty()) return null
            return list.joinToString("|||")
        }

        private fun writeListToPdc(pdc: PersistentDataContainer, key: NamespacedKey, list: List<String>) {
            if (list.isNotEmpty()) {
                pdc.set(key, PersistentDataType.STRING, list.joinToString(","))
            } else {
                pdc.remove(key)
            }
        }

        private fun writeMapToPdc(pdc: PersistentDataContainer, keys: Map<SkillTrigger, NamespacedKey>, values: Map<SkillTrigger, String>) {
            keys.values.forEach { pdc.remove(it) }
            values.forEach { (trigger, desc) ->
                val key = keys[trigger]
                if (key != null) pdc.set(key, PersistentDataType.STRING, desc)
            }
        }

        private fun <T : Any, Z : Any> PersistentDataContainer.setOrRemove(key: NamespacedKey, type: PersistentDataType<T, Z>, value: Z?) {
            if (value != null && (value !is String || value.isNotEmpty())) {
                this.set(key, type, value)
            } else {
                this.remove(key)
            }
        }

        // 简化版 PDC getOrDefault 扩展
        private fun <T : Any, Z : Any> PersistentDataContainer.getOrDefault(key: NamespacedKey, type: PersistentDataType<T, Z>, default: Z): Z {
            return this.get(key, type) ?: default
        }

        private fun isEquipment(type: Material): Boolean {
            val name = type.name
            return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") ||
                    name.endsWith("_BOOTS") || name.endsWith("_SWORD") || name.endsWith("_AXE") ||
                    name.endsWith("_HOE") || name.endsWith("_SHOVEL") || name.endsWith("_PICKAXE")
        }

        private fun getEquipmentSlotGroup(type: Material): EquipmentSlotGroup {
            val name = type.name
            return when {
                name.endsWith("_HELMET") -> EquipmentSlotGroup.HEAD
                name.endsWith("_CHESTPLATE") -> EquipmentSlotGroup.CHEST
                name.endsWith("_LEGGINGS") -> EquipmentSlotGroup.LEGS
                name.endsWith("_BOOTS") -> EquipmentSlotGroup.FEET
                else -> EquipmentSlotGroup.MAINHAND
            }
        }

        private fun flattenLoreMap(section: ConfigurationSection): String? {
            val builder = StringBuilder()
            for (rarityKey in section.getKeys(false)) {
                val typeSection = section.getConfigurationSection(rarityKey) ?: continue
                for (typeKey in typeSection.getKeys(false)) {
                    val desc = typeSection.getString(typeKey)?.replace("§", "&")
                    builder.append(rarityKey).append(":").append(typeKey).append(":").append(desc).append(";")
                }
            }
            return if (builder.isNotEmpty()) builder.toString() else null
        }
    }
}