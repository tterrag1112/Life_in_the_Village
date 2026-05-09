package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Nobility.SuccessionResolver;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Passes the office to the deceased / departing holder's heir. Heir
 * resolution uses the {@link tterrag1112.life_in_the_village.Entities.custom.FamilyComponent}
 * of the previous holder:
 *
 * <ul>
 *   <li>Eligible adult children, sorted oldest-first.</li>
 *   <li>If none, falls back to MERITOCRATIC ("succession crisis" per spec
 *   — recorded in the holding's {@code actualSelection} as MERITOCRATIC
 *   so the history reads correctly).</li>
 * </ul>
 *
 * <p>Phase 3 spec note: "will-overridable in scribal contracts later" is
 * deferred. The Phase 0 OfficeHolding has no designated-heir field; the
 * Phase 5 scribal-contract pass will add the override.</p>
 */
public final class HereditarySelection implements OfficeSelectionEngine {

    @Override public SelectionMethod method() { return SelectionMethod.HEREDITARY; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        // The previous-holder reference comes from the existing (now-vacant)
        // holding — the driver vacates BEFORE invoking the engine, so we
        // read the holder UUID off the prior holding kept in the codec'd
        // history. v1 simplification: read the latest holding regardless
        // of vacant status.
        OfficeHolding existing = ctx.orgState().get(ctx.def().id()).orElse(null);
        UUID heirOf = existing != null
                ? existing.holderUuid().orElse(null)
                : null;
        if (heirOf != null) {
            Optional<TownspersonMob> heir = pickHeir(ctx, heirOf);
            if (heir.isPresent()) {
                long termEnd = ctx.def().isIndefiniteTerm()
                        ? 0L
                        : ctx.tick() + (long) ctx.def().termDays() * 24000L;
                return Optional.of(OfficeHolding.heldByNpc(
                        ctx.def().id(), ctx.orgId(), heir.get().getUUID(),
                        ctx.tick(), termEnd, SelectionMethod.HEREDITARY));
            }
        }
        // Succession crisis. Spec: fall back to council, then merit.
        Optional<OfficeHolding> council = new CouncilSelection().select(ctx);
        if (council.isPresent()) return council;
        return new MeritocraticSelection().select(ctx);
    }

    private static Optional<TownspersonMob> pickHeir(OfficeSelectionContext ctx, UUID predecessorId) {
        TownspersonMob predecessor = TownspersonMob.findByUUID(ctx.level(), predecessorId).orElse(null);
        if (predecessor == null) return Optional.empty();
        // Track D3.2b — culture's SuccessionRule drives heir filter
        // (PRIMOGENITURE / AGNATIC / SEMI_SALIC). Returns empty for
        // ELECTIVE / COUNCIL / DIVINE_DESIGNATION; caller falls
        // through to CouncilSelection.
        Optional<TownspersonMob> ruleHeir = SuccessionResolver.pickHeir(ctx.level(), predecessor);
        if (ruleHeir.isPresent()) return ruleHeir;
        // Pre-D3.2b fallback: any eligible adult child, oldest first.
        // Retained because some hereditary offices may sit on cultures
        // whose rule is electoral; SuccessionResolver returns empty
        // there, so this fallback still serves them.
        List<UUID> children = predecessor.getFamily().getChildrenIds();
        return children.stream()
                .map(cid -> TownspersonMob.findByUUID(ctx.level(), cid))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(c -> CandidatePool.isEligible(c, ctx.def()) || c.isAdult())
                .max(Comparator.comparingInt(TownspersonMob::getAge));
    }
}
