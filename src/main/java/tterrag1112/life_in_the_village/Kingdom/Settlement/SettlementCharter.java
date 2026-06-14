package tterrag1112.life_in_the_village.Kingdom.Settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.Optional;
import java.util.UUID;

/**
 * Track C1 — a lightweight, pre-siting settlement commitment issued by a
 * kingdom into one atlas cell of its claim. A charter decouples kingdom
 * birth from capital block-siting: the kingdom is born the moment it has a
 * claim plus a charter, and survey / realization happen later, locally.
 *
 * <h3>Not the political {@code Charter}</h3>
 * This is NOT {@code tterrag1112...Kingdom.Charters.Charter} — that type is
 * a Track D3.4b political/economic grant (TOLL_RIGHTS / TITLE_GRANT / …).
 * This is the settlement charter from the C-track charter-gen design:
 * atlas-cell + role + size band + name + digest.
 *
 * <h3>Staged commitment</h3>
 * Fields advance monotonically with {@link SettlementCharterStage}. The
 * staged (stage-1 / stage-2) fields are {@code optionalFieldOf} so a
 * charter persists cleanly at any stage:
 * <ul>
 *   <li><b>identity</b> — {@code id}, {@code kingdomId}, {@code name},
 *       {@code stage}, {@code issuedTick}.</li>
 *   <li><b>stage 0 (chartered)</b> — {@code targetCellKey} (the committed
 *       cell, not a block), {@code role} (reuses the {@code kingdomRoles}
 *       vocabulary — {@code "capital"}/{@code "food"}/…), {@code villageType}
 *       (registry id), {@code sizeBand} ({@link VillageSizeTier}),
 *       {@code capital} flag, {@code digest} ({@link CharterDigest}).</li>
 *   <li><b>stage 1 (surveyed)</b> — {@code surveyedAnchor} (exact anchor
 *       within the cell), {@code footprintScore}. Empty in C1-a.</li>
 *   <li><b>stage 2 (realized)</b> — {@code realizedVillageId} (the
 *       {@code Village} this charter became). Empty in C1-a.</li>
 * </ul>
 *
 * <p>Field count: 13 — under the 16-field codec cap.
 *
 * <p>Mutation is copy-with (record), matching the political
 * {@code Charter.revoke(tick)} style.
 *
 * @param id                identity / primary key in
 *                          {@code Kingdom.settlementCharters}.
 * @param kingdomId         issuing kingdom.
 * @param name              settlement name, generated at issuance.
 * @param stage             lifecycle stage.
 * @param targetCellKey     committed atlas cell ({@link AtlasCell#packKey}).
 * @param role              {@code kingdomRoles} vocabulary string.
 * @param villageType       village-type registry id (resolved from role).
 * @param sizeBand          target size band.
 * @param capital           true if this is the kingdom's capital charter.
 * @param digest            stage-0 resource snapshot estimate.
 * @param surveyedAnchor    exact anchor; empty until surveyed (C1-b).
 * @param footprintScore    survey suitability; empty until surveyed (C1-b).
 * @param realizedVillageId the realized {@code Village}; empty until
 *                          realized (C1-c).
 * @param issuedTick        worldgen / issuance tick (history + age).
 */
public record SettlementCharter(
        // ── identity ──
        UUID id,
        UUID kingdomId,
        String name,
        SettlementCharterStage stage,

        // ── stage 0: chartered (atlas-cell digest only) ──
        long targetCellKey,
        String role,
        String villageType,
        VillageSizeTier sizeBand,
        boolean capital,
        CharterDigest digest,

        // ── stage 1: surveyed (C1-b) ──
        Optional<BlockPos> surveyedAnchor,
        Optional<Float> footprintScore,

        // ── stage 2: realized (C1-c) ──
        Optional<UUID> realizedVillageId,

        long issuedTick
) {

    /** Reusable role string — the capital role in the kingdomRoles vocab. */
    public static final String ROLE_CAPITAL = "capital";

    /**
     * Track V5 — the sentinel {@code kingdomId} for a FRONTIER (ownerless)
     * settlement charter. A frontier village exists outside every kingdom
     * claim; it has no owning kingdom, so its charter carries this sentinel
     * instead of a real kingdom id (NO new codec field — the existing
     * {@code kingdomId} carries the null-owner sentinel, per doc 16 §5 +
     * the dispatch's "reuse the existing record with a sentinel owner").
     * Ownership is otherwise DERIVED from the cell-claim
     * ({@code VillageSavedData.getKingdomForCell}); a frontier charter is
     * simply one whose cell no claim contains yet. On absorption (its cell
     * becomes claimed) the charter is re-stamped with the real kingdom id
     * via {@link #absorbedBy} and moved into that kingdom.
     */
    public static final UUID FRONTIER_KINGDOM = new UUID(0L, 0L);

    /** Role label used when a frontier charter's role is unknown. */
    public static final String ROLE_FRONTIER = "frontier";

    public static final Codec<SettlementCharter> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(SettlementCharter::id),
            UUIDUtil.CODEC.fieldOf("kingdomId").forGetter(SettlementCharter::kingdomId),
            Codec.STRING.fieldOf("name").forGetter(SettlementCharter::name),
            Codec.STRING.xmap(SettlementCharterStage::valueOf, Enum::name)
                    .optionalFieldOf("stage", SettlementCharterStage.CHARTERED)
                    .forGetter(SettlementCharter::stage),
            Codec.LONG.fieldOf("cellKey").forGetter(SettlementCharter::targetCellKey),
            Codec.STRING.optionalFieldOf("role", ROLE_CAPITAL)
                    .forGetter(SettlementCharter::role),
            Codec.STRING.fieldOf("villageType").forGetter(SettlementCharter::villageType),
            Codec.STRING.xmap(VillageSizeTier::valueOf, Enum::name)
                    .optionalFieldOf("sizeBand", VillageSizeTier.VILLAGE)
                    .forGetter(SettlementCharter::sizeBand),
            Codec.BOOL.optionalFieldOf("capital", false).forGetter(SettlementCharter::capital),
            CharterDigest.CODEC.optionalFieldOf("digest", CharterDigest.UNKNOWN)
                    .forGetter(SettlementCharter::digest),
            BlockPos.CODEC.optionalFieldOf("surveyedAnchor")
                    .forGetter(SettlementCharter::surveyedAnchor),
            Codec.FLOAT.optionalFieldOf("footprintScore")
                    .forGetter(SettlementCharter::footprintScore),
            UUIDUtil.CODEC.optionalFieldOf("realizedVillageId")
                    .forGetter(SettlementCharter::realizedVillageId),
            Codec.LONG.optionalFieldOf("issuedTick", 0L)
                    .forGetter(SettlementCharter::issuedTick)
    ).apply(i, SettlementCharter::new));

    /**
     * Convenience constructor for a brand-new stage-0 charter (no survey,
     * no realization yet).
     */
    public static SettlementCharter chartered(UUID id, UUID kingdomId, String name,
                                              long targetCellKey, String role,
                                              String villageType, VillageSizeTier sizeBand,
                                              boolean capital, CharterDigest digest,
                                              long issuedTick) {
        return new SettlementCharter(id, kingdomId, name,
                SettlementCharterStage.CHARTERED,
                targetCellKey, role, villageType, sizeBand, capital, digest,
                Optional.empty(), Optional.empty(), Optional.empty(),
                issuedTick);
    }

    /**
     * Track V5: a brand-new stage-0 FRONTIER charter -- ownerless
     * ({@link #FRONTIER_KINGDOM}), never capital, {@code default} culture is
     * implied by its lack of an owning kingdom. {@code villageType} = role
     * label (type is near-vestigial in V2; the realizer derives the roster
     * from terrain).
     */
    public static SettlementCharter frontier(UUID id, String name,
                                             long targetCellKey, String role,
                                             VillageSizeTier sizeBand,
                                             CharterDigest digest, long issuedTick) {
        return new SettlementCharter(id, FRONTIER_KINGDOM, name,
                SettlementCharterStage.CHARTERED,
                targetCellKey, role, role, sizeBand, false, digest,
                Optional.empty(), Optional.empty(), Optional.empty(),
                issuedTick);
    }

    /** True when this charter has not yet realized into a Village. */
    public boolean isUnrealized() {
        return realizedVillageId.isEmpty();
    }

    /**
     * Track V5: true when this is a FRONTIER (ownerless) charter -- its
     * {@code kingdomId} is the {@link #FRONTIER_KINGDOM} sentinel. A frontier
     * charter is enumerated/persisted outside any claim; it is absorbed (and
     * loses this status) when its cell becomes claimed (see {@link #absorbedBy}).
     */
    public boolean isFrontier() {
        return FRONTIER_KINGDOM.equals(kingdomId);
    }

    /**
     * Map-pin position for this charter: the exact {@link #surveyedAnchor}
     * once SURVEYED (C1-b), the cell centre otherwise. Map data builders
     * read this so a SURVEYED pin snaps from the cell centre to the precise
     * surveyed point. No new codec field — derived from existing state.
     */
    public BlockPos pinPos() {
        return surveyedAnchor.orElseGet(this::targetCellCentre);
    }

    /** Block-space centre of the committed atlas cell — the pre-survey pin. */
    public BlockPos targetCellCentre() {
        int cx = AtlasCell.unpackX(targetCellKey);
        int cz = AtlasCell.unpackZ(targetCellKey);
        int x = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int z = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        // Cell-centre marker: the map projects on X/Z; Y is nominal until
        // the survey (C1-b) fills surveyedAnchor with a real ground height.
        return new BlockPos(x, 64, z);
    }

    // ── copy-with mutators (C1-b / C1-c plug-in points) ──

    /** C1-b: advance to SURVEYED with an exact anchor + footprint score. */
    public SettlementCharter surveyed(BlockPos anchor, float score) {
        return new SettlementCharter(id, kingdomId, name,
                SettlementCharterStage.SURVEYED,
                targetCellKey, role, villageType, sizeBand, capital, digest,
                Optional.of(anchor), Optional.of(score), realizedVillageId,
                issuedTick);
    }

    /**
     * V3: promote this charter to the kingdom's capital. Sets {@code capital}
     * true and adopts the capital name + village type. Preserves id, target
     * cell, stage, survey, and realization fields so a map pin or an
     * in-flight realization survives the promotion. Copy-with (record).
     */
    public SettlementCharter withCapital(String capitalName, String capitalVillageType) {
        return new SettlementCharter(id, kingdomId, capitalName, stage,
                targetCellKey, ROLE_CAPITAL, capitalVillageType, sizeBand,
                true, digest,
                surveyedAnchor, footprintScore, realizedVillageId,
                issuedTick);
    }

    /** C1-c: advance to REALIZED, pointing at the produced Village. */
    public SettlementCharter realized(UUID villageId) {
        return new SettlementCharter(id, kingdomId, name,
                SettlementCharterStage.REALIZED,
                targetCellKey, role, villageType, sizeBand, capital, digest,
                surveyedAnchor, footprintScore, Optional.of(villageId),
                issuedTick);
    }

    /**
     * Track V5 -- ABSORPTION: re-stamp this (frontier) charter with the
     * kingdom that now claims its cell. Preserves id / cell / candidate role /
     * stage / survey / realization, so a frontier village that was already
     * placed (or even realized) is simply re-owned -- NO duplicate, NO
     * position change (doc 16 §5; the cell + candidate are identical to what
     * the kingdom grid would have enumerated, the subset rule). The culture
     * upgrade from {@code default} to the kingdom's is DERIVED -- a realized
     * village's culture follows its owning kingdom, which now exists. Copy-with.
     */
    public SettlementCharter absorbedBy(UUID newKingdomId) {
        return new SettlementCharter(id, newKingdomId, name, stage,
                targetCellKey, role, villageType, sizeBand, capital, digest,
                surveyedAnchor, footprintScore, realizedVillageId,
                issuedTick);
    }
}
