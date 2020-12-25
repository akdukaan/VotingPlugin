package com.bencodez.votingplugin.listeners;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.advancedcore.api.misc.PlayerUtils;
import com.bencodez.votingplugin.VotingPluginMain;
import com.bencodez.votingplugin.bungee.BungeeMethod;
import com.bencodez.votingplugin.events.PlayerVoteEvent;
import com.bencodez.votingplugin.objects.VoteSite;
import com.bencodez.votingplugin.user.UserManager;
import com.bencodez.votingplugin.user.VotingPluginUser;

// TODO: Auto-generated Javadoc
/**
 * The Class VotiferEvent.
 */
public class PlayerVoteListener implements Listener {

	private static Object object = new Object();

	private VotingPluginMain plugin;

	/**
	 * Instantiates a new votifer event.
	 *
	 * @param plugin the plugin
	 */
	public PlayerVoteListener(VotingPluginMain plugin) {
		this.plugin = plugin;
	}

	/**
	 * On votifer event.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onplayerVote(PlayerVoteEvent event) {
		String playerName = event.getPlayer();
		if (!PlayerUtils.getInstance().isValidUser(playerName, plugin.getConfigFile().isAllowUnJoinedCheckServer())) {
			if (!plugin.getConfigFile().isAllowUnjoined()) {
				plugin.getLogger().warning("Player " + playerName
						+ " has not joined before, disregarding vote, set AllowUnjoined to true to prevent this");
				if (event.isBungee() && plugin.getBungeeSettings().isRemoveInvalidUsers()) {
					UserManager.getInstance().getVotingPluginUser(playerName).remove();
				}
				return;
			}
		}

		if (event.isBungee()) {
			plugin.debug("BungeePlayerVote forcebungee: " + event.isForceBungee() + ", bungeetotals: "
					+ event.getBungeeTextTotals());
		}

		VoteSite voteSite = event.getVoteSite();

		// check valid service sites
		if (voteSite == null) {
			if (!plugin.getConfigFile().isDisableNoServiceSiteMessage()) {
				plugin.getLogger().warning("No voting site with the service site: '" + event.getServiceSite() + "'");

				ArrayList<String> services = new ArrayList<String>();
				for (VoteSite site : plugin.getVoteSites()) {
					services.add(site.getServiceSite());
				}
				plugin.getLogger()
						.warning("Current known service sites: " + ArrayUtils.getInstance().makeStringList(services));
			}
			return;
		}

		VotingPluginUser user = UserManager.getInstance().getVotingPluginUser(playerName);
		user.updateName(true);

		if (plugin.getConfigFile().isClearCacheOnVote() || plugin.getBungeeSettings().isUseBungeecoord()) {
			user.clearCache();
		}

		if (voteSite.isWaitUntilVoteDelay() && !user.canVoteSite(voteSite)) {
			plugin.getLogger().info(user.getPlayerName() + " must wait until votedelay is over, ignoring vote");
			return;
		}

		synchronized (object) {

			// vote party
			plugin.getVoteParty().vote(user, event.isRealVote());

			// broadcast vote if enabled in config
			if (plugin.getConfigFile().isBroadcastVotesEnabled()
					&& (plugin.getBungeeSettings().isBungeeBroadcast() || !event.isBungee())) {
				if (!plugin.getConfigFile().getFormatBroadcastWhenOnline() || user.isOnline()) {
					voteSite.broadcastVote(user);
				}
			}

			// update last vote time
			if (event.getTime() != 0) {
				user.setTime(voteSite, event.getTime());
			} else {
				user.setTime(voteSite);
			}
			user.setLastVoteCoolDownCheck(false, voteSite);

			// try logging to file
			if (plugin.getConfigFile().isLogVotesToFile()) {
				try {
					VotingPluginMain.plugin.logVote(
							LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalDateTime(), playerName,
							voteSite.getKey());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			// check first vote rewards
			plugin.getSpecialRewards().checkFirstVote(user);

			if (user.isReminded()) {
				user.setReminded(false);
			}

			// check if player has voted on all sites in one day

			if (((user.isOnline() || voteSite.isGiveOffline())
					&& VotingPluginMain.plugin.getOptions().isProcessRewards()) || event.isBungee()) {
				user.playerVote(voteSite, true, false, event.isForceBungee());
				user.sendVoteEffects(true);
				user.closeInv();
			} else {
				user.addOfflineVote(voteSite.getKey());
				plugin.debug(
						"Offline vote set for " + playerName + " (" + user.getUUID() + ") on " + voteSite.getKey());
			}

			// add to total votes
			if ((plugin.getConfigFile().isCountFakeVotes() || event.isRealVote()) && event.isAddTotals()) {
				if (plugin.getConfigFile().isAddTotals()) {
					user.addTotal();
					user.addTotalDaily();
					user.addTotalWeekly();
				}
				user.addPoints();
			}

			user.checkDayVoteStreak();

			// other rewards
			plugin.getSpecialRewards().checkAllSites(user);
			plugin.getSpecialRewards().checkCumualativeVotes(user, event.getBungeeTextTotals());
			plugin.getSpecialRewards().checkMilestone(user, event.getBungeeTextTotals());

			if (plugin.getBungeeSettings().isUseBungeecoord()) {
				if (plugin.getBungeeHandler().getMethod().equals(BungeeMethod.MYSQL)) {
					final String uuid = user.getUUID();
					Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {

						@Override
						public void run() {
							if (Bukkit.getOnlinePlayers().size() > 0) {
								plugin.getPluginMessaging().sendPluginMessage(
										PlayerUtils.getInstance().getRandomOnlinePlayer(), "VoteUpdate", uuid);
							}
						}
					}, 40);
				}
			}
		}

		plugin.setUpdate(true);
	}

}
