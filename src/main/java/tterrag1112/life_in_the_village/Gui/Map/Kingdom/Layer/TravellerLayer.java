package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.Village.Travel.TravellerSnapshot;
import tterrag1112.life_in_the_village.Village.Travel.TravellerType;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5e — moving-icon layer for travelling caravans (and any future
 * {@code TravellingGroup} surfaced as a {@link TravellerSnapshot}). Drawn
 * last, above routes and villages.
 *
 * <p>Travellers are simulated (no entity), so positions come from the
 * server progress synced once per map refresh; this layer interpolates
 * client-side — advancing each traveller's progress by elapsed real time
 * and indexing the already-synced route polyline (matched by village
 * pair, since the route id key is unreliable for graph routes). Icons
 * therefore drift slightly between syncs; the map's Refresh resyncs.
 *
 * <p>No per-tick work and no server→client position packet: the only
 * per-frame cost is index math per traveller.
 */
public final class TravellerLayer implements MapLayer {

    private static final int    ICON_HALF   = 3;
    private static final double MS_PER_TICK = 50.0; // 20 ticks/sec

    @Override public String id()    { return "travellers"; }
    @Override public String label() { return "Travellers"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data, MapProjection proj,
                     LayerState state, int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;
        if (data.travellers.isEmpty()) return;

        double elapsedTicks = Math.max(0.0,
                (System.currentTimeMillis() - data.buildTimeMs) / MS_PER_TICK);

        TravellerSnapshot hovered = null;
        for (TravellerSnapshot t : data.travellers) {
            KingdomMapData.RoutePath path = findRoute(data, t);
            if (path == null) continue;
            List<BlockPos> wps = path.waypoints();
            if (wps.size() < 2) continue;

            // Mirror TravellingGroupEngine.computePosition: progress → a
            // fraction along the path, advanced client-side for motion.
            double prog = clamp01(t.progress()
                    + elapsedTicks * TravellingGroupEngine.BASE_PROGRESS_PER_TICK
                    * Math.max(1.0, t.speed()));
            double fOrigin = t.reversed() ? (1.0 - prog) : prog;
            // The route polyline runs villageA→villageB; flip if the
            // traveller's origin is villageB.
            double fA = t.originVillageId().equals(path.villageA())
                    ? fOrigin : (1.0 - fOrigin);

            // Fractional waypoint index → smooth motion between cells.
            double f = fA * (wps.size() - 1);
            int i0 = Math.max(0, Math.min(wps.size() - 2, (int) Math.floor(f)));
            double frac = f - i0;
            BlockPos a = wps.get(i0), b = wps.get(i0 + 1);
            double bx = a.getX() + (b.getX() - a.getX()) * frac;
            double bz = a.getZ() + (b.getZ() - a.getZ()) * frac;

            int sx = Math.round(proj.blockToScreenX(bx));
            int sy = Math.round(proj.blockToScreenY(bz)); // Z → screen Y

            drawIcon(g, sx, sy, t.type());

            if (mouseX >= sx - ICON_HALF - 1 && mouseX <= sx + ICON_HALF + 1
                    && mouseY >= sy - ICON_HALF - 1 && mouseY <= sy + ICON_HALF + 1) {
                hovered = t;
            }
        }

        if (hovered != null) {
            var font = Minecraft.getInstance().font;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(typeLabel(hovered.type())));
            lines.add(Component.literal(hovered.originName() + " → " + hovered.destName()));
            lines.add(Component.literal("Cargo: " + hovered.cargo()));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    /** First route whose endpoint pair matches the traveller's (sea/land split). */
    private static KingdomMapData.RoutePath findRoute(KingdomMapData data, TravellerSnapshot t) {
        List<KingdomMapData.RoutePath> paths = t.isSea() ? data.seaRoutes : data.landRoutes;
        for (KingdomMapData.RoutePath p : paths) {
            if (matchesPair(p, t.originVillageId(), t.destVillageId())) return p;
        }
        return null;
    }

    private static boolean matchesPair(KingdomMapData.RoutePath p, UUID o, UUID d) {
        return (p.villageA().equals(o) && p.villageB().equals(d))
                || (p.villageA().equals(d) && p.villageB().equals(o));
    }

    private void drawIcon(GuiGraphics g, int x, int y, TravellerType type) {
        int color = iconColor(type);
        // Dark outline + coloured core (mirrors VillageLayer's marker style),
        // with a white pip so a small moving dot reads clearly on any terrain.
        g.fill(x - ICON_HALF - 1, y - ICON_HALF - 1, x + ICON_HALF + 1, y + ICON_HALF + 1,
                BookScreenColors.DARK);
        g.fill(x - ICON_HALF, y - ICON_HALF, x + ICON_HALF, y + ICON_HALF, color);
        g.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF);
    }

    private static int iconColor(TravellerType type) {
        return switch (type) {
            case CARAVAN_EXPORT      -> 0xFFE0B040; // amber — laden, outbound
            case CARAVAN_PROCUREMENT -> 0xFF40C0E0; // cyan  — buying run
            case BOAT                -> 0xFF60A0FF; // blue  — sea
            case PILGRIM             -> 0xFFC080F0; // violet — pilgrimage
            // Future traveller types until they get a bespoke icon.
            default                  -> 0xFFCCCCCC;
        };
    }

    private static String typeLabel(TravellerType type) {
        return switch (type) {
            case CARAVAN_EXPORT      -> "Export caravan";
            case CARAVAN_PROCUREMENT -> "Procurement caravan";
            case BOAT                -> "Boat caravan";
            case PILGRIM             -> "Pilgrim";
            default                  -> "Traveller";
        };
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
