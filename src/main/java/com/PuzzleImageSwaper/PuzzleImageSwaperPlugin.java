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
 * Replaces clue puzzle visuals with a user image (static/gif), while preserving puzzle interaction.
 * Now supports per-puzzle model-id mappings via PuzzleProfileRegistry.
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
	 */
	private static final int WIDGET_GRID_STEP = 50;

	/**
	 * Target size for the custom image before splitting.
	 */
	private static final int TARGET_IMAGE_SIZE = 256;

	/** Safety bound to avoid decoding extremely large GIFs. */
	private static final int GIF_MAX_FRAMES = 200;

	/**
	 * We reserve the last image tile (bottom-right, tileIndex 24) as blank.
	 */
	private static final int BLANK_TILE_INDEX = 24;

	/** Log unknown puzzle profile at most once per cooldown period. */
	private static final long UNKNOWN_PROFILE_LOG_COOLDOWN_MS = 5000L;

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
	 * Active puzzle profile detected from current board model IDs.
	 * Null means "unknown / unsupported puzzle profile."
	 */
	private PuzzleProfile activeProfile;

	/** Last time we logged unknown profile warning (rate-limit spam). */
	private long lastUnknownProfileLogMs = 0L;

	/**
	 * cellBounds maps cellIndex (row-major 0..24) -> screen bounds for the interactive grid.
	 */
	private final Map<Integer, Rectangle> cellBounds = new HashMap<>();

	/**
	 * modelIdAtCell[cellIndex]:
	 * - non-blank piece => modelId >= 0
	 * - blank cell      => -1
	 */
	private final int[] modelIdAtCell = new int[TILE_COUNT];

	public Map<Integer, Rectangle> getCellBounds()
	{
		return Collections.unmodifiableMap(cellBounds);
	}

	public int getModelIdAtCell(int cellIndex)
	{
		if (cellIndex < 0 || cellIndex >= TILE_COUNT)
		{
			return -1;
		}
		return modelIdAtCell[cellIndex];
	}

	public String getActiveProfileName()
	{
		return activeProfile == null ? "UNKNOWN" : activeProfile.getName();
	}

	/**
	 * Profile-based deterministic mapping:
	 * modelId -> tileIndex 0..23; returns -1 if unknown.
	 */
	public int mapModelIdToTileIndex(int modelId)
	{
		if (activeProfile == null)
		{
			return -1;
		}
		return activeProfile.mapModelIdToTileIndex(modelId);
	}

	@Override
	protected void startUp()
	{
		log.debug("PuzzleImageSwaper started!");

		overlayManager.add(puzzleOverlay);

		panel = new PuzzleImageSwaperPanel(configManager);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/Icon_00.png");

		navButton = NavigationButton.builder()
				.tooltip("PuzzleImageSwaper")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		Arrays.fill(modelIdAtCell, -1);
		activeProfile = null;
		lastUnknownProfileLogMs = 0L;

		reloadUserImage();
	}

	@Override
	protected void shutDown()
	{
		log.debug("PuzzleImageSwaper stopped!");

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;

		overlayManager.remove(puzzleOverlay);

		splitTiles = null;
		gifAnimation = null;
		activeProfile = null;
		puzzleOverlay.setTiles(null);

		cellBounds.clear();
		Arrays.fill(modelIdAtCell, -1);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!"PuzzleImageSwaper".equals(e.getGroup()))
		{
			return;
		}

		reloadUserImage();

		if (panel != null)
		{
			panel.refreshCurrentPath();
		}
	}

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
			if (!f.exists() || !f.isFile() || !f.canRead())
			{
				log.warn("Configured image path is not readable: {}", f.getAbsolutePath());
				splitTiles = null;
				gifAnimation = null;
				puzzleOverlay.setTiles(null);
				return;
			}

			gifAnimation = null;
			gifStartTimeMs = System.currentTimeMillis();
			lastGifFrameIndex = -1;

			if (lower.endsWith(".gif"))
			{
				GifAnimationUtil.Animation anim = GifAnimationUtil.decodeGifToTiles(
						f, TARGET_IMAGE_SIZE, PUZZLE_SIZE, PUZZLE_SIZE, GIF_MAX_FRAMES
				);

				if (anim == null || anim.getFrameCount() == 0)
				{
					log.warn("GIF decoded but no frames: {}", f.getAbsolutePath());
					splitTiles = null;
					puzzleOverlay.setTiles(null);
					return;
				}

				gifAnimation = anim;
				splitTiles = anim.framesTiles[0];
				puzzleOverlay.setTiles(splitTiles);
			}
			else
			{
				BufferedImage loaded = ImageIO.read(f);
				if (loaded == null)
				{
					log.warn("ImageIO.read returned null for {}", f.getAbsolutePath());
					splitTiles = null;
					puzzleOverlay.setTiles(null);
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
			splitTiles = null;
			gifAnimation = null;
			puzzleOverlay.setTiles(null);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (gifAnimation != null)
		{
			advanceGifFrameIfNeeded();
		}

		Widget pieces = client.getWidget(PUZZLE_GROUP_ID, PIECES_CHILD_ID);
		if (pieces == null || pieces.getChildren() == null)
		{
			cellBounds.clear();
			Arrays.fill(modelIdAtCell, -1);
			activeProfile = null;
			return;
		}

		Widget[] children = pieces.getChildren();

		// (1) Bounds from interactive layer (type=4, indices 0..24)
		cellBounds.clear();
		buildCellBoundsFromType4(children);

		// (2) Model IDs from visual layer (type=6, indices 25..48)
		buildModelGridFromType6(children);

		// (3) Detect active puzzle profile
		PuzzleProfile detected = PuzzleProfileRegistry.detectProfile(modelIdAtCell);
		if (detected != activeProfile)
		{
			String oldName = activeProfile == null ? "UNKNOWN" : activeProfile.getName();
			String newName = detected == null ? "UNKNOWN" : detected.getName();
			log.info("Puzzle profile changed: {} -> {}", oldName, newName);
		}
		activeProfile = detected;

		if (activeProfile == null)
		{
			long now = System.currentTimeMillis();
			if (now - lastUnknownProfileLogMs > UNKNOWN_PROFILE_LOG_COOLDOWN_MS)
			{
				lastUnknownProfileLogMs = now;
				log.warn("Unknown puzzle profile detected; overlay mapping disabled for this board.");
			}
		}

		// (4) Hide default visuals only when fully ready
		if (config.enableCustomBackground() && splitTiles != null && activeProfile != null)
		{
			hideType6(children, true);
			hideSpritesOnType4(children);
		}
		else
		{
			hideType6(children, false);
		}
	}

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

	private void advanceGifFrameIfNeeded()
	{
		GifAnimationUtil.Animation anim = gifAnimation;
		if (anim == null || anim.totalDurationMs <= 0)
		{
			return;
		}

		long now = System.currentTimeMillis();
		int frameIndex = GifAnimationUtil.computeFrameIndex(
				anim.frameDurationsMs,
				anim.totalDurationMs,
				gifStartTimeMs,
				now
		);

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