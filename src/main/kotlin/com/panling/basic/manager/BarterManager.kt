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

    // 存储 交易方案ID -> (标题 + 配方列表)
    private data class BarterData(val title: String, val recipes: List<MerchantRecipe>)
    private val barters = HashMap<String, BarterData>()

    init {
        plugin.reloadManager?.register(this)
        loadBarters()
    }

    override fun reload() {
        loadBarters()
        plugin.logger.info("以物换物配置已重载。")
    }

    private fun loadBarters() {
        barters.clear()
        val folder = File(plugin.dataFolder, "barter")
        if (!folder.exists()) {
            folder.mkdirs()
            return
        }

        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { file ->
                val config = YamlConfiguration.loadConfiguration(file)
                val barterId = file.nameWithoutExtension
                val merchantTitle = config.getString("title", "§6神秘商人")!!

                val recipesList = ArrayList<MerchantRecipe>()

                val recipesSection = config.getConfigurationSection("recipes") ?: return@forEach
                for (key in recipesSection.getKeys(false)) {
                    val recipeSec = recipesSection.getConfigurationSection(key) ?: continue

                    val resultId = recipeSec.getString("result.id") ?: continue
                    val resultAmt = recipeSec.getInt("result.amount", 1)
                    val resultItem = plugin.itemManager.createItem(resultId, null) ?: continue
                    resultItem.amount = resultAmt

                    val recipe = MerchantRecipe(resultItem, 9999)

                    val ingSec = recipeSec.getConfigurationSection("ingredients") ?: continue
                    for (ingId in ingSec.getKeys(false)) {
                        if (recipe.ingredients.size >= 2) break

                        val ingAmt = ingSec.getInt(ingId)
                        val ingItem = plugin.itemManager.createItem(ingId, null) ?: continue
                        ingItem.amount = ingAmt
                        recipe.addIngredient(ingItem)
                    }

                    if (recipe.ingredients.isNotEmpty()) {
                        recipesList.add(recipe)
                    }
                }

                barters[barterId] = BarterData(merchantTitle, recipesList)
            }
        plugin.logger.info("加载了 ${barters.size} 个以物换物商人。")
    }

    fun openBarter(player: Player, barterId: String) {
        val data = barters[barterId]
        if (data != null) {
            // 每次打开都创建新 Merchant 实例，避免多玩家互抢
            val merchant = Bukkit.createMerchant(data.title)
            merchant.recipes = data.recipes
            player.openMerchant(merchant, true)
        } else {
            player.sendMessage("§c错误：交易方案 $barterId 不存在！")
        }
    }
}