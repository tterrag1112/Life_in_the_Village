package tterrag1112.life_in_the_village.Gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reusable map renderer for village and kingdom maps.
 *
 * Terrain is baked once into a NativeImage, uploaded to a DynamicTexture,
 * and drawn as a single blit() call every frame — no per-pixel fill loop
 * during rendering.
 *
 * Overlays (buildings, borders, player marker) are drawn on top with
 * normal fill calls, which is cheap because there are only ~20–50 buildings.
 */
public class VillageMapRenderer {

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private static final int MAX_BLOCK_SPAN_VILLAGE  = 320;
    private static final int MAX_BLOCK_SPAN_KINGDOM  = 3000;
    private static final int MIN_BLOCK_SPAN          = 64;
    private static final int BOUNDS_PAD_VILLAGE      = 28;
    private static final int BOUNDS_PAD_KINGDOM      = 80;

    // Texture resolution — independent of display size.
    // 256×256 gives good detail without a huge bake cost.
    private static final int TEX_W = 256;
    private static final int TEX_H = 256;

    // -------------------------------------------------------------------------
    // Texture state
    // -------------------------------------------------------------------------

    private DynamicTexture texture;
    private Identifier      textureId;
    private boolean         textureReady = false;

    // -------------------------------------------------------------------------
    // World-space geometry
    // -------------------------------------------------------------------------

    private List<Village> villages;   // one for village map, all for kingdom map
    private int originX, originZ;
    private int blockSpanX, blockSpanZ;




    // Add a mode flag
    private boolean isKingdomMap = false;

    // -------------------------------------------------------------------------
    // Bake thread
    // -------------------------------------------------------------------------

    private Thread bakeThread;
    // Raw pixels produced by bake thread, consumed on render thread
    private volatile int[] pendingPixels;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start baking a single village map.
     * Safe to call on any thread.
     */
    public void startBakeKingdom(List<Village> villageList, int displayW, int displayH) {
        isKingdomMap = true;
        startBakeInternal(villageList, displayW, displayH,
                MAX_BLOCK_SPAN_KINGDOM, BOUNDS_PAD_KINGDOM);
    }

    public void startBake(Village village, int displayW, int displayH) {
        isKingdomMap = false;
        startBakeInternal(List.of(village), displayW, displayH,
                MAX_BLOCK_SPAN_VILLAGE, BOUNDS_PAD_VILLAGE);
    }

    public void startBakeMulti(List<Village> villageList, int displayW, int displayH) {
        isKingdomMap = false;
        startBakeInternal(villageList, displayW, displayH,
                MAX_BLOCK_SPAN_VILLAGE, BOUNDS_PAD_VILLAGE);
    }

    /**
     * Start baking a kingdom map covering all supplied villages.
     */
    private void startBakeInternal(List<Village> villageList,
                                   int displayW, int displayH,
                                   int maxSpan, int pad) {
        stopBake();
        releaseTexture();
        this.villages = List.copyOf(villageList);
        this.textureReady = false;
        this.pendingPixels = null;

        AABB bounds = computeBoundsForAll(villageList);
        if (bounds == null) return;

        int villageW = (int)(bounds.maxX - bounds.minX);
        int villageD = (int)(bounds.maxZ - bounds.minZ);

        blockSpanX = clamp(villageW + pad * 2, MIN_BLOCK_SPAN, maxSpan);
        blockSpanZ = clamp(villageD + pad * 2, MIN_BLOCK_SPAN, maxSpan);
        int span   = Math.max(blockSpanX, blockSpanZ);
        blockSpanX = span;
        blockSpanZ = span;

        double cx = (bounds.minX + bounds.maxX) / 2.0;
        double cz = (bounds.minZ + bounds.maxZ) / 2.0;
        originX = (int)(cx - blockSpanX / 2.0);
        originZ = (int)(cz - blockSpanZ / 2.0);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Level level = mc.level;

        // Capture for lambda
        final int ox = originX, oz = originZ;
        final int spanX = blockSpanX, spanZ = blockSpanZ;

        bakeThread = new Thread(() -> {
            int[] pixels = new int[TEX_W * TEX_H];

            for (int ty = 0; ty < TEX_H; ty++) {
                if (Thread.interrupted()) return;
                for (int tx = 0; tx < TEX_W; tx++) {
                    // Map texture pixel → world block
                    int wx = ox + (int)((tx + 0.5f) / TEX_W * spanX);
                    int wz = oz + (int)((ty + 0.5f) / TEX_H * spanZ);
                    int wy = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                    BlockPos pos = new BlockPos(wx, wy - 1, wz);
                    Block block = level.getBlockState(pos).getBlock();
                    int color = blockMapColor(block, level, pos);
                    float hf = Math.min(1f, (wy - 50) / 100f);
                    // NativeImage uses ABGR — swap R and B
                    pixels[ty * TEX_W + tx] = toABGR(shadedColor(color, hf));
                }
            }

            // Hand off to render thread — don't touch GL here
            pendingPixels = pixels;
        }, "village-map-bake");
        bakeThread.setDaemon(true);
        bakeThread.start();
    }

    /**
     * Must be called on the render thread each frame before draw().
     * Uploads pending pixels to the GPU texture if the bake just finished.
     */
    public void tick() {
        int[] pixels = pendingPixels;
        if (pixels == null) return;
        pendingPixels = null;

        // Create or reuse texture
        if (texture == null) {
            texture = new DynamicTexture(
                    Component.literal("village_map").toString(),
                    TEX_W, TEX_H, false);
            textureId = Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID,
                    "map/village_" + System.nanoTime());
            Minecraft.getInstance()
                    .getTextureManager()
                    .register(textureId, texture);
        }

        NativeImage img = texture.getPixels();
        if (img == null) return;

        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                img.setPixel(x, y, pixels[y * TEX_W + x]);
            }
        }
        texture.upload();
        textureReady = true;
    }

    public boolean isLoading() {
        return !textureReady && pendingPixels == null && bakeThread != null && bakeThread.isAlive();
    }

    /**
     * Draw the map into [mapX, mapY, mapX+mapW, mapY+mapH].
     * Call tick() first each frame.
     */
    public void draw(GuiGraphics g, int mapX, int mapY, int mapW, int mapH,
                     int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;

        if (isLoading()) {
            g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF1A1A1A);
            g.drawCenteredString(font, "Generating map...",
                    mapX + mapW / 2, mapY + mapH / 2 - 4, 0xFFFFAA);
            return;
        }

        if (!textureReady) {
            g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF222222);
            g.drawCenteredString(font, "Map unavailable",
                    mapX + mapW / 2, mapY + mapH / 2 - 4, 0xAAAAAA);
            return;
        }

        // Single blit — terrain drawn in one draw call
        g.blit(textureId, mapX, mapY, mapX + mapW, mapY + mapH, 0f, 1f, 0f, 1f);


        // --- Village borders ---
        if (villages != null) {
            for (Village v : villages) {
                AABB vb = computeBoundsForAll(List.of(v));
                if (vb == null) continue;
                int bsx = clampX(worldToScreenX(vb.minX, mapX, mapW), mapX, mapW);
                int bsy = clampY(worldToScreenZ(vb.minZ, mapY, mapH), mapY, mapH);
                int bex = clampX(worldToScreenX(vb.maxX, mapX, mapW), mapX, mapW);
                int bey = clampY(worldToScreenZ(vb.maxZ, mapY, mapH), mapY, mapH);
                drawDashedRect(g, bsx, bsy, bex, bey, 0xFFFFDD00, mapX, mapY, mapW, mapH);
            }
        }

// --- Foreign kingdom borders ---
        if (isKingdomMap && villages != null) {
            drawForeignBorders(g, mapX, mapY, mapW, mapH);
        }

// --- Trade routes ---
        if (isKingdomMap) {
            drawTradeRoutes(g, mapX, mapY, mapW, mapH);
        }



        // --- Building overlays ---
        Building hoveredBuilding = null;
        List<Building> allBuildings = Building.ClientBuildingCache.getBuildings();

        // --- Village name labels (kingdom map only) ---
        if (isKingdomMap && villages != null) {
            for (Village v : villages) {
                AABB vb = computeBoundsForAll(List.of(v));
                if (vb == null) continue;
                double cx = (vb.minX + vb.maxX) / 2.0;
                double cz = (vb.minZ + vb.maxZ) / 2.0;
                int lx = worldToScreenX(cx, mapX, mapW);
                int lz = worldToScreenZ(cz, mapY, mapH);
                if (lx < mapX || lx > mapX + mapW
                        || lz < mapY || lz > mapY + mapH) continue;
                String name = v.getName();
                int lw = font.width(name);
                // Shadow then text
                g.drawString(font, name, lx - lw / 2 + 1, lz + 1, 0xFF000000, false);
                g.drawString(font, name, lx - lw / 2,     lz,     0xFFFFFFCC, false);
            }
        }

        if (villages != null) {
            for (Village v : villages) {
                for (UUID id : v.getBuildingIds()) {
                    Optional<Building> opt = allBuildings.stream()
                            .filter(b -> b.getId().equals(id)).findFirst();
                    if (opt.isEmpty()) continue;
                    Building building = opt.get();

                    BlockPos bmin = building.getShape().getMin();
                    BlockPos bmax = building.getShape().getMax();

                    int sx = worldToScreenX(bmin.getX(), mapX, mapW);
                    int sy = worldToScreenZ(bmin.getZ(), mapY, mapH);
                    int ex = worldToScreenX(bmax.getX() + 1, mapX, mapW);
                    int ey = worldToScreenZ(bmax.getZ() + 1, mapY, mapH);

                    sx = Math.max(sx, mapX);   sy = Math.max(sy, mapY);
                    ex = Math.min(ex, mapX + mapW); ey = Math.min(ey, mapY + mapH);
                    if (ex <= sx || ey <= sy) continue;

                    int fill    = buildingFillColor(building.getType());
                    int outline = buildingOutlineColor(building.getType());

                    g.fill(sx, sy, ex, ey, fill);
                    g.fill(sx, sy, ex, sy + 1, outline);
                    g.fill(sx, ey - 1, ex, ey, outline);
                    g.fill(sx, sy, sx + 1, ey, outline);
                    g.fill(ex - 1, sy, ex, ey, outline);

                    if (ex - sx >= 16) {
                        String label = building.getName();
                        int lw = font.width(label);
                        int cx = (sx + ex) / 2, cy = (sy + ey) / 2;
                        if (lw < ex - sx - 2)
                            g.drawString(font, label, cx - lw / 2, cy - 4, 0xFFFFFF, true);
                    }

                    if (mouseX >= sx && mouseX <= ex && mouseY >= sy && mouseY <= ey)
                        hoveredBuilding = building;
                }
            }
        }

        // --- Player marker ---
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            BlockPos pp = mc.player.blockPosition();
            int psx = worldToScreenX(pp.getX(), mapX, mapW);
            int psz = worldToScreenZ(pp.getZ(), mapY, mapH);
            if (psx >= mapX && psx < mapX + mapW && psz >= mapY && psz < mapY + mapH) {
                g.fill(psx - 1, psz - 3, psx + 2, psz + 4, 0xFFFFFFFF);
                g.fill(psx - 3, psz - 1, psx + 4, psz + 2, 0xFFFFFFFF);
                g.fill(psx,     psz,     psx + 1, psz + 1, 0xFFFF0000);
            }
        }

        // --- Tooltip ---
        if (hoveredBuilding != null) {
            List<Component> lines = List.of(
                    Component.literal(hoveredBuilding.getName()),
                    Component.literal(formatType(hoveredBuilding.getType())
                            + "  Lv." + hoveredBuilding.getLevel()));
            List<ClientTooltipComponent> comps = lines.stream()
                    .map(Component::getVisualOrderText)
                    .map(ClientTooltipComponent::create)
                    .toList();
            g.renderTooltip(font, comps, mouseX, mouseY,
                    DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    public void stopBake() {
        if (bakeThread != null) {
            bakeThread.interrupt();
            bakeThread = null;
        }
        pendingPixels = null;
    }

    public void releaseTexture() {
        if (texture != null) {
            if (textureId != null)
                Minecraft.getInstance().getTextureManager().release(textureId);
            texture.close();
            texture   = null;
            textureId = null;
        }
        textureReady = false;
    }

    // Call this from onClose()
    public void close() {
        stopBake();
        releaseTexture();
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    public int worldToScreenX(double worldX, int mapX, int mapW) {
        return mapX + (int)((worldX - originX) / blockSpanX * mapW);
    }

    public int worldToScreenZ(double worldZ, int mapY, int mapH) {
        return mapY + (int)((worldZ - originZ) / blockSpanZ * mapH);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static AABB computeBoundsForAll(List<Village> villageList) {
        List<Building> allBuildings = Building.ClientBuildingCache.getBuildings();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (Village v : villageList) {
            for (UUID id : v.getBuildingIds()) {
                Optional<Building> opt = allBuildings.stream()
                        .filter(b -> b.getId().equals(id)).findFirst();
                if (opt.isEmpty()) continue;
                Building b = opt.get();
                BlockPos mn = b.getShape().getMin(), mx = b.getShape().getMax();
                minX = Math.min(minX, mn.getX()); minZ = Math.min(minZ, mn.getZ());
                maxX = Math.max(maxX, mx.getX()); maxZ = Math.max(maxZ, mx.getZ());
                any = true;
            }
        }
        return any ? new AABB(minX, 0, minZ, maxX, 255, maxZ) : null;
    }

    private void drawDashedRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        drawDashedLine(g, x1, y1, x2, y1, true,  color);
        drawDashedLine(g, x1, y2, x2, y2, true,  color);
        drawDashedLine(g, x1, y1, x1, y2, false, color);
        drawDashedLine(g, x2, y1, x2, y2, false, color);
    }

    private void drawDashedLine(GuiGraphics g, int x1, int y1, int x2, int y2,
                                boolean horizontal, int color) {
        int on = 4, off = 2;
        if (horizontal) {
            for (int x = x1; x < x2; ) {
                int end = Math.min(x + on, x2);
                g.fill(x, y1, end, y1 + 1, color);
                x = end + off;
            }
        } else {
            for (int y = y1; y < y2; ) {
                int end = Math.min(y + on, y2);
                g.fill(x1, y, x1 + 1, end, color);
                y = end + off;
            }
        }
    }

    // ARGB → ABGR (NativeImage uses ABGR internally)
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    static int blockMapColor(Block block, Level level, BlockPos pos) {
        if (block == Blocks.GRASS_BLOCK)
            return level.getBiome(pos).value().getSpecialEffects()
                    .grassColorOverride().orElse(0x79C05A);
        if (block == Blocks.WATER || block == Blocks.KELP
                || block == Blocks.SEAGRASS)             return 0x3F76E4;
        if (block == Blocks.SAND || block == Blocks.SANDSTONE) return 0xDBD3A0;
        if (block == Blocks.RED_SAND
                || block == Blocks.RED_SANDSTONE)        return 0xBE6020;
        if (block == Blocks.GRAVEL)                      return 0x9E9E9E;
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE
                || block == Blocks.STONE_BRICKS)         return 0x7D7D7D;
        if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT)          return 0x8B6340;
        if (block == Blocks.OAK_LOG || block == Blocks.OAK_PLANKS
                || block == Blocks.OAK_LEAVES)           return 0x4A7A30;
        if (block == Blocks.BIRCH_LOG
                || block == Blocks.BIRCH_LEAVES)         return 0x6A9A50;
        if (block == Blocks.SPRUCE_LOG
                || block == Blocks.SPRUCE_LEAVES)        return 0x3A6020;
        if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK) return 0xF0F0F0;
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE)  return 0x9090E0;
        if (block == Blocks.LAVA)                        return 0xFF4500;
        if (block == Blocks.NETHERRACK)                  return 0x7D2020;
        if (block == Blocks.OBSIDIAN)                    return 0x1A0A2E;
        if (block == Blocks.MYCELIUM)                    return 0x7D5A7D;
        if (block == Blocks.PODZOL)                      return 0x5A3A1A;
        return 0x5A5A5A;
    }

    static int shadedColor(int color, float hf) {
        float shade = 0.55f + 0.45f * hf;
        int r = Math.min(255, (int)(((color >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int)(((color >>  8) & 0xFF) * shade));
        int b = Math.min(255, (int)( (color        & 0xFF) * shade));
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    static int buildingFillColor(BuildingType type) {
        return switch (type) {
            case TOWN_HALL   -> 0xCC2255CC;
            case GUILD_HALL  -> 0xCC9922CC;
            case INN         -> 0xCCCC7700;
            case CASTLE      -> 0xCC664488;
            case HOUSE       -> 0xCC228833;
            case BLACKSMITH  -> 0xCCCC3311;
            case MARKET      -> 0xCCCCBB00;
            case BARRACKS    -> 0xCC886622;
            case FARMHOUSE   -> 0xCC88AA22;
            case MINE        -> 0xCC554433;
            case GUARD_TOWER -> 0xCC775533;
            default          -> 0xCC888888;
        };
    }

    static int buildingOutlineColor(BuildingType type) {
        return switch (type) {
            case TOWN_HALL  -> 0xFFAABBFF;
            case GUILD_HALL -> 0xFFCCAAFF;
            case INN        -> 0xFFFFCC88;
            case CASTLE     -> 0xFFCCAADD;
            case HOUSE      -> 0xFF88DD99;
            default         -> 0xFFFFFFFF;
        };
    }

    static String formatType(BuildingType type) {
        String s = type.name();
        return s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // Clamp screen coords to the map rectangle
    private int clampX(int x, int mapX, int mapW) {
        return Math.max(mapX, Math.min(mapX + mapW, x));
    }
    private int clampY(int y, int mapY, int mapH) {
        return Math.max(mapY, Math.min(mapY + mapH, y));
    }

    // Overload drawDashedRect with map bounds clipping
    private void drawDashedRect(GuiGraphics g, int x1, int y1, int x2, int y2,
                                int color, int mapX, int mapY, int mapW, int mapH) {
        // Clamp each edge to the map rect before drawing
        int cx1 = Math.max(x1, mapX),     cy1 = Math.max(y1, mapY);
        int cx2 = Math.min(x2, mapX + mapW), cy2 = Math.min(y2, mapY + mapH);
        if (cx2 <= cx1 || cy2 <= cy1) return;
        drawDashedLine(g, cx1, cy1, cx2, cy1, true,  color); // top
        drawDashedLine(g, cx1, cy2, cx2, cy2, true,  color); // bottom
        drawDashedLine(g, cx1, cy1, cx1, cy2, false, color); // left
        drawDashedLine(g, cx2, cy1, cx2, cy2, false, color); // right
    }

    private void drawTradeRoutes(GuiGraphics g,
                                 int mapX, int mapY, int mapW, int mapH) {
        List<TradeRoute> routes = TradeRoute.ClientRouteCache.getRoutes();
        List<Village> allVillages = Building.ClientBuildingCache.getVillages();

        for (TradeRoute route : routes) {
            if (route.getStatus() != TradeRoute.RouteStatus.ACTIVE) continue;

            // Find center of each village
            Village vA = allVillages.stream()
                    .filter(v -> v.getId().equals(route.getVillageA()))
                    .findFirst().orElse(null);
            Village vB = allVillages.stream()
                    .filter(v -> v.getId().equals(route.getVillageB()))
                    .findFirst().orElse(null);
            if (vA == null || vB == null) continue;

            AABB bA = computeBoundsForAll(List.of(vA));
            AABB bB = computeBoundsForAll(List.of(vB));
            if (bA == null || bB == null) continue;

            int ax = worldToScreenX((bA.minX + bA.maxX) / 2.0, mapX, mapW);
            int az = worldToScreenZ((bA.minZ + bA.maxZ) / 2.0, mapY, mapH);
            int bx = worldToScreenX((bB.minX + bB.maxX) / 2.0, mapX, mapW);
            int bz = worldToScreenZ((bB.minZ + bB.maxZ) / 2.0, mapY, mapH);

            // Skip if both endpoints are outside the map
            boolean aInside = ax >= mapX && ax <= mapX + mapW
                    && az >= mapY && az <= mapY + mapH;
            boolean bInside = bx >= mapX && bx <= mapX + mapW
                    && bz >= mapY && bz <= mapY + mapH;
            if (!aInside && !bInside) continue;

            int lineColor = switch (route.getRouteType()) {
                case KINGDOM_INTERNAL -> 0xCC88DDFF; // light blue
                case CROSS_KINGDOM    -> 0xCCFFAA44; // amber
                case NEUTRAL          -> 0xCC888888; // grey
            };

            drawLine(g, ax, az, bx, bz, lineColor, mapX, mapY, mapW, mapH);
        }
    }

    private void drawForeignBorders(GuiGraphics g,
                                    int mapX, int mapY, int mapW, int mapH) {
        // Find which kingdom owns the villages on this map
        UUID homeKingdomId = null;
        if (!villages.isEmpty()) {
            homeKingdomId = Kingdom.ClientKingdomCache.getKingdoms().stream()
                    .filter(k -> k.getVillageIds().containsAll(
                            villages.stream().map(Village::getId).toList()))
                    .map(Kingdom::getId)
                    .findFirst().orElse(null);
        }

        // Draw borders for all other kingdoms that are visible in the map area
        for (Kingdom other : Kingdom.ClientKingdomCache.getKingdoms()) {
            if (homeKingdomId != null && other.getId().equals(homeKingdomId)) continue;

            // Determine border color from diplomatic relation
            int borderColor = 0xCCFF4444; // default: unknown/hostile red
            if (homeKingdomId != null) {
                Kingdom home = Kingdom.ClientKingdomCache.getById(homeKingdomId).orElse(null);
                if (home != null) {
                    DiplomaticRelation rel = home.getAllRelations()
                            .getOrDefault(other.getId(), null);
                    if (rel != null) {
                        borderColor = switch (rel) {
                            case ALLIANCE  -> 0xCC44FF88; // green
                            case TRADE     -> 0xCC44AAFF; // blue
                            case NEUTRAL   -> 0xCCAAAA44; // yellow
                            case COLD_WAR  -> 0xCCFF8844; // orange
                            case WAR       -> 0xCCFF2222; // red
                        };
                    }
                }
            }

            // Draw borders for each village in this foreign kingdom
            for (UUID vid : other.getVillageIds()) {
                Village fv = Building.ClientBuildingCache.getVillages().stream()
                        .filter(v -> v.getId().equals(vid))
                        .findFirst().orElse(null);
                if (fv == null) continue;
                AABB fb = computeBoundsForAll(List.of(fv));
                if (fb == null) continue;
                int bsx = worldToScreenX(fb.minX, mapX, mapW);
                int bsy = worldToScreenZ(fb.minZ, mapY, mapH);
                int bex = worldToScreenX(fb.maxX, mapX, mapW);
                int bey = worldToScreenZ(fb.maxZ, mapY, mapH);
                // Only draw if at least partially in view
                if (bex < mapX || bsx > mapX + mapW
                        || bey < mapY || bsy > mapY + mapH) continue;
                drawDashedRect(g, bsx, bsy, bex, bey, borderColor,
                        mapX, mapY, mapW, mapH);
            }
        }
    }

    // Bresenham line, clipped to map bounds
    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2,
                          int color, int mapX, int mapY, int mapW, int mapH) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int x = x1, y = y1;
        int maxLen = dx + dy + 1;
        for (int i = 0; i < maxLen; i++) {
            if (x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) {
                g.fill(x, y, x + 1, y + 1, color);
            }
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
            if (x == x2 && y == y2) break;
        }
    }
}