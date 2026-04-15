package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;

import java.util.*;

/**
 * Resolves which atlas cells belong to a kingdom.
 *
 * <p>Kingdoms now carry a {@link KingdomClaim} computed at spawn time
 * by Dijkstra expansion over the atlas. This provider just reads it.
 */
public interface KingdomBoundsProvider {

    Set<Long> getOwnedCells(Kingdom kingdom);

    /** Default implementation — reads the authoritative claim. */
    final class ClaimBasedProvider implements KingdomBoundsProvider {
        @Override
        public Set<Long> getOwnedCells(Kingdom kingdom) {
            return kingdom.getTerritorialClaim()
                    .map(KingdomClaim::claimedCellKeys)
                    .map(list -> (Set<Long>) new LinkedHashSet<>(list))
                    .orElse(Collections.emptySet());
        }
    }
}