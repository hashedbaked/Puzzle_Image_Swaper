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

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import java.util.*;

@Slf4j
@PluginDescriptor(
		name = "PuzzleImageSwaper"
)
public class PuzzleImageSwaperPlugin extends Plugin
{
	/**
	 * These constants identify the clue slide puzzle interface widgets.
	 * Group 306 = TrailSlidepuzzle.UNIVERSE
	 * Child 4  = TrailSlidepuzzle.PIECES (an array-ish container holding the tile widgets)
	 */
	private static final int PUZZLE_GROUP_ID = 306;
	private static final int PIECES_CHILD_ID = 4;

	@Inject
	private Client client;

	@Inject
	private PuzzleImageSwaperConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PuzzleOverlay puzzleOverlay;

	/**
	 * This is our custom image split into 7x7 tiles (49 total).
	 * Each entry is a BufferedImage that we draw over a puzzle piece widget.
	 */
	private BufferedImage[] splitTiles;

	/**
	 * IMPORTANT CONCEPT:
	 * We want each moving puzzle piece to "carry" its own custom image tile.
	 *
	 * To do that, we need a stable "identity" for each piece widget.
	 * The simplest identity to try is the widget's spriteId BEFORE we blank it out.
	 *
	 * When we hide sprites (tile.setSpriteId(-1)), the spriteId becomes useless.
	 * So we store the original spriteId per Widget instance the first time we see it.
	 *
	 * IdentityHashMap is used intentionally:
	 * - In Java, normal HashMap uses equals()/hashCode()
	 * - For Widgets, we don't want "equivalent" widgets; we want the exact object instance
	 * IdentityHashMap compares keys by reference (==), like pointer identity in C++.
	 */
	private final IdentityHashMap<Widget, Integer> originalSpriteIdByWidget = new IdentityHashMap<>();

	/**
	 * Maps original spriteId -> our custom tile index.
	 *
	 * Example:
	 *   spriteId 12345 -> tile index 0
	 *   spriteId 12346 -> tile index 1
	 *
	 * Then in the overlay, any widget with original spriteId 12346
	 * will always draw tiles[1], even after it moves.
	 */
	private final Map<Integer, Integer> spriteIdToTileIndex = new HashMap<>();

	/**
	 * We only want to build the mapping once per "puzzle open session".
	 * When the puzzle closes, we reset state so next time it opens we rebuild.
	 */
	private boolean mappingBuilt = false;

	/**
	 * Small debug helper: log tile properties once each time a puzzle opens,
	 * so we can confirm spriteId/itemId/text values look usable.
	 */
	private boolean debugLoggedThisOpen = false;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("PuzzleImageSwaper started!");

		/**
		 * Load your bundled resource image from inside the plugin jar.
		 * This is like loading a resource from your executable in C++.
		 */
		BufferedImage image = ImageIO.read(
				getClass().getResourceAsStream("/frieren_256_256_px.jpg"));

		// Split image into 7x7 = 49 small sub-images
		splitTiles = splitImage(image, 7, 7);

		// Give the overlay access to those sub-images
		puzzleOverlay.setTiles(splitTiles);

		// Enable the overlay
		overlayManager.add(puzzleOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("PuzzleImageSwaper stopped!");
		overlayManager.remove(puzzleOverlay);

		// Clean up our per-puzzle cached state
		resetPuzzleTracking();
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
		/**
		 * ClientTick happens very frequently (every client frame).
		 * We use it because widgets update / get recreated during play.
		 * In a perfect world we would hook a "widget opened" event,
		 * but polling is simple and good enough for this plugin.
		 */
		if (!config.enableCustomBackground())
		{
			return;
		}

		// Get the widget containing all puzzle piece widgets.
		Widget pieces = client.getWidget(PUZZLE_GROUP_ID, PIECES_CHILD_ID);

		/**
		 * If the puzzle isn't open, getWidget returns null.
		 * If the puzzle is opening but not fully built, children can be null.
		 * In either case, we reset so next time the puzzle opens we rebuild mapping cleanly.
		 */
		if (pieces == null || pieces.getChildren() == null)
		{
			resetPuzzleTracking();
			return;
		}

		Widget[] children = pieces.getChildren();

		/**
		 * Debug log once per puzzle open: show the first few tiles' properties.
		 * This lets us verify if spriteId is actually non-zero / unique.
		 */
		if (!debugLoggedThisOpen)
		{
			debugLoggedThisOpen = true;
			for (int i = 0; i < Math.min(children.length, 10); i++)
			{
				Widget t = children[i];
				if (t == null) continue;
				log.debug("tile[{}]: spriteId={} itemId={} text='{}' w={} h={}",
						i, t.getSpriteId(), t.getItemId(), t.getText(), t.getWidth(), t.getHeight());
			}
		}

		/**
		 * Step 1: capture the original spriteId for each widget instance.
		 * We do this BEFORE we call setSpriteId(-1).
		 */
		for (Widget tile : children)
		{
			if (tile == null)
			{
				continue;
			}

			/**
			 * putIfAbsent:
			 * - If this widget is already in the map, keep the original spriteId we stored earlier.
			 * - Otherwise store its current spriteId as the original.
			 */
			originalSpriteIdByWidget.putIfAbsent(tile, tile.getSpriteId());
		}

		/**
		 * Step 2: build a mapping from sprite IDs to our tile indexes.
		 * We only build once per puzzle open. If the puzzle closes, resetPuzzleTracking()
		 * will set mappingBuilt=false, and we'll rebuild next open.
		 */
		if (!mappingBuilt)
		{
			buildSpriteMapping(children);
			mappingBuilt = true;

			log.debug("Built spriteId mapping: {} entries", spriteIdToTileIndex.size());
		}

		/**
		 * Step 3: hide the game's original sprites so we only see our custom drawing.
		 * This does NOT remove the puzzle logic; it just hides the visuals.
		 */
		for (Widget tile : children)
		{
			if (tile == null)
			{
				continue;
			}

			// -1 means "no sprite"
			tile.setSpriteId(-1);
		}
	}

	private void buildSpriteMapping(Widget[] children)
	{
		spriteIdToTileIndex.clear();

		/**
		 * Collect all unique ORIGINAL sprite IDs we captured.
		 * We ignore <=0 because those usually mean "no sprite".
		 */
		List<Integer> spriteIds = new ArrayList<>();

		for (Widget tile : children)
		{
			if (tile == null)
			{
				continue;
			}

			Integer sidObj = originalSpriteIdByWidget.get(tile);
			if (sidObj == null)
			{
				continue;
			}

			int sid = sidObj;
			if (sid > 0 && !spriteIds.contains(sid))
			{
				spriteIds.add(sid);
			}
		}

		/**
		 * Sort to make the mapping deterministic.
		 * (If we don't sort, the order depends on discovery order and can vary.)
		 */
		Collections.sort(spriteIds);

		/**
		 * Map each spriteId to a tile index.
		 * If there are fewer sprite IDs than tiles, some tiles won't be used.
		 * If there are more sprite IDs, we only map up to splitTiles.length.
		 */
		for (int i = 0; i < spriteIds.size() && i < splitTiles.length; i++)
		{
			spriteIdToTileIndex.put(spriteIds.get(i), i);
		}

		log.debug("Unique original spriteIds found: {}", spriteIds.size());
	}

	private void resetPuzzleTracking()
	{
		/**
		 * Called when puzzle closes (or plugin stops).
		 * Clears all cached state so the next puzzle open will rebuild fresh.
		 */
		mappingBuilt = false;
		debugLoggedThisOpen = false;
		originalSpriteIdByWidget.clear();
		spriteIdToTileIndex.clear();
	}

	/**
	 * Called by the overlay to retrieve the spriteId this widget had before we blanked it.
	 * Returns null if we haven't captured it for this widget instance.
	 */
	public Integer getOriginalSpriteId(Widget tileWidget)
	{
		return originalSpriteIdByWidget.get(tileWidget);
	}

	/**
	 * Called by the overlay to map an original spriteId to a custom tile index.
	 * Returns null if we don't have a mapping for that spriteId.
	 */
	public Integer getTileIndexForSpriteId(int originalSpriteId)
	{
		return spriteIdToTileIndex.get(originalSpriteId);
	}

	private BufferedImage[] splitImage(BufferedImage image, int rows, int cols)
	{
		int tileWidth = image.getWidth() / cols;
		int tileHeight = image.getHeight() / rows;

		BufferedImage[] tiles = new BufferedImage[rows * cols];

		int index = 0;

		for (int y = 0; y < rows; y++)
		{
			for (int x = 0; x < cols; x++)
			{
				tiles[index++] = image.getSubimage(
						x * tileWidth,
						y * tileHeight,
						tileWidth,
						tileHeight
				);
			}
		}

		return tiles;
	}

	@Provides
	PuzzleImageSwaperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PuzzleImageSwaperConfig.class);
	}
}