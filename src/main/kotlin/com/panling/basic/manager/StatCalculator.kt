package com.panling.basic.manager

import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.api.SkillTrigger
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.HashMap
import kotlin.math.abs

class StatCalculator(
    private val dataManager: PlayerDataManager,
    private val accessoryManager: AccessoryManager,
    private val itemManager: ItemManager,
    private val qualificationManager: QualificationManager,
    private val setManager: SetManager
) {

    // 可选依赖，通过 setter 注入
    var buffManager: BuffManager? = null
    var subClassManager: SubClassManager? = null

    companion object {
        // [NEW] 定义原版基准属性
        private const val VANILLA_BASE_HEALTH = 20.0
        private const val VANILLA_BASE_SPEED = 0.1 // 0.1 是一切速度的基石

        // 状态码常量
        const val STATUS_ACTIVE = 1
        const val STATUS_WRONG_CLASS = 2
        const val STATUS_MAGE_OFFHAND = 3
        const val STATUS_FORBIDDEN_TYPE = 4
        const val STATUS_NEED_WEAR = 5
        const val STATUS_NEED_ACCESSORY = 6
        const val STATUS_FABAO_ACTIVE = 7
        const val STATUS_FABAO_SLOT = 8
        const val STATUS_NO_QUALIFICATION = 9
        const val STATUS_MATERIAL_ONLY = 10
        const val STATUS_LEVEL_TOO_LOW = 11
        // [新增] 状态码：非物品拥有者
        const val STATUS_NOT_OWNER = 12
        const val STATUS_INACTIVE_SLOT = 0
    }

    /**
     * 获取玩家的最终属性总值 (Total)
     * [重构] 现在返回的是包含原版基数、装备加成、Buff倍率的最终数值
     */
    fun getPlayerTotalStat(player: Player, key: NamespacedKey): Double {
        if (!dataManager.areStatsCalculated(player)) {
            recalculateAllStats(player)
        }

        // 1. 获取装备/被动提供的基础加成 (Bonus)
        var value = dataManager.getCachedStat(player, key) ?: 0.0

        // 2. [核心修复] 注入原版基准值 (Injected Base)
        // 将 "加成值" 转换为 "当前总值"，以便 Buff 能正确乘算
        if (key == BasicKeys.ATTR_MAX_HEALTH) {
            // 生命值：装备+100，原版20 -> 总基数 120
            value += VANILLA_BASE_HEALTH
        } else if (key == BasicKeys.ATTR_MOVE_SPEED) {
            // 移速：装备+0.5 (即+50%)，原版0.1 -> 总基数 0.1 * (1 + 0.5) = 0.15
            // 注意：这里我们将百分比逻辑转为了扁平数值逻辑，以便后续统一计算
            value = VANILLA_BASE_SPEED * (1.0 + value)
        }
        // 其他属性 (如攻击力) 默认基数为 0 (完全由装备提供)

        // 3. 应用动态修饰 (流派)
        subClassManager?.let { manager ->
            val heldTime = dataManager.getSlotHoldDuration(player)
            val strategy = manager.getStrategy(player)

            // 策略可能为空，取决于 SubClassManager 的实现
            // 如果 getStrategy 保证不返回空，则不需要 ?.
            if (strategy != null) {
                value = when (key) {
                    BasicKeys.ATTR_DEFENSE -> strategy.modifyDefense(player, value, heldTime)
                    BasicKeys.ATTR_LIFE_STEAL -> strategy.modifyLifeSteal(player, value, heldTime)
                    BasicKeys.ATTR_PHYSICAL_DAMAGE -> strategy.modifyAttackDamage(player, value, heldTime)
                    // [NEW] 新增生命值和移速的修饰
                    BasicKeys.ATTR_MAX_HEALTH -> strategy.modifyMaxHealth(player, value, heldTime)
                    BasicKeys.ATTR_MOVE_SPEED -> strategy.modifyMovementSpeed(player, value, heldTime)
                    else -> value
                }
            }
        }

        // 4. 应用 Buff (乘算/加算)
        // 此时 value 已经是总值了，Buff x1.2 会正确放大原版属性
        buffManager?.let { manager ->
            value = manager.applyBuffsToValue(player, key, value)
        }

        return value
    }

    private fun recalculateAllStats(player: Player) {
        // [核心] 标记计算已完成，防止下次调用重复计算
        dataManager.setStatsCalculated(player)

        // 1. 获取有效物品 (利用 activeItemsCache)
        val activeItems = getAllActiveItems(player)

        val baseStats = HashMap<NamespacedKey, Double>()
        val setCounts = HashMap<String, Int>()

        // 2. 遍历物品累计属性 & 被动
        for (item in activeItems) {
            val meta = item.itemMeta ?: continue
            val pdc = meta.persistentDataContainer

            // 累计基础属性
            for (key in BasicKeys.ALL_STATS) {
                val valInPdc = pdc.get(key, PersistentDataType.DOUBLE)
                if (valInPdc != null) {
                    baseStats.merge(key, valInPdc) { a, b -> a + b }
                }
            }

            // 收集被动技能
            collectPassives(player, pdc, BasicKeys.PASSIVE_ON_ATTACK, PlayerDataManager.PassiveTrigger.ATTACK, item)
            collectPassives(player, pdc, BasicKeys.PASSIVE_ON_HIT, PlayerDataManager.PassiveTrigger.HIT, item)
            collectPassives(player, pdc, BasicKeys.FEATURE_PASSIVE_ID, PlayerDataManager.PassiveTrigger.CONSTANT, item)

            // =========================================================
            // [修复] 增加对 SkillTrigger.PASSIVE (触发器系统) 的读取
            // =========================================================
            val passiveKey = BasicKeys.TRIGGER_KEYS[SkillTrigger.PASSIVE]
            if (passiveKey != null) {
                collectPassives(
                    player,
                    pdc,
                    passiveKey,
                    PlayerDataManager.PassiveTrigger.CONSTANT,
                    item
                )
            }

            // 统计套装
            val setId = pdc.get(BasicKeys.ITEM_SET_ID, PersistentDataType.STRING)
            if (setId != null) {
                setCounts.merge(setId, 1, Integer::sum)
            }
        }

        // 3. 套装加成
        setCounts.forEach { (setId, count) ->
            val setDef = setManager.getSet(setId)
            if (setDef != null) {
                // 套装属性加成
                setDef.getBonuses(count).forEach { (key, `val`) ->
                    baseStats.merge(key, `val`) { a, b -> a + b }
                }
                // 缓存套装被动
                cacheSetPassives(player, setDef, count)
            }
        }

        // 4. 种族加成
        val race = dataManager.getPlayerRace(player)
        if (race != PlayerRace.NONE) {
            val raceBonus = race.calculateBonus(player.level)
            // 假设 PlayerRace.bonusStats 是 List<NamespacedKey>
            for (key in race.bonusStats) {
                baseStats.merge(key, raceBonus) { a, b -> a + b }
            }
        }

        // 5. 应用百分比加成并写入缓存
        for (baseKey in BasicKeys.ALL_STATS) {
            var finalValue = baseStats.getOrDefault(baseKey, 0.0)

            val pctKey = BasicKeys.BASE_TO_PERCENT_MAP[baseKey]
            if (pctKey != null) {
                val pctVal = baseStats.getOrDefault(pctKey, 0.0)
                if (pctVal != 0.0) {
                    finalValue *= (1.0 + pctVal)
                }
            }
            dataManager.cacheStat(player, baseKey, finalValue)
        }
    }

    // === [核心优化] 活跃物品获取 ===
    fun getAllActiveItems(player: Player): List<ItemStack> {
        // 1. 尝试读缓存
        val cached = dataManager.getCachedActiveItems(player)
        if (cached != null) return cached

        // 2. 缓存未命中，执行完整扫描 (耗时操作)
        val items = ArrayList<ItemStack>()
        val pc = dataManager.getPlayerClass(player)
        val activeSlot = dataManager.getActiveSlot(player)

        // A. 遍历背包
        val contents = player.inventory.contents
        // 使用索引遍历，因为需要 index 判读槽位
        for (i in contents.indices) {
            val item = contents[i]
            // 注意：Kotlin 中 contents 可能包含 null，必须检查
            if (item == null || !item.hasItemMeta()) continue

            // 状态校验 (包含 NBT 读取)
            val status = getValidationStatus(player, item, i, activeSlot, pc)

            if (status == STATUS_ACTIVE || status == STATUS_FABAO_ACTIVE) {
                val type = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
                if ("ELEMENT" == type) continue
                items.add(item)
            }
        }

        // B. 遍历饰品 (使用 AccessoryManager 的内部缓存)
        val accessories = accessoryManager.loadAccessories(player)
        for (acc in accessories) {
            if (acc != null) {
                if (qualificationManager.checkQualification(player, acc)) {
                    items.add(acc)
                }
            }
        }

        // 3. 写入缓存
        dataManager.cacheActiveItems(player, items)
        return items
    }

    private fun cacheSetPassives(player: Player, setDef: SetManager.SetDefinition, count: Int) {
        // 假设 SetManager 定义了这些内部类和枚举
        for (sp in setDef.getActivePassives(count, SetManager.PassiveType.ATTACK)) {
            dataManager.addPassiveToCache(player, PlayerDataManager.PassiveTrigger.ATTACK, sp.id, null, sp.cooldownMillis)
        }
        for (sp in setDef.getActivePassives(count, SetManager.PassiveType.HIT)) {
            dataManager.addPassiveToCache(player, PlayerDataManager.PassiveTrigger.HIT, sp.id, null, sp.cooldownMillis)
        }
        for (sp in setDef.getActivePassives(count, SetManager.PassiveType.CONSTANT)) {
            dataManager.addPassiveToCache(player, PlayerDataManager.PassiveTrigger.CONSTANT, sp.id, null, sp.cooldownMillis)
        }
    }

    private fun collectPassives(
        player: Player,
        pdc: PersistentDataContainer,
        key: NamespacedKey,
        type: PlayerDataManager.PassiveTrigger,
        item: ItemStack
    ) {
        val raw = pdc.get(key, PersistentDataType.STRING)
        if (!raw.isNullOrEmpty()) {
            for (pid in raw.split(",")) {
                if (pid.isNotEmpty()) {
                    dataManager.addPassiveToCache(player, type, pid, item, 0)
                }
            }
        }
    }

    fun getValidationStatus(
        player: Player,
        item: ItemStack?,
        slotIndex: Int,
        activeSlot: Int,
        pc: PlayerClass
    ): Int {
        if (item == null || !item.hasItemMeta()) return -1

        // [新增] 1. 优先检查绑定权
        // 如果不是主人，直接返回 STATUS_NOT_OWNER
        // 这会导致 getAllActiveItems 忽略此物品（不加属性），且技能触发检查也会失败
        if (!itemManager.checkItemOwner(item, player)) {
            return STATUS_NOT_OWNER
        }

        if (!qualificationManager.checkQualification(player, item)) return STATUS_NO_QUALIFICATION

        val meta = item.itemMeta ?: return -1
        val pdc = meta.persistentDataContainer

        val reqLevel = pdc.getOrDefault(BasicKeys.FEATURE_REQ_LEVEL, PersistentDataType.INTEGER, 0)
        if (reqLevel > 0 && player.level < reqLevel) return STATUS_LEVEL_TOO_LOW

        val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
        val reqStr = pdc.get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)

        if (reqStr != null) {
            try {
                if (PlayerClass.valueOf(reqStr) != pc) return STATUS_WRONG_CLASS
            } catch (ignored: Exception) {}
        }

        if ("ACCESSORY" == type) return STATUS_NEED_ACCESSORY

        if ("FABAO" == type) {
            val targetSlot = getFabaoTargetSlot(player, item)
            return if (slotIndex == targetSlot) STATUS_FABAO_ACTIVE else STATUS_FABAO_SLOT
        }

        if (!pc.isAllowedWeapon(item.type)) return STATUS_FORBIDDEN_TYPE

        if ("ELEMENT" == type) {
            if (pc != PlayerClass.MAGE) return STATUS_WRONG_CLASS
        }

        val isOffHandSlot = (slotIndex == 40)

        if (PlayerClass.isArmor(item.type)) {
            // 36=Boots, 37=Leggings, 38=Chestplate, 39=Helmet
            return if (slotIndex in 36..39) STATUS_ACTIVE else STATUS_NEED_WEAR
        }

        if (pc == PlayerClass.MAGE) {
            if ("ELEMENT" == type) {
                return if (slotIndex == activeSlot) STATUS_ACTIVE else STATUS_INACTIVE_SLOT
            }
            if ("WEAPON" == type) {
                return if (isOffHandSlot) STATUS_ACTIVE else STATUS_MAGE_OFFHAND
            }
        }

        if (slotIndex == activeSlot) return STATUS_ACTIVE
        // 盾牌逻辑
        if (item.type == org.bukkit.Material.SHIELD && isOffHandSlot) return STATUS_ACTIVE

        return STATUS_INACTIVE_SLOT
    }

    fun getFabaoTargetSlot(player: Player, item: ItemStack): Int {
        if (!isFabao(item)) return -1

        val pdc = item.itemMeta?.persistentDataContainer
        // 注意：getOrDefault 在 pdc 上没有直接实现(除非有扩展)，这里用 Elvis 操作符替代
        val configSlot = pdc?.get(BasicKeys.FEATURE_FABAO_SLOT, PersistentDataType.INTEGER) ?: 0

        if (dataManager.getPlayerClass(player) == PlayerClass.MAGE) {
            return if (configSlot == 40) 40 else (if (configSlot == -1) 40 else configSlot)
        }

        val activeSlot = dataManager.getActiveSlot(player)
        return if (configSlot == activeSlot) {
            if (activeSlot == 0) 40 else 0
        } else {
            configSlot
        }
    }

    fun isFabao(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val type = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
        return "FABAO" == type
    }

    fun getSetPieceCount(player: Player, setId: String): Int {
        val items = getAllActiveItems(player) // 这里也会利用缓存
        var count = 0
        for (item in items) {
            val id = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_SET_ID, PersistentDataType.STRING)
            if (setId == id) count++
        }
        return count
    }

    fun isWearingItem(player: Player, targetItemId: String): Boolean {
        val items = getAllActiveItems(player) // 利用缓存
        for (item in items) {
            val id = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
            if (targetItemId == id) return true
        }
        return false
    }

    // =========================================================
    // [重构] 同步属性到原版
    // 现在 getPlayerTotalStat 返回的就是最终值，直接 Set 即可
    // =========================================================
    fun syncPlayerAttributes(player: Player) {
        // 1. 生命值
        var finalHp = getPlayerTotalStat(player, BasicKeys.ATTR_MAX_HEALTH)
        // 保底 1.0 防止改死
        finalHp = finalHp.coerceAtLeast(1.0)

        // Attribute.GENERIC_MAX_HEALTH 是现代 API 写法，Attribute.MAX_HEALTH 是旧版/别名
        // 视你的 API 版本而定，这里保持原样 Attribute.MAX_HEALTH
        val attrHp = player.getAttribute(Attribute.MAX_HEALTH)
        if (attrHp != null && abs(attrHp.baseValue - finalHp) > 0.01) {
            attrHp.baseValue = finalHp
            // 如果当前血量超过上限，修剪一下；如果升级了血量，通常不需要自动回血
            if (player.health > finalHp) player.health = finalHp
        }

        // 2. 移动速度
        var finalSpeed = getPlayerTotalStat(player, BasicKeys.ATTR_MOVE_SPEED)
        // 安全范围截断 (0.0 - 1.0)
        finalSpeed = finalSpeed.coerceIn(0.0, 1.0)

        val attrSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED)
        if (attrSpeed != null && abs(attrSpeed.baseValue - finalSpeed) > 0.0001) {
            attrSpeed.baseValue = finalSpeed
        }

        // 3. 击退抗性
        var kb = getPlayerTotalStat(player, BasicKeys.ATTR_KB_RESIST)
        kb = kb.coerceIn(0.0, 1.0)

        val attrKb = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)
        if (attrKb != null && abs(attrKb.baseValue - kb) > 0.001) {
            attrKb.baseValue = kb
        }
    }
}