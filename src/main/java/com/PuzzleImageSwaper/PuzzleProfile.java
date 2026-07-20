package com.PuzzleImageSwaper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Immutable profile describing one puzzle's model-id -> tile-index mapping.
 */
public final class PuzzleProfile
{
    private final String name;
    private final Map<Integer, Integer> modelIdToTileIndex;
    private final Set<Integer> modelIds;

    public PuzzleProfile(String name, Map<Integer, Integer> modelIdToTileIndex)
    {
        this.name = name;
        this.modelIdToTileIndex = Collections.unmodifiableMap(modelIdToTileIndex);
        this.modelIds = Collections.unmodifiableSet(modelIdToTileIndex.keySet());
    }

    public String getName()
    {
        return name;
    }

    public int mapModelIdToTileIndex(int modelId)
    {
        Integer idx = modelIdToTileIndex.get(modelId);
        return idx == null ? -1 : idx;
    }

    public boolean containsModelId(int modelId)
    {
        return modelIds.contains(modelId);
    }

    public int size()
    {
        return modelIdToTileIndex.size();
    }
}