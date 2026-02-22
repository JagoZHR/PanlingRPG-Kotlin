package com.panling.basic.forge

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.ForgeCategory
import com.panling.basic.api.ForgeRecipe
import com.panling.basic.api.Reloadable
import com.panling.basic.manager.ItemManager
import com.panling.basic.manager.PlayerDataManager
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.HashMap

class ForgeManager(private val plugin: PanlingBasic) : Reloadable {

    private val recipes = HashMap<String, ForgeRecipe>()

    init {
        // [NEW] 注册到重载管理器
        // 假设 ReloadManager 有 register 方法
        plugin.reloadManager.register(this)

        loadRecipesFromConfig()
    }

    // 将 getPlayerDataManager() 简化为属性
    val playerDataManager: PlayerDataManager
        get() = plugin.playerDataManager

    val itemManager: ItemManager
        get() = plugin.itemManager

    // [API] 玩家解锁配方
    fun unlockRecipe(player: Player, recipeId: String) {
        val recipe = getRecipe(recipeId) ?: return

        playerDataManager.addUnlockedRecipe(player, recipeId)
        player.sendMessage("§e[系统] 你领悟了新的锻造配方: ${recipe.displayName}")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    // [核心修改] 替换为 NIO 多文件加载逻辑
    private fun loadRecipesFromConfig() {
        recipes.clear()

        // 1. 优先检查 forge 文件夹
        val folder = File(plugin.dataFolder, "forge")
        if (!folder.exists()) {
            folder.mkdirs()

            // 2. 兼容性检查
            val legacyFile = File(plugin.dataFolder, "recipes.yml")
            if (legacyFile.exists()) {
                plugin.logger.info("Detected legacy recipes.yml, loading...")
                loadSingleFile(legacyFile)
            } else {
                // 如果是全新安装，释放一个默认文件
                try {
                    plugin.saveResource("forge/example_sword.yml", false)
                } catch (ignored: Exception) {}
            }
        }

        // 3. 递归遍历 forge 文件夹下的所有 .yml 文件
        // Kotlin 的 walk 扩展函数非常强大
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleFile(it) }

        plugin.logger.info("Loaded ${recipes.size} forge recipes.")
    }

    private fun loadSingleFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        config.getKeys(false).forEach { key ->
            try {
                // 这里的 key 是配方ID
                loadOneRecipe(key, config.getConfigurationSection(key)!!)
            } catch (e: Exception) {
                plugin.logger.warning("Error loading forge recipe '$key' in ${file.name}")
                e.printStackTrace()
            }
        }
    }

    private fun loadOneRecipe(id: String, section: ConfigurationSection) {
        val targetId = section.getString("target")
        if (targetId == null) {
            plugin.logger.warning("Recipe $id missing target item!")
            return
        }

        val name = section.getString("name", "Unknown")!!
        val catStr = section.getString("category", "OTHER")!!

        val category = try {
            ForgeCategory.valueOf(catStr.uppercase())
        } catch (e: IllegalArgumentException) {
            ForgeCategory.OTHER
        }

        val mats = HashMap<String, Int>()
        val matSection = section.getConfigurationSection("materials")
        matSection?.getKeys(false)?.forEach { matId ->
            mats[matId] = matSection.getInt(matId)
        }

        val cost = section.getDouble("cost", 0.0)
        val time = section.getInt("time", 0)
        val requiresUnlock = section.getBoolean("requires_unlock", false)

        val recipe = ForgeRecipe(id, targetId, category, name, mats, cost, time, requiresUnlock)
        recipes[id] = recipe
    }

    override fun reload() {
        loadRecipesFromConfig()
        plugin.logger.info("锻造配方已重载。")
    }

    fun getRecipe(id: String): ForgeRecipe? {
        return recipes[id]
    }

    fun getRecipesByCategory(category: ForgeCategory): List<ForgeRecipe> {
        return recipes.values.filter { it.category == category }
    }

    fun attemptForge(player: Player, recipe: ForgeRecipe) {
        // [NEW] 再次校验配方权限 (防止作弊包)
        if (recipe.requiresUnlock && !playerDataManager.hasUnlockedRecipe(player, recipe.id)) {
            player.sendMessage("§c[锻造] 你尚未领悟此配方！")
            return
        }

        // 1. 校验材料
        for ((matId, amount) in recipe.materials) {
            if (!hasEnoughMaterial(player, matId, amount)) {
                player.sendMessage("§c[锻造] 材料不足！")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }
        }

        // 检查并扣除金币
        if (recipe.cost > 0) {
            if (!playerDataManager.takeMoney(player, recipe.cost)) {
                player.sendMessage("§c[锻造] 余额不足！需要: ${recipe.cost}")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }
        }

        // 2. 扣除材料
        for ((matId, amount) in recipe.materials) {
            consumeMaterial(player, matId, amount)
        }

        // 3. 产出物品
        val result = itemManager.createItem(recipe.targetItemId, player)
        if (result != null) {
            // =========================================================
            // [新增] 锻造产物自动绑定
            // =========================================================
            // 为了防止把锻造出来的消耗品(如药水/材料)也绑定导致无法堆叠，
            // 建议加一个简单的类型判断。
            // 假设 ItemManager 会把类型写入 PDC (BasicKeys.ITEM_TYPE_TAG)
            val meta = result.itemMeta
            val type = meta?.persistentDataContainer?.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING) ?: "UNKNOWN"

            // 定义哪些类型的物品需要绑定 (根据你的需求调整)
            val needBind = when (type) {
                "WEAPON", "ARMOR", "ACCESSORY", "FABAO" -> true
                else -> false // 材料(MATERIAL)、消耗品(CONSUMABLE) 等不绑定
            }

            if (needBind) {
                // 调用 ItemManager 的绑定方法
                itemManager.bindItem(result, player)

                // 可选：给个提示
                player.sendMessage("§8[提示] 锻造的装备已与你的灵魂绑定。")
            }
            // =========================================================
            player.inventory.addItem(result)
            player.sendMessage("§a[锻造] 打造成功: ${recipe.displayName}")
            player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
            player.closeInventory()

            // =========================================================
            // [核心逻辑] 锻造成功 -> 获得使用资格
            // =========================================================
            // 假设 QualificationManager 存在
            if (!plugin.qualificationManager.hasUnlocked(player, recipe.targetItemId)) {
                plugin.qualificationManager.unlockItem(player, recipe.targetItemId)
            }
            // =========================================================
        } else {
            player.sendMessage("§c[系统] 产物配置丢失: ${recipe.targetItemId}")
        }

        if (recipe.cost > 0) {
            player.sendMessage("§e[锻造] 消耗铜钱: ${recipe.cost}")
        }
    }

    private fun hasEnoughMaterial(player: Player, targetId: String, amountNeeded: Int): Boolean {
        var count = 0
        // 使用 filterNotNull 过滤空物品，filter 过滤有效材料
        player.inventory.contents
            .filterNotNull()
            .filter { isValidMaterial(player, it, targetId) }
            .forEach { count += it.amount }
        return count >= amountNeeded
    }

    private fun consumeMaterial(player: Player, targetId: String, amountToConsume: Int) {
        var remaining = amountToConsume

        // 传统的 for 循环在这里可能更直观，或者使用迭代器
        for (item in player.inventory.contents) {
            if (item == null) continue
            if (remaining <= 0) break

            if (isValidMaterial(player, item, targetId)) {
                val stackAmount = item.amount
                if (stackAmount > remaining) {
                    item.amount = stackAmount - remaining
                    remaining = 0
                } else {
                    item.amount = 0
                    remaining -= stackAmount
                }
            }
        }
    }

    // [修改] 增加 player 参数
    private fun isValidMaterial(player: Player, item: ItemStack?, targetId: String): Boolean {
        if (item == null || !item.hasItemMeta()) return false

        // 1. 检查 ID 是否匹配 (原有逻辑)
        val id = item.itemMeta?.persistentDataContainer?.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
        if (targetId != id) return false

        // 2. [新增] 检查物品绑定状态
        // 只有当物品归属权通过 (是该玩家的 或 未绑定) 才视为有效材料
        return itemManager.checkItemOwner(item, player)
    }
    /**
     * [API] 检查玩家是否已解锁某配方
     * 实际逻辑委托给 PlayerDataManager
     */
    fun hasUnlockedRecipe(player: Player, recipeId: String): Boolean {
        // 直接调用 PlayerDataManager 中已有的方法
        return playerDataManager.hasUnlockedRecipe(player, recipeId)
    }

    // 在 ForgeManager 类中添加：
    fun getAllRecipes(): Collection<ForgeRecipe> {
        return recipes.values
    }
}