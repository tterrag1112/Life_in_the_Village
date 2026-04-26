package tterrag1112.life_in_the_village.Npc.Office.Selection;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;

import java.util.UUID;

/**
 * Carrying envelope for one selection run. Built by {@link
 * tterrag1112.life_in_the_village.Npc.Office.OfficeElection} and consumed by
 * {@link OfficeSelectionEngine} implementations.
 *
 * <p>Holds the level and saved-data references the engines need to walk
 * candidate pools (loaded NPCs, household heads, council members) without
 * reaching into static helpers.</p>
 *
 * @param level     the server level the org lives on
 * @param vdata     {@link VillageSavedData} for cross-org lookups
 * @param orgType   {@link OrgType#VILLAGE}, {@link OrgType#GUILD}, etc.
 * @param orgId     UUID of the org instance
 * @param orgState  mutable office state for the org (engines do not write
 *                  it; the driver does, after picking a winner)
 * @param def       definition for the office being filled
 * @param tick      current game tick (used for term-end calculation)
 */
public record OfficeSelectionContext(ServerLevel level,
                                     VillageSavedData vdata,
                                     OrgType orgType,
                                     UUID orgId,
                                     OfficeState orgState,
                                     OfficeDefinition def,
                                     long tick) {
}
