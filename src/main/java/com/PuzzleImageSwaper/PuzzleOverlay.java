package com.PuzzleImageSwaper;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * PuzzleOverlay
 * Draws the user-selected image tiles on top of the clue scroll sliding puzzle.
 * - Uses cell bounds from the interactive layer (type=4) so drawing position is stable.
 * - Uses model IDs from the visual layer (type=6) for deterministic tile identity.
 * - Reserves custom image tileIndex 24 as the blank. i.e:
 *   - Do not draw it anywhere.
 *   - The blank cell is painted with a solid color background for consistent visuals.
 */
public class PuzzleOverlay extends Overlay
{
    private static final int PUZZLE_SIZE = 5;
    private static final int TILE_COUNT = PUZZLE_SIZE * PUZZLE_SIZE; // 25

    // Tile index reserved as blank (bottom-right in a 5x5 image).
    private static final int BLANK_TILE_INDEX = 24;

    // Color overlay for empty tile
    // Simple blank fill color (wood-ish).
    private static final Color BLANK_COLOR = new Color(44, 30, 16);

    private final PuzzleImageSwaperConfig config;
    private final PuzzleImageSwaperPlugin plugin;

    // Current 25 tiles (static image or current GIF frame).
    private BufferedImage[] tiles;

    @Inject
    public PuzzleOverlay(Client client, PuzzleImageSwaperConfig config, PuzzleImageSwaperPlugin plugin)
    {
        this.config = config;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setTiles(BufferedImage[] tiles)
    {
        this.tiles = tiles;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Only draw when enabled full set of 25 tiles available.
        if (!config.enableCustomBackground() || tiles == null || tiles.length < TILE_COUNT)
        {
            return null;
        }

        // Bounds come from plugin each tick.
        Map<Integer, Rectangle> cellBounds = plugin.getCellBounds();
        if (cellBounds == null || cellBounds.isEmpty())
        {
            return null;
        }

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        for (int cellIndex = 0; cellIndex < TILE_COUNT; cellIndex++)
        {
            Rectangle b = cellBounds.get(cellIndex);
            if (b == null || b.width <= 0 || b.height <= 0 || b.x < 0 || b.y < 0)
            {
                continue;
            }

            // Use modelIdAtCell to determine which logical piece is currently in each board cell.
            // The blank cell has modelId < 0.
            int modelId = plugin.getModelIdAtCell(cellIndex);

            // Blank cell: paint background and skip drawing any image tile.
            if (modelId < 0)
            {
                graphics.setColor(BLANK_COLOR);
                graphics.fillRect(b.x, b.y, b.width, b.height);
                continue;
            }

            // Deterministic mapping to a tile index in our custom image (0..23).
            int tileIndex = plugin.mapModelIdToTileIndex(modelId);

            // Skip invalid mapping and explicitly never draw the reserved blank tile (24).
            if (tileIndex < 0 || tileIndex >= tiles.length || tileIndex == BLANK_TILE_INDEX)
            {
                continue;
            }

            graphics.drawImage(tiles[tileIndex], b.x, b.y, b.width, b.height, null);
        }

        return null;
    }
}