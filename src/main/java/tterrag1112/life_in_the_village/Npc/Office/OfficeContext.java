package tterrag1112.life_in_the_village.Npc.Office;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Phase 6.3.2.d — envelope passed to an
 * {@link OfficeEligibilityPredicate}. Carries everything a predicate
 * plausibly needs to evaluate a candidate without reaching into static
 * helpers: the office, the org scope, the selection method that's
 * driving the seat, and the culture id resolvable from the scope.
 *
 * <p>Mirrors the shape of {@link
 * tterrag1112.life_in_the_village.Npc.Office.Selection.OfficeSelectionContext}
 * (the existing election context) but is intentionally narrower —
 * predicates evaluate a single candidate, not a candidate pool, and
 * don't need the OfficeState handle.
 *
 * @param def              office definition being seated
 * @param orgType          which org-class owns the office (village /
 *                         kingdom / guild / company)
 * @param orgId            the org instance UUID
 * @param selectionMethod  the method that produced this candidate
 *                         (election / appointment / inheritance / debug)
 * @param cultureId        optional culture id resolved from the scope;
 *                         {@code null} when culture is not applicable
 *                         (e.g. guild offices)
 * @param level            server level (for predicate look-ups that
 *                         walk savedata)
 * @param vdata            village savedata (cross-org lookups)
 */
public record OfficeContext(OfficeDefinition def,
                            OrgType orgType,
                            UUID orgId,
                            SelectionMethod selectionMethod,
                            @Nullable String cultureId,
                            ServerLevel level,
                            VillageSavedData vdata) {
}
