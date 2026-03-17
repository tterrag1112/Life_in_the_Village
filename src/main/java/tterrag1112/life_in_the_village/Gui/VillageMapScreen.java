package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageMapScreen extends Screen {

    // Map display area in screen pixels
    private static final int MAP_SIZE = 200;
    private static final int PADDING = 10;

    // The village and its bounds
    private Village village;
    private AABB villageBounds;

    // Cached terrain colors — one int per block position in the map
    private int[] terrainColors;
    private int mapBlockWidth;
    private int mapBlockDepth;

    // Block origin of the map (min corner of village bounds)
    private int originX, originZ;
    private volatile boolean isLoading = true;
    private final Object colorLock = new Object();

    public VillageMapScreen() {
        super(Component.literal("Village Map"));
    }

    @Override
    protected void init() {
        super.init();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockPos playerPos = mc.player.blockPosition();
        Optional<Village> nearest = Building.ClientBuildingCache.getNearestVillage(playerPos);
        if (nearest.isEmpty()) return;

        village = nearest.get();
        villageBounds = computeBoundsForVillage(village);
        if (villageBounds == null) return;

        int pad = 16;
        originX = (int) villageBounds.minX - pad;
        originZ = (int) villageBounds.minZ - pad;
        mapBlockWidth = Math.max((int)(villageBounds.maxX - villageBounds.minX) + pad * 2, 64);
        mapBlockDepth = Math.max((int)(villageBounds.maxZ - villageBounds.minZ) + pad * 2, 64);

        // Capture level reference for the background thread
        Level level = mc.level;

        // Bake terrain on a background thread so the render thread isn't blocked
        Thread bakeThread = new Thread(() -> {
            int[] colors = new int[mapBlockWidth * mapBlockDepth];

            for (int z = 0; z < mapBlockDepth; z++) {
                for (int x = 0; x < mapBlockWidth; x++) {
                    int worldX = originX + x;
                    int worldZ = originZ + z;
                    int worldY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                    BlockPos pos = new BlockPos(worldX, worldY - 1, worldZ);
                    Block block = level.getBlockState(pos).getBlock();
                    int color = getBlockMapColor(block, level, pos);
                    float heightFactor = Math.min(1.0f, (worldY - 50) / 100.0f);
                    colors[z * mapBlockWidth + x] = shadedColor(color, heightFactor);
                }
            }

            // Write result safely and clear loading flag
            synchronized (colorLock) {
                terrainColors = colors;
                isLoading = false;
            }
        }, "village-map-bake");

        bakeThread.setDaemon(true);
        bakeThread.start();
    }

    /**
     * Samples the top block color at each position in the map area.
     * Uses biome-influenced tinting similar to vanilla map rendering.
     */
    private void bakeTerrainColors(Level level) {
        terrainColors = new int[mapBlockWidth * mapBlockDepth];

        for (int z = 0; z < mapBlockDepth; z++) {
            for (int x = 0; x < mapBlockWidth; x++) {
                int worldX = originX + x;
                int worldZ = originZ + z;

                // Find the surface block
                int worldY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                BlockPos pos = new BlockPos(worldX, worldY - 1, worldZ);
                Block block = level.getBlockState(pos).getBlock();

                // Get base color for this block
                int color = getBlockMapColor(block, level, pos);

                // Apply simple height shading — darker at lower Y
                float heightFactor = Math.min(1.0f, (worldY - 50) / 100.0f);
                color = shadedColor(color, heightFactor);

                terrainColors[z * mapBlockWidth + x] = color;
            }
        }
    }

    /**
     * Maps a block to an approximate top-down map color.
     * Covers the most common surface blocks.
     */
    private int getBlockMapColor(Block block, Level level, BlockPos pos) {
        if (block == Blocks.GRASS_BLOCK) {
            // Tint grass by biome
            int biomeGrass = level.getBiome(pos).value()
                    .getSpecialEffects().grassColorOverride()
                    .orElse(0x79C05A);
            return biomeGrass;
        }
        if (block == Blocks.WATER || block == Blocks.KELP || block == Blocks.SEAGRASS) return 0x3F76E4;
        if (block == Blocks.SAND || block == Blocks.SANDSTONE) return 0xDBD3A0;
        if (block == Blocks.GRAVEL) return 0x9E9E9E;
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE) return 0x7D7D7D;
        if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT) return 0x8B6340;
        if (block == Blocks.OAK_LOG || block == Blocks.OAK_PLANKS) return 0x9B7B4B;
        if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK) return 0xF0F0F0;
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE) return 0x9090E0;
        if (block == Blocks.LAVA) return 0xFF4500;
        if (block == Blocks.NETHERRACK) return 0x7D2020;
        if (block == Blocks.OBSIDIAN) return 0x1A0A2E;
        // Default fallback
        return 0x5A5A5A;
    }

    private int shadedColor(int color, float heightFactor) {
        float shade = 0.6f + 0.4f * heightFactor;
        int r = (int)(((color >> 16) & 0xFF) * shade);
        int g = (int)(((color >> 8) & 0xFF) * shade);
        int b = (int)((color & 0xFF) * shade);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0000000);

        if (village == null || villageBounds == null) {
            graphics.drawCenteredString(font, "No nearby village found", width / 2, height / 2, 0xFFFFFF);
            return;
        }

        if (isLoading) {
            graphics.drawCenteredString(font, "Generating map...", width / 2, height / 2, 0xFFFFAA);
            return;
        }

        int mapLeft = (width - MAP_SIZE) / 2;
        int mapTop = (height - MAP_SIZE) / 2;

        // Read terrain colors safely
        int[] colors;
        synchronized (colorLock) {
            colors = terrainColors;
        }

        drawTerrain(graphics, mapLeft, mapTop, colors);
        drawBuildingOverlays(graphics, mapLeft, mapTop);
        drawVillageBounds(graphics, mapLeft, mapTop);
        drawPlayerMarker(graphics, mapLeft, mapTop);
        graphics.drawCenteredString(font, village.getName(), width / 2, mapTop - 12, 0xFFFFAA);
        drawHoveredBuildingTooltip(graphics, mouseX, mouseY, mapLeft, mapTop);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTerrain(GuiGraphics graphics, int mapLeft, int mapTop, int[] colors) {
        float scaleX = (float) MAP_SIZE / mapBlockWidth;
        float scaleZ = (float) MAP_SIZE / mapBlockDepth;

        for (int z = 0; z < mapBlockDepth; z++) {
            for (int x = 0; x < mapBlockWidth; x++) {
                int color = colors[z * mapBlockWidth + x];
                int screenX = mapLeft + (int)(x * scaleX);
                int screenY = mapTop + (int)(z * scaleZ);
                int pixelW = Math.max(1, (int)scaleX);
                int pixelH = Math.max(1, (int)scaleZ);
                graphics.fill(screenX, screenY, screenX + pixelW, screenY + pixelH, color);
            }
        }
    }

    private void drawBuildingOverlays(GuiGraphics graphics, int mapLeft, int mapTop) {
        List<Building> buildings = Building.ClientBuildingCache.getBuildings();
        float scaleX = (float) MAP_SIZE / mapBlockWidth;
        float scaleZ = (float) MAP_SIZE / mapBlockDepth;

        for (UUID id : village.getBuildingIds()) {
            buildings.stream()
                    .filter(b -> b.getId().equals(id))
                    .findFirst()
                    .ifPresent(building -> {
                        BlockPos min = building.getShape().getMin();
                        BlockPos max = building.getShape().getMax();

                        int sx = mapLeft + (int)((min.getX() - originX) * scaleX);
                        int sy = mapTop + (int)((min.getZ() - originZ) * scaleZ);
                        int ex = mapLeft + (int)((max.getX() - originX + 1) * scaleX);
                        int ey = mapTop + (int)((max.getZ() - originZ + 1) * scaleZ);

                        // Semi-transparent fill by building type
                        int fillColor = getBuildingColor(building.getType());
                        graphics.fill(sx, sy, ex, ey, fillColor);

                        // Solid outline
                        int outlineColor = 0xFFFFFFFF;
                        graphics.fill(sx, sy, ex, sy + 1, outlineColor);
                        graphics.fill(sx, ey - 1, ex, ey, outlineColor);
                        graphics.fill(sx, sy, sx + 1, ey, outlineColor);
                        graphics.fill(ex - 1, sy, ex, ey, outlineColor);

                        // Building name label centered in the rectangle
                        int centerX = (sx + ex) / 2;
                        int centerY = (sy + ey) / 2;
                        String label = building.getName();
                        int labelWidth = font.width(label);
                        // Only draw label if it fits
                        if (labelWidth < (ex - sx) - 2) {
                            graphics.drawString(font, label, centerX - labelWidth / 2, centerY - 4, 0xFFFFFF, true);
                        }
                    });
        }
    }

    private void drawVillageBounds(GuiGraphics graphics, int mapLeft, int mapTop) {
        float scaleX = (float) MAP_SIZE / mapBlockWidth;
        float scaleZ = (float) MAP_SIZE / mapBlockDepth;

        int sx = mapLeft + (int)((villageBounds.minX - originX) * scaleX);
        int sy = mapTop + (int)((villageBounds.minZ - originZ) * scaleZ);
        int ex = mapLeft + (int)((villageBounds.maxX - originX) * scaleX);
        int ey = mapTop + (int)((villageBounds.maxZ - originZ) * scaleZ);

        int color = 0xAAFFFF00; // semi-transparent yellow
        graphics.fill(sx, sy, ex, sy + 1, color);
        graphics.fill(sx, ey - 1, ex, ey, color);
        graphics.fill(sx, sy, sx + 1, ey, color);
        graphics.fill(ex - 1, sy, ex, ey, color);
    }

    private void drawPlayerMarker(GuiGraphics graphics, int mapLeft, int mapTop) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        float scaleX = (float) MAP_SIZE / mapBlockWidth;
        float scaleZ = (float) MAP_SIZE / mapBlockDepth;

        BlockPos playerPos = mc.player.blockPosition();
        int px = mapLeft + (int)((playerPos.getX() - originX) * scaleX);
        int pz = mapTop + (int)((playerPos.getZ() - originZ) * scaleZ);

        // Draw a small white cross as the player marker
        graphics.fill(px - 1, pz - 3, px + 2, pz + 4, 0xFFFFFFFF);
        graphics.fill(px - 3, pz - 1, px + 4, pz + 2, 0xFFFFFFFF);
        // Red center
        graphics.fill(px, pz, px + 1, pz + 1, 0xFFFF0000);
    }

    private void drawHoveredBuildingTooltip(GuiGraphics graphics, int mouseX, int mouseY, int mapLeft, int mapTop) {
        float scaleX = (float) MAP_SIZE / mapBlockWidth;
        float scaleZ = (float) MAP_SIZE / mapBlockDepth;

        List<Building> buildings = Building.ClientBuildingCache.getBuildings();
        for (UUID id : village.getBuildingIds()) {
            buildings.stream()
                    .filter(b -> b.getId().equals(id))
                    .findFirst()
                    .ifPresent(building -> {
                        BlockPos min = building.getShape().getMin();
                        BlockPos max = building.getShape().getMax();

                        int sx = mapLeft + (int)((min.getX() - originX) * scaleX);
                        int sy = mapTop + (int)((min.getZ() - originZ) * scaleZ);
                        int ex = mapLeft + (int)((max.getX() - originX + 1) * scaleX);
                        int ey = mapTop + (int)((max.getZ() - originZ + 1) * scaleZ);

                        if (mouseX >= sx && mouseX <= ex && mouseY >= sy && mouseY <= ey) {
                            List<Component> tooltipLines = List.of(
                                    Component.literal(building.getName()),
                                    Component.literal("Type: " + building.getType().name()),
                                    Component.literal("Level: " + building.getLevel()),
                                    Component.literal("Origin: " + min.getX() + ", " + min.getY() + ", " + min.getZ())
                            );

                            List<ClientTooltipComponent> clientComponents = tooltipLines.stream()
                                    .map(Component::getVisualOrderText)
                                    .map(ClientTooltipComponent::create)
                                    .toList();

                            graphics.renderTooltip(
                                    font,
                                    clientComponents,
                                    mouseX,
                                    mouseY,
                                    DefaultTooltipPositioner.INSTANCE,
                                    null
                            );
                        }
                    });
        }
    }


    private int getBuildingColor(BuildingType type) {
        return switch (type) {
            case INN -> 0x55FF8800;        // orange
            case TOWN_HALL -> 0x550000FF;  // blue
            //case BLACKSMITH -> 0x55FF0000; // red
            case GUILD_HALL -> 0x55FF0000; // purple
            case CASTLE -> 0x55800080;
            default -> 0x55FFFFFF;         // white
        };
    }

    private AABB computeBoundsForVillage(Village village) {
        List<Building> allBuildings = Building.ClientBuildingCache.getBuildings();
        List<Building> villageBuildings = village.getBuildingIds().stream()
                .map(id -> allBuildings.stream()
                        .filter(b -> b.getId().equals(id))
                        .findFirst())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (villageBuildings.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Building b : villageBuildings) {
            BlockPos min = b.getShape().getMin();
            BlockPos max = b.getShape().getMax();
            minX = Math.min(minX, min.getX());
            minZ = Math.min(minZ, min.getZ());
            maxX = Math.max(maxX, max.getX());
            maxZ = Math.max(maxZ, max.getZ());
        }

        return new AABB(minX, 0, minZ, maxX, 255, maxZ);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private Thread bakeThread;

    // Store the thread reference and interrupt on close
    @Override
    public void onClose() {
        if (bakeThread != null) bakeThread.interrupt();
        super.onClose();
    }
}
