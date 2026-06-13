package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer.*;
import tterrag1112.life_in_the_village.Networking.ContinentMapSyncPacket;
import tterrag1112.life_in_the_village.Networking.RequestContinentMapSyncPacket;

import java.util.*;

/**
 * Continent-scale map screen. Opened via keybind; seeds from a chosen
 * kingdom (the player's home or a selected one) and shows everything
 * on the connected landmass.
 *
 * <p>Layers are composed inline rather than via {@link KingdomMapPanel}
 * because the territory layer is fundamentally different (per-kingdom
 * tints instead of focus/foreign). All other layers are reused
 * unchanged.
 */
public class ContinentMapScreen extends Screen {

    private static final int SIDEBAR_W = 140;
    private static final int PAD = 10;

    private final UUID seedKingdomId;
    private ContinentMapData data;
    private MapProjection projection;
    private String statusLine = "Requesting continent data...";
    private boolean prefillComplete = true;

    // Reuse existing layers (they work on KingdomMapData, so we adapt)
    private final MapLayer.LayerState layerState = new MapLayer.LayerState();

    public ContinentMapScreen(UUID seedKingdomId) {
        super(Component.literal("Continent Map"));
        this.seedKingdomId = seedKingdomId;
    }

    public UUID getSeedKingdomId() { return seedKingdomId; }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(
                Component.literal("Refresh"),
                b -> requestSync()
        ).pos(PAD, PAD + 20).size(120, 16).build());
        requestSync();
    }

    private void requestSync() {
        statusLine = "Requesting continent data...";
        var req = RequestContinentMapSyncPacket.forLocalPlayer(seedKingdomId);
        if (req != null) ClientPacketDistributor.sendToServer(req);
    }

    /** Called by {@link ContinentMapSyncPacket#handle} after caches populate. */
    public void onContinentSynced(List<Long> continentCellsList,
                                  List<tterrag1112.life_in_the_village.Gui.Map.Kingdom
                                          .ContinentMapScope.ForeignClaimSnapshot> claims,
                                  boolean prefillComplete) {
        Set<Long> continentCells = new LinkedHashSet<>(continentCellsList);
        List<Pair<UUID, List<Long>>> pairs = new ArrayList<>();
        for (var claim : claims) {
            pairs.add(Pair.of(claim.kingdomId(), claim.cellKeys()));
        }
        data = ContinentMapDataBuilder.build(
                seedKingdomId, continentCells, List.of(), pairs).orElse(null);
        projection = null;
        this.prefillComplete = prefillComplete;
        statusLine = data == null
                ? "Loading terrain..."
                : (prefillComplete
                ? data.kingdoms.size() + " kingdom(s) in range, "
                + continentCells.size() + " cells"
                : "Partial: " + continentCells.size()
                + " cells so far — refresh to fill more");
        if (data != null && data.kingdoms.isEmpty()) {
            // Player-centered terrain still renders; just note the absence.
            statusLine = prefillComplete
                    ? "No kingdoms in range — " + continentCells.size() + " cells"
                    : statusLine;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        g.fill(0, 0, width, height, 0xCC000000);

        // Sidebar
        g.fill(0, 0, SIDEBAR_W, height, BookScreenColors.SIDEBAR);
        g.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, height, BookScreenColors.BORDER);
        g.drawString(font, "Continent Map",
                PAD, PAD, BookScreenColors.DARK, false);

        // Seed kingdom name + status
        if (data != null) {
            g.drawString(font, data.seedKingdomName,
                    PAD, PAD + 42, BookScreenColors.GOLD, false);
        }
        g.drawString(font, statusLine,
                PAD, height - 24, BookScreenColors.MID, false);

        // Map area
        int mapX = SIDEBAR_W + PAD;
        int mapY = PAD;
        int mapW = width - mapX - PAD;
        int mapH = height - PAD * 2;

        g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1,
                BookScreenColors.BORDER);
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH,
                BookScreenColors.OCEAN_DEEP);

        if (data == null) {
            // Terrain has not arrived yet (first pass / budget). The ocean
            // backdrop is already drawn above; show a transient note, never
            // a permanent "no data" state.
            String msg = statusLine;
            g.drawString(font, msg,
                    mapX + (mapW - font.width(msg)) / 2,
                    mapY + mapH / 2 - 4, BookScreenColors.LIGHT, false);
            super.render(g, mouseX, mouseY, pt);
            return;
        }

        if (projection == null) {
            projection = MapProjection.build(
                    data.minCellX, data.minCellZ,
                    data.maxCellX, data.maxCellZ,
                    mapX, mapY, mapW, mapH);
        }

        // Render layers. We adapt ContinentMapData → KingdomMapData for the
        // shared layers that only read villages/routes/terrain — all layer
        // reads are against the common fields, so we build a view adapter.
        KingdomMapData adapter = new KingdomMapData(
                data.seedKingdomId, data.seedKingdomName,
                data.minCellX, data.minCellZ, data.maxCellX, data.maxCellZ,
                data.terrainGrid,
                java.util.Collections.<Long>emptySet(),                              // focus cells
                java.util.Collections.<KingdomMapData.ForeignKingdom>emptyList(),    // foreign
                data.villages, data.landRoutes, data.seaRoutes,
                java.util.Collections.<KingdomMapData.ProvinceMarker>emptyList(),    // D3.3 provinces
                java.util.Collections.<tterrag1112.life_in_the_village.Village.Travel
                        .TravellerSnapshot>emptyList());                             // 5e — no travellers here

        new TerrainLayer().draw(g, adapter, projection, layerState, mouseX, mouseY);
        ContinentTerritoryLayer.draw(g, data, projection);
        new RouteLayer(false).draw(g, adapter, projection, layerState, mouseX, mouseY);
        new RouteLayer(true).draw(g, adapter, projection, layerState, mouseX, mouseY);
        new VillageLayer().draw(g, adapter, projection, layerState, mouseX, mouseY);
        new LabelLayer().draw(g, adapter, projection, layerState, mouseX, mouseY);



        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /**
     * Convenience opener — the map is player-centered, so it opens
     * anywhere regardless of kingdom association. The screen-identity
     * UUID prefers a kingdom the player rules (for the region label and
     * sync-reply matching), else any kingdom, else a synthetic key.
     */
    public static void openForPlayer() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new ContinentMapScreen(pickScreenKey(mc.player.getUUID())));
    }

    /**
     * Screen-identity UUID used only to match the sync reply and to label
     * the region; it does NOT scope the player-centered viewport. Falls
     * back to a fixed sentinel when the player rules/knows no kingdom, so
     * the map still opens for an unaffiliated player.
     */
    private static UUID pickScreenKey(UUID playerId) {
        var kingdoms = tterrag1112.life_in_the_village.Kingdom
                .Kingdom.ClientKingdomCache.getKingdoms();
        for (var k : kingdoms) {
            if (k.getRulerPlayerId().map(id -> id.equals(playerId)).orElse(false)) {
                return k.getId();
            }
        }
        return kingdoms.isEmpty() ? NO_KINGDOM_KEY : kingdoms.get(0).getId();
    }

    /** Sentinel screen-key when the player is unaffiliated (no kingdom). */
    private static final UUID NO_KINGDOM_KEY =
            new UUID(0L, 0x10AD_C0FFEEL); // arbitrary stable non-kingdom key
}