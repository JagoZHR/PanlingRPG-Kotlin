package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.PartyManager
import com.panling.basic.party.PartyHolder
import com.panling.basic.party.PartyUIType
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

class PartyUI(
    private val plugin: PanlingBasic,
    private val partyManager: PartyManager
) : Listener {

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    // 入口方法
    fun openGUI(player: Player) {
        val invites = partyManager.getInvites(player)

        // 如果有邀请未处理，打开处理邀请界面
        if (invites.isNotEmpty()) {
            openInvitesGUI(player, invites)
        } else {
            // 否则打开队伍管理界面
            openTeamGUI(player)
        }
    }

    // === 界面 1: 处理邀请 ===
    private fun openInvitesGUI(player: Player, invites: List<UUID>) {
        val inv = Bukkit.createInventory(PartyHolder(PartyUIType.HANDLE_INVITES), 54, Component.text("§8处理组队邀请"))

        // 最多显示 6 个邀请，每行一个
        val displayCount = minOf(invites.size, 6)
        for (i in 0 until displayCount) {
            val inviterId = invites[i]
            val inviterName = Bukkit.getOfflinePlayer(inviterId).name ?: "未知玩家"
            val rowStart = i * 9

            // 第 3 格: 邀请者的头颅
            val skull = getPlayerSkull(inviterId, inviterName, listOf("§7来自 §e$inviterName §7的邀请"))
            inv.setItem(rowStart + 2, skull)

            // 第 5 格: 同意按钮
            val acceptBtn = ItemStack(Material.LIME_WOOL).apply {
                val meta = itemMeta
                meta.setDisplayName("§a§l[ ✔ 同意 ]")
                meta.lore = listOf("§7点击加入 §e$inviterName §7的队伍")
                itemMeta = meta
            }
            inv.setItem(rowStart + 4, acceptBtn)

            // 第 7 格: 拒绝按钮
            val declineBtn = ItemStack(Material.RED_WOOL).apply {
                val meta = itemMeta
                meta.setDisplayName("§c§l[ ✖ 拒绝 ]")
                meta.lore = listOf("§7点击拒绝该邀请")
                itemMeta = meta
            }
            inv.setItem(rowStart + 6, declineBtn)
        }

        player.openInventory(inv)
    }

    // === 界面 2: 队伍管理与拉人 ===
    // [修改] 增加 page 参数
    private fun openTeamGUI(player: Player, page: Int = 0) {
        val inv = Bukkit.createInventory(PartyHolder(PartyUIType.MANAGE_TEAM), 54, Component.text("§8组队面板 - 第 ${page + 1} 页"))
        val party = partyManager.getParty(player)

        // 1. 绘制当前队伍成员 (前 16 格，两行)
        val currentMembers = party?.members?.toList() ?: listOf(player.uniqueId)
        val maxMembers = 16

        for ((index, memberId) in currentMembers.withIndex()) {
            if (index >= 16) break
            val isSelf = memberId == player.uniqueId
            val isLeader = (party != null && party.leader == memberId) || (party == null && isSelf)
            val memberName = Bukkit.getOfflinePlayer(memberId).name ?: "未知玩家"

            val lore = mutableListOf<String>()
            if (isSelf) {
                lore.add("§7目前人数: §a${currentMembers.size}§8/§c$maxMembers")
            }
            if (isLeader) lore.add("§e[队长]") else lore.add("§b[队员]")
            lore.add("")
            lore.add("§c[Shift + 左键] §7踢出队员/离开队伍") // [新增] 操作提示

            val skull = getPlayerSkull(memberId, "§f$memberName", lore)

            // [新增] 写入 NBT 以便点击时识别
            val meta = skull.itemMeta
            meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin, "party_action"), org.bukkit.persistence.PersistentDataType.STRING, "PARTY_MEMBER_HEAD")
            meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin, "member_uuid"), org.bukkit.persistence.PersistentDataType.STRING, memberId.toString())
            skull.itemMeta = meta

            inv.setItem(index, skull)
        }

        // 2. 绘制分割线 (第 3 行，18 - 26 格)
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val meta = itemMeta
            meta.setDisplayName(" ")
            itemMeta = meta
        }
        for (i in 18..26) inv.setItem(i, glass)

        // 3. 绘制附近可邀请的玩家 (从 27 格开始，到 44 格结束，共 18 格作为一页)
        val nearbyPlayers = player.getNearbyEntities(50.0, 50.0, 50.0)
            .filterIsInstance<Player>()
            .filter { it.uniqueId !in currentMembers }
            .filter { partyManager.getParty(it) == null } // [新增] 过滤掉已经有队伍的人

        val slotsPerPage = 18
        val startIndex = page * slotsPerPage
        val endIndex = minOf(startIndex + slotsPerPage, nearbyPlayers.size)

        var slot = 27
        for (i in startIndex until endIndex) {
            val target = nearbyPlayers[i]
            val skull = getPlayerSkull(target.uniqueId, "§f${target.name}", listOf("§e点击左键邀请该玩家"))
            inv.setItem(slot, skull)
            slot++
        }

        // ================= [新增] 底部控制栏 =================
        // 49格: 返回主菜单
        val backBtn = ItemStack(Material.ARROW).apply {
            val meta = itemMeta
            meta.setDisplayName("§c[ 返回主菜单 ]")
            meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin, "party_action"), org.bukkit.persistence.PersistentDataType.STRING, "BACK_TO_MENU")
            itemMeta = meta
        }
        inv.setItem(49, backBtn)

        // 45格: 上一页
        if (page > 0) {
            val prevBtn = ItemStack(Material.ARROW).apply {
                val meta = itemMeta
                meta.setDisplayName("§a[ 上一页 ]")
                meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin, "party_page"), org.bukkit.persistence.PersistentDataType.INTEGER, page - 1)
                itemMeta = meta
            }
            inv.setItem(45, prevBtn)
        }

        // 53格: 下一页
        if (endIndex < nearbyPlayers.size) {
            val nextBtn = ItemStack(Material.ARROW).apply {
                val meta = itemMeta
                meta.setDisplayName("§a[ 下一页 ]")
                meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin, "party_page"), org.bukkit.persistence.PersistentDataType.INTEGER, page + 1)
                itemMeta = meta
            }
            inv.setItem(53, nextBtn)
        }

        player.openInventory(inv)
    }

    // === 点击事件处理 ===
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? PartyHolder ?: return
        event.isCancelled = true // 禁止拿取物品

        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return

        val slot = event.slot

        when (holder.type) {
            PartyUIType.HANDLE_INVITES -> {
                // 每行有三个有效按钮，基于行号和列号计算
                val row = slot / 9
                val col = slot % 9

                val invites = partyManager.getInvites(player)
                if (row >= invites.size) return

                val inviterId = invites[row]

                if (col == 4) { // 绿色羊毛 (同意)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f)
                    if (partyManager.acceptInvite(player, inviterId)) {
                        player.sendMessage("§a成功加入队伍！")
                        openTeamGUI(player) // 加入成功后直接打开队伍面板
                    }
                } else if (col == 6) { // 红色羊毛 (拒绝)
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    player.sendMessage("§e已拒绝该邀请。")
                    partyManager.declineInvite(player, inviterId)

                    // 刷新界面，如果没邀请了就进主面板
                    openGUI(player)
                }
            }

            PartyUIType.MANAGE_TEAM -> {
                val clickedItem = event.currentItem ?: return
                val pdc = clickedItem.itemMeta?.persistentDataContainer ?: return

                // [新增] 1. 处理翻页
                if (pdc.has(org.bukkit.NamespacedKey(plugin, "party_page"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
                    val targetPage = pdc.get(org.bukkit.NamespacedKey(plugin, "party_page"), org.bukkit.persistence.PersistentDataType.INTEGER) ?: 0
                    openTeamGUI(player, targetPage)
                    return
                }

                val action = pdc.get(org.bukkit.NamespacedKey(plugin, "party_action"), org.bukkit.persistence.PersistentDataType.STRING)

                // [新增] 2. 返回主菜单
                if (action == "BACK_TO_MENU") {
                    plugin.menuManager.openMenu(player)
                    return
                }

                // [新增] 3. 处理踢人/退队 (基于点击头颅的 NBT)
                if (action == "PARTY_MEMBER_HEAD") {
                    val targetUuidStr = pdc.get(org.bukkit.NamespacedKey(plugin, "member_uuid"), org.bukkit.persistence.PersistentDataType.STRING) ?: return
                    val targetUuid = java.util.UUID.fromString(targetUuidStr)

                    if (event.click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) {
                        val party = partyManager.getParty(player) ?: return

                        if (player.uniqueId == targetUuid) {
                            // 点击自己的头像
                            partyManager.leaveParty(player)
                            player.closeInventory()
                        } else if (party.leader == player.uniqueId) {
                            // 队长点击别人
                            partyManager.kickMember(party, targetUuid)
                            val targetPlayer = Bukkit.getPlayer(targetUuid)
                            targetPlayer?.sendMessage("§c你被队长踢出了队伍！")
                            openTeamGUI(player, 0) // 刷新界面
                        } else {
                            player.sendMessage("§c只有队长可以踢出队员！")
                        }
                    } else {
                        player.sendMessage("§7想要踢出队员或退出队伍，请使用 §cShift + 左键 §7点击头像。")
                    }
                    return
                }

                // [保留] 原有的邀请逻辑 (修改槽位判定为 27 到 44，因为下排变成控制栏了)
                if (slot in 27..44) {
                    if (clickedItem.type != Material.PLAYER_HEAD) return
                    val meta = clickedItem.itemMeta as? org.bukkit.inventory.meta.SkullMeta ?: return
                    val targetPlayer = meta.owningPlayer?.player

                    if (targetPlayer != null) {
                        if (partyManager.getInvites(player).isNotEmpty()) {
                            partyManager.clearInvites(player)
                            player.sendMessage("§7尝试邀请他人，已自动清空待处理的邀请。")
                        }
                        partyManager.invitePlayer(player, targetPlayer)
                        player.sendMessage("§a已向 §e${targetPlayer.name} §a发送组队邀请。")
                        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                        player.closeInventory()
                    } else {
                        player.sendMessage("§c该玩家已离线或不在附近。")
                    }
                }
            }
        }
    }

    // 辅助方法：生成玩家头颅
    private fun getPlayerSkull(uuid: UUID, displayName: String, loreLines: List<String>): ItemStack {
        return ItemStack(Material.PLAYER_HEAD).apply {
            val meta = itemMeta as SkullMeta
            meta.owningPlayer = Bukkit.getOfflinePlayer(uuid)
            meta.setDisplayName(displayName)
            meta.lore = loreLines
            itemMeta = meta
        }
    }
}