package com.deathreplay;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

/**
 * Keeps a rolling log of the damage the local player takes and saves it when
 * they die, so the death can be looked at afterwards.
 *
 * Everything here is passive: it only reads what the game already shows the
 * player, shows nothing during combat and never touches input or menus.
 */
@Slf4j
@PluginDescriptor(
	name = "Death Replay",
	description = "Breaks down the damage you took in the seconds before each death",
	tags = {"death", "damage", "recap", "log", "pvm", "replay"},
	internalName = "death-replay"
)
public class DeathReplayPlugin extends Plugin
{
	/**
	 * Ticks recorded after the death tick, so the final hitsplats and the
	 * death animation are included.
	 */
	private static final int TICKS_AFTER_DEATH = 4;
	/**
	 * How long an eat/drink click waits for the inventory to change.
	 */
	private static final int CONSUME_TIMEOUT_TICKS = 3;
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

	@Inject
	private Client client;

	@Inject
	private DeathReplayConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private Gson gson;

	private final CombatLog combatLog = new CombatLog();
	private final List<PendingConsume> pendingConsumes = new ArrayList<>();
	private TickRecord current = new TickRecord();

	/**
	 * The plugin's own thread for video compression and disk IO, kept off the
	 * shared executor because compressing frames is continuous work.
	 */
	private ExecutorService executor;
	private FrameRecorder recorder;
	private DeathStore store;
	private DeathReplayPanel panel;
	private NavigationButton navButton;
	// Swing thread only
	private ReplayViewer viewer;

	/**
	 * Game tick the player died on, or -1 when no death is being recorded.
	 */
	private int deathTick = -1;
	private DeathRecord pendingDeath;

	@AllArgsConstructor
	private static class PendingConsume
	{
		final int itemId;
		final String name;
		final int countBefore;
		final int tick;
	}

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread thread = new Thread(r, "death-replay");
			thread.setDaemon(true);
			return thread;
		});
		recorder = new FrameRecorder(client, drawManager, config, executor);
		recorder.start();

		panel = new DeathReplayPanel(this::deleteDeath, this::openReplay, this::openFolder);
		BufferedImage icon = DeathReplayPanel.createIcon();
		navButton = NavigationButton.builder()
			.tooltip("Death Replay")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		executor.execute(() ->
		{
			try
			{
				store = new DeathStore(getPluginDirectory(), gson.newBuilder().setPrettyPrinting().create());
				List<DeathRecord> deaths = store.loadAll();
				SwingUtilities.invokeLater(() -> panel.setDeaths(deaths));
			}
			catch (IOException e)
			{
				log.warn("Unable to open the Death Replay data folder", e);
			}
		});
	}

	@Override
	protected void shutDown()
	{
		recorder.stop();
		recorder = null;
		executor.shutdownNow();
		executor = null;

		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		store = null;
		resetRecording();

		SwingUtilities.invokeLater(() ->
		{
			if (viewer != null)
			{
				viewer.dispose();
				viewer = null;
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			if (pendingDeath != null)
			{
				finishDeath();
			}
			resetRecording();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		current.getHits().add(new HitRecord(
			event.getHitsplat().getAmount(),
			HitKind.fromHitsplatType(event.getHitsplat().getHitsplatType())));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getMenuOption());
		if (!"Eat".equalsIgnoreCase(option) && !"Drink".equalsIgnoreCase(option))
		{
			return;
		}

		int itemId = event.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		String name = Names.stripDose(client.getItemDefinition(itemId).getName());
		pendingConsumes.add(new PendingConsume(itemId, name, inventoryCount(itemId), client.getTickCount()));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV || pendingConsumes.isEmpty())
		{
			return;
		}

		// Only count an eat/drink click once the item actually leaves the inventory,
		// since clicks during the eat delay do nothing.
		Iterator<PendingConsume> it = pendingConsumes.iterator();
		while (it.hasNext())
		{
			PendingConsume consume = it.next();
			if (count(event.getItemContainer(), consume.itemId) < consume.countBefore)
			{
				current.getConsumed().add(consume.name);
				it.remove();
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (event.getActor() != local || pendingDeath != null)
		{
			return;
		}

		deathTick = client.getTickCount();

		DeathRecord record = new DeathRecord();
		LocalDateTime now = LocalDateTime.now();
		record.setId(now.format(ID_FORMAT));
		record.setTimeMillis(System.currentTimeMillis());
		record.setWorld(client.getWorld());
		record.setDeathTick(deathTick);
		record.setGear(equippedGear());

		WorldPoint location = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		if (location != null)
		{
			record.setX(location.getX());
			record.setY(location.getY());
			record.setPlane(location.getPlane());
			record.setRegionId(location.getRegionID());
		}

		pendingDeath = record;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		int tick = client.getTickCount();
		current.setTick(tick);
		current.setTimeMillis(System.currentTimeMillis());
		current.setHp(client.getBoostedSkillLevel(Skill.HITPOINTS));
		current.setMaxHp(client.getRealSkillLevel(Skill.HITPOINTS));
		current.setPrayer(client.getBoostedSkillLevel(Skill.PRAYER));
		current.setMaxPrayer(client.getRealSkillLevel(Skill.PRAYER));
		current.setSpecialAttack(client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);
		current.setTarget(describe(local.getInteracting()));
		current.setPrayers(activePrayers());

		combatLog.add(current, windowTicks() + TICKS_AFTER_DEATH);
		current = new TickRecord();

		pendingConsumes.removeIf(c -> tick - c.tick > CONSUME_TIMEOUT_TICKS);

		if (pendingDeath != null && tick >= deathTick + TICKS_AFTER_DEATH)
		{
			finishDeath();
		}
	}

	@Provides
	DeathReplayConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeathReplayConfig.class);
	}

	private void finishDeath()
	{
		DeathRecord record = pendingDeath;
		pendingDeath = null;

		List<TickRecord> ticks = combatLog.snapshot();
		record.setTicks(ticks);
		record.setLastTarget(lastTarget(ticks));
		record.setSummary(DeathSummary.compute(ticks, record.getDeathTick()));
		deathTick = -1;

		if (config.chatRecap())
		{
			String message = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("Death Replay: ")
				.append(ChatColorType.NORMAL)
				.append(record.getSummary().headline() + ". Open the Death Replay panel to watch it back.")
				.build();
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
		}

		// video from one tick before the first saved tick
		long videoFrom = ticks.isEmpty()
			? record.getTimeMillis() - config.windowSeconds() * 1000L
			: ticks.get(0).getTimeMillis() - 600;
		boolean video = config.recordVideo();
		int keep = config.maxSaved();
		int keepVideos = config.videosToKeep();
		executor.execute(() -> saveDeath(record, video ? videoFrom : -1, keep, keepVideos));
	}

	/**
	 * Runs on the plugin executor, after any frames still being compressed.
	 *
	 * @param videoFrom earliest frame time to save, or -1 for no video
	 */
	private void saveDeath(DeathRecord record, long videoFrom, int keep, int keepVideos)
	{
		DeathStore store = this.store;
		DeathReplayPanel panel = this.panel;
		FrameRecorder recorder = this.recorder;
		if (store == null)
		{
			return;
		}

		if (videoFrom >= 0 && recorder != null && keepVideos > 0)
		{
			List<CapturedFrame> frames = recorder.framesSince(videoFrom);
			if (!frames.isEmpty())
			{
				try
				{
					record.setClip(store.writeClip(record.getId(), frames));
				}
				catch (IOException e)
				{
					log.warn("Unable to save video for death {}", record.getId(), e);
				}
			}
		}

		try
		{
			store.save(record);
			store.pruneClips(keepVideos);
			List<String> pruned = store.prune(keep);
			if (panel != null)
			{
				SwingUtilities.invokeLater(() ->
				{
					panel.addDeath(record);
					pruned.forEach(panel::removeDeath);
				});
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to save death {}", record.getId(), e);
		}
	}

	private void openReplay(DeathRecord record)
	{
		executor.execute(() ->
		{
			DeathStore store = this.store;
			if (store == null)
			{
				return;
			}

			ClipInfo clip = record.getClip();
			List<byte[]> frames = null;
			String message;
			if (clip == null)
			{
				message = "No video was recorded for this death";
			}
			else
			{
				message = "This video was deleted to save space (see \"Videos to keep\")";
				try
				{
					frames = store.readClip(clip);
				}
				catch (IOException e)
				{
					log.warn("Unable to read video for death {}", record.getId(), e);
					message = "Unable to read this video";
				}
			}

			List<byte[]> loaded = frames;
			String noVideo = message;
			Runnable openFile = loaded != null ? () -> LinkBrowser.open(store.clipPath(clip)) : null;
			SwingUtilities.invokeLater(() ->
			{
				if (viewer != null)
				{
					viewer.dispose();
				}
				viewer = new ReplayViewer(record, loaded, noVideo, openFile);
				viewer.setVisible(true);
			});
		});
	}

	private void openFolder()
	{
		DeathStore store = this.store;
		if (store != null)
		{
			LinkBrowser.open(store.directory());
		}
	}

	private void deleteDeath(DeathRecord record)
	{
		executor.execute(() ->
		{
			DeathStore store = this.store;
			if (store == null)
			{
				return;
			}

			try
			{
				store.delete(record.getId());
			}
			catch (IOException e)
			{
				log.warn("Unable to delete death {}", record.getId(), e);
			}
		});
	}

	private void resetRecording()
	{
		if (recorder != null)
		{
			recorder.clear();
		}
		combatLog.clear();
		pendingConsumes.clear();
		current = new TickRecord();
		pendingDeath = null;
		deathTick = -1;
	}

	private int windowTicks()
	{
		// round up so the full number of seconds is always covered
		return (config.windowSeconds() * 10 + 5) / 6;
	}

	private List<String> activePrayers()
	{
		List<String> prayers = new ArrayList<>();
		boolean inLms = client.getVarbitValue(VarbitID.BR_INGAME) != 0;
		boolean deadeye = !inLms && client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0;
		boolean vigour = !inLms && client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0;
		boolean zeal = inLms || client.getVarbitValue(VarbitID.PRAYER_ZEAL_UNLOCKED) != 0;

		for (Prayer prayer : Prayer.values())
		{
			if (client.getVarbitValue(prayer.getVarbit()) == 0)
			{
				continue;
			}

			// Upgraded prayers share a slot with the one they replace, matching
			// the core Prayer plugin's handling
			switch (prayer)
			{
				case EAGLE_EYE:
					if (deadeye)
					{
						continue;
					}
					break;
				case DEADEYE:
					if (!deadeye)
					{
						continue;
					}
					break;
				case MYSTIC_MIGHT:
					if (vigour)
					{
						continue;
					}
					break;
				case MYSTIC_VIGOUR:
					if (!vigour)
					{
						continue;
					}
					break;
				case PIETY:
					if (zeal)
					{
						continue;
					}
					break;
				case ZEAL:
					if (!zeal)
					{
						continue;
					}
					break;
				default:
					break;
			}

			prayers.add(Names.prayer(prayer));
		}
		return prayers;
	}

	private List<GearItem> equippedGear()
	{
		List<GearItem> gear = new ArrayList<>();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return gear;
		}

		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = worn.getItem(slot.getSlotIdx());
			if (item != null && item.getId() > 0)
			{
				String name = client.getItemDefinition(item.getId()).getName();
				gear.add(new GearItem(Names.titleCase(slot.name()), item.getId(), name));
			}
		}
		return gear;
	}

	private int inventoryCount(int itemId)
	{
		return count(client.getItemContainer(InventoryID.INV), itemId);
	}

	private static int count(ItemContainer container, int itemId)
	{
		if (container == null)
		{
			return 0;
		}

		int total = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	/**
	 * Describes the player's own target. This is the actor the player chose to
	 * interact with, not anything about who is attacking them.
	 */
	private static String describe(Actor actor)
	{
		if (actor == null || actor.getName() == null)
		{
			return null;
		}

		String name = Text.removeTags(actor.getName());
		int level = actor.getCombatLevel();
		if (actor instanceof NPC && level > 0)
		{
			return name + " (level " + level + ")";
		}
		return name;
	}

	private static String lastTarget(List<TickRecord> ticks)
	{
		for (int i = ticks.size() - 1; i >= 0; i--)
		{
			String target = ticks.get(i).getTarget();
			if (target != null)
			{
				return target;
			}
		}
		return null;
	}
}
