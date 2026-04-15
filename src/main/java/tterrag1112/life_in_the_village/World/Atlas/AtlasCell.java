package tterrag1112.life_in_the_village.World.Atlas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * One cell of the World Atlas — a 64×64 block region with sampled
 * worldgen data. All values come from {@link AtlasSampler} which
 * queries the chunk generator's noise functions WITHOUT loading
 * chunks.
 *
 * <h3>Coordinates</h3>
 * Cell coordinates are integer dividends of {@link #CELL_SIZE}:
 * <pre>
 *   cellX = blockX &gt;&gt; CELL_SHIFT
 *   blockX_centre = (cellX &lt;&lt; CELL_SHIFT) + CELL_HALF
 * </pre>
 *
 * <h3>Sampled values</h3>
 * <ul>
 *   <li><b>centerY</b> — surface height at the cell centre</li>
 *   <li><b>minY/maxY</b> — min/max from 5-point sample (centre + 4 corners)</li>
 *   <li><b>biomeKey</b> — full resource location of the centre biome</li>
 *   <li><b>category</b> — broad classification</li>
 *   <li><b>flags</b> — bitfield of derived properties (see FLAG_* constants)</li>
 * </ul>
 *
 * <h3>Coast/river/ocean flags</h3>
 * Coast and water-adjacency are computed from neighbour cells AFTER
 * the cell itself is sampled — see {@link WorldAtlas#computeAdjacencyFlags}.
 * The cell starts with only its own intrinsic flags set.
 */
public record AtlasCell(
        int cellX,
        int cellZ,
        int centerY,
        int minY,
        int maxY,
        String biomeKey,
        BiomeCategory category,
        int flags
) {

    // ── Cell sizing ──────────────────────────────────────────────────────────

    public static final int CELL_SHIFT = 6;            // 1 << 6 = 64
    public static final int CELL_SIZE  = 1 << CELL_SHIFT;
    public static final int CELL_HALF  = CELL_SIZE >> 1;

    // ── Flag bits ────────────────────────────────────────────────────────────

    public static final int FLAG_HAS_RIVER     = 1;        // own biome is river
    public static final int FLAG_HAS_OCEAN     = 1 << 1;   // own biome is ocean
    public static final int FLAG_HAS_BEACH     = 1 << 2;   // own biome is beach
    public static final int FLAG_HAS_SWAMP     = 1 << 3;   // own biome is swamp
    public static final int FLAG_COAST         = 1 << 4;   // adjacent to ocean
    public static final int FLAG_RIVER_ADJ     = 1 << 5;   // adjacent to river
    public static final int FLAG_FRESHWATER    = 1 << 6;   // own or adj river/swamp
    public static final int FLAG_STEEP         = 1 << 7;   // maxY - minY > STEEP_THRESHOLD
    public static final int FLAG_SAMPLED       = 1 << 8;   // initialized

    /** maxY - minY above this counts as steep. */
    public static final int STEEP_THRESHOLD = 12;

    // ── Codec ────────────────────────────────────────────────────────────────

    public static final Codec<AtlasCell> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("cx").forGetter(AtlasCell::cellX),
            Codec.INT.fieldOf("cz").forGetter(AtlasCell::cellZ),
            Codec.INT.fieldOf("y").forGetter(AtlasCell::centerY),
            Codec.INT.fieldOf("yMin").forGetter(AtlasCell::minY),
            Codec.INT.fieldOf("yMax").forGetter(AtlasCell::maxY),
            Codec.STRING.fieldOf("biome").forGetter(AtlasCell::biomeKey),
            BiomeCategory.CODEC.fieldOf("cat").forGetter(AtlasCell::category),
            Codec.INT.fieldOf("flags").forGetter(AtlasCell::flags)
    ).apply(i, AtlasCell::new));

    // ── Convenience ──────────────────────────────────────────────────────────

    public int blockCenterX() { return (cellX << CELL_SHIFT) + CELL_HALF; }
    public int blockCenterZ() { return (cellZ << CELL_SHIFT) + CELL_HALF; }
    public int blockMinX()    { return  cellX << CELL_SHIFT; }
    public int blockMinZ()    { return  cellZ << CELL_SHIFT; }

    public int slope() { return maxY - minY; }
    public boolean has(int flag) { return (flags & flag) != 0; }

    public boolean isCoast()      { return has(FLAG_COAST); }
    public boolean isRiverAdj()   { return has(FLAG_RIVER_ADJ); }
    public boolean isFreshwater() { return has(FLAG_FRESHWATER); }
    public boolean isSteep()      { return has(FLAG_STEEP); }

    /** True if this cell is suitable for any building placement at all. */
    public boolean isBuildable() {
        return !category.isUnbuildable() && centerY > 0;
    }

    public AtlasCell withFlags(int newFlags) {
        return new AtlasCell(cellX, cellZ, centerY, minY, maxY,
                biomeKey, category, newFlags);
    }

    /** Packs (cellX, cellZ) into a long for use as a map key. */
    public static long packKey(int cellX, int cellZ) {
        return ((long) cellX & 0xFFFFFFFFL) | (((long) cellZ & 0xFFFFFFFFL) << 32);
    }

    public long key() { return packKey(cellX, cellZ); }

    public static int unpackX(long key) { return (int) (key & 0xFFFFFFFFL); }
    public static int unpackZ(long key) { return (int) ((key >>> 32) & 0xFFFFFFFFL); }
    public static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, AtlasCell>
            STREAM_CODEC = new net.minecraft.network.codec.StreamCodec<>() {
        @Override
        public AtlasCell decode(io.netty.buffer.ByteBuf buf) {
            int cellX = buf.readInt();
            int cellZ = buf.readInt();
            int centerY = buf.readInt();
            int minY = buf.readInt();
            int maxY = buf.readInt();
            int biomeLen = buf.readShort();
            byte[] biomeBytes = new byte[biomeLen];
            buf.readBytes(biomeBytes);
            String biomeKey = new String(biomeBytes, java.nio.charset.StandardCharsets.UTF_8);
            BiomeCategory category = BiomeCategory.values()[buf.readByte()];
            int flags = buf.readInt();
            return new AtlasCell(cellX, cellZ, centerY, minY, maxY,
                    biomeKey, category, flags);
        }

        @Override
        public void encode(io.netty.buffer.ByteBuf buf, AtlasCell c) {
            buf.writeInt(c.cellX());
            buf.writeInt(c.cellZ());
            buf.writeInt(c.centerY());
            buf.writeInt(c.minY());
            buf.writeInt(c.maxY());
            byte[] b = c.biomeKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(b.length);
            buf.writeBytes(b);
            buf.writeByte(c.category().ordinal());
            buf.writeInt(c.flags());
        }
    };
}