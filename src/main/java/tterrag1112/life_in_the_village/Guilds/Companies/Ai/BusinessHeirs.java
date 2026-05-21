package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessOwner;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.3.3.c.5 — heir-chain population helper. Per inspection
 * 6.3.3.0.III, {@code Business.heirs} had API but no caller. This
 * helper is the canonical population point, invoked from every owner-
 * setup callsite (MerchantPromotion / FarmerPromotion / future
 * promotion paths).
 *
 * <h3>Ordering rules</h3>
 * <ul>
 *   <li><strong>NpcOwner</strong>: heirs = the NPC's adult children
 *       (descending age), then adult siblings, then parents. Inspects
 *       the owner NPC's FamilyComponent.</li>
 *   <li><strong>FamilyOwner</strong>: heirs = household adult members
 *       (descending age). Children of household members aren't first-
 *       class heirs in v1; they inherit by becoming adults later
 *       (refresh fires on lifestage transitions — TODO 6.3.3.c.5
 *       follow-up).</li>
 *   <li><strong>PlayerOwner</strong>: heir list left empty. Player
 *       death isn't modelled; succession doesn't apply.</li>
 *   <li><strong>VillageOwner / KingdomOwner / GuildOwner</strong>:
 *       heir list left empty. Collective ownership delegates to its
 *       owning subsystem.</li>
 * </ul>
 */
public final class BusinessHeirs {

    private BusinessHeirs() {}

    /** Repopulates {@code business.heirs} based on the current owner. */
    public static void populateForOwner(ServerLevel level, Business business) {
        if (business == null || business.getOwner() == null) return;
        VillageSavedData vdata = VillageSavedData.get(level);
        List<UUID> heirs = switch (business.getOwner()) {
            case BusinessOwner.NpcOwner n     -> collectNpcHeirs(level, vdata, n.npcUuid());
            case BusinessOwner.FamilyOwner f  -> collectFamilyHeirs(level, vdata, f.householdId(), null);
            case BusinessOwner.PlayerOwner p  -> List.of();
            case BusinessOwner.VillageOwner v -> List.of();
            case BusinessOwner.KingdomOwner k -> List.of();
            case BusinessOwner.GuildOwner   g -> List.of();
        };
        business.setHeirs(heirs);
    }

    private static List<UUID> collectNpcHeirs(ServerLevel level, VillageSavedData vdata,
                                              UUID ownerUuid) {
        // Adult children first, ordered descending by age.
        TownspersonMob owner = TownspersonMob.findByUUID(level, ownerUuid).orElse(null);
        if (owner == null) return List.of();
        List<UUID> heirs = new ArrayList<>();
        // Children: pull from FamilyComponent, sort by age desc.
        var family = owner.getFamily();
        List<UUID> childIds = new ArrayList<>(family.getChildrenIds());
        sortByAgeDesc(level, childIds);
        for (UUID id : childIds) {
            TownspersonMob c = TownspersonMob.findByUUID(level, id).orElse(null);
            if (c != null && c.getLifeStage() != LifeStage.CHILD) heirs.add(id);
        }
        // Then household-co-resident adults (siblings / spouse).
        vdata.getHouseholdForNpc(ownerUuid).ifPresent(h -> {
            List<UUID> extras = new ArrayList<>(h.getMemberNpcIds());
            extras.remove(ownerUuid);
            extras.removeAll(heirs);
            sortByAgeDesc(level, extras);
            for (UUID id : extras) {
                TownspersonMob m = TownspersonMob.findByUUID(level, id).orElse(null);
                if (m != null && m.getLifeStage() != LifeStage.CHILD) heirs.add(id);
            }
        });
        return heirs;
    }

    private static List<UUID> collectFamilyHeirs(ServerLevel level, VillageSavedData vdata,
                                                  UUID householdId, UUID excludeUuid) {
        HouseholdData h = null;
        for (HouseholdData candidate : vdata.getAllHouseholds()) {
            if (householdId.equals(candidate.getHouseholdId())) { h = candidate; break; }
        }
        if (h == null) return List.of();
        List<UUID> ids = new ArrayList<>(h.getMemberNpcIds());
        if (excludeUuid != null) ids.remove(excludeUuid);
        sortByAgeDesc(level, ids);
        List<UUID> heirs = new ArrayList<>();
        for (UUID id : ids) {
            TownspersonMob m = TownspersonMob.findByUUID(level, id).orElse(null);
            if (m != null && m.getLifeStage() != LifeStage.CHILD) heirs.add(id);
        }
        return heirs;
    }

    private static void sortByAgeDesc(ServerLevel level, List<UUID> ids) {
        ids.sort(Comparator.comparingInt((UUID id) -> {
            TownspersonMob m = TownspersonMob.findByUUID(level, id).orElse(null);
            return m == null ? -1 : m.getAge();
        }).reversed());
    }
}
