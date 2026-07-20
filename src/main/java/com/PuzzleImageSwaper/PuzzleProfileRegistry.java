package com.PuzzleImageSwaper;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Registry of known puzzle profiles.
 * Start with TREE + TROLL, add more as discovered.
 */
@Slf4j
public final class PuzzleProfileRegistry
{
    private PuzzleProfileRegistry() {}

    public static final int TILE_COUNT_NO_BLANK = 24;

    private static final PuzzleProfile TREE = profileFromRange("TREE", 4156, 4179);
    private static final PuzzleProfile TROLL = profileFromRange("TROLL", 4180, 4203);

    private static final List<PuzzleProfile> PROFILES = Arrays.asList(
            TREE,
            TROLL
    );

    public static List<PuzzleProfile> all()
    {
        return PROFILES;
    }

    public static PuzzleProfile detectProfile(int[] modelIdAtCell)
    {
        if (modelIdAtCell == null || modelIdAtCell.length == 0)
        {
            return null;
        }

        Map<PuzzleProfile, Integer> hits = new HashMap<>();

        for (int modelId : modelIdAtCell)
        {
            if (modelId < 0) continue; // blank

            for (PuzzleProfile p : PROFILES)
            {
                if (p.containsModelId(modelId))
                {
                    hits.merge(p, 1, Integer::sum);
                }
            }
        }

        PuzzleProfile best = null;
        int bestHits = 0;
        for (Map.Entry<PuzzleProfile, Integer> e : hits.entrySet())
        {
            if (e.getValue() > bestHits)
            {
                best = e.getKey();
                bestHits = e.getValue();
            }
        }

        // Require all visible pieces to belong to one known profile.
        // Typical solved/active board has 24 visible model widgets.
        if (best != null && bestHits >= TILE_COUNT_NO_BLANK)
        {
            return best;
        }

        return null;
    }

    private static PuzzleProfile profileFromRange(String name, int minInclusive, int maxInclusive)
    {
        Map<Integer, Integer> map = new HashMap<>();
        int idx = 0;
        for (int modelId = minInclusive; modelId <= maxInclusive; modelId++)
        {
            map.put(modelId, idx++);
        }

        if (map.size() != TILE_COUNT_NO_BLANK)
        {
            log.warn("Profile {} has {} ids, expected {}", name, map.size(), TILE_COUNT_NO_BLANK);
        }

        return new PuzzleProfile(name, map);
    }
}