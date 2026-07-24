package com.PuzzleImageSwaper;

public final class PuzzleProfileImageKeys
{
    private PuzzleProfileImageKeys() {}

    public static final String CONFIG_GROUP = "PuzzleImageSwaper";
    public static final String KEY_PLUGIN_ENABLED = "pluginEnabled";
    public static final String KEY_USE_GLOBAL_IMAGE = "useGlobalImage";
    public static final String KEY_GLOBAL_IMAGE_PATH = "imagePath";

    public static String keyForProfile(String profileName)
    {
        if (profileName == null) return KEY_GLOBAL_IMAGE_PATH;

        switch (profileName.toUpperCase())
        {
            case "TREE": return "treeImagePath";
            case "TROLL": return "trollImagePath";
            case "CASTLE": return "castleImagePath";
            default: return KEY_GLOBAL_IMAGE_PATH; // fallback for unknown profiles
        }
    }
}