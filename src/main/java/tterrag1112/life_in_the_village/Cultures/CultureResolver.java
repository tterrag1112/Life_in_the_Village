package tterrag1112.life_in_the_village.Cultures;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureApprenticeshipNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureEconomicNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureLawDefaults;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureVisitorAffinity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Phase 5 doc 31 — single-entry behavior resolver. Subsystems call
 * the static accessors here instead of reaching into a {@link Culture}
 * record themselves; that keeps the per-culture lookup logic
 * (NPC → village → kingdom fallback) in one place.
 *
 * <p>Lives in the {@code Cultures} package so it doesn't collide with
 * the unrelated {@code Village.CultureResolver} which is a structure-
 * template path resolver. Both classes are referenced by their
 * fully-qualified package; no source file imports both.</p>
 */
public final class CultureResolver {

    private CultureResolver() {}

    // ── Resolution ────────────────────────────────────────────────────────

    /** Resolves the culture for an NPC. Reads via the NPC's assigned
     *  village → village's kingdom → registry default chain. NPCs with
     *  no village context get the {@code default} culture. */
    public static Culture of(TownspersonMob npc) {
        if (npc == null) return CultureRegistry.getOrDefault(null);
        if (!(npc.level() instanceof ServerLevel level)) {
            return CultureRegistry.getOrDefault(null);
        }
        return npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(v -> of(level, v))
                .orElseGet(() -> CultureRegistry.getOrDefault(null));
    }

    /** Resolves the culture for a village. Looks up the village's
     *  kingdom (if any) and reads the kingdom culture; falls back to
     *  the default registry entry when no kingdom is set. */
    public static Culture of(ServerLevel level, Village village) {
        if (village == null) return CultureRegistry.getOrDefault(null);
        return VillageSavedData.get(level).getKingdomForVillage(village.getId())
                .map(Kingdom::getCulture)
                .map(CultureRegistry::getOrDefault)
                .orElseGet(() -> CultureRegistry.getOrDefault(null));
    }

    public static Culture ofKingdom(Kingdom kingdom) {
        if (kingdom == null) return CultureRegistry.getOrDefault(null);
        return CultureRegistry.getOrDefault(kingdom.getCulture());
    }

    // ── Hook accessors ────────────────────────────────────────────────────

    /** Phase 5 spec line 281: office election consults the culture's
     *  preferred selection before the office definition's default. */
    public static Optional<SelectionMethod> selectionFor(String cultureId, String officeId) {
        if (cultureId == null || officeId == null) return Optional.empty();
        return CultureRegistry.get(cultureId)
                .flatMap(c -> c.officeRules().selectionFor(officeId));
    }

    /** Phase 5 spec line 286: village founding enacts these laws. */
    public static CultureLawDefaults lawDefaultsFor(Culture culture) {
        return culture == null ? CultureLawDefaults.EMPTY : culture.lawDefaults();
    }

    /** Phase 5 spec line 292: ChannelRouter / VillageSimEngine read
     *  the haggling / consumption / luxury fields. */
    public static CultureEconomicNorms economicNormsFor(Culture culture) {
        return culture == null ? CultureEconomicNorms.NEUTRAL : culture.economicNorms();
    }

    /** Phase 5 spec line 300: HobbyCatalogue multiplies trait score. */
    public static float hobbyWeightFor(Culture culture, String hobbyId) {
        if (culture == null || hobbyId == null) return 1f;
        return culture.hobbyWeights().weightFor(hobbyId);
    }

    /** Phase 5 spec line 305: VisitorFluxEngine multiplies typeWeights. */
    public static float visitorAffinityFor(Culture culture, VisitorType type) {
        if (culture == null || type == null) return 1f;
        return culture.visitorAffinity().multiplierFor(type);
    }

    public static CultureVisitorAffinity visitorAffinityFor(Culture culture) {
        return culture == null ? CultureVisitorAffinity.NEUTRAL : culture.visitorAffinity();
    }

    /** Phase 5 spec line 310: ApprenticeshipContract reads norms. */
    public static CultureApprenticeshipNorms apprenticeshipNormsFor(Culture culture) {
        return culture == null ? CultureApprenticeshipNorms.DEFAULT : culture.apprenticeshipNorms();
    }

    /** Phase 5 spec line 316: Religion id NPC piety should seed at
     *  spawn. Replaces the legacy
     *  {@code ReligionRegistry.dominantReligionFor} switch. */
    public static String religionFor(Culture culture) {
        if (culture == null) return "sunstead";
        return culture.religion().religionId();
    }
}
