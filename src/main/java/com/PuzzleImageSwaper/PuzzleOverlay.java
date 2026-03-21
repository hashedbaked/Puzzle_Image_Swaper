package com.PuzzleImageSwaper;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

public class PuzzleOverlay extends Overlay
{
    private static final int GROUP_ID = 306;
    private static final int PIECES_CHILD_ID = 4;

    private final Client client;
    private final PuzzleImageSwaperConfig config;

    /**
     * We inject the plugin so we can query:
     * - what the original spriteId was for a widget (before we hid it)
     * - which custom tile index corresponds to that spriteId
     */
    private final PuzzleImageSwaperPlugin plugin;

    /**
     * The custom image split into 49 pieces.
     * The plugin sets this once on startup.
     */
    private BufferedImage[] tiles;

    @Inject
    public PuzzleOverlay(Client client, PuzzleImageSwaperConfig config, PuzzleImageSwaperPlugin plugin)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);

        /**
         * ABOVE_WIDGETS means:
         * - RuneLite draws the game UI widgets first
         * - Then it draws this overlay on top
         *
         * We hide the original sprite of each puzzle tile, then draw our custom tile image here.
         */
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setTiles(BufferedImage[] tiles)
    {
        this.tiles = tiles;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // If the feature is disabled or tiles aren't loaded, do nothing.
        if (!config.enableCustomBackground() || tiles == null)
        {
            return null;
        }

        // Find the widget that contains all puzzle tile widgets
        Widget pieces = client.getWidget(GROUP_ID, PIECES_CHILD_ID);
        if (pieces == null || pieces.getChildren() == null)
        {
            return null;
        }

        Widget[] children = pieces.getChildren();

        /**
         * Nearest neighbor prevents blurring when scaling pixel-art-ish images.
         * (Similar to point sampling vs linear filtering in graphics APIs.)
         */
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        /**
         * Loop over each tile widget.
         * Each widget's bounds tell us WHERE on screen to draw.
         */
        for (int i = 0; i < children.length; i++)
        {
            Widget tile = children[i];

            // Skip null widgets or hidden widgets
            if (tile == null || tile.isHidden())
            {
                continue;
            }

            Rectangle bounds = tile.getBounds();
            if (bounds == null)
            {
                continue;
            }

            /**
             * The "empty" puzzle space is usually represented by a 0x0 widget.
             * We don't want to draw anything there.
             */
            if (tile.getWidth() == 0 || tile.getHeight() == 0)
            {
                continue;
            }

            /**
             * We want the image to be SCRAMBLED like the default puzzle.
             *
             * That means we do NOT pick a tile slice based on board cell position.
             * Instead, each moving piece has an identity, and keeps the same slice wherever it moves.
             *
             * We use "originalSpriteId" as that identity.
             */
            Integer tileIndex = null;

            Integer originalSpriteId = plugin.getOriginalSpriteId(tile);

            if (originalSpriteId != null && originalSpriteId > 0)
            {
                tileIndex = plugin.getTileIndexForSpriteId(originalSpriteId);
            }

            /**
             * IMPORTANT FALLBACK:
             * If mapping isn't available (for example, spriteId wasn't unique or wasn't captured),
             * draw by array index so the user still sees something.
             *
             * This is mainly a safety net + debugging aid.
             */
            if (tileIndex == null)
            {
                tileIndex = i;
            }

            if (tileIndex < 0 || tileIndex >= tiles.length)
            {
                continue;
            }

            // Finally draw the correct custom image slice at this widget's screen rectangle.
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