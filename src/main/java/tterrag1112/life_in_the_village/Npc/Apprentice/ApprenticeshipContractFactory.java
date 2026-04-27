package tterrag1112.life_in_the_village.Npc.Apprentice;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeProductType;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.UUID;

/**
 * Mints the physical {@code WrittenLetter} (LetterSpecial.CONTRACT)
 * for an apprenticeship. Spec line 208.
 *
 * <p>Two paths:</p>
 * <ul>
 *   <li>{@link #mintDirect}: produce the contract item directly,
 *       useful when no scribe is in the village (spec line 222
 *       fallback).</li>
 *   <li>{@link #queueViaScribe}: submit a {@link ScribeCommission}
 *       to the village's scribe workshop so the actual writing
 *       happens through the existing scribal workflow.</li>
 * </ul>
 */
public final class ApprenticeshipContractFactory {

    /** Bronze fee charged when routing through a scribe. */
    public static final long SCRIBE_FEE_BRONZE = 25L;

    private ApprenticeshipContractFactory() {}

    /** Compose the contract body text per spec line 213. */
    public static String composeBody(ApprenticeshipContract c,
                                     String masterName,
                                     String apprenticeName,
                                     String witnessName) {
        long days = c.expectedDurationTicks() / 24000L;
        return "Apprenticeship Contract: " + masterName
                + ", " + c.profession().name().toLowerCase()
                + " takes " + apprenticeName
                + " as apprentice for " + days + " days. "
                + "Terms: " + (c.contractTerms().isEmpty()
                        ? "Standard." : c.contractTerms()) + " "
                + "Witnessed by: "
                + (witnessName == null || witnessName.isEmpty()
                        ? "the village" : witnessName) + ".";
    }

    /** Mint the contract item directly (no scribe involved). */
    public static ItemStack mintDirect(ApprenticeshipContract c,
                                       String masterName, String apprenticeName,
                                       String witnessName) {
        String body = composeBody(c, masterName, apprenticeName, witnessName);
        ItemStack stack = ScribalItems.contract(
                "Apprenticeship", body,
                c.masterId(), masterName,
                c.apprenticeId(), apprenticeName,
                c.startTick());
        return stack;
    }

    /**
     * Routes the contract through the village scribe workshop's
     * commission queue when a scribe is present. Falls back to
     * dropping a directly-minted contract at the master's feet
     * when no workshop exists. Returns the assigned scribe
     * commission id (when routed) or empty (when fallback).
     */
    public static Optional<UUID> queueViaScribe(ServerLevel level,
                                                ApprenticeshipContract c,
                                                TownspersonMob master,
                                                TownspersonMob apprentice) {
        VillageSavedData data = VillageSavedData.get(level);
        Building workshop = master.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(v -> {
                    for (UUID bid : v.getBuildingIds()) {
                        Building b = data.getBuildingById(bid).orElse(null);
                        if (b != null && b.getType() == BuildingType.SCRIBE_WORKSHOP) return b;
                    }
                    return null;
                })
                .orElse(null);
        String body = composeBody(c, master.getNpcName(), apprentice.getNpcName(),
                "the village");
        if (workshop == null) {
            // Fallback path: direct mint, drop at master's feet.
            ItemStack stack = mintDirect(c, master.getNpcName(),
                    apprentice.getNpcName(), "the village");
            ItemEntity drop = new ItemEntity(level,
                    master.getX(), master.getY() + 0.5, master.getZ(), stack);
            level.addFreshEntity(drop);
            return Optional.empty();
        }
        CommissionQueue queue = data.getOrCreateCommissionQueue(workshop.getId());
        ScribeCommission commission = new ScribeCommission(
                UUID.randomUUID(),
                master.getUUID(), false,
                ScribeProductType.CONTRACT,
                body,
                Optional.of(apprentice.getUUID()),
                level.getGameTime(),
                level.getGameTime() + 24000L * 3L,
                SCRIBE_FEE_BRONZE,
                CommissionStatus.PENDING,
                15);
        queue.enqueue(commission);
        data.setDirty();
        return Optional.of(commission.commissionId());
    }

    /**
     * Helper for verbs / debug commands that want to refer to a
     * contract's profession-specific masterpiece target.
     */
    public static String defaultMasterpieceFor(Profession profession) {
        return ApprenticeshipManager.masterpieceTargetFor(profession);
    }
}
