package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-player session for the /castle design workflow.
 *
 * Lifecycle:
 *   1. /castle design begin <type> <style>  — creates session, player sets pos1 with wand
 *   2. /castle design pos1                  — marks first corner at player feet
 *   3. /castle design pos2                  — marks second corner at player feet
 *   4. /castle design layer <relY> <trait>  — tags a Y slice (relative to pos1.Y)
 *   5. /castle design export                — saves NBT, clears session
 *   6. /castle design cancel                — clears session without saving
 */
public class DesignSession {

    // =========================================================================
    // Global session store
    // =========================================================================

    private static final ConcurrentHashMap<UUID, DesignSession> SESSIONS =
            new ConcurrentHashMap<>();

    public static DesignSession get(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    public static DesignSession begin(UUID playerId, DesignType type,
                                      String styleId) {
        DesignSession session = new DesignSession(type, styleId);
        SESSIONS.put(playerId, session);
        return session;
    }

    public static void clear(UUID playerId) {
        SESSIONS.remove(playerId);
    }

    // =========================================================================
    // Session state
    // =========================================================================

    public final DesignType type;
    public final String styleId;

    public BlockPos pos1 = null;  // min corner
    public BlockPos pos2 = null;  // max corner

    /**
     * Layer trait assignments: relativeY (from pos1.Y) → LayerTrait.
     * Stored in insertion order for display purposes.
     */
    public final Map<Integer, LayerTrait> layerTraits = new LinkedHashMap<>();

    private DesignSession(DesignType type, String styleId) {
        this.type    = type;
        this.styleId = styleId;
    }

    // =========================================================================
    // Validation
    // =========================================================================

    public boolean hasRegion() {
        return pos1 != null && pos2 != null;
    }

    public BlockPos min() {
        if (!hasRegion()) return pos1;
        return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()));
    }

    public BlockPos max() {
        if (!hasRegion()) return pos2;
        return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ()));
    }

    public int width()  { return max().getX() - min().getX() + 1; }
    public int height() { return max().getY() - min().getY() + 1; }
    public int depth()  { return max().getZ() - min().getZ() + 1; }

    /**
     * Converts an absolute world Y to a relative Y within the region.
     * Returns -1 if outside the region.
     */
    public int toRelativeY(int worldY) {
        if (!hasRegion()) return -1;
        int rel = worldY - min().getY();
        return (rel >= 0 && rel < height()) ? rel : -1;
    }

    /**
     * Summary string for feedback messages.
     */
    public String summary() {
        if (!hasRegion()) return String.format("[%s / %s] no region selected",
                type.name().toLowerCase(), styleId);
        return String.format("[%s / %s] %dx%dx%d at %s → %s, %d layer traits",
                type.name().toLowerCase(), styleId,
                width(), height(), depth(),
                min(), max(), layerTraits.size());
    }
    public String styleId() { return styleId; }

}