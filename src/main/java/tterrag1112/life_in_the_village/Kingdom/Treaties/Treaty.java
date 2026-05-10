package tterrag1112.life_in_the_village.Kingdom.Treaties;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.4b — diplomatic treaty between two or more kingdoms.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><b>Drafted</b> — Scholar drafts. {@code ratifiedTicks} is empty.</li>
 *   <li><b>Ratified</b> — every party's ruler has signed.
 *       {@code ratifiedTicks} contains an entry per party UUID
 *       with the tick they ratified at.</li>
 *   <li><b>Active</b> — derived from "all parties ratified AND not
 *       broken". Derived rather than stored to avoid drift.</li>
 *   <li><b>Broken</b> — any party can break.
 *       {@code brokenBy} / {@code brokenTick} / {@code brokenReason}
 *       set; {@code active} reads false.</li>
 * </ol>
 *
 * <p>Stored on each participating kingdom's
 * {@code KingdomGovernanceData.treaties}. The same {@link #id}
 * appears on every party's list — the canonical record is whichever
 * one has the latest {@link #brokenTick} or, if both unbroken, any
 * (they should be in sync).
 *
 * <h3>Migration shape</h3>
 * Pre-D3.4b saves with cooperative {@code DiplomaticRelation}
 * entries get auto-migrated treaties whose {@code drafterUuid} is
 * empty (no Scholar attribution; signals "auto-migrated") and
 * {@code ratifiedTicks} are populated with tick 0 for both parties
 * (treats migration time as ratification time).
 *
 * @param id              stable UUID; primary key.
 * @param type            treaty kind.
 * @param parties         participating kingdom UUIDs (always 2 in v1).
 * @param drafterUuid     Scholar NPC who drafted; empty for
 *                        auto-migrated treaties.
 * @param draftedTick     server tick the draft was created.
 * @param ratifiedTicks   per-party ratification tick. Treaty is
 *                        active when every party id has an entry
 *                        AND {@link #broken} is false.
 * @param termsSummary    short human-readable terms description
 *                        for GUI / debug. Stable across reloads.
 * @param broken          true after any party breaks the treaty.
 * @param brokenBy        breaking party's kingdom UUID; empty
 *                        until {@code broken=true}.
 * @param brokenTick      tick of the break.
 * @param brokenReason    one-line reason tag ("declared war on
 *                        ally", "unilateral", "vassalage rebellion",
 *                        "cascade.declared war on ally").
 */
public record Treaty(
        UUID id,
        TreatyType type,
        List<UUID> parties,
        Optional<UUID> drafterUuid,
        long draftedTick,
        Map<UUID, Long> ratifiedTicks,
        String termsSummary,
        boolean broken,
        Optional<UUID> brokenBy,
        long brokenTick,
        String brokenReason
) {

    public Treaty {
        parties = List.copyOf(parties);
        ratifiedTicks = Map.copyOf(ratifiedTicks);
        if (termsSummary == null) termsSummary = "";
        if (brokenReason == null) brokenReason = "";
    }

    public static final Codec<Treaty> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Treaty::id),
            Codec.STRING.xmap(TreatyType::valueOf, Enum::name)
                    .fieldOf("type").forGetter(Treaty::type),
            UUIDUtil.CODEC.listOf().fieldOf("parties").forGetter(Treaty::parties),
            UUIDUtil.CODEC.optionalFieldOf("drafterUuid").forGetter(Treaty::drafterUuid),
            Codec.LONG.fieldOf("draftedTick").forGetter(Treaty::draftedTick),
            Codec.unboundedMap(UUIDUtil.CODEC, Codec.LONG)
                    .optionalFieldOf("ratifiedTicks", new HashMap<>())
                    .forGetter(Treaty::ratifiedTicks),
            Codec.STRING.optionalFieldOf("termsSummary", "").forGetter(Treaty::termsSummary),
            Codec.BOOL.optionalFieldOf("broken", false).forGetter(Treaty::broken),
            UUIDUtil.CODEC.optionalFieldOf("brokenBy").forGetter(Treaty::brokenBy),
            Codec.LONG.optionalFieldOf("brokenTick", 0L).forGetter(Treaty::brokenTick),
            Codec.STRING.optionalFieldOf("brokenReason", "").forGetter(Treaty::brokenReason)
    ).apply(i, Treaty::new));

    /** True when every party UUID has a ratification tick AND the treaty isn't broken. */
    public boolean isActive() {
        if (broken) return false;
        if (parties.isEmpty()) return false;
        for (UUID p : parties) {
            if (!ratifiedTicks.containsKey(p)) return false;
        }
        return true;
    }

    /** True when {@code party} has signed but the treaty isn't yet fully ratified. */
    public boolean isAwaitingRatificationFrom(UUID party) {
        return !broken && parties.contains(party) && !ratifiedTicks.containsKey(party);
    }

    public boolean involves(UUID kingdomId) {
        return parties.contains(kingdomId);
    }

    /**
     * For VASSALAGE: returns the overlord kingdom UUID. Convention:
     * {@code parties.get(0)} = vassal, {@code parties.get(1)} =
     * overlord. Empty when not a vassalage treaty or the parties
     * list is malformed.
     */
    public Optional<UUID> overlordOf() {
        if (type != TreatyType.VASSALAGE || parties.size() < 2) return Optional.empty();
        return Optional.of(parties.get(1));
    }

    /** For VASSALAGE: returns the vassal kingdom UUID. */
    public Optional<UUID> vassalOf() {
        if (type != TreatyType.VASSALAGE || parties.size() < 2) return Optional.empty();
        return Optional.of(parties.get(0));
    }

    // ── Lifecycle copy helpers ─────────────────────────────────────────────

    /** Records a party's ratification at the given tick. */
    public Treaty withRatification(UUID party, long tick) {
        Map<UUID, Long> next = new HashMap<>(ratifiedTicks);
        next.put(party, tick);
        return new Treaty(id, type, parties, drafterUuid, draftedTick,
                next, termsSummary, broken, brokenBy, brokenTick, brokenReason);
    }

    /** Marks broken with a one-line reason. */
    public Treaty asBroken(UUID by, long tick, String reason) {
        return new Treaty(id, type, parties, drafterUuid, draftedTick,
                ratifiedTicks, termsSummary, true, Optional.ofNullable(by),
                tick, reason);
    }

    // ── Convenience factories ──────────────────────────────────────────────

    /** Fresh draft. No party has ratified yet. */
    public static Treaty freshDraft(UUID id, TreatyType type, List<UUID> parties,
                                    Optional<UUID> drafter, String termsSummary,
                                    long tick) {
        return new Treaty(id, type, parties, drafter, tick,
                new HashMap<>(), termsSummary, false, Optional.empty(), 0L, "");
    }

    /**
     * Auto-migrated treaty (no scholar drafter; both parties ratified
     * at tick 0). Used by {@code Kingdom.fromCodec} to upgrade
     * pre-D3.4b cooperative DiplomaticRelation entries.
     */
    public static Treaty autoMigrated(UUID id, TreatyType type,
                                      UUID partyA, UUID partyB) {
        Map<UUID, Long> ratified = new HashMap<>();
        ratified.put(partyA, 0L);
        ratified.put(partyB, 0L);
        return new Treaty(id, type, List.of(partyA, partyB), Optional.empty(),
                0L, ratified, "auto-migrated from DiplomaticRelation",
                false, Optional.empty(), 0L, "");
    }
}
