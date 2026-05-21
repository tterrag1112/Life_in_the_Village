package tterrag1112.life_in_the_village.Entities.custom.Appearance;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5 doc 33 § Rebuild triggers.
 *
 * <p>One-stop static helper that recomputes an NPC's Layer-1
 * appearance by walking every org's {@link OfficeState} for the
 * offices the NPC currently holds, resolving culture via
 * {@link CultureResolver}, and forwarding the data to
 * {@link AppearanceComponent#rebuild}.</p>
 */
public final class AppearanceRebuilder {

    private AppearanceRebuilder() {}

    /**
     * Recomputes Layer-1 fields for {@code npc}. Safe to call from any
     * thread that holds the server tick — does not yield.
     *
     * @return {@code true} if the rebuild was performed (i.e. the NPC
     *         is in a server level and has an AppearanceComponent).
     */
    public static boolean rebuild(TownspersonMob npc) {
        if (npc == null) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;

        AppearanceLayerRegistry.ensureInit();

        String cultureId;
        try {
            cultureId = CultureResolver.of(npc).id();
        } catch (RuntimeException ex) {
            cultureId = "default";
        }

        List<String> offices = collectOfficesHeldBy(level, npc.getUUID());
        String stage = npc.getLifeStage() == null ? "" : npc.getLifeStage().name();

        npc.getAppearance().rebuild(cultureId, stage, offices, level.getGameTime());
        return true;
    }

    /**
     * Walks every Village / Guild / Business / Kingdom on the world and
     * returns every office id currently held by the given UUID across
     * all orgs. Insertion order is village → guild → business → kingdom.
     * Used both by the rebuilder and by debug commands.
     */
    public static List<String> collectOfficesHeldBy(ServerLevel level, UUID holderId) {
        List<String> out = new ArrayList<>();
        if (holderId == null) return out;

        VillageSavedData vdata = VillageSavedData.get(level);

        for (Village v : vdata.getAllVillages()) {
            collectFromState(v.getOffices(), holderId, out);
        }
        for (GuildData g : vdata.getAllGuilds()) {
            collectFromState(g.offices(), holderId, out);
        }
        for (Kingdom k : vdata.getAllKingdoms()) {
            collectFromState(k.getOffices(), holderId, out);
        }
        try {
            BusinessSavedData cdata = BusinessSavedData.get(level);
            for (Business c : cdata.getAllBusinesses()) {
                collectFromState(c.getOffices(), holderId, out);
            }
        } catch (RuntimeException ignored) {
            // Companies optional — survive if the saved data isn't loaded.
        }
        return out;
    }

    private static void collectFromState(OfficeState state, UUID holderId, List<String> out) {
        if (state == null) return;
        for (OfficeHolding h : state.holdingsHeldBy(holderId)) {
            String id = h.officeId();
            if (id != null && !id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
    }
}
