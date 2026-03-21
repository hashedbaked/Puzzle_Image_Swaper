package com.PuzzleImageSwaper;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Overlay that draws our custom puzzle tiles on top of the puzzle widget tiles.
 *
 * Class Description:
 * - The plugin hides original widget sprites by setting spriteId = -1.
 * - This overlay draws image tiles in the exact widget bounds so it "replaces" the UI art.
 */
public class PuzzleOverlay extends Overlay
{
    private static final int GROUP_ID = 306;
    private static final int PIECES_CHILD_ID = 4;

    private final Client client;
    private final PuzzleImageSwaperConfig config;

    /**
     * Inject the plugin so we can query:
     * - the original spriteId for a widget (before it was hidden)
     * - the mapping from spriteId -> custom tile index
     */
    private final PuzzleImageSwaperPlugin plugin;

    /**
     * Custom image split into 49 pieces.
     * Set by the plugin when the image is loaded/reloaded.
     */
    private BufferedImage[] tiles;

    @Inject
    public PuzzleOverlay(Client client, PuzzleImageSwaperConfig config, PuzzleImageSwaperPlugin plugin)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;

        // DYNAMIC means it renders where we draw it (not fixed to a corner).
        setPosition(OverlayPosition.DYNAMIC);

        /**
         * ABOVE_WIDGETS:
         * - RuneLite draws game widgets first
         * - then it draws overlays above them
         *
         * Drawing ABOVE_WIDGETS makes us the visible image since it is possible to hide the original sprites.
         */
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    /**
     * Called by the plugin after loading/scaling/splitting an image.
     */
    public void setTiles(BufferedImage[] tiles)
    {
        this.tiles = tiles;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // If disabled or no tiles loaded, draw nothing.
        if (!config.enableCustomBackground() || tiles == null)
        {
            return null;
        }

        // Find the puzzle piece container widget.
        Widget pieces = client.getWidget(GROUP_ID, PIECES_CHILD_ID);
        if (pieces == null || pieces.getChildren() == null)
        {
            return null;
        }

        Widget[] children = pieces.getChildren();

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        // Draw a tile image for each puzzle piece widget.
        for (int i = 0; i < children.length; i++)
        {
            Widget tile = children[i];

            // Skip null/hidden widgets.
            if (tile == null || tile.isHidden())
            {
                continue;
            }

            // Bounds are the on-screen rectangle where we must draw.
            Rectangle bounds = tile.getBounds();
            if (bounds == null)
            {
                continue;
            }

            // Skip "empty slot" (often 0x0 bounds).
            if (bounds.width <= 0 || bounds.height <= 0)
            {
                continue;
            }

            /**
             * Determine which tile slice to draw.
             *
             * Scramble the same way as the default puzzle:
             * - Each physical piece has an identity (originalSpriteId)
             * - That identity stays with the piece as it moves around
             */
            Integer tileIndex = null;

            Integer originalSpriteId = plugin.getOriginalSpriteId(tile);
            if (originalSpriteId != null && originalSpriteId > 0)
            {
                tileIndex = plugin.getTileIndexForSpriteId(originalSpriteId);
            }

            /**
             * Fallback:
             * If we can't map identity, draw by child index so user sees something.
             */
            if (tileIndex == null)
            {
                tileIndex = i;
            }

            if (tileIndex < 0 || tileIndex >= tiles.length)
            {
                continue;
            }

            // Draw the tile image into this widget's bounds.
            graphics.drawImage(
                    tiles[tileIndex],
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    null
            );
        }

        return null;
    }
}