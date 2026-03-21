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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(
		name = "PuzzleImageSwaper"
)
public class PuzzleImageSwaperPlugin extends Plugin
{
	/**
	 * Clue scroll slide puzzle group id.
	 * The group/child IDs identify RuneLite widgets in the game UI.
	 * This affects all clue puzzles.
	 */
	private static final int PUZZLE_GROUP_ID = 306;

	/**
	 * Widget child that holds the individual tile widgets (the pieces).
	 */
	private static final int PIECES_CHILD_ID = 4;

	/**
	 * "Working size" for the custom image:
	 * - Scale the user image to 256x256
	 * - Then split into 7x7 tiles
	 *
	 * The overlay will then scale each tile to the widget bounds on-screen.
	 */
	private static final int TARGET_IMAGE_SIZE = 256;

	@Inject private Client client;
	@Inject private PuzzleImageSwaperConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private PuzzleOverlay puzzleOverlay;

	/**
	 * Used for:
	 * - reading config values
	 * - writing imagePath when user selects a file in the panel
	 */
	@Inject private ConfigManager configManager;

	/**
	 * Used to add a navigation button + plugin panel to the RuneLite left sidebar.
	 */
	@Inject private ClientToolbar clientToolbar;

	/**
	 * Sidebar navigation button and our custom panel.
	 * These are separate from the standard config panel.
	 */
	private NavigationButton navButton;
	private PuzzleImageSwaperPanel panel;

	/**
	 * Custom image split into 49 pieces.
	 * If null: no custom image is loaded, and the overlay draws nothing.
	 */
	private BufferedImage[] splitTiles;

	/**
	 * Map each tile widget instance -> sprite id that it originally had before replaced/hid it.
	 *
	 * IdentityHashMap is used because Widget does not necessarily implement stable equals/hashCode for identity usage.
	 */
	private final IdentityHashMap<Widget, Integer> originalSpriteIdByWidget = new IdentityHashMap<>();

	/**
	 * Map sprite id -> tile index (0..48).
	 *
	 * The core trick:
	 * - The puzzle pieces move around, but each piece keeps its original sprite id identity.
	 * - Use that sprite id to decide which custom image slice to draw for that piece.
	 */
	private final Map<Integer, Integer> spriteIdToTileIndex = new HashMap<>();

	private boolean mappingBuilt = false;
	private boolean debugLoggedThisOpen = false;

	@Override
	protected void startUp()
	{
		log.debug("PuzzleImageSwaper started!");

		// Register overlay to draw custom tile images above the UI widgets.
		overlayManager.add(puzzleOverlay);

		// Create our sidebar panel (contains "Choose image..." button).
		panel = new PuzzleImageSwaperPanel(configManager);

		/**
		 * Create a navigation button in RuneLite's left sidebar.
		 *
		 * Note:
		 * - This panel is NOT inside the config screen.
		 * - It appears as a new sidebar icon. Click it to open.
		 */
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/net/runelite/client/plugins/camera/icon.png");

		navButton = NavigationButton.builder()
				.tooltip("PuzzleImageSwaper")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		// Attempt to load any previously saved imagePath.
		reloadUserImage();
	}

	@Override
	protected void shutDown()
	{
		log.debug("PuzzleImageSwaper stopped!");

		// Remove navigation button/panel from the sidebar.
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;

		// Remove overlay from overlay manager.
		overlayManager.remove(puzzleOverlay);

		// Clear puzzle tracking to avoid stale widget references.
		resetPuzzleTracking();

		// Clear tiles from memory (and from overlay).
		splitTiles = null;
		puzzleOverlay.setTiles(null);
	}

	/**
	 * Fires whenever a config value in this group changes.
	 * Use it to reload the image when imagePath changes.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!"PuzzleImageSwaper".equals(e.getGroup()))
		{
			return;
		}

		reloadUserImage();

		// Keep panel UI label in sync (optional convenience)
		if (panel != null)
		{
			panel.refreshCurrentPath();
		}
	}

	/**
	 * Loads the user image from config.imagePath(), scales it to 256x256, splits into 7x7 tiles,
	 * then pushes those tiles into the overlay.
	 */
	private void reloadUserImage()
	{
		String path = config.imagePath();
		if (path == null || path.trim().isEmpty())
		{
			// No image set -> disable custom drawing (overlay will early-return).
			log.debug("No imagePath set; custom puzzle tiles disabled until an image is selected.");
			splitTiles = null;
			puzzleOverlay.setTiles(null);
			return;
		}

		try
		{
			// Load from local file system.
			File f = new File(path.trim());
			BufferedImage loaded = ImageIO.read(f);

			if (loaded == null)
			{
				log.warn("ImageIO.read returned null for file: {}", f.getAbsolutePath());
				return;
			}

			// Scale to a fixed working size so slicing is consistent.
			BufferedImage scaled = scaleTo(loaded, TARGET_IMAGE_SIZE, TARGET_IMAGE_SIZE);

			// Slice into 7x7.
			splitTiles = splitImage(scaled, 7, 7);

			// Hand to overlay; overlay will draw these onto the puzzle tile widgets.
			puzzleOverlay.setTiles(splitTiles);

			log.debug("Loaded image: original={}x{}, scaled={}x{}",
					loaded.getWidth(), loaded.getHeight(),
					scaled.getWidth(), scaled.getHeight());
		}
		catch (Exception ex)
		{
			log.warn("Failed to read image from path '{}': {}", path, ex.toString());
		}
	}

	/**
	 * Utility: scale an image to WxH.
	 * Use bilinear filtering for smoother results on photos.
	 */
	private static BufferedImage scaleTo(BufferedImage src, int w, int h)
	{
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dst.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(src, 0, 0, w, h, null);
		}
		finally
		{
			g.dispose();
		}
		return dst;
	}

	/**
	 * Called every client tick (~50 times/sec).
	 *
	 * While the puzzle is open:
	 *  1) capture original sprite ids
	 *  2) hide original sprites (set sprite id to -1)
	 *
	 * The overlay then draws custom tiles over the tile widgets.
	 */
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		// If disabled, do nothing (overlay also does nothing).
		if (!config.enableCustomBackground())
		{
			return;
		}

		// If no image loaded, do nothing.
		if (splitTiles == null)
		{
			return;
		}

		// Get the widget that contains all puzzle pieces.
		Widget pieces = client.getWidget(PUZZLE_GROUP_ID, PIECES_CHILD_ID);
		if (pieces == null || pieces.getChildren() == null)
		{
			// Puzzle UI closed, clear tracking so we rebuild next time it opens.
			resetPuzzleTracking();
			return;
		}

		Widget[] children = pieces.getChildren();

		// Debug logging once per puzzle open (optional).
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

		// Capture the original sprite id for each widget once per widget instance.
		for (Widget tile : children)
		{
			if (tile == null) continue;
			originalSpriteIdByWidget.putIfAbsent(tile, tile.getSpriteId());
		}

		// Build mapping from sprite ids to our custom tile indices, once per puzzle open.
		if (!mappingBuilt)
		{
			buildSpriteMapping(children);
			mappingBuilt = true;
		}

		// Hide the original sprites so the user only sees our overlay image.
		for (Widget tile : children)
		{
			if (tile == null) continue;
			tile.setSpriteId(-1);
		}
	}

	/**
	 * Build a stable spriteId -> tileIndex mapping.
	 *
	 * The simplest method used here:
	 * - collect unique sprite ids observed
	 * - sort them
	 * - assign in sorted order to tiles[0..48]
	 *
	 * Default puzzle pieces appear to have distinct sprite ids.
	 */
	private void buildSpriteMapping(Widget[] children)
	{
		spriteIdToTileIndex.clear();
		List<Integer> spriteIds = new ArrayList<>();

		for (Widget tile : children)
		{
			if (tile == null) continue;

			Integer sidObj = originalSpriteIdByWidget.get(tile);
			if (sidObj == null) continue;

			int sid = sidObj;
			if (sid > 0 && !spriteIds.contains(sid))
			{
				spriteIds.add(sid);
			}
		}

		Collections.sort(spriteIds);

		for (int i = 0; i < spriteIds.size() && splitTiles != null && i < splitTiles.length; i++)
		{
			spriteIdToTileIndex.put(spriteIds.get(i), i);
		}

		log.debug("Unique original spriteIds found: {}, mapped: {}", spriteIds.size(), spriteIdToTileIndex.size());
	}

	/**
	 * Clear mapping and captured widget state when the puzzle closes.
	 */
	private void resetPuzzleTracking()
	{
		mappingBuilt = false;
		debugLoggedThisOpen = false;
		originalSpriteIdByWidget.clear();
		spriteIdToTileIndex.clear();
	}

	/**
	 * Used by the overlay to retrieve the sprite id that this widget had before hiding.
	 */
	public Integer getOriginalSpriteId(Widget tileWidget)
	{
		return originalSpriteIdByWidget.get(tileWidget);
	}

	/**
	 * Used by the overlay to map an original sprite id to a custom tile index.
	 */
	public Integer getTileIndexForSpriteId(int originalSpriteId)
	{
		return spriteIdToTileIndex.get(originalSpriteId);
	}

	/**
	 * Split an image into rows x cols equally-sized sub-images.
	 * For the clue puzzle: 7x7 = 49.
	 */
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

	/**
	 * Standard RuneLite config provider method.
	 */
	@Provides
	PuzzleImageSwaperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PuzzleImageSwaperConfig.class);
	}
}