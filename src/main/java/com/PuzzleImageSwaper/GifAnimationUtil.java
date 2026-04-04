package com.PuzzleImageSwaper;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * GIF + image helper utilities:
 * - Decode animated GIF frames and their per-frame delays
 * - Compute which frame index should be displayed at a given time
 * - Scale and split images into a grid (used by both GIFs and static images)
 */
@Slf4j
public final class GifAnimationUtil
{
    private GifAnimationUtil() {}

    /**
     * GIF metadata delayTime is in 1/100th seconds -> 10ms.
     */
    public static final int GIF_DELAY_UNIT_MS = 10;

    public static final int DEFAULT_GIF_FRAME_DELAY_MS = 100;

    /**
     * Decoded animation container.
     * framesTiles[i] contains the already-split tiles for that frame.
     */
    public static final class Animation
    {
        public final BufferedImage[][] framesTiles;
        public final int[] frameDurationsMs;
        public final int totalDurationMs;

        public Animation(BufferedImage[][] framesTiles, int[] frameDurationsMs, int totalDurationMs)
        {
            this.framesTiles = framesTiles;
            this.frameDurationsMs = frameDurationsMs;
            this.totalDurationMs = totalDurationMs;
        }

        public int getFrameCount()
        {
            return framesTiles.length;
        }
    }

    /**
     * Decode an animated GIF and return an Animation where each frame is:
     * - scaled to targetSize x targetSize
     * - split into rows x cols tiles
     */
    public static Animation decodeGifToTiles(File gifFile, int targetSize, int rows, int cols, int maxFrames) throws Exception
    {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext())
        {
            throw new IllegalStateException("No GIF ImageReader available in this JVM");
        }

        ImageReader reader = readers.next();

        try (ImageInputStream stream = ImageIO.createImageInputStream(gifFile))
        {
            if (stream == null)
            {
                throw new IllegalStateException("ImageIO.createImageInputStream returned null for " + gifFile.getAbsolutePath());
            }

            reader.setInput(stream, false);

            int reportedFrameCount = -1;
            try
            {
                reportedFrameCount = reader.getNumImages(true);
            }
            catch (Exception ignored)
            {
                // Some readers don't support this reliably; we'll read until failure.
            }

            List<BufferedImage[]> framesTiles = new ArrayList<>();
            List<Integer> frameDurations = new ArrayList<>();

            int i = 0;
            while (true)
            {
                if (reportedFrameCount != -1 && i >= reportedFrameCount)
                {
                    break;
                }
                if (i >= maxFrames)
                {
                    log.warn("GIF has more than {} frames; truncating at {} frames: {}", maxFrames, maxFrames, gifFile.getAbsolutePath());
                    break;
                }

                BufferedImage frame;
                IIOMetadata metadata;

                try
                {
                    frame = reader.read(i);
                    metadata = reader.getImageMetadata(i);
                }
                catch (IndexOutOfBoundsException out)
                {
                    break;
                }
                catch (Exception ex)
                {
                    // If first frame fails, it’s fatal; otherwise stop gracefully.
                    if (i == 0)
                    {
                        throw ex;
                    }
                    break;
                }

                if (frame == null)
                {
                    break;
                }

                int delayMs = extractGifDelayMs(metadata);
                if (delayMs <= 0)
                {
                    delayMs = DEFAULT_GIF_FRAME_DELAY_MS;
                }

                BufferedImage scaled = scaleTo(frame, targetSize, targetSize);
                BufferedImage[] tiles = splitImage(scaled, rows, cols);

                framesTiles.add(tiles);
                frameDurations.add(delayMs);

                i++;
            }

            if (framesTiles.isEmpty())
            {
                return new Animation(new BufferedImage[0][], new int[0], 0);
            }

            BufferedImage[][] ft = framesTiles.toArray(new BufferedImage[0][]);

            int[] durations = new int[frameDurations.size()];
            int total = 0;
            for (int idx = 0; idx < frameDurations.size(); idx++)
            {
                durations[idx] = frameDurations.get(idx);
                total += durations[idx];
            }

            if (total <= 0)
            {
                total = durations.length * DEFAULT_GIF_FRAME_DELAY_MS;
            }

            return new Animation(ft, durations, total);
        }
        finally
        {
            reader.dispose();
        }
    }

    /**
     * Compute the current frame index given per-frame durations and a looping total duration.
     */
    public static int computeFrameIndex(int[] frameDurationsMs, int totalDurationMs, long startTimeMs, long nowTimeMs)
    {
        if (frameDurationsMs == null || frameDurationsMs.length == 0)
        {
            return 0;
        }
        if (totalDurationMs <= 0)
        {
            return 0;
        }

        int elapsed = (int) ((nowTimeMs - startTimeMs) % totalDurationMs);
        if (elapsed < 0)
        {
            elapsed = 0;
        }

        int acc = 0;
        for (int i = 0; i < frameDurationsMs.length; i++)
        {
            acc += frameDurationsMs[i];
            if (elapsed < acc)
            {
                return i;
            }
        }

        return frameDurationsMs.length - 1;
    }

    /**
     * Extract per-frame delay from GIF metadata.
     *
     * Typical metadata tree:
     * - "javax_imageio_gif_image_1.0"
     *   - "GraphicControlExtension" with attribute "delayTime" (hundredths of seconds)
     */
    public static int extractGifDelayMs(IIOMetadata metadata)
    {
        if (metadata == null)
        {
            return 0;
        }

        try
        {
            String format = metadata.getNativeMetadataFormatName();
            if (format == null)
            {
                format = "javax_imageio_gif_image_1.0";
            }

            Node root = metadata.getAsTree(format);
            NodeList children = root.getChildNodes();

            for (int i = 0; i < children.getLength(); i++)
            {
                Node n = children.item(i);
                if (n == null)
                {
                    continue;
                }

                if ("GraphicControlExtension".equals(n.getNodeName()))
                {
                    NamedNodeMap attrs = n.getAttributes();
                    if (attrs == null)
                    {
                        continue;
                    }

                    Node delay = attrs.getNamedItem("delayTime");
                    if (delay != null)
                    {
                        int hundredths = Integer.parseInt(delay.getNodeValue());
                        return hundredths * GIF_DELAY_UNIT_MS;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // Fall back to default delay elsewhere
        }

        return 0;
    }

    /**
     * Scale an image to WxH using bilinear interpolation.
     */
    public static BufferedImage scaleTo(BufferedImage src, int w, int h)
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
     * Split an image into rows x cols equally-sized tiles.
     */
    public static BufferedImage[] splitImage(BufferedImage image, int rows, int cols)
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
}