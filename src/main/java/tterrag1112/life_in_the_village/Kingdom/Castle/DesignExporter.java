package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Exports a selected region from the world as an NBT structure file,
 * placed in the correct directory for a given DesignType and style.
 *
 * Output path:
 *   src/main/resources/data/life_in_the_village/structures/castle/<type>/<style>_<index>.nbt
 *
 * The index is auto-incremented — it finds the next unused N and writes there.
 *
 * Also embeds layer trait metadata as a compound tag at the end of the NBT
 * so the stamping layer can use it for height stretching.
 */
public class DesignExporter {

    private static final Logger LOGGER = LogManager.getLogger(DesignExporter.class);

    // Root relative to the working directory (project root when running in dev)
    private static final String RESOURCES_ROOT =
            "src/main/resources/data/life_in_the_village/structures/";

    // =========================================================================
    // Export entry point
    // =========================================================================

    /**
     * Exports the region defined by the session to an NBT file.
     *
     * @return The path written, or null if export failed.
     */
    public static ExportResult export(DesignSession session, ServerLevel level) {
        if (!session.hasRegion()) {
            return ExportResult.failure("No region selected. Use /castle design pos1 and pos2 first.");
        }

        BlockPos min = session.min();
        BlockPos max = session.max();
        Vec3i size   = new Vec3i(
                max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1);

        if (size.getX() == 0 || size.getY() == 0 || size.getZ() == 0) {
            return ExportResult.failure("Region has zero size.");
        }

        // Build the StructureTemplate from the world region
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, min, size, false, List.of(Blocks.VOID_AIR));

        // Embed layer trait metadata into the template NBT
        CompoundTag nbt = template.save(new CompoundTag());
        embedLayerTraits(nbt, session);

        // Find the next available index for this type+style
        int index = findNextIndex(session.type, session.styleId);
        String nbtPath = session.type.nbtPath(session.styleId, index);
        Path outputPath = resolveOutputPath(nbtPath);

        try {
            Files.createDirectories(outputPath.getParent());
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                NbtIo.writeCompressed(nbt, out);
            }
            LOGGER.info("DesignExporter: wrote {} ({} blocks, {}x{}x{})",
                    outputPath, size.getX() * size.getY() * size.getZ(),
                    size.getX(), size.getY(), size.getZ());
            return ExportResult.success(outputPath.toString(), index,
                    size.getX(), size.getY(), size.getZ());
        } catch (IOException e) {
            LOGGER.error("DesignExporter: failed to write {}: {}", outputPath, e.getMessage());
            return ExportResult.failure("IO error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Layer trait embedding
    // =========================================================================

    /**
     * Embeds layer trait data as a "LayerTraits" compound in the NBT tag.
     * Format: { "0": "STANDARD", "3": "CLONE_THRICE", "7": "OPTIONAL" }
     * Keys are relative Y indices as strings.
     */
    private static void embedLayerTraits(CompoundTag nbt, DesignSession session) {
        if (session.layerTraits.isEmpty()) return;
        CompoundTag traitsTag = new CompoundTag();
        session.layerTraits.forEach((relY, trait) ->
                traitsTag.putString(String.valueOf(relY), trait.name()));
        nbt.put("LayerTraits", traitsTag);
    }

    // =========================================================================
    // Index finding
    // =========================================================================

    private static int findNextIndex(DesignType type, String styleId) {
        for (int i = 0; i <= 99; i++) {
            Path path = resolveOutputPath(type.nbtPath(styleId, i));
            if (!Files.exists(path)) return i;
        }
        return 100; // fallback — shouldn't happen
    }

    // =========================================================================
    // Path resolution
    // =========================================================================

    private static Path resolveOutputPath(String nbtPath) {
        return Paths.get(RESOURCES_ROOT + nbtPath + ".nbt");
    }

    // =========================================================================
    // Result record
    // =========================================================================

    public record ExportResult(
            boolean success,
            String message,
            int index,
            int width, int height, int depth
    ) {
        static ExportResult success(String path, int index, int w, int h, int d) {
            return new ExportResult(true,
                    "Exported to: " + path, index, w, h, d);
        }

        static ExportResult failure(String reason) {
            return new ExportResult(false, reason, -1, 0, 0, 0);
        }
    }
}