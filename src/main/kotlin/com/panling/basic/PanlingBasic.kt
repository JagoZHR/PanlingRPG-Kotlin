package com.panling.basic

import com.panling.basic.api.BasicKeys
import com.panling.basic.command.CommandManager
import com.panling.basic.dungeon.DungeonManager
import com.panling.basic.forge.ForgeManager
import com.panling.basic.listener.*
import com.panling.basic.manager.*
import com.panling.basic.quest.QuestLoader
import com.panling.basic.quest.feature.QuestNpcFeature
import com.panling.basic.shop.feature.ShopNpcFeature
import com.panling.basic.ui.BankUI
import com.panling.basic.ui.QuestUI
import com.panling.basic.ui.SetUI
import org.bukkit.plugin.java.JavaPlugin

class PanlingBasic : JavaPlugin() {

    companion object {
        lateinit var instance: PanlingBasic
    }

    // Managers - 直接公开 lateinit 属性，Kotlin 风格
    lateinit var playerDataManager: PlayerDataManager
    lateinit var itemManager: ItemManager
    lateinit var cooldownManager: CooldownManager
    lateinit var skillManager: SkillManager
    lateinit var subClassManager: SubClassManager
    lateinit var menuManager: MenuManager
    lateinit var accessoryManager: AccessoryManager
    lateinit var statCalculator: StatCalculator
    lateinit var qualificationManager: QualificationManager
    lateinit var setManager: SetManager
    lateinit var setUI: SetUI
    lateinit var economyManager: EconomyManager
    lateinit var bankUI: BankUI
    lateinit var lootManager: LootManager
    lateinit var mobManager: MobManager
    lateinit var locationManager: LocationManager
    lateinit var elementalManager: ElementalManager
    lateinit var buffManager: BuffManager
    lateinit var warehouseManager: WarehouseManager
    lateinit var questManager: QuestManager
    lateinit var questUI: QuestUI
    lateinit var npcManager: NpcManager

    // [NEW] 锻造 & 对话 & 商店
    lateinit var forgeManager: ForgeManager
    lateinit var dialogManager: DialogManager
    lateinit var shopManager: ShopManager
    lateinit var reloadManager: ReloadManager
    lateinit var dungeonManager: DungeonManager
    lateinit var worldScriptManager: WorldScriptManager

    // 内部使用的管理器，不需要公开 getter 也可以直接 private
    lateinit var commandManager: CommandManager

    // 可空管理器 (例如 SpawnerManager 可能有关闭状态)
    var spawnerManager: SpawnerManager? = null

    // 内部私有监听器
    private lateinit var invListener: InventoryListener

    override fun onEnable() {
        instance = this

        // 这一行配合上一条回复修复的 BasicKeys 使用，确保数据在启动时立即加载
        BasicKeys.load(this)

        // 1. [最优先] 初始化重载管理器
        reloadManager = ReloadManager(this)

        // 2. 基础数据层
        playerDataManager = PlayerDataManager(this)
        itemManager = ItemManager(this)
        cooldownManager = CooldownManager()
        locationManager = LocationManager(this)

        // 3. 核心功能层
        economyManager = EconomyManager(this, playerDataManager)
        bankUI = BankUI(this, economyManager)
        qualificationManager = QualificationManager(playerDataManager)
        setManager = SetManager(this)
        accessoryManager = AccessoryManager(this, playerDataManager, itemManager)
        setManager.rebuildItemCache(itemManager)
        worldScriptManager = WorldScriptManager(this)

        // 4. 状态与属性计算层
        buffManager = BuffManager(this)

        statCalculator = StatCalculator(
            playerDataManager,
            accessoryManager,
            itemManager,
            qualificationManager,
            setManager
        )
        this.statCalculator.buffManager = this.buffManager

        // 反向连接
        this.buffManager.statCalculator = this.statCalculator

        subClassManager = SubClassManager(this, playerDataManager, statCalculator)
        this.statCalculator.subClassManager = this.subClassManager

        lootManager = LootManager(this, itemManager)

        mobManager = MobManager(this, itemManager, lootManager)
        this.mobManager.buffManager = this.buffManager

        spawnerManager = SpawnerManager(this, mobManager)

        // 锻造模块
        forgeManager = ForgeManager(this)

        // 5. 业务逻辑层
        invListener = InventoryListener(this, playerDataManager, itemManager, statCalculator)
        accessoryManager.setInventoryListener(invListener)

        menuManager = MenuManager(
            this,
            playerDataManager,
            accessoryManager,
            statCalculator
        )
        menuManager.setBankUI(bankUI)

        skillManager = SkillManager(this, cooldownManager, playerDataManager)
        setUI = SetUI(this)
        elementalManager = ElementalManager(
            this,
            playerDataManager,
            buffManager,
            statCalculator,
            mobManager
        )
        warehouseManager = WarehouseManager(this, playerDataManager)

        // 6. NPC 与 任务系统
        dialogManager = DialogManager(this)
        npcManager = NpcManager(this)
        questManager = QuestManager(this)
        questUI = QuestUI(this, questManager)

        // 注册 NPC Feature
        dialogManager.registerFeature(QuestNpcFeature(questManager))
        dialogManager.registerFeature(ShopNpcFeature())

        // 加载任务
        QuestLoader(this, questManager).loadAll()

        shopManager = ShopManager(this)

        // 7. 副本管理器
        dungeonManager = DungeonManager(this)

        // 8. 注册监听器 (使用 server.pluginManager 简化写法)
        server.pluginManager.apply {
            registerEvents(ForgeListener(forgeManager), this@PanlingBasic)
            registerEvents(PlayerCombatListener(
                this@PanlingBasic,
                playerDataManager,
                statCalculator,
                skillManager,
                mobManager,
                subClassManager,
                buffManager
            ), this@PanlingBasic)
            registerEvents(invListener, this@PanlingBasic)
            registerEvents(menuManager, this@PanlingBasic)
            registerEvents(accessoryManager, this@PanlingBasic)
            registerEvents(CacheListener(playerDataManager), this@PanlingBasic)
            registerEvents(MobListener(mobManager), this@PanlingBasic)
            registerEvents(WorldTriggerListener(this@PanlingBasic), this@PanlingBasic)
            registerEvents(DungeonListener(this@PanlingBasic), this@PanlingBasic)
            // [新增] 注册副本入口 UI 监听器
            // 确保引入 import com.panling.basic.listener.DungeonEntryListener
            registerEvents(DungeonEntryListener(this@PanlingBasic), this@PanlingBasic)
        }

        // 延迟修复任务
        server.scheduler.runTaskLater(this, Runnable {
            val fixed = locationManager.restoreMissingEntities()
            if (fixed > 0) logger.info("自动修复了 $fixed 个丢失的 RPG 交互实体。")
        }, 100L)

        // 9. 注册指令系统
        commandManager = CommandManager(this)
        getCommand("plbasic")?.apply {
            setExecutor(commandManager)
            tabCompleter = commandManager
        }

        logger.info("PanlingBasic Enabled!")
    }

    override fun onDisable() {
        // Kotlin 检查 lateinit 是否初始化的标准写法
        if (::npcManager.isInitialized) {
            npcManager.despawnAll()
        }

        spawnerManager?.stop()
    }
}