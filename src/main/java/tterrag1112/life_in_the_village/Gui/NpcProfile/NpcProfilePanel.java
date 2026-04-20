package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

/**
 * A single content panel rendered in the right-hand page area of the NPC
 * profile screen. Each panel owns the full page rectangle; the screen calls
 * {@link #render} once per frame when the panel's section is active.
 */
public interface NpcProfilePanel {

    /** The page rectangle passed to every panel. */
    record PageArea(int x, int y, int w, int h) {}

    /**
     * Called once per frame while this panel is the active section.
     *
     * @param g         graphics context
     * @param font      the screen font
     * @param area      the page area to render into
     * @param snapshot  the current NPC data snapshot
     * @param mouseX    mouse X in screen coordinates
     * @param mouseY    mouse Y in screen coordinates
     */
    void render(GuiGraphics g, Font font,
                PageArea area, NpcProfileSnapshot snapshot,
                int mouseX, int mouseY);

    /**
     * Called when a fresh snapshot arrives while this panel is active.
     * Implementations may cache derived data here. Default: no-op.
     */
    default void onSnapshotUpdated(NpcProfileSnapshot snapshot) {}
}
