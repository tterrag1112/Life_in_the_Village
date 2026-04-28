package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.UUID;

/**
 * Doc 15 §"Player-requested repaint" — ownership / eligibility check
 * for the repaint UI confirm button.
 *
 * <p>Returns {@code true} when the building is either:</p>
 * <ul>
 *   <li>player-owned by the requesting player ({@link
 *       VillageSavedData#getPropertyForBuilding} matches), or</li>
 *   <li>unowned — no player property record AND no NPC has the
 *       building registered as their {@code houseId}.</li>
 * </ul>
 *
 * <p>Buildings owned by an NPC household return {@code false}.
 * Doc 15 reserves NPC-relationship-gated repaint of others' homes
 * for a future feature; this gate is the conservative cut.</p>
 */
public final class RepaintAuthorization {

    private RepaintAuthorization() {}

    public static boolean isRepaintAllowed(ServerLevel level,
                                           Building building,
                                           Player player) {
        if (level == null || building == null || player == null) return false;
        VillageSavedData data = VillageSavedData.get(level);
        UUID buildingId = building.getId();

        var propOpt = data.getPropertyForBuilding(buildingId);
        if (propOpt.isPresent()) {
            return propOpt.get().playerId().equals(player.getUUID());
        }

        // Unowned — only allowed when no NPC has claimed it as their
        // house. Mirrors HousePurchaseManager's NPC-occupancy check.
        AABB scan = building.getShape().toAABB().inflate(32);
        boolean npcOccupied = !level.getEntitiesOfClass(
                TownspersonMob.class, scan,
                npc -> npc.getHouseId()
                        .map(id -> id.equals(buildingId))
                        .orElse(false)).isEmpty();
        return !npcOccupied;
    }
}
