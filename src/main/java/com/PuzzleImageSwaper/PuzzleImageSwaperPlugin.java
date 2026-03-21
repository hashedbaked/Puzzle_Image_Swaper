package com.PuzzleImageSwaper;

import com.google.inject.Provides;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.widgets.Widget;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
		name = "PuzzleImageSwaper"
)
public class PuzzleImageSwaperPlugin extends Plugin
{
	private static final int PUZZLE_GROUP_ID = 306;
	private static final int GRID_BACKGROUND_CHILD_ID = 1;

	@Inject
	private Client client;

	@Inject
	private PuzzleImageSwaperConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PuzzleOverlay puzzleOverlay;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("PuzzleImageSwaper started!");
		overlayManager.add(puzzleOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("PuzzleImageSwaper stopped!");
		overlayManager.remove(puzzleOverlay);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(
					net.runelite.api.ChatMessageType.GAMEMESSAGE,
					"",
					"PuzzleImageSwaper says " + config.greeting(),
					null
			);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (!config.enableCustomBackground())
		{
			return;
		}

		Widget bg = client.getWidget(PUZZLE_GROUP_ID, GRID_BACKGROUND_CHILD_ID);

		if (bg != null)
		{
			// Remove original background rendering
			bg.setFilled(false);
		}
	}

	@Provides
	PuzzleImageSwaperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PuzzleImageSwaperConfig.class);
	}
}