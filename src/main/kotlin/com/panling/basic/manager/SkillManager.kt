package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.impl.archer.*
import com.panling.basic.skill.impl.mage.*
import com.panling.basic.skill.impl.mage.baseArtifact.MageBaseArtifactAttackSkill
import com.panling.basic.skill.impl.mage.baseArtifact.MageBaseArtifactHealSkill
import com.panling.basic.skill.impl.warrior.*
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import com.panling.basic.util.ClassScanner
import java.util.*

class SkillManager(
    // [修改] 直接接收 PanlingBasic，方便反射传参
    private val plugin: PanlingBasic,
    private val cooldownManager: CooldownManager,
    private val dataManager: PlayerDataManager
) {
    private val skills = HashMap<String, AbstractSkill>()

    companion object {
        private const val META_CHARGED_SKILLS = "pl_charged_skills"
    }

    init {
        // [修改] 替换为自动扫描
        loadAllSkills()

        // 启动被动技能心跳检测
        startPassiveTask()
    }

    /**
     * [核心修改] 自动扫描并注册所有技能
     */
    private fun loadAllSkills() {
        skills.clear()
        plugin.logger.info("开始扫描并注册技能...")

        // 注册箭袋相关技能 (传入 dataManager)
        registerSkill(QuiverSkills.Store(dataManager))
        registerSkill(QuiverSkills.Withdraw(dataManager))
        registerSkill(QuiverSkills.Supply(dataManager))

        // 扫描 com.panling.basic 包下所有继承 AbstractSkill 的类
        val skillClasses = ClassScanner.scanClasses(plugin, "com.panling.basic", AbstractSkill::class.java)

        var count = 0
        for (clazz in skillClasses) {
            try {
                // 尝试实例化技能类
                // 优先寻找带 PanlingBasic 参数的构造函数
                val skillInstance = try {
                    val constructor = clazz.getConstructor(PanlingBasic::class.java)
                    constructor.newInstance(plugin)
                } catch (e: NoSuchMethodException) {
                    // 如果没有，尝试寻找无参构造函数
                    try {
                        val constructor = clazz.getConstructor()
                        constructor.newInstance()
                    } catch (ex: Exception) {
                        // 两个构造函数都没有，跳过
                        plugin.logger.warning("无法实例化技能类 ${clazz.simpleName}: 需要 constructor(plugin) 或无参构造函数")
                        continue
                    }
                }

                registerSkill(skillInstance)
                count++
            } catch (e: Exception) {
                plugin.logger.severe("加载技能 ${clazz.simpleName} 时发生错误: ${e.message}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("共自动注册了 $count 个技能。")
    }

    /**
     * 注册单个技能
     */
    fun registerSkill(skill: AbstractSkill) {
        if (skills.containsKey(skill.id)) {
            plugin.logger.warning("发现重复的技能ID: ${skill.id} (${skill.javaClass.simpleName})，原有技能将被覆盖。")
        }
        skills[skill.id] = skill
        // 可以在这里打印日志: plugin.logger.info("已注册技能: [${skill.id}] ${skill.displayName}")
    }

    fun getSkill(id: String): AbstractSkill? {
        return skills[id]
    }

    val skillIds: Set<String>
        get() = skills.keys

    /**
     * 统一执行入口
     */
    fun castSkill(skillId: String, context: SkillContext): Boolean {
        val skill = skills[skillId] ?: return false
        return skill.execute(context, cooldownManager, dataManager)
    }

    // === 蓄力/下一次攻击逻辑 ===

    fun addCharge(player: Player, skillId: String) {
        val charges = getChargedSkills(player).toMutableSet() // 使用 Set 自动去重
        charges.add(skillId)
        setChargedSkills(player, charges)
    }

    fun triggerCharges(player: Player, event: EntityDamageByEntityEvent) {
        val raw = getMetaString(player, META_CHARGED_SKILLS) ?: return
        if (raw.isEmpty()) return

        // 立即清除蓄力 (一次性消耗)
        player.removeMetadata(META_CHARGED_SKILLS, plugin)

        val target = event.entity as? LivingEntity
        // 构建临时 Context
        val ctx = SkillContext(
            player, target, player.inventory.itemInMainHand, null, player.location, 0.0, SkillTrigger.ATTACK
        )

        raw.split(";").forEach { skillId ->
            val skill = skills[skillId]
            skill?.onNextAttack(event, ctx)
        }
    }

    private fun getChargedSkills(player: Player): Set<String> {
        val raw = getMetaString(player, META_CHARGED_SKILLS) ?: return emptySet()
        return raw.split(";").filter { it.isNotEmpty() }.toSet()
    }

    private fun setChargedSkills(player: Player, charges: Set<String>) {
        if (charges.isEmpty()) {
            player.removeMetadata(META_CHARGED_SKILLS, plugin)
        } else {
            setMetaString(player, META_CHARGED_SKILLS, charges.joinToString(";"))
        }
    }

    // === 物品绑定技能触发 ===

    fun triggerItemSkill(player: Player, trigger: SkillTrigger, event: EntityDamageByEntityEvent?) {
        val item = player.inventory.itemInMainHand
        if (!item.hasItemMeta()) return

        val key = BasicKeys.TRIGGER_KEYS[trigger] ?: return
        val pdc = item.itemMeta!!.persistentDataContainer

        val skillId = pdc.get(key, PersistentDataType.STRING) ?: return
        val skill = skills[skillId] ?: return

        // 智能构建目标
        var target: LivingEntity? = null
        if (event != null) {
            target = when (trigger) {
                // 如果是受击触发，目标是"攻击者"
                SkillTrigger.DAMAGED -> {
                    val damager = event.damager
                    if (damager is LivingEntity) damager
                    else if (damager is Projectile && damager.shooter is LivingEntity) damager.shooter as LivingEntity
                    else null
                }
                // 如果是攻击触发，目标是"受击者"
                SkillTrigger.ATTACK -> event.entity as? LivingEntity
                else -> null
            }
        }

        val ctx = SkillContext(
            player, target, item, null, player.location, 1.0, trigger
        )

        if (skill.execute(ctx, cooldownManager, dataManager)) {
            // 钩子分发
            if (event != null) {
                when (trigger) {
                    SkillTrigger.ATTACK -> skill.onAttack(event, ctx)
                    SkillTrigger.DAMAGED -> skill.onDamaged(event, ctx)
                    else -> {}
                }
            }
        }
    }

    // === 被动技能处理 ===

    fun handlePassives(player: Player, type: PlayerDataManager.PassiveTrigger, baseContext: SkillContext) {
        val passives = dataManager.getCachedPassives(player, type)
        if (passives.isEmpty()) return

        for (cp in passives) {
            val ctx = SkillContext(
                baseContext.player,
                baseContext.target,
                cp.sourceItem, // 注入来源物品
                baseContext.projectile,
                baseContext.location,
                baseContext.power,
                baseContext.triggerType
            )
            castSkill(cp.id, ctx)
        }
    }

    private fun startPassiveTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                // 构造基础 Context
                val ctx = SkillContext.of(player, 0.0, SkillTrigger.PASSIVE)
                handlePassives(player, PlayerDataManager.PassiveTrigger.CONSTANT, ctx)
            }
        }, 20L, 20L)
    }

    // === 冷却辅助 ===

    /**
     * [通用] 尝试检查并应用冷却
     * 适用于不走 execute() 流程的被动/策略技能
     */
    fun tryApplyCooldown(skill: AbstractSkill, ctx: SkillContext): Boolean {
        val player = ctx.player
        val cdKey = skill.getCooldownKey(ctx)

        if (cooldownManager.getRemainingCooldown(player, cdKey) > 0) {
            return false
        }

        val cdMillis = skill.calculateCooldown(ctx, dataManager)
        if (cdMillis > 0) {
            cooldownManager.setCooldown(player, cdKey, cdMillis)
        }
        return true
    }

    // === 辅助扩展 ===
    private fun getMetaString(player: Player, key: String): String? {
        if (!player.hasMetadata(key)) return null
        return try {
            player.getMetadata(key)[0].asString()
        } catch (e: Exception) {
            null
        }
    }

    private fun setMetaString(player: Player, key: String, value: String) {
        player.setMetadata(key, FixedMetadataValue(plugin, value))
    }
}