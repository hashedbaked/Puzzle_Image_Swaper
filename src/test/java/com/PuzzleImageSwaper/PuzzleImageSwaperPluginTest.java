package com.PuzzleImageSwaper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PuzzleImageSwaperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PuzzleImageSwaperPlugin.class);
		RuneLite.main(args);
	}
}