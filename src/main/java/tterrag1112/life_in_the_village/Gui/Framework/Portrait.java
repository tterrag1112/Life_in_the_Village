package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

import javax.annotation.Nullable;

/**
 * NPC portrait widget. Renders a live rotating 3D bust of the NPC using
 * {@link InventoryScreen#renderEntityInInventoryFollowsAngle}. Falls back to initials
 * when no preview entity is available.
 *
 * <p>Rotation: one full Y-axis revolution every 160 ticks (8 s).
 *
 * <p>Signature used: {@code InventoryScreen.renderEntityInInventory(
 *     GuiGraphics, int cx, int cy, int scale,
 *     Vector3f translation, Quaternionf rotation,
 *     Quaternionf cameraOrientation, LivingEntity entity)}
 */
public final class Portrait {

    /**
     * Client-safe appearance snapshot derived from {@code AppearanceComponent}.
     *
     * @param name       display name (initials fallback)
     * @param isMale     biological sex, selects body model
     * @param age        controls scale via {@code TownspersonMob.setAge}
     * @param skinId     skin tone id
     * @param hairId     hair style id
     * @param hairColor  hair colour id
     * @param profession profession name string (matches {@code Profession.name()})
     * @param entity     pre-built client-side preview entity, or {@code null}
     */
    public record AppearanceSnapshot(
            String name,
            boolean isMale,
            int age,
            int skinId,
            int hairId,
            int hairColor,
            String profession,
            @Nullable TownspersonMob entity) {}

    private Portrait() {}

    /**
     * Draws a {@code size × size} portrait at (x, y).
     *
     * @param tick        current game tick (drives Y-axis rotation)
     * @param partialTick sub-tick interpolation fraction
     */
    public static void draw(GuiGraphics g, Font font,
                            int x, int y, int size,
                            @Nullable AppearanceSnapshot appearance,
                            long tick, float partialTick) {
        g.fill(x, y, x + size, y + size, BookScreenColors.SIDEBAR);
        g.renderOutline(x, y, size, size, BookScreenColors.BORDER);

        TownspersonMob entity = appearance != null ? appearance.entity() : null;
        if (entity == null) {
            drawFallback(g, font, x, y, size, appearance == null ? "" : appearance.name());
            return;
        }

        float yaw = ((tick + partialTick) * 360f / 160f) % 360f;
        entity.yBodyRot = yaw;
        entity.yHeadRot = yaw;
        //entity.yRot     = yaw;

        int cx = x + size / 2;
        int cy = y + size - 2;
        int scale = size / 2;

        g.enableScissor(x + 1, y + 1, x + size - 1, y + size - 1);
        try {
            float yawRad = (float) Math.toRadians(yaw);
            //Quaternionf rotation = new Quaternionf().rotateY(yawRad);
            InventoryScreen.renderEntityInInventoryFollowsAngle(g, x, cx, y, cy, scale, 0f, 0f, 0f, entity);
        } finally {
            g.disableScissor();
        }
    }

    private static void drawFallback(GuiGraphics g, Font font,
                                     int x, int y, int size, String name) {
        String initials = initials(name);
        int tw = font.width(initials);
        g.drawString(font, initials,
                x + (size - tw) / 2,
                y + (size - 8) / 2,
                BookScreenColors.DARK, false);
    }

    private static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0)));
            if (out.length() >= 2) break;
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
