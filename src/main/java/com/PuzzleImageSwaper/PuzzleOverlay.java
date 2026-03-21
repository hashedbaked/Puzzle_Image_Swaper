package com.PuzzleImageSwaper;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class PuzzleOverlay extends Overlay
{
    private final Client client;
    private final PuzzleImageSwaperConfig config;

    private BufferedImage customImage;

    @Inject
    public PuzzleOverlay(Client client, PuzzleImageSwaperConfig config)
    {
        this.client = client;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);

        try
        {
            customImage = ImageIO.read(
                    getClass().getResourceAsStream("/frieren_256_256_px.jpg"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.enableCustomBackground())
        {
            return null;
        }

        Widget bg = client.getWidget(306, 1);

        if (bg == null)
        {
            return null;
        }

        Rectangle bounds = bg.getBounds();

        if (bounds == null)
        {
            return null;
        }

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        graphics.drawImage(
                customImage,
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                null
        );

        return null;
    }
}