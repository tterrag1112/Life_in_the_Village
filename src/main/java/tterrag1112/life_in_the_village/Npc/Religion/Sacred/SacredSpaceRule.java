package tterrag1112.life_in_the_village.Npc.Religion.Sacred;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import tterrag1112.life_in_the_village.Utilities.PosUtil;

import java.util.List;

/**
 * Sacred Space S1a — one contribution to how sacred a position is to a god. A rule
 * yields a <b>potency</b> (a float ≥ 0; 0 = not sacred here), not a boolean — so a
 * god's sacred space is a LIST of rules whose contributions <b>sum</b>, and
 * sacredness STACKS (the Sea-Mother is sacred over ocean and <i>doubly</i> sacred at
 * an ocean monument when both rules fire).
 *
 * <p><b>The sealed type is the extension point.</b> Future content (a holy-city
 * bonus, a realm rule) adds a new variant or a new fold-source ({@link SacredSpace})
 * without touching any god's authored list. The variants below are exactly the ones
 * the four starter gods need; the sealed type makes later additions cheap (one
 * record + one {@code permits} entry).</p>
 *
 * <p>S1a wires <b>no effects</b> — only {@link SacredSpace#sacrednessAt} reads these,
 * surfaced by the {@code /religion sacred} debug readout.</p>
 */
public sealed interface SacredSpaceRule
        permits SacredSpaceRule.BiomeRule, SacredSpaceRule.AltitudeRule,
                SacredSpaceRule.CoordinateRule, SacredSpaceRule.SkyRule,
                SacredSpaceRule.StructureRule {

    /** This rule's sacredness contribution at {@code pos} (0 when it does not apply). */
    float potencyAt(ServerLevel level, BlockPos pos);

    /** A short human label for the debug readout ("biome", "altitude", …). */
    String label();

    // ── Variants (only what the four starters need) ──────────────────────────

    /** Sacred when the biome at {@code pos} is in ANY of {@code tags}
     *  ({@code level.getBiome(pos).is(tag)}). */
    record BiomeRule(List<TagKey<Biome>> tags, float potency) implements SacredSpaceRule {
        public float potencyAt(ServerLevel level, BlockPos pos) {
            var biome = level.getBiome(pos);
            for (TagKey<Biome> tag : tags) if (biome.is(tag)) return potency;
            return 0f;
        }
        public String label() { return "biome"; }
    }

    /** Sacred when {@code pos.getY()} is within {@code [minY, maxY]} (absolute world
     *  Y) — the Forge-Father's high mountains. */
    record AltitudeRule(int minY, int maxY, float potency) implements SacredSpaceRule {
        public float potencyAt(ServerLevel level, BlockPos pos) {
            int y = pos.getY();
            return (y >= minY && y <= maxY) ? potency : 0f;
        }
        public String label() { return "altitude"; }
    }

    /** Geometric sacredness relative to the world origin: concentric rings of period
     *  {@code spacing}, each {@code band} blocks wide (the origin is ring 0). The
     *  Loom's "concentric rings from origin". */
    record CoordinateRule(int spacing, int band, float potency) implements SacredSpaceRule {
        public float potencyAt(ServerLevel level, BlockPos pos) {
            if (spacing <= 0) return 0f;
            double d = new PosUtil(pos).horizontalDistanceTo(BlockPos.ZERO);
            double m = d % spacing;
            double nearestRing = Math.min(m, spacing - m);
            return nearestRing <= band ? potency : 0f;
        }
        public String label() { return "rings"; }
    }

    /** <b>Dynamic</b>: sacred under open sky and/or in daylight — the Sun-Mother,
     *  sacred <i>where her light reaches</i>. Re-evaluated every query (time/sky
     *  change). */
    record SkyRule(boolean requireOpenSky, boolean requireDay, float potency)
            implements SacredSpaceRule {
        public float potencyAt(ServerLevel level, BlockPos pos) {
            if (requireOpenSky && !level.canSeeSky(pos)) return 0f;
            if (requireDay && !level.isDay()) return 0f;
            return potency;
        }
        public String label() { return "sky"; }
    }

    /** Sacred within a piece of ANY of {@code structures} (vanilla structures via
     *  {@code level.structureManager().getStructureWithPieceAt}). <b>Cost note:</b>
     *  the structure-manager query reads the chunk's structure references — the most
     *  expensive rule; S1b should throttle/cache when calling per-tick. */
    record StructureRule(List<ResourceKey<Structure>> structures, float potency)
            implements SacredSpaceRule {
        public float potencyAt(ServerLevel level, BlockPos pos) {
            for (ResourceKey<Structure> key : structures) {
                if (level.structureManager().getStructureWithPieceAt(pos, key).isValid()) {
                    return potency;
                }
            }
            return 0f;
        }
        public String label() { return "structure"; }
    }
}
