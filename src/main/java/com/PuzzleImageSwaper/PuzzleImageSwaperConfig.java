package com.PuzzleImageSwaper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("PuzzleImageSwaper")
public interface PuzzleImageSwaperConfig extends Config
{
	@ConfigItem(
			keyName = "greeting",
			name = "Welcome Greeting",
			description = "The message to show to the user when they login"
	)
	default String greeting()
	{
		return "Hello";
	}

	@ConfigItem(
			keyName = "enableCustomBackground",
			name = "Enable Custom Puzzle Background",
			description = "Replace puzzle background image"
	)
	default boolean enableCustomBackground()
	{
		return true;
	}
}