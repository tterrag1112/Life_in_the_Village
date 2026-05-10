package tterrag1112.life_in_the_village.Kingdom.Merger;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Audience.Petition;
import tterrag1112.life_in_the_village.Kingdom.Audience.PetitionKind;
import tterrag1112.life_in_the_village.Kingdom.Audience.PetitionPayload;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Heraldry;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Rebellion.CollapseEngine;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.3 — kingdom merger engine.
 *
 * <p>Three merger paths per the locked design:
 * <ul>
 *   <li><b>Marriage-union</b>: detected when a house head in one
 *       kingdom marries the head of another's ruling house AND
 *       both kingdoms' succession would pick the same heir. v1
 *       simplification: triggers on any cross-kingdom royal
 *       marriage where the spouse's kingdom is small and the
 *       other large; future refinement adds true succession-
 *       resolved cross-kingdom heir detection.</li>
 *   <li><b>Voluntary union</b>: weak kingdom petitions a strong
 *       neighbour via {@link #createVoluntaryUnionRequest}. The
 *       receiving ruler's approval calls {@link #absorbInto}.</li>
 *   <li><b>Conquest</b>: war shell (Slice 2) calls
 *       {@link #absorbInto} directly with mergerPath="conquest"
 *       once a war resolves in attacker's favor.</li>
 * </ul>
 *
 * <p>Per the locked design "war wins, marriage-union deferred":
 * {@link #canMerge} checks for active WAR relation between the
 * two kingdoms and refuses if found. The deferred marriage-union
 * gets rechecked the day after the war ends.
 */
public final class MergerEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Voluntary-union petition expires after this many days if not resolved. */
    public static final long UNION_PETITION_TIMEOUT_TICKS = 14L * 24000L;

    /** Cooldown after a failed/rejected voluntary union before retry. */
    public static final long UNION_RETRY_COOLDOWN_TICKS = 30L * 24000L;

    /** Stability+legitimacy below this combined value qualifies as "weak". */
    public static final int WEAK_KINGDOM_THRESHOLD = 80; // 40 stab + 40 leg approx

    private MergerEngine() {}

    // =========================================================================
    // Path 1 — marriage-union
    // =========================================================================

    /**
     * Triggers a marriage-union merger between two kingdoms when an
     * NPC inherits both thrones. Per the locked design, this path
     * is symbolic in v1 — the smaller of the two kingdoms absorbs
     * into the larger; the larger's capital becomes the merged
     * capital; heraldry is quartered.
     */
    public static UUID triggerMarriageUnion(ServerLevel level, VillageSavedData data,
                                            Kingdom a, Kingdom b, long tick) {
        if (!canMerge(a, b)) return null;
        Kingdom survivor = (a.getVillageIds().size() >= b.getVillageIds().size()) ? a : b;
        Kingdom mergedAway = (survivor == a) ? b : a;

        // Quartered heraldry — survivor's field + mergedAway's chargeColour.
        Heraldry quartered = new Heraldry(
                survivor.getHeraldry().field(),
                mergedAway.getHeraldry().chargeColour(),
                survivor.getHeraldry().primaryCharge(),
                Heraldry.Layout.QUARTERED);
        survivor.setHeraldry(quartered);

        absorbInto(level, data, survivor, mergedAway, tick, "marriage_union");
        return survivor.getId();
    }

    // =========================================================================
    // Path 2 — voluntary union
    // =========================================================================

    /**
     * Creates a {@link PetitionPayload.VoluntaryUnion} petition on
     * the strong kingdom's audience queue, asking for absorption
     * of the weak kingdom. Returns the petition UUID, or empty if
     * a recent attempt exists (cooldown).
     */
    public static UUID createVoluntaryUnionRequest(Kingdom weak, Kingdom strong,
                                                    long tick, String reason) {
        // Respect retry cooldown: don't spam.
        for (Petition p : strong.getPetitions()) {
            if (p.kind() != PetitionKind.VOLUNTARY_UNION) continue;
            if (!(p.payload() instanceof PetitionPayload.VoluntaryUnion vu)) continue;
            if (!vu.petitioningKingdomId().equals(weak.getId())) continue;
            if (tick - p.submittedTick() < UNION_RETRY_COOLDOWN_TICKS) return null;
        }
        UUID pid = UUID.randomUUID();
        var payload = new PetitionPayload.VoluntaryUnion(weak.getId(), reason);
        strong.submitPetition(Petition.freshSubmission(pid,
                weak.getRulerEntityId().orElse(weak.getId()),
                strong.getId(), payload, tick));
        KingdomEventBus.fire(new KingdomEvent.UnionRequest(
                weak.getId(), strong.getId(), pid, tick));
        KingdomNewsfeed.append(strong, "union.requested",
                weak.getName() + " has petitioned for absorption", tick);
        KingdomNewsfeed.append(weak, "union.petitioned",
                "Sent absorption petition to " + strong.getName(), tick);
        return pid;
    }

    /**
     * Resolves an approved VOLUNTARY_UNION petition. Called from
     * {@code AudienceDriver.applyApproval} when the strong
     * kingdom's ruler approves.
     */
    public static UUID approveVoluntaryUnion(ServerLevel level, VillageSavedData data,
                                             Kingdom strong, UUID weakKingdomId, long tick) {
        Kingdom weak = data.getKingdomById(weakKingdomId).orElse(null);
        if (weak == null) return null;
        if (!canMerge(strong, weak)) return null;
        absorbInto(level, data, strong, weak, tick, "voluntary_union");
        return strong.getId();
    }

    // =========================================================================
    // Path 3 — conquest (called from Slice 2's WarEngine on win)
    // =========================================================================

    /**
     * Called by the war engine when a conquest casus belli
     * resolves in the attacker's favor. Attacker absorbs defender
     * via the same {@link #absorbInto} path.
     */
    public static UUID triggerConquestMerger(ServerLevel level, VillageSavedData data,
                                             Kingdom attacker, Kingdom defender, long tick) {
        if (!canMerge(attacker, defender)) return null;
        absorbInto(level, data, attacker, defender, tick, "conquest");
        return attacker.getId();
    }

    // =========================================================================
    // Driver — voluntary-union petition creation on weak kingdoms
    // =========================================================================

    /**
     * Daily tick scans for weak kingdoms with strong stable
     * neighbours; eligible pairs get a {@code VOLUNTARY_UNION}
     * petition created.
     */
    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom weak : new ArrayList<>(data.getAllKingdoms())) {
            if (weak.hasModifierWithId(CollapseEngine.COLLAPSED_MODIFIER_ID)) continue;
            int combined = weak.getStability() + weak.getLegitimacy()
                    + weak.stabilityModifierSum() + weak.legitimacyModifierSum();
            if (combined > WEAK_KINGDOM_THRESHOLD) continue;
            // Find a strong neighbour with cooperative relation.
            for (var entry : weak.getAllRelations().entrySet()) {
                if (entry.getValue() == DiplomaticRelation.WAR
                        || entry.getValue() == DiplomaticRelation.COLD_WAR) continue;
                Kingdom strong = data.getKingdomById(entry.getKey()).orElse(null);
                if (strong == null) continue;
                if (strong.getStability() + strong.getLegitimacy() < combined + 40) continue;
                createVoluntaryUnionRequest(weak, strong, tick,
                        "stability " + weak.getStability()
                                + " + legitimacy " + weak.getLegitimacy());
                break; // one petition per day per weak kingdom
            }
        }
    }

    // =========================================================================
    // Common absorb path
    // =========================================================================

    /**
     * Absorbs {@code mergedAway} into {@code survivor}: villages
     * transfer, charters / treaties on the merged-away side break,
     * houses move over with prestige preserved, mergedAway marked
     * collapsed-history per
     * {@link CollapseEngine#COLLAPSED_MODIFIER_ID}. Fires
     * {@link KingdomEvent.KingdomMerged}.
     */
    public static void absorbInto(ServerLevel level, VillageSavedData data,
                                  Kingdom survivor, Kingdom mergedAway,
                                  long tick, String mergerPath) {
        if (survivor == null || mergedAway == null) return;
        if (survivor.getId().equals(mergedAway.getId())) return;

        // Transfer villages.
        List<UUID> transferred = new ArrayList<>(mergedAway.getVillageIds());
        for (UUID villageId : transferred) {
            data.getVillageById(villageId).ifPresent(v -> v.setKingdomId(survivor.getId()));
            mergedAway.removeVillage(villageId);
            survivor.addVillage(villageId);
        }
        // Transfer houses (prestige preserved).
        for (House h : new ArrayList<>(mergedAway.getHouses())) {
            survivor.addHouse(h);
        }
        // Break mergedAway charters and treaties.
        for (var c : new ArrayList<>(mergedAway.getCharters())) {
            if (c.active()) mergedAway.revokeCharter(c.id(), tick);
        }
        for (var t : new ArrayList<>(mergedAway.getTreaties())) {
            if (!t.broken()) {
                mergedAway.breakTreaty(t.id(), mergedAway.getId(), tick,
                        "merger: " + mergerPath);
            }
        }
        // Force province recompute on survivor.
        survivor.setLastProvinceRecomputeTick(-1L);
        // Mark mergedAway as collapsed-history.
        mergedAway.addModifier(KingdomModifier.permanent(
                CollapseEngine.COLLAPSED_MODIFIER_ID,
                "Merged into " + survivor.getName() + " (" + mergerPath + ")",
                0, 0, tick));
        mergedAway.setStability(0);
        mergedAway.setLegitimacy(0);

        KingdomEventBus.fire(new KingdomEvent.KingdomMerged(
                survivor.getId(), mergedAway.getId(), mergerPath, tick));
        KingdomNewsfeed.append(survivor, "kingdom.merged",
                "Absorbed " + mergedAway.getName() + " (" + mergerPath + ")", tick);
        KingdomNewsfeed.append(mergedAway, "kingdom.merged",
                "Merged into " + survivor.getName() + " (" + mergerPath + ")", tick);
        data.setDirty();

        LOGGER.info("[MergerEngine] {} absorbed {} ({})",
                survivor.getName(), mergedAway.getName(), mergerPath);
    }

    /** Merger preconditions — both alive, no active WAR between them. */
    public static boolean canMerge(Kingdom a, Kingdom b) {
        if (a == null || b == null) return false;
        if (a.getId().equals(b.getId())) return false;
        if (a.hasModifierWithId(CollapseEngine.COLLAPSED_MODIFIER_ID)) return false;
        if (b.hasModifierWithId(CollapseEngine.COLLAPSED_MODIFIER_ID)) return false;
        // Per the locked design "war wins": don't merge during war.
        if (a.getRelation(b.getId()) == DiplomaticRelation.WAR) return false;
        return true;
    }
}
