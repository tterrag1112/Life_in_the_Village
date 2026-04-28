package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.AnchorRule;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfile;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfileRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationTag;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Doc 04 §"Per-tier kit selection" — registers the default-culture
 * town-square kits and their corresponding
 * {@link DecorationProfile}s into
 * {@link DecorationProfileRegistry}.
 *
 * <p>Called once from {@code commonSetup}. NBT pieces themselves are
 * authored separately under
 * {@code data/life_in_the_village/structures/default/decoration/town_square/}
 * — the matcher logs a missing-NBT warning and skips the slot if a
 * piece file isn't present.</p>
 */
public final class DefaultTownSquareKits {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DefaultTownSquareKits.class);

    private static final String CULTURE = DecorationProfileRegistry.DEFAULT_CULTURE;

    /** Doc 00 §"NBT resource paths" pieceId convention:
     *  {@code {culture}/{category}/{name}}. The decoration pass
     *  inserts {@code /decoration/} between culture and category at
     *  resolve time. */
    private static final String CATEGORY = "town_square";

    /** Tracks pieceIds already registered during this {@link
     *  #registerAll} call so that pieces shared across tiers
     *  (e.g. {@code bench_oak_1} appears in VILLAGE / TOWN / CITY)
     *  don't fire redundant duplicate-registration warnings from
     *  {@link DecorationProfileRegistry}. */
    private static final Set<String> registeredPieceIds = new HashSet<>();

    private DefaultTownSquareKits() {}

    public static void registerAll() {
        registeredPieceIds.clear();
        registerHamletKit();
        registerVillageKit();
        registerTownKit();
        registerCityKit();
        LOGGER.info("DefaultTownSquareKits: registered {} kits, {} unique profiles",
                TownSquareKitRegistry.INSTANCE.size(),
                registeredPieceIds.size());
        registeredPieceIds.clear();
    }

    // ── Per-tier kit registration ───────────────────────────────────────

    private static void registerHamletKit() {
        TownSquareKit kit = new TownSquareKit(
                CULTURE, VillageSizeTier.HAMLET,
                piece("well_hamlet"),
                List.of(),                 // no benches at HAMLET
                Optional.empty(),          // no gazebo
                Optional.empty(),          // no monument
                piece("notice_board"),
                piece("lamp_post"),
                Optional.empty()           // no flowerbed
        );
        TownSquareKitRegistry.INSTANCE.register(kit);
        registerProfilesFor(kit);
    }

    private static void registerVillageKit() {
        TownSquareKit kit = new TownSquareKit(
                CULTURE, VillageSizeTier.VILLAGE,
                piece("well_village"),
                List.of(piece("bench_oak_1"), piece("bench_oak_2")),
                Optional.empty(),
                Optional.empty(),
                piece("notice_board"),
                piece("lamp_post"),
                Optional.of(piece("flowerbed_default"))
        );
        TownSquareKitRegistry.INSTANCE.register(kit);
        registerProfilesFor(kit);
    }

    private static void registerTownKit() {
        TownSquareKit kit = new TownSquareKit(
                CULTURE, VillageSizeTier.TOWN,
                piece("fountain_town"),
                List.of(piece("bench_oak_1"), piece("bench_oak_2")),
                Optional.of(piece("gazebo_town")),
                Optional.empty(),
                piece("notice_board"),
                piece("lamp_post"),
                Optional.of(piece("flowerbed_default"))
        );
        TownSquareKitRegistry.INSTANCE.register(kit);
        registerProfilesFor(kit);
    }

    private static void registerCityKit() {
        TownSquareKit kit = new TownSquareKit(
                CULTURE, VillageSizeTier.CITY,
                piece("fountain_city"),
                List.of(piece("bench_oak_1"), piece("bench_oak_2")),
                Optional.of(piece("gazebo_town")),       // shared with TOWN
                Optional.of(piece("monument_city")),
                piece("notice_board"),
                piece("lamp_post"),
                Optional.of(piece("flowerbed_default"))
        );
        TownSquareKitRegistry.INSTANCE.register(kit);
        registerProfilesFor(kit);
    }

    // ── Profile registration ────────────────────────────────────────────

    /**
     * Registers one {@link DecorationProfile} per piece in the kit.
     * Each profile requires {@code PARK_FEATURE} plus the piece-
     * specific sub-role tag, so the matcher only places this piece in
     * slots authored by {@code DecorationSlotEmitter}'s plaza sub-pass.
     *
     * <p>Profiles are keyed by pieceId, so re-registering the same
     * piece across multiple kits (e.g. {@code bench_oak_1} appears in
     * VILLAGE / TOWN / CITY) just replaces the entry — fine, since
     * the profile content is identical.</p>
     */
    private static void registerProfilesFor(TownSquareKit kit) {
        // Central feature.
        register(buildProfile(
                pieceIdFor(kit.centralFeatureNbt()),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_FOUNTAIN),
                /* footprintSize */ centralFeatureFootprint(kit.tier()),
                AnchorRule.ON_GROUND));

        // Benches.
        for (Identifier benchNbt : kit.benchVariants()) {
            register(buildProfile(
                    pieceIdFor(benchNbt),
                    EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_BENCH),
                    /* footprintSize */ 2,
                    AnchorRule.ON_GROUND));
        }

        // Gazebo.
        kit.gazeboNbt().ifPresent(nbt -> register(buildProfile(
                pieceIdFor(nbt),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_GAZEBO),
                /* footprintSize */ 5,
                AnchorRule.ON_GROUND)));

        // Monument.
        kit.monumentNbt().ifPresent(nbt -> register(buildProfile(
                pieceIdFor(nbt),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_MONUMENT),
                /* footprintSize */ 3,
                AnchorRule.ON_GROUND)));

        // Notice board.
        register(buildProfile(
                pieceIdFor(kit.noticeBoardNbt()),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_NOTICE_BOARD),
                /* footprintSize */ 1,
                AnchorRule.ON_GROUND));

        // Lamp.
        register(buildProfile(
                pieceIdFor(kit.lampNbt()),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_LAMP),
                /* footprintSize */ 1,
                AnchorRule.ON_GROUND));

        // Flowerbed.
        kit.flowerbedNbt().ifPresent(nbt -> register(buildProfile(
                pieceIdFor(nbt),
                EnumSet.of(DecorationTag.PARK_FEATURE, DecorationTag.PLAZA_FLOWERBED),
                /* footprintSize */ 2,
                AnchorRule.ON_GROUND)));

        // PLAZA_VENDOR_ZONE intentionally has no profile in Phase 1 —
        // doc 04 §"Vendor zone": "reserved flat slot, stays empty
        // until MARKET_DAY event". The slot is still emitted so the
        // event system can find it deterministically.
    }

    private static int centralFeatureFootprint(VillageSizeTier tier) {
        return switch (tier) {
            case HAMLET  -> 1;
            case VILLAGE -> 2;
            case TOWN    -> 3;
            case CITY    -> 5;
        };
    }

    private static DecorationProfile buildProfile(String pieceId,
                                                  EnumSet<DecorationTag> requiredTags,
                                                  int footprintSize,
                                                  AnchorRule anchorRule) {
        return new DecorationProfile(
                pieceId,
                requiredTags,
                /* preferredTags */ EnumSet.noneOf(DecorationTag.class),
                /* primaryWeight */ 10,
                /* secondaryWeight */ 5,
                /* backfillWeight */ 1,
                footprintSize,
                CULTURE,
                /* biomeConstraint */ Optional.empty(),
                anchorRule);
    }

    private static void register(DecorationProfile profile) {
        if (!registeredPieceIds.add(profile.pieceId())) return;
        DecorationProfileRegistry.INSTANCE.register(profile);
    }

    // ── pieceId helpers ─────────────────────────────────────────────────

    /** Builds an Identifier for a piece in the default culture's
     *  {@code town_square} category. The decoration pass appends the
     *  trailing {@code /decoration/} segment automatically. */
    private static Identifier piece(String name) {
        return Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID,
                CULTURE + "/" + CATEGORY + "/" + name);
    }

    /** {@code "life_in_the_village:default/town_square/well_hamlet"} →
     *  {@code "default/town_square/well_hamlet"}. Strips the namespace
     *  to leave the {@code culture/category/name} pieceId expected by
     *  {@link DecorationProfile#pieceId()}. */
    private static String pieceIdFor(Identifier nbt) {
        return nbt.getPath();
    }
}
