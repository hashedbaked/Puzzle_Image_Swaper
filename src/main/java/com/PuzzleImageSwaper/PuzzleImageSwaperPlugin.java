package com.PuzzleImageSwaper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PuzzleImageSwaperPlugin
 *
 * Description:
 * - Replace the Clue Scroll sliding puzzle tile art with a custom user-selected image (static or animated GIF).
 *
 * Key implementation details:
 * - The puzzle UI (Widget group 306) has two relevant layers:
 *   1) Interactive "logic" layer (children 0..24, type=4): provides stable bounds and click targets.
 *   2) Visual "model" layer (children 25..48, type=6): renders the actual in-game puzzle pieces.
 *
 * Why we use modelId:
 * - Varc "piece ids" (varcs 82..106). These can contain duplicates and cannot uniquely identify puzzle pieces.
 * - The model layer uses unique model IDs (observed 4156..4179) for the 24 puzzle pieces (blank is missing).
 * - Using model IDs gives a deterministic, image-independent mapping and avoids fragile pixel matching.
 *
 * Blank tile rule:
 * - Our custom image is split into a 5x5 grid (25 tiles).
 * - We reserve tileIndex 24 (bottom-right) as the blank and NEVER draw it.
 *
 * IMPORTANT NOTE ABOUT HARDCODED MODEL_ID_MIN/MAX:
 * - MODEL_ID_MIN=4156 and MODEL_ID_MAX=4179 are observed for the sliding puzzle models
 *   at the time of writing.
 * - If Jagex changes these model IDs (or the widget structure), the mapping will break and the overlay
 *   will stop drawing correctly.
 */
@Slf4j
@PluginDescriptor(
		name = "PuzzleImageSwaper"
)
public class PuzzleImageSwaperPlugin extends Plugin
{
	// Puzzle widget group + child containing puzzle pieces container.
	private static final int PUZZLE_GROUP_ID = 306;
	private static final int PIECES_CHILD_ID = 4;

	// Puzzle is always 5x5.
	private static final int PUZZLE_SIZE = 5;
	private static final int TILE_COUNT = PUZZLE_SIZE * PUZZLE_SIZE; // 25

	/**
	 * RuneLite widget layout:
	 * - The puzzle uses 50px grid steps in widget original coordinates.
	 * - Actual tile bounds are typically 49x49, but OriginalX/OriginalY still align to 0,50,100,150,200.
	 */
	private static final int WIDGET_GRID_STEP = 50;

	/**
	 * Target size for the custom image before splitting.
	 * We scale to a square so each of the 25 tiles has a consistent pixel size.
	 */
	private static final int TARGET_IMAGE_SIZE = 256;

	/** Safety bound to avoid decoding extremely large GIFs. */
	private static final int GIF_MAX_FRAMES = 200;

	/**
	 * Hardcoded model ID range for clue scroll sliding puzzle pieces.
	 *
	 * Observed:
	 * - 24 distinct model ids: 4156..4179.
	 * - The blank piece is represented by a missing model widget in the 5x5 grid (cell is "BLANK").
	 *
	 * Example observations of initial puzzle:
	 * [INFO] modelId grid (row-major):
	 * [INFO]  4157  4161  4158  4159  4160
	 * [INFO]  4163  4168  4164  4169 BLANK
	 * [INFO]  4171  4156  4166  4170  4165
	 * [INFO]  4162  4177  4173  4172  4175
	 * [INFO]  4167  4176  4178  4174  4179
	 * [INFO] Counts: inRange[4156..4179]=24, blankCells=1
	 *
	 * TODO: If these change in the future, update these constants or implement dynamic detection.
	 */
	private static final int MODEL_ID_MIN = 4156;
	private static final int MODEL_ID_MAX = 4179;

	/**
	 * We reserve the last image tile (bottom-right, tileIndex 24) to represent the blank.
	 * This ensures that when the puzzle is solved the visible 24 tiles form the correct image
	 * and the blank appears in the canonical bottom-right location.
	 */
	private static final int BLANK_TILE_INDEX = 24;
	private static final int TILE_COUNT_NO_BLANK = 24; // 0..23 are drawable

	@Inject private Client client;
	@Inject private PuzzleImageSwaperConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private PuzzleOverlay puzzleOverlay;

	@Inject private ConfigManager configManager;
	@Inject private ClientToolbar clientToolbar;

	private NavigationButton navButton;
	private PuzzleImageSwaperPanel panel;

	/**
	 * The current set of 25 image tiles used by the overlay.
	 * For GIFs, this will point at the current frame's split tiles.
	 */
	private BufferedImage[] splitTiles;

	/** GIF state (null when using static images). */
	private GifAnimationUtil.Animation gifAnimation;
	private long gifStartTimeMs = 0L;
	private int lastGifFrameIndex = -1;

	/**
	 * cellBounds maps cellIndex (row-major 0..24) -> screen bounds for the interactive grid.
	 * The overlay uses this to know where to draw each custom tile.
	 */
	private final Map<Integer, Rectangle> cellBounds = new HashMap<>();

	/**
	 * modelIdAtCell[cellIndex] is read from the model layer (type=6 widgets).
	 * - For the 24 non-blank pieces, it will be in [MODEL_ID_MIN..MODEL_ID_MAX].
	 * - For the blank cell, it remains -1.
	 */
	private final int[] modelIdAtCell = new int[TILE_COUNT];

	/**
	 * Exposes stable bounds to the overlay.
	 * (Overlay should never mutate this map; so it returns an unmodifiable view.)
	 */
	public Map<Integer, Rectangle> getCellBounds()
	{
		return Collections.unmodifiableMap(cellBounds);
	}

	/**
	 * Exposes the per-cell model IDs to the overlay.
	 */
	public int getModelIdAtCell(int cellIndex)
	{
		if (cellIndex < 0 || cellIndex >= TILE_COUNT)
		{
			return -1;
		}
		return modelIdAtCell[cellIndex];
	}

	/**
	 * Deterministic mapping:
	 * - modelId 4156..4179 -> tileIndex 0..23
	 * - tileIndex 24 is reserved as blank and never assigned
	 */
	public int mapModelIdToTileIndex(int modelId)
	{
		if (modelId < MODEL_ID_MIN || modelId > MODEL_ID_MAX)
		{
			return -1;
		}

		// 4156->0 ... 4179->23
		int idx = modelId - MODEL_ID_MIN;
		if (idx < 0 || idx >= TILE_COUNT_NO_BLANK)
		{
			return -1;
		}

		// Note: Intentionally not mapping anything to BLANK_TILE_INDEX (24)
		return idx;
	}

	@Override
	protected void startUp()
	{
		log.debug("PuzzleImageSwaper started!");

		// Register overlay so it can draw custom tiles above the puzzle widgets.
		overlayManager.add(puzzleOverlay);

		// Create custom panel + nav button for choosing an image file.
		panel = new PuzzleImageSwaperPanel(configManager);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/Icon_00.png");

		navButton = NavigationButton.builder()
				.tooltip("PuzzleImageSwaper")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		// Initialize model grid to blank state.
		Arrays.fill(modelIdAtCell, -1);

		// Load tiles from the currently configured path (if any).
		reloadUserImage();
	}

	@Override
	protected void shutDown()
	{
		log.debug("PuzzleImageSwaper stopped!");

		// Remove nav button
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;

		// Remove overlay
		overlayManager.remove(puzzleOverlay);

		// Clear tiles / animation state
		splitTiles = null;
		gifAnimation = null;
		puzzleOverlay.setTiles(null);

		// Clear puzzle state caches
		cellBounds.clear();
		Arrays.fill(modelIdAtCell, -1);
	}

	/**
	 * Reload image whenever plugin config changes (new file path, enable toggle, etc.)
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!"PuzzleImageSwaper".equals(e.getGroup()))
		{
			return;
		}

		reloadUserImage();

		// Keep panel label in sync with config
		if (panel != null)
		{
			panel.refreshCurrentPath();
		}
	}

	/**
	 * Load user's selected image path, scale it, split into a 5x5 tile array,
	 * and hand tiles to the overlay.
	 *
	 * Supports:
	 * - static images (png/jpg/jpeg)
	 * - animated gifs
	 */
	private void reloadUserImage()
	{
		String path = config.imagePath();
		if (path == null || path.trim().isEmpty())
		{
			log.debug("No imagePath set.");
			splitTiles = null;
			gifAnimation = null;
			puzzleOverlay.setTiles(null);
			return;
		}

		String normalizedPath = path.trim();
		String lower = normalizedPath.toLowerCase();

		try
		{
			File f = new File(normalizedPath);

			// Reset GIF state; will be repopulated if .gif
			gifAnimation = null;
			gifStartTimeMs = System.currentTimeMillis();
			lastGifFrameIndex = -1;

			if (lower.endsWith(".gif"))
			{
				// Decode GIF frames, scale each, split each into 25 tiles
				GifAnimationUtil.Animation anim = GifAnimationUtil.decodeGifToTiles(
						f,
						TARGET_IMAGE_SIZE,
						PUZZLE_SIZE,
						PUZZLE_SIZE,
						GIF_MAX_FRAMES
				);

				if (anim == null || anim.getFrameCount() == 0)
				{
					log.warn("GIF decoded but no frames");
					splitTiles = null;
					puzzleOverlay.setTiles(null);
					return;
				}

				gifAnimation = anim;

				// Start with frame 0
				splitTiles = anim.framesTiles[0];
				puzzleOverlay.setTiles(splitTiles);
			}
			else
			{
				// Static image
				BufferedImage loaded = ImageIO.read(f);
				if (loaded == null)
				{
					log.warn("ImageIO.read returned null for {}", f.getAbsolutePath());
					return;
				}

				BufferedImage scaled = GifAnimationUtil.scaleTo(loaded, TARGET_IMAGE_SIZE, TARGET_IMAGE_SIZE);
				splitTiles = GifAnimationUtil.splitImage(scaled, PUZZLE_SIZE, PUZZLE_SIZE);
				puzzleOverlay.setTiles(splitTiles);
			}
		}
		catch (Exception ex)
		{
			log.warn("Failed to read image from path '{}': {}", path, ex.toString());
		}
	}

	/**
	 * Main per-tick update:
	 * - advance GIF frame if needed
	 * - read puzzle widget structure
	 * - compute cell bounds (type=4 layer)
	 * - compute per-cell model IDs (type=6 layer)
	 * - hide default puzzle art when enabled so only our overlay is visible
	 */
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		// Update GIF frame
		if (gifAnimation != null)
		{
			advanceGifFrameIfNeeded();
		}

		Widget pieces = client.getWidget(PUZZLE_GROUP_ID, PIECES_CHILD_ID);
		if (pieces == null || pieces.getChildren() == null)
		{
			// Puzzle UI not present
			cellBounds.clear();
			Arrays.fill(modelIdAtCell, -1);
			return;
		}

		Widget[] children = pieces.getChildren();

		// (1) Bounds from interactive layer (type=4, indices 0..24)
		cellBounds.clear();
		buildCellBoundsFromType4(children);

		// (2) Model IDs from visual layer (type=6, indices 25..48)
		buildModelGridFromType6(children);

		// (3) Hide default visuals (only when overlay is enabled and tiles are loaded)
		if (config.enableCustomBackground() && splitTiles != null)
		{
			hideType6(children, true);       // hide default in-game models
			hideSpritesOnType4(children);    // clear any sprites on interactive layer
		}
		else
		{
			// If disabled, restore default visuals
			hideType6(children, false);
		}
	}

	/**
	 * Reads bounds for each cell by using the interactive widgets' OriginalX/OriginalY.
	 */
	private void buildCellBoundsFromType4(Widget[] children)
	{
		int max = Math.min(children.length, 25);
		for (int i = 0; i < max; i++)
		{
			Widget w = children[i];
			if (w == null)
			{
				continue;
			}

			Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}

			int col = w.getOriginalX() / WIDGET_GRID_STEP;
			int row = w.getOriginalY() / WIDGET_GRID_STEP;
			if (col < 0 || col >= PUZZLE_SIZE || row < 0 || row >= PUZZLE_SIZE)
			{
				continue;
			}

			int cellIndex = row * PUZZLE_SIZE + col;
			cellBounds.put(cellIndex, b);
		}
	}

	/**
	 * Builds modelIdAtCell[] using the model widgets.
	 * The blank is represented by the missing model cell (modelIdAtCell stays -1).
	 */
	private void buildModelGridFromType6(Widget[] children)
	{
		Arrays.fill(modelIdAtCell, -1);

		for (int i = 25; i < Math.min(children.length, 49); i++)
		{
			Widget w = children[i];
			if (w == null)
			{
				continue;
			}

			int col = w.getOriginalX() / WIDGET_GRID_STEP;
			int row = w.getOriginalY() / WIDGET_GRID_STEP;
			if (col < 0 || col >= PUZZLE_SIZE || row < 0 || row >= PUZZLE_SIZE)
			{
				continue;
			}

			int cellIndex = row * PUZZLE_SIZE + col;
			modelIdAtCell[cellIndex] = w.getModelId();
		}
	}

	/**
	 * Hide/unhide the default model widgets.
	 * When hidden, only our overlay remains visible.
	 */
	private void hideType6(Widget[] children, boolean hidden)
	{
		for (int i = 25; i < Math.min(49, children.length); i++)
		{
			Widget w = children[i];
			if (w != null)
			{
				w.setHidden(hidden);
			}
		}
	}

	/**
	 * Clear sprites on the interactive widgets.
	 * (These widgets are primarily click targets; sprite clearing prevents any stray art from showing.)
	 */
	private void hideSpritesOnType4(Widget[] children)
	{
		int max = Math.min(children.length, 25);
		for (int i = 0; i < max; i++)
		{
			Widget w = children[i];
			if (w != null)
			{
				w.setSpriteId(-1);
			}
		}
	}

	/**
	 * Advances animated GIF to the correct frame based on per-frame durations.
	 * Updates overlay tiles when the frame changes.
	 */
	private void advanceGifFrameIfNeeded()
	{
		GifAnimationUtil.Animation anim = gifAnimation;
		if (anim == null || anim.totalDurationMs <= 0)
		{
			return;
		}

		long now = System.currentTimeMillis();
		int frameIndex = GifAnimationUtil.computeFrameIndex(anim.frameDurationsMs, anim.totalDurationMs, gifStartTimeMs, now);

		if (frameIndex != lastGifFrameIndex)
		{
			lastGifFrameIndex = frameIndex;
			splitTiles = anim.framesTiles[frameIndex];
			puzzleOverlay.setTiles(splitTiles);
		}
	}

	@Provides
	PuzzleImageSwaperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PuzzleImageSwaperConfig.class);
	}
}