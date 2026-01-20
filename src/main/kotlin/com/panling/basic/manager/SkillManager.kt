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
import java.util.*

class SkillManager(
    private val plugin: JavaPlugin,
    private val cooldownManager: CooldownManager,
    private val dataManager: PlayerDataManager
) {
    private val skills = HashMap<String, AbstractSkill>()

    companion object {
        private const val META_CHARGED_SKILLS = "pl_charged_skills"
    }

    init {
        registerBuiltInSkills()
        startPassiveTask()
    }

    private fun registerBuiltInSkills() {
        // 注册弓箭手技能
        register(ExplosiveArrowSkill(plugin))

        // 注册箭袋相关技能 (传入 dataManager)
        register(QuiverSkills.Store(dataManager))
        register(QuiverSkills.Withdraw(dataManager))
        register(QuiverSkills.Supply(dataManager))

        // 强转插件实例以传递给需要特定类型的构造函数
        val pb = plugin as? PanlingBasic ?: return

        // 注册法师技能 (五行)
        register(MageMetalSkill(pb))
        register(MageWoodSkill(pb))
        register(MageEarthSkill(pb))
        register(MageWaterSkill(pb))
        register(MageFireSkill(pb))

        // 注册法师法宝基础技能
        register(MageBaseArtifactAttackSkill(pb))
        register(MageBaseArtifactHealSkill(pb))

        // 注册战士技能
        register(GoldenBellT2Skill(pb))
        register(PoJunT2Skill(pb))
        register(GoldenBellT3Skill(pb))
        register(PoJunT3Skill(pb))
        register(GoldenBellT4Skill(pb))
        register(PoJunT4Skill(pb))
        register(GoldenBellT5Skill(pb))
        register(PoJunT5Skill(pb))

        // 注册通用/其他技能
        register(YunshuiActiveSkill(pb))
        register(YunshuiPassiveSkill(pb))
        register(ZidianPassiveSkill(pb))
        register(ZidianActiveSkill(pb))

        // 注册进阶弓箭手技能
        register(RangerT3Skill(pb))
        register(SniperT4Skill(pb))
        register(RangerT4Skill(pb))
        register(SniperT5Skill(pb))
        register(RangerT5Skill(pb))
        register(RangerT6Skill(pb))
        register(ChangfengSkill(pb))
    }

    fun register(skill: AbstractSkill) {
        skills[skill.id] = skill
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