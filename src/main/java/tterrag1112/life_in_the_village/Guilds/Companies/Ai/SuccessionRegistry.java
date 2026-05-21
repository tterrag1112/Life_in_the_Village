package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessOwner;
import tterrag1112.life_in_the_village.Guilds.Companies.EmploymentTier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.3.c.6 — predicate-stack registry for resolving Business
 * succession. Mirrors 6.3.2.d's {@code OfficeEligibilityRegistry}
 * pattern but with OR semantics (first non-empty result wins).
 *
 * <p>Key: (cultureId, businessType). Wildcards: {@link #ANY_CULTURE} +
 * {@link #ANY_BUSINESS_TYPE}. Predicates resolve in registration order;
 * the default stack (registered at class load) covers the common cases:
 *
 * <ol>
 *   <li><strong>DESIGNATED_HEIR</strong> — walks Business.heirs, picks
 *       first alive + adult</li>
 *   <li><strong>FAMILY_SUCCESSOR</strong> — FamilyOwner: walks
 *       household adult members ordered by seniority</li>
 *   <li><strong>APPRENTICE_HEIR</strong> — walks workers tier=MASTER,
 *       then tier=JOURNEYMAN, picks highest-skill living NPC</li>
 *   <li><strong>VILLAGE_ALLOCATION_POOL</strong> — returns
 *       TransferredToPool. Business parked for later reassignment
 *       rather than auto-dissolving.</li>
 * </ol>
 *
 * <p>Content packs add culture-specific predicates via
 * {@link #register}.
 */
public final class SuccessionRegistry {

    private SuccessionRegistry() {}

    @Nullable public static final String ANY_CULTURE = null;
    @Nullable public static final Business.BusinessType ANY_BUSINESS_TYPE = null;

    private static final Map<String, List<SuccessionPredicate>> PREDICATES = new HashMap<>();

    private static String key(@Nullable String cultureId,
                              @Nullable Business.BusinessType bt) {
        return (cultureId == null ? "*" : cultureId)
                + ":" + (bt == null ? "*" : bt.name());
    }

    public static void register(@Nullable String cultureId,
                                @Nullable Business.BusinessType bt,
                                SuccessionPredicate predicate) {
        if (predicate == null) return;
        PREDICATES.computeIfAbsent(key(cultureId, bt), k -> new ArrayList<>())
                .add(predicate);
    }

    /** Resolves succession by walking the stack: culture-specific
     *  predicates first, then ANY_CULTURE, OR semantics. */
    public static SuccessionResult resolve(@Nullable String cultureId,
                                           Business business,
                                           ServerLevel level) {
        Business.BusinessType bt = business.getBusinessType();
        for (String k : List.of(
                key(cultureId, bt),
                key(cultureId, ANY_BUSINESS_TYPE),
                key(ANY_CULTURE, bt),
                key(ANY_CULTURE, ANY_BUSINESS_TYPE))) {
            List<SuccessionPredicate> stack = PREDICATES.get(k);
            if (stack == null) continue;
            for (SuccessionPredicate p : stack) {
                Optional<SuccessionResult> r = p.resolve(business, level);
                if (r.isPresent()) return r.get();
            }
        }
        return SuccessionResult.NoEligibleSuccessor.INSTANCE;
    }

    public static void clearAll() { PREDICATES.clear(); }

    // ── Default predicate stack (registered at class load) ────────────

    static {
        // 1. DESIGNATED_HEIR: walks Business.heirs.
        register(ANY_CULTURE, ANY_BUSINESS_TYPE, (business, level) -> {
            for (UUID heirId : business.getHeirs()) {
                TownspersonMob heir = TownspersonMob.findByUUID(level, heirId).orElse(null);
                if (heir != null && heir.isAlive()
                        && heir.getLifeStage() != LifeStage.CHILD) {
                    return Optional.of(new SuccessionResult.HeirFound(heirId));
                }
            }
            return Optional.empty();
        });

        // 2. FAMILY_SUCCESSOR: FamilyOwner household members.
        register(ANY_CULTURE, ANY_BUSINESS_TYPE, (business, level) -> {
            if (!(business.getOwner() instanceof BusinessOwner.FamilyOwner f)) {
                return Optional.empty();
            }
            VillageSavedData vdata = VillageSavedData.get(level);
            List<UUID> members = vdata.getAllHouseholds().stream()
                    .filter(h -> h.getHouseholdId().equals(f.householdId()))
                    .findFirst()
                    .map(h -> new ArrayList<>(h.getMemberNpcIds()))
                    .orElse(new ArrayList<>());
            members.sort(Comparator.comparingInt((UUID id) -> {
                TownspersonMob m = TownspersonMob.findByUUID(level, id).orElse(null);
                return m == null ? -1 : m.getAge();
            }).reversed());
            for (UUID id : members) {
                TownspersonMob m = TownspersonMob.findByUUID(level, id).orElse(null);
                if (m != null && m.isAlive()
                        && m.getLifeStage() != LifeStage.CHILD) {
                    return Optional.of(new SuccessionResult.HeirFound(id));
                }
            }
            return Optional.empty();
        });

        // 3. APPRENTICE_HEIR: MASTER → JOURNEYMAN among workers,
        //    highest-skill wins.
        register(ANY_CULTURE, ANY_BUSINESS_TYPE, (business, level) -> {
            UUID bestUuid = null;
            int bestTierOrdinal = -1;
            for (var worker : business.getWorkers()) {
                if (worker.tier() == EmploymentTier.APPRENTICE) continue;
                TownspersonMob m = TownspersonMob.findByUUID(level, worker.npcId()).orElse(null);
                if (m == null || !m.isAlive()) continue;
                int tierOrd = worker.tier().ordinal(); // MASTER=2, JOURNEYMAN=1
                if (tierOrd > bestTierOrdinal) {
                    bestTierOrdinal = tierOrd;
                    bestUuid = worker.npcId();
                }
            }
            return bestUuid == null
                    ? Optional.empty()
                    : Optional.of(new SuccessionResult.HeirFound(bestUuid));
        });

        // 4. VILLAGE_ALLOCATION_POOL: park instead of auto-dissolving.
        //    Disabled by default to preserve legacy "no heir → dissolve"
        //    behavior. Re-enable in content packs that want lenient
        //    succession.
        // register(ANY_CULTURE, ANY_BUSINESS_TYPE, (business, level) ->
        //         Optional.of(SuccessionResult.TransferredToPool.INSTANCE));
    }
}
