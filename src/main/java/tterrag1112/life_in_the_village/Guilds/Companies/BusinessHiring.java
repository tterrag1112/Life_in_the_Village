package tterrag1112.life_in_the_village.Guilds.Companies;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Career.CareerTransitions;
import tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Shared hiring logic that wires an NPC into a {@link Business} as a
 * {@link Business.BusinessWorker}.
 *
 * <p>Callers:
 * <ul>
 *   <li>{@code BusinessActionPacket.HIRE_NPC} — direct UI hire from the
 *       management screen (delegates here; behaviour identical to before).</li>
 *   <li>{@code NpcInteractionHandler.handleJobContract} — right-click hire
 *       via a business-recruit {@code JobContractItem}.</li>
 * </ul>
 */
public final class BusinessHiring {

    private BusinessHiring() {}

    /**
     * Hires {@code npc} into {@code business} with the given {@code role}.
     *
     * <p>Pre-conditions the caller must satisfy:
     * <ul>
     *   <li>The business exists and the calling player owns it.</li>
     *   <li>The NPC exists in {@code level}.</li>
     * </ul>
     *
     * @return {@code true} if the hire succeeded; {@code false} if the NPC
     *         is already a business worker (no side-effects on false).
     */
    public static boolean hireIntoBusiness(ServerLevel level,
                                           Business business,
                                           TownspersonMob npc,
                                           Business.WorkerRole role,
                                           VillageSavedData vdata,
                                           BusinessSavedData cdata) {
        if (npc.isBusinessWorker()) return false;

        long wage = Math.max(business.getEffectiveMinWage(vdata), 8L);

        Business.BusinessWorker worker = new Business.BusinessWorker(
                npc.getUUID(),
                role,
                Business.ProducerType.GENERIC,
                Business.NO_BUILDING,
                wage,
                level.getGameTime(),
                "",
                8,
                EmploymentTier.APPRENTICE);

        business.addWorker(worker);
        npc.setBusinessId(business.getBusinessId());
        CareerTransitions.changeProfession(
                npc, Profession.COMPANY_WORKER,
                ProfessionChangeRequest.Reason.BUSINESS_PROMOTION,
                ProfessionChangeRequest.Source.PLAYER);
        cdata.markDirty();
        return true;
    }
}
