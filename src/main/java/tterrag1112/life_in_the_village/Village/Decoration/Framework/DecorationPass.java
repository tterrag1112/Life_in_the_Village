package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.core.registries.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Map;

/**
 * Doc 01 §"Pipeline" — orchestrates {@link DecorationSlotEmitter}
 * → {@link DecorationMatcher} → NBT stamp → persistence.
 *
 * <p>Inserted into the realisation chain in
 * {@code VillageSpawner} after building placement, terrain, farm
 * plots, NPC population, market stalls, and the legacy
 * {@code VillageDecorator} pass — and before {@code
 * TradeRouteManager.establishRoutes}. Phase 0c (AdjunctPlot) and
 * 0d (Subbuilding) realisers will land between {@code
 * VillageDecorator} and this pass when they ship; for now this is
 * the last decoration-related step in the chain.</p>
 *
 * <p>Behaviour with no profiles registered: the matcher logs an
 * INFO message and returns zero placements. The pass then exits
 * cleanly. This is the expected state at the close of Phase 0b —
 * content subsystems (street furniture, signs, etc.) populate the
 * registry in later phases.</p>
 */
public final class DecorationPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecorationPass.class);

    private DecorationPass() {}

    /**
     * Run the decoration pass for {@code village}. Idempotent for a
     * given village id: any prior placements are cleared first so
     * re-running the pass produces a fresh placement list.
     *
     * @param level    server level the village lives in
     * @param village  village to decorate
     * @param data     saved data — placements are persisted here
     * @param layout   planning-time layout (carries the road
     *                 centerlines the emitter walks); may be null
     *                 for runtime expansion paths that don't have
     *                 a planning context, in which case road-side
     *                 slots are skipped
     */
    public static void run(ServerLevel level, Village village,
                           VillageSavedData data,
                           @Nullable VillageLayout layout) {
        if (level == null || village == null || data == null) return;

        long started = System.nanoTime();

        DecorationSlotEmitter.Context ctx =
                new DecorationSlotEmitter.Context(village, data, layout);
        List<DecorationSlot> slots = DecorationSlotEmitter.emit(ctx);
        long emitNanos = System.nanoTime() - started;

        long matchStart = System.nanoTime();
        List<DecorationPlacement> placements = DecorationMatcher.match(
                slots,
                DecorationProfileRegistry.INSTANCE,
                village.getId(),
                cultureFor(village),
                /* slotBiomes */ Map.of(),
                level.getGameTime());
        long matchNanos = System.nanoTime() - matchStart;

        // Re-runnable: drop prior placements before persisting the
        // new list. Doc 01 §"Behavior contract": pass runs once at
        // realisation; this safeguard supports re-runs from debug
        // commands.
        data.removeDecorationsForVillage(village.getId());

        for (DecorationPlacement placement : placements) {
            stamp(level, placement);
            data.addDecorationPlacement(placement);
        }

        LOGGER.info("DecorationPass: village {} — {} slots emitted, {} placed "
                + "(emit {}ms, match {}ms)",
                village.getName(), slots.size(), placements.size(),
                emitNanos / 1_000_000, matchNanos / 1_000_000);
    }

    /**
     * Loads the decoration NBT for {@code placement.pieceId()} and
     * stamps it at the placement's position with rotation derived
     * from the slot's facing.
     *
     * <p>Doc 00 §"NBT resource paths" puts decoration files under
     * {@code structures/decoration/{category}/{name}.nbt}. The
     * {@code pieceId} embeds the {@code {culture}/{category}/{name}}
     * triple; we strip the leading culture segment because it's
     * carried separately on the placement, and the resolver lives
     * with the variant system rather than this pass.</p>
     */
    private static void stamp(ServerLevel level, DecorationPlacement placement) {
        Identifier id = pieceIdToResource(placement);
        Identifier resourcePath = Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "structures/" + id.getPath() + ".nbt");
        var resourceOpt = level.getServer().getResourceManager().getResource(resourcePath);
        if (resourceOpt.isEmpty()) {
            LOGGER.warn("DecorationPass: NBT not found for piece {} at {} "
                    + "(resolved to {}). Slot burned.",
                    placement.pieceId(), placement.pos(), resourcePath);
            return;
        }
        try {
            StructureTemplate template = new StructureTemplate();
            try (var stream = resourceOpt.get().open()) {
                CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
            }
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotationFromFacing(placement));
            template.placeInWorld(level, placement.pos(), BlockPos.ZERO,
                    settings, level.random, 2);
        } catch (Exception e) {
            LOGGER.error("DecorationPass: failed to stamp piece {} at {}: {}",
                    placement.pieceId(), placement.pos(), e.getMessage());
        }
    }

    /** {@code "default/street/bench"} →
     *  {@code "life_in_the_village:default/decoration/street/bench"}. */
    private static Identifier pieceIdToResource(DecorationPlacement placement) {
        return Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID,
                placement.pieceId().contains("/decoration/")
                        ? placement.pieceId()
                        : insertDecorationSegment(placement.pieceId()));
    }

    /** Inserts {@code /decoration/} between the culture and category
     *  segments of a {@code culture/category/name} pieceId. Authors
     *  may write {@code default/decoration/street/bench} explicitly
     *  or rely on the implicit form {@code default/street/bench}. */
    private static String insertDecorationSegment(String pieceId) {
        int firstSlash = pieceId.indexOf('/');
        if (firstSlash <= 0) return pieceId;
        return pieceId.substring(0, firstSlash) + "/decoration"
                + pieceId.substring(firstSlash);
    }

    /** Maps a placement's facing direction onto a structure
     *  {@link Rotation} consistent with the convention used by the
     *  building placer (NONE → SOUTH-facing). */
    private static Rotation rotationFromFacing(DecorationPlacement placement) {
        return switch (placement.facing()) {
            case SOUTH -> Rotation.NONE;
            case WEST  -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST  -> Rotation.COUNTERCLOCKWISE_90;
            // Vertical facings can occur for FLOATING anchor pieces;
            // map them to NONE so the stamp at least doesn't fail
            // due to an unhandled enum case.
            default -> Rotation.NONE;
        };
    }

    private static String cultureFor(Village village) {
        // Until VillageSavedData exposes a per-village culture
        // accessor, use the default culture. Most villages today are
        // default-culture; non-default cultures are still authored
        // content that hasn't shipped.
        return DecorationProfileRegistry.DEFAULT_CULTURE;
    }
}
