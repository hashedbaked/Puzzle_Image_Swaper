package com.PuzzleImageSwaper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * RuneLite plugin configuration interface.
 *
 * Notes:
 * - RuneLite stores config values by (group, keyName).
 * - This interface is used by ConfigManager to generate the config UI (checkboxes/text inputs).
 */
@ConfigGroup("PuzzleImageSwaper")
public interface PuzzleImageSwaperConfig extends Config
{
	/**
	 * Master enable/disable toggle.
	 * When false, the plugin will not hide original sprites and the overlay will draw nothing.
	 */
	@ConfigItem(
			keyName = "enableCustomBackground",
			name = "Enable Custom Puzzle Background",
			description = "Replace puzzle background image"
	)
	default boolean enableCustomBackground()
	{
		return true;
	}

	/**
	 * Full path to a local image file.
	 *
	 * Keep this as a String for maximum compatibility (no @Path annotation required).
	 * The "Choose image..." button in the plugin panel writes this value using ConfigManager.
	 */
	@ConfigItem(
			keyName = "imagePath",
			name = "Custom image path",
			description = "Full path to an image file (PNG/JPG). You can set this manually, or use the plugin panel button to choose a file."
	)
	default String imagePath()
	{
		return "";
	}
}