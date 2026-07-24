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
	@ConfigItem(
			keyName = "pluginEnabled",
			name = "Enable plugin",
			description = "Global on/off for PuzzleImageSwaper",
			hidden = true
	)
	default boolean pluginEnabled()
	{
		return true;
	}

	@ConfigItem(
			keyName = "useGlobalImage",
			name = "Use one global image",
			description = "If enabled, use global image for all puzzles; otherwise use per-puzzle images",
			hidden = true
	)
	default boolean useGlobalImage()
	{
		return true;
	}

	@ConfigItem(
			keyName = "imagePath",
			name = "Global image path",
			description = "Full path to image file (PNG/JPG/GIF) used for all puzzles when global mode is enabled",
			hidden = true
	)
	default String imagePath()
	{
		return "";
	}

	@ConfigItem(
			keyName = "treeImagePath",
			name = "Tree image path",
			description = "Image path for Tree puzzle",
			hidden = true
	)
	default String treeImagePath()
	{
		return "";
	}

	@ConfigItem(
			keyName = "trollImagePath",
			name = "Troll image path",
			description = "Image path for Troll puzzle",
			hidden = true
	)
	default String trollImagePath()
	{
		return "";
	}

	@ConfigItem(
			keyName = "castleImagePath",
			name = "Castle image path",
			description = "Image path for Castle puzzle",
			hidden = true
	)
	default String castleImagePath()
	{
		return "";
	}
}