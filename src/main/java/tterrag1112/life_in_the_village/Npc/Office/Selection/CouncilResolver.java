package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the "council body" for {@link tterrag1112.life_in_the_village.Npc.Office.SelectionMethod#COUNCIL}
 * elections per spec section "Things to flag":
 *
 * <pre>
 * Council composition for COUNCIL selection. Who's on the council? Spec
 * should specify. Likely guild-aligned NPCs for guild_master, household
 * heads for village_leader, etc.
 * </pre>
 *
 * <p>v1 mapping decided this session and documented in 06 Revision Notes:</p>
 * <ul>
 *   <li><b>VILLAGE_LEADER (council fallback):</b> household heads in the
 *   village.</li>
 *   <li><b>GUILD_MASTER (council):</b> existing guild members (current
 *   guildmaster + master_of_apprentices + any guild office holders).</li>
 *   <li><b>KINGDOM_KING (council):</b> council seat holders if any;
 *   otherwise village leaders of every village in the kingdom.</li>
 *   <li><b>KINGDOM_COUNCIL_SEAT:</b> village leaders of every village in
 *   the kingdom (each seat is one leader voting on themself / nominee).</li>
 *   <li><b>OTHER:</b> empty — engine returns no winner.</li>
 * </ul>
 */
public final class CouncilResolver {

    private CouncilResolver() {}

    public static List<TownspersonMob> resolve(OfficeSelectionContext ctx) {
        return switch (ctx.def().id()) {
            case OfficeRegistry.VILLAGE_LEADER -> villageHouseholdHeads(ctx);
            case OfficeRegistry.GUILD_MASTER, OfficeRegistry.MASTER_OF_APPRENTICES ->
                    guildMembers(ctx);
            case OfficeRegistry.KINGDOM_KING, OfficeRegistry.KINGDOM_COUNCIL_SEAT ->
                    kingdomVillageLeaders(ctx);
            default -> List.of();
        };
    }

    // ── Per-org-type collectors ───────────────────────────────────────────

    private static List<TownspersonMob> villageHouseholdHeads(OfficeSelectionContext ctx) {
        if (ctx.orgType() != OrgType.VILLAGE) return List.of();
        Village v = ctx.vdata().getVillageById(ctx.orgId()).orElse(null);
        if (v == null) return List.of();
        String villageName = v.getName();
        List<TownspersonMob> out = new ArrayList<>();
        for (var entity : ctx.level().getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.isAdult()) continue;
            if (npc.getFamilyRole() != FamilyRole.HEAD) continue;
            if (npc.getAssignedVillageName().filter(n -> n.equals(villageName)).isPresent()) {
                out.add(npc);
            }
        }
        return out;
    }

    private static List<TownspersonMob> guildMembers(OfficeSelectionContext ctx) {
        if (ctx.orgType() != OrgType.GUILD) return List.of();
        GuildData g = ctx.vdata().getGuildById(ctx.orgId()).orElse(null);
        if (g == null) return List.of();
        Optional<Village> villageOpt = ctx.vdata().getVillageById(g.villageId());
        if (villageOpt.isEmpty()) return List.of();
        String villageName = villageOpt.get().getName();
        List<TownspersonMob> out = new ArrayList<>();
        for (var entity : ctx.level().getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.isAdult()) continue;
            // v1 guild-membership proxy: profession is in the GUILD pool
            // and the NPC is assigned to the guild's village. Phase 2's
            // guild refactor (doc 27) replaces this with explicit
            // membership records; until then this is the best signal.
            Profession p = npc.getProfession();
            if (p != Profession.GUILDMASTER && p != Profession.GUILDWORKER && p != Profession.ADVENTURER) continue;
            if (npc.getAssignedVillageName().filter(n -> n.equals(villageName)).isPresent()) {
                out.add(npc);
            }
        }
        return out;
    }

    private static List<TownspersonMob> kingdomVillageLeaders(OfficeSelectionContext ctx) {
        Kingdom k = ctx.vdata().getKingdomById(ctx.orgId()).orElse(null);
        if (k == null) return List.of();
        List<TownspersonMob> out = new ArrayList<>();
        for (UUID vid : k.getVillageIds()) {
            Village v = ctx.vdata().getVillageById(vid).orElse(null);
            if (v == null) continue;
            // Use OfficeState's authoritative answer for "who is the
            // village leader" — falls back to the legacy field via the
            // codec migration during the one-release window.
            UUID leaderId = v.getOffices().get(OfficeRegistry.VILLAGE_LEADER)
                    .flatMap(tterrag1112.life_in_the_village.Npc.Office.OfficeHolding::holderNpcId)
                    .orElse(v.getVillageLeaderId().orElse(null));
            if (leaderId == null) continue;
            TownspersonMob leader = TownspersonMob.findByUUID(ctx.level(), leaderId).orElse(null);
            if (leader != null && leader.isAdult()) out.add(leader);
        }
        return out;
    }

}
