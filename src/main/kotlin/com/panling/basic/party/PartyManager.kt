package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.party.Party
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class PartyManager(private val plugin: PanlingBasic) : Listener {

    init {
        // [新增] 注册监听器，用于处理玩家下线
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    // 玩家 UUID -> 所在的队伍对象
    private val playerParties = HashMap<UUID, Party>()

    // 玩家 UUID -> 收到的邀请列表 (邀请者的 UUID)
    private val pendingInvites = HashMap<UUID, MutableList<UUID>>()

    // 获取玩家当前的队伍（如果没有，则返回 null，此时他可以被视为自己的队长）
    fun getParty(player: Player): Party? {
        return playerParties[player.uniqueId]
    }

    // 获取玩家收到的所有邀请
    fun getInvites(player: Player): List<UUID> {
        return pendingInvites[player.uniqueId] ?: emptyList()
    }

    // 发送邀请
    fun invitePlayer(inviter: Player, target: Player) {
        // [新增] 拦截：如果目标玩家已经有队伍了，不允许邀请
        val targetParty = getParty(target)
        if (targetParty != null) {
            inviter.sendMessage("§c邀请失败：${target.name} 已经在其他队伍中了！")
            return
        }
        val invites = pendingInvites.computeIfAbsent(target.uniqueId) { mutableListOf() }
        if (!invites.contains(inviter.uniqueId)) {
            invites.add(inviter.uniqueId)
            target.sendMessage("§a你收到了来自 §e${inviter.name} §a的组队邀请！打开组队菜单以处理。")
        }
    }

    // 同意邀请
    fun acceptInvite(player: Player, inviterId: UUID): Boolean {
        // 清空该玩家的所有邀请
        pendingInvites.remove(player.uniqueId)

        var party = playerParties[inviterId]
        if (party == null) {
            // 如果邀请者还没队伍，为他创建一个
            party = Party(inviterId)
            playerParties[inviterId] = party
        }

        if (party.isFull) {
            player.sendMessage("§c对方队伍已满！")
            return false
        }

        // 离开当前队伍逻辑（如果以后需要实现的话，可以在这里加）

        // 加入新队伍
        party.members.add(player.uniqueId)
        playerParties[player.uniqueId] = party
        return true
    }

    // 拒绝某人的邀请
    fun declineInvite(player: Player, inviterId: UUID) {
        pendingInvites[player.uniqueId]?.remove(inviterId)
    }

    // 一键清空邀请 (用于当玩家无视邀请，直接去拉别人时)
    fun clearInvites(player: Player) {
        pendingInvites.remove(player.uniqueId)
    }

    // ================= [新增] 队伍管理与下线处理 =================

    fun leaveParty(player: Player) {
        val party = getParty(player) ?: return
        if (party.leader == player.uniqueId) {
            disbandParty(party) // 队长退出直接解散 (或者你可以写移交队长的逻辑)
        } else {
            party.members.remove(player.uniqueId)
            playerParties.remove(player.uniqueId)
            party.members.forEach { uuid ->
                org.bukkit.Bukkit.getPlayer(uuid)?.sendMessage("§e${player.name} 离开了队伍。")
            }
        }
    }

    fun disbandParty(party: Party) {
        party.members.forEach { uuid ->
            playerParties.remove(uuid)
            org.bukkit.Bukkit.getPlayer(uuid)?.sendMessage("§c队伍已被队长解散。")
        }
        party.members.clear()
    }

    fun kickMember(party: Party, targetId: UUID) {
        party.members.remove(targetId)
        playerParties.remove(targetId)
        party.members.forEach { uuid ->
            org.bukkit.Bukkit.getPlayer(uuid)?.sendMessage("§e一名队员被队长踢出了队伍。")
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        leaveParty(event.player)
        clearInvites(event.player)
    }
}