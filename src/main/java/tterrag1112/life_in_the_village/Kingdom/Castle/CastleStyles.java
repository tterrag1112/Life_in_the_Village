package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.resources.Identifier;

/**
 * Built-in CastleStyle instances.
 *
 * These provide ready-to-use styles and serve as living documentation
 * for the expected structure file layout.
 *
 * Each style references pools of structure templates stored at:
 *   assets/yourmod/structures/castle/<category>/<style>/<variant>.nbt
 *
 * ============================================================
 * STRUCTURE FILE MANIFEST
 * ============================================================
 *
 * Every .nbt file listed below must be created in your data pack.
 * Pool key format: yourmod:castle/<category>/<style>
 * Template variant format: yourmod:castle/<category>/<style>_<n>
 *
 * The DetailApplicator tries _0, _1, _2, _3 suffixes automatically,
 * so create as many variants as you want and they will be randomly chosen.
 *
 * ── BATTLEMENTS ─────────────────────────────────────────────
 * Dimensions: 3 wide (X) × 2 tall (Y) × 2 deep (Z)
 * The template covers ONE merlon + ONE crenel slot (3 blocks wide = 1 unit).
 * Place your template flat on the Y=0 plane; the applicator stamps it at
 * the top course of each wall with the correct rotation.
 *
 *   Pool key:   yourmod:castle/battlements/norman
 *   Templates:
 *     yourmod:castle/battlements/norman.nbt   — square merlons, standard height
 *     yourmod:castle/battlements/norman_1.nbt   — square merlons with slab cap
 *
 *   Pool key:   yourmod:castle/battlements/moorish
 *   Templates:
 *     yourmod:castle/battlements/moorish_0.nbt  — stepped pyramid merlon profile
 *     yourmod:castle/battlements/moorish_1.nbt  — sloped/angled merlon cap
 *
 *   Pool key:   yourmod:castle/battlements/fantasy
 *   Templates:
 *     yourmod:castle/battlements/fantasy_0.nbt  — pointed spike merlons
 *     yourmod:castle/battlements/fantasy_1.nbt  — arcaded crenels (decorative arch between merlons)
 *
 *   Pool key:   yourmod:castle/battlements/ruin
 *   Templates:
 *     yourmod:castle/battlements/ruin_0.nbt     — broken, irregular merlons
 *     yourmod:castle/battlements/ruin_1.nbt     — missing sections (partial wall top)
 *
 * ── TOWER ROOFS ──────────────────────────────────────────────
 * Dimensions: (2*towerRadius + 1) wide × 3–6 tall × same deep
 * Must exactly cover the tower's XZ footprint. The Y=0 plane should align
 * with the top of the tower shell. The template is stamped once per tower.
 * Provide separate templates for each tower size (small = radius 3, large = radius 5).
 *
 *   Pool key:   yourmod:castle/tower_roofs/norman
 *   Templates:
 *     yourmod:castle/tower_roofs/norman.nbt   — flat merlon cap, round tower (r3)
 *     yourmod:castle/tower_roofs/norman_1.nbt   — flat merlon cap, round tower (r5)
 *     yourmod:castle/tower_roofs/norman_2.nbt   — flat merlon cap, square tower (r3)
 *     yourmod:castle/tower_roofs/norman_3.nbt   — flat merlon cap, square tower (r5)
 *
 *   Pool key:   yourmod:castle/tower_roofs/moorish
 *   Templates:
 *     yourmod:castle/tower_roofs/moorish_0.nbt  — pointed conical roof (round tower)
 *     yourmod:castle/tower_roofs/moorish_1.nbt  — domed top (round tower)
 *     yourmod:castle/tower_roofs/moorish_2.nbt  — pyramid cap (square tower)
 *
 *   Pool key:   yourmod:castle/tower_roofs/fantasy
 *   Templates:
 *     yourmod:castle/tower_roofs/fantasy_0.nbt  — tall witch-hat spire
 *     yourmod:castle/tower_roofs/fantasy_1.nbt  — stepped pyramidal cap
 *     yourmod:castle/tower_roofs/fantasy_2.nbt  — open-top octagonal parapet
 *
 *   Pool key:   yourmod:castle/tower_roofs/ruin
 *   Templates:
 *     yourmod:castle/tower_roofs/ruin_0.nbt     — collapsed top, debris scatter
 *     yourmod:castle/tower_roofs/ruin_1.nbt     — open-top, partial parapet
 *
 * ── GATEHOUSE CAPS ───────────────────────────────────────────
 * Dimensions: (gateWidth + 2*towerRadius + 6) wide × 4–8 tall × (wallThickness + 6) deep
 * Covers the full gatehouse span. Y=0 aligns with the top of the gatehouse towers.
 * The template must leave the gate passage clear (do not fill the arch opening).
 *
 *   Pool key:   yourmod:castle/gatehouses/norman
 *   Templates:
 *     yourmod:castle/gatehouses/norman.nbt    — twin square towers with square battlements
 *     yourmod:castle/gatehouses/norman_1.nbt    — twin towers with chapel window above gate
 *
 *   Pool key:   yourmod:castle/gatehouses/moorish
 *   Templates:
 *     yourmod:castle/gatehouses/moorish_0.nbt   — horseshoe arch top panel + decorative tile band
 *     yourmod:castle/gatehouses/moorish_1.nbt   — twin minarets flanking the gate span
 *
 *   Pool key:   yourmod:castle/gatehouses/fantasy
 *   Templates:
 *     yourmod:castle/gatehouses/fantasy_0.nbt   — tall central arch with spiked finials
 *     yourmod:castle/gatehouses/fantasy_1.nbt   — fortified barbican with murder-hole walkway
 *
 *   Pool key:   yourmod:castle/gatehouses/ruin
 *   Templates:
 *     yourmod:castle/gatehouses/ruin_0.nbt      — partially collapsed gatehouse cap
 *
 * ── DONJON ROOFS ─────────────────────────────────────────────
 * Dimensions: (2*donjeonSize + 2) wide × 5–10 tall × same deep
 * The donjon cap is typically more elaborate than a regular tower cap.
 *
 *   Pool key:   yourmod:castle/donjon_roofs/norman
 *   Templates:
 *     yourmod:castle/donjon_roofs/norman.nbt  — crenellated flat roof with corner turrets
 *     yourmod:castle/donjon_roofs/norman_1.nbt  — stepped pyramidal cap
 *
 *   Pool key:   yourmod:castle/donjon_roofs/moorish
 *   Templates:
 *     yourmod:castle/donjon_roofs/moorish_0.nbt — large dome with lantern top
 *     yourmod:castle/donjon_roofs/moorish_1.nbt — octagonal tiered cap
 *
 *   Pool key:   yourmod:castle/donjon_roofs/fantasy
 *   Templates:
 *     yourmod:castle/donjon_roofs/fantasy_0.nbt — cathedral spire
 *     yourmod:castle/donjon_roofs/fantasy_1.nbt — open-top throne platform
 *
 *   Pool key:   yourmod:castle/donjon_roofs/ruin
 *   Templates:
 *     yourmod:castle/donjon_roofs/ruin_0.nbt    — roofless shell, overgrown interior
 *
 * ── WINDOWS / ARROW SLITS (optional overlay) ─────────────────
 * Dimensions: 1 wide × 3 tall × wallThickness deep
 * These are optional decorative overlays placed in the arrow slit openings
 * cut by WallSegmentPlacer. They add things like carved stone surrounds,
 * cross-slit shapes using panes, or lancet window frames.
 * If the pool is absent or empty, the raw openings remain as-is.
 *
 *   Pool key:   yourmod:castle/windows/norman
 *   Templates:
 *     yourmod:castle/windows/norman.nbt       — plain rectangular slit
 *     yourmod:castle/windows/norman_1.nbt       — cross slit (iron bars cross pattern)
 *
 *   Pool key:   yourmod:castle/windows/moorish
 *   Templates:
 *     yourmod:castle/windows/moorish_0.nbt      — horseshoe arch slit (chiseled surround)
 *
 *   Pool key:   yourmod:castle/windows/fantasy
 *   Templates:
 *     yourmod:castle/windows/fantasy_0.nbt      — lancet (pointed top) window frame
 *     yourmod:castle/windows/fantasy_1.nbt      — rose window (glass pane circle, upper)
 *
 * ── INTERIOR DETAILS (optional) ──────────────────────────────
 * Placed inside tower floors at floor-level positions.
 * Small 5×3×5 templates containing furniture, chests, crafting stations, etc.
 * Entirely optional — if absent, tower interiors are bare stone.
 *
 *   Pool key:   yourmod:castle/interiors/barracks
 *   Templates:
 *     yourmod:castle/interiors/barracks_0.nbt   — beds + weapon rack
 *     yourmod:castle/interiors/barracks_1.nbt   — table + chairs
 *
 *   Pool key:   yourmod:castle/interiors/storage
 *   Templates:
 *     yourmod:castle/interiors/storage_0.nbt    — barrels + chest
 *     yourmod:castle/interiors/storage_1.nbt    — crafting room
 *
 *   Pool key:   yourmod:castle/interiors/empty
 *   Templates:
 *     (none required — empty pool = bare floors)
 *
 * ============================================================
 * SUMMARY CHECKLIST (minimum viable set)
 * ============================================================
 *
 * To have a fully functional Norman castle you need at minimum:
 *   [ ] yourmod:castle/battlements/norman.nbt
 *   [ ] yourmod:castle/tower_roofs/norman.nbt  (round, small)
 *   [ ] yourmod:castle/tower_roofs/norman_2.nbt  (square, small)
 *   [ ] yourmod:castle/gatehouses/norman.nbt
 *   [ ] yourmod:castle/donjon_roofs/norman.nbt
 *
 * Everything else uses the code fallbacks (simple alternating merlons,
 * slab tower caps) which look functional if plain.
 *
 * ============================================================
 */


/*public final class CastleStyles {

    private CastleStyles() {}

    // -------------------------------------------------------------------------
    // Norman — solid square towers, thick walls, minimal decoration
    // -------------------------------------------------------------------------

    public static final CastleStyle NORMAN = new CastleStyle(
            new CastleStyle.LayoutConfig(CastleStyle.PlanType.SQUARE, 24, 32),
            new CastleStyle.WallConfig(10, 14, 3),
            new CastleStyle.TowerConfig(CastleStyle.TowerShape.SQUARE, 4, 6, 4, 0.6f),
            new CastleStyle.DonjeonConfig(true, 5, 7, 6, false, 0.5f),
            new CastleStyle.FeatureConfig(true, 4, 3, true, true, true, true),
            new CastleStyle.StructurePoolConfig(
                    rl("castle/battlements/norman"),
                    rl("castle/gatehouses/norman"),
                    rl("castle/windows/norman"),
                    rl("castle/tower_roofs/norman"),
                    rl("castle/donjon_roofs/norman"),
                    rl("castle/interiors/barracks")
            ),
            MaterialPalette.stoneBrick(),
            MaterialPalette.stoneBrick(),
            MaterialPalette.stoneBrick(),
            0.0f,
            1001L
    );

    // -------------------------------------------------------------------------
    // Moorish — polygonal towers, ornate gatehouse, sandstone
    // -------------------------------------------------------------------------


    public static final CastleStyle MOORISH = new CastleStyle(
            new CastleStyle.LayoutConfig(CastleStyle.PlanType.POLYGON, 28, 38),
            new CastleStyle.WallConfig(8, 12, 2),
            new CastleStyle.TowerConfig(CastleStyle.TowerShape.POLYGONAL, 3, 5, 5, 0.8f),
            new CastleStyle.DonjeonConfig(true, 4, 6, 4, true, 0.5f),
            new CastleStyle.FeatureConfig(false, 0, 0, false, false, true, true),
            new CastleStyle.StructurePoolConfig(
                    rl("castle/battlements/moorish"),
                    rl("castle/gatehouses/moorish"),
                    rl("castle/windows/moorish"),
                    rl("castle/tower_roofs/moorish"),
                    rl("castle/donjon_roofs/moorish"),
                    rl("castle/interiors/empty")
            ),
            MaterialPalette.sandstone(),
            new MaterialPalette(
                    java.util.List.of(
                            new MaterialPalette.WeightedBlock(
                                    net.minecraft.world.level.block.Blocks.CHISELED_SANDSTONE, 100)
                    ),
                    java.util.List.of(),
                    net.minecraft.world.level.block.Blocks.SANDSTONE_STAIRS,
                    net.minecraft.world.level.block.Blocks.SANDSTONE_SLAB,
                    net.minecraft.world.level.block.Blocks.SANDSTONE_WALL),
            MaterialPalette.sandstone(),
            0.0f,
            2002L
    );

    // -------------------------------------------------------------------------
    // Fantasy — round towers, tall spires, dark stone
    // -------------------------------------------------------------------------

    public static final CastleStyle FANTASY = new CastleStyle(
            new CastleStyle.LayoutConfig(CastleStyle.PlanType.IRREGULAR, 30, 42),
            new CastleStyle.WallConfig(12, 18, 2),
            new CastleStyle.TowerConfig(CastleStyle.TowerShape.ROUND, 4, 7, 8, 0.9f),
            new CastleStyle.DonjeonConfig(true, 6, 9, 10, true, 0.45f),
            new CastleStyle.FeatureConfig(true, 5, 4, true, true, true, true),
            new CastleStyle.StructurePoolConfig(
                    rl("castle/battlements/fantasy"),
                    rl("castle/gatehouses/fantasy"),
                    rl("castle/windows/fantasy"),
                    rl("castle/tower_roofs/fantasy"),
                    rl("castle/donjon_roofs/fantasy"),
                    rl("castle/interiors/storage")
            ),
            MaterialPalette.deepslate(),
            MaterialPalette.deepslate(),
            MaterialPalette.deepslate(),
            0.0f,
            3003L
    );

    // -------------------------------------------------------------------------
    // Ruin — any style, heavily collapsed
    // -------------------------------------------------------------------------

    public static final CastleStyle RUIN = new CastleStyle(
            new CastleStyle.LayoutConfig(CastleStyle.PlanType.IRREGULAR, 20, 30),
            new CastleStyle.WallConfig(6, 10, 2),
            new CastleStyle.TowerConfig(CastleStyle.TowerShape.ROUND, 3, 5, 2, 0.5f),
            new CastleStyle.DonjeonConfig(false, 4, 6, 4, false, 0.5f),
            new CastleStyle.FeatureConfig(false, 0, 0, false, false, false, false),
            new CastleStyle.StructurePoolConfig(
                    rl("castle/battlements/ruin"),
                    rl("castle/gatehouses/ruin"),
                    rl("castle/windows/norman"),
                    rl("castle/tower_roofs/ruin"),
                    rl("castle/donjon_roofs/ruin"),
                    rl("castle/interiors/empty")
            ),
            MaterialPalette.stoneBrick(),
            MaterialPalette.stoneBrick(),
            MaterialPalette.stoneBrick(),
            0.65f,
            4004L
    );

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static Identifier rl(String path) {
        return Identifier.parse("life_in_the_village:data/structures/" + path);
    }
}

 */
