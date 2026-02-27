package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MerchantRecipe
import java.io.File
import java.util.HashMap

class BarterManager(private val plugin: PanlingBasic) : Reloadable {

    // 存储 交易方案ID -> 虚拟商人实例
    private val merchants = HashMap<String, Merchant>()

    init {
        plugin.reloadManager?.register(this)
        loadBarters()
    }

    override fun reload() {
        loadBarters()
        plugin.logger.info("以物换物配置已重载。")
    }

    private fun loadBarters() {
        merchants.clear()
        val folder = File(plugin.dataFolder, "barter")
        if (!folder.exists()) {
            folder.mkdirs()
            return
        }

        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { file ->
                val config = YamlConfiguration.loadConfiguration(file)
                // 每个文件作为一个独立的商人 (比如 novice_merchant.yml -> ID 就是 novice_merchant)
                val barterId = file.nameWithoutExtension
                val merchantTitle = config.getString("title", "§6神秘商人")!!

                val merchant = Bukkit.createMerchant(merchantTitle)
                val recipesList = ArrayList<MerchantRecipe>()

                val recipesSection = config.getConfigurationSection("recipes") ?: return@forEach
                for (key in recipesSection.getKeys(false)) {
                    val recipeSec = recipesSection.getConfigurationSection(key) ?: continue

                    // 1. 解析产物 (Result)
                    val resultId = recipeSec.getString("result.id") ?: continue
                    val resultAmt = recipeSec.getInt("result.amount", 1)
                    val resultItem = plugin.itemManager.createItem(resultId, null) ?: continue
                    resultItem.amount = resultAmt

                    // 创建配方 (最大交易次数设为 9999)
                    val recipe = MerchantRecipe(resultItem, 9999)

                    // 2. 解析材料 (Ingredients, 原版最多支持2种材料)
                    val ingSec = recipeSec.getConfigurationSection("ingredients") ?: continue
                    for (ingId in ingSec.getKeys(false)) {
                        if (recipe.ingredients.size >= 2) break // 原版交易最多只支持2个格子

                        val ingAmt = ingSec.getInt(ingId)
                        val ingItem = plugin.itemManager.createItem(ingId, null) ?: continue
                        ingItem.amount = ingAmt
                        recipe.addIngredient(ingItem)
                    }

                    if (recipe.ingredients.isNotEmpty()) {
                        recipesList.add(recipe)
                    }
                }

                merchant.recipes = recipesList
                merchants[barterId] = merchant
            }
        plugin.logger.info("加载了 ${merchants.size} 个以物换物商人。")
    }

    fun openBarter(player: Player, barterId: String) {
        val merchant = merchants[barterId]
        if (merchant != null) {
            player.openMerchant(merchant, true)
        } else {
            player.sendMessage("§c错误：交易方案 $barterId 不存在！")
        }
    }
}