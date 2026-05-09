package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Static registry of every {@link OfficeDefinition}. Populated lazily on
 * first access (no class-load ordering issues with Skill / Profession);
 * idempotent.
 *
 * <p>Also hosts the cross-entity walker
 * {@link #findOfficesHeldBy(UUID, ServerLevel)} that powers
 * {@code /npc offices}. The walker iterates every Village, GuildData,
 * Company, and Kingdom in the level — at v1 scale (low hundreds of orgs)
 * this is a sub-millisecond pass; document if it ever becomes hot.</p>
 */
public final class OfficeRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, OfficeDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    private OfficeRegistry() {}

    // ── Office IDs (string constants for type-safety at call sites) ─────────

    public static final String VILLAGE_LEADER     = "village_leader";
    public static final String VILLAGE_TREASURER  = "village_treasurer";
    public static final String VILLAGE_CONSTABLE  = "village_constable";
    public static final String VILLAGE_BAILIFF    = "village_bailiff";
    public static final String VILLAGE_SCRIBE     = "village_scribe";
    public static final String VILLAGE_PRIEST     = "village_priest";
    public static final String VILLAGE_HEALER     = "village_healer";

    public static final String GUILD_MASTER          = "guild_master";
    public static final String GUILD_TREASURER       = "guild_treasurer";
    public static final String GUILD_REGISTRAR       = "guild_registrar";
    public static final String MASTER_OF_APPRENTICES = "master_of_apprentices";

    public static final String COMPANY_OWNER      = "company_owner";
    public static final String COMPANY_FOREMAN    = "company_foreman";
    public static final String COMPANY_BOOKKEEPER = "company_bookkeeper";

    public static final String KINGDOM_KING         = "kingdom_king";
    public static final String KINGDOM_CHANCELLOR   = "kingdom_chancellor";
    public static final String KINGDOM_TREASURER    = "kingdom_treasurer";
    // Track D1 (Phase 0) — five new kingdom-tier office stubs.
    // Inert this phase: registered for D2's culture-driven required-
    // office checks and D3's behaviour-goal wiring. No active holder
    // logic, no workplace bindings until D3.
    public static final String KINGDOM_SCHOLAR      = "kingdom_scholar";
    public static final String KINGDOM_GENERAL      = "kingdom_general";
    public static final String KINGDOM_MAGISTRATE   = "kingdom_magistrate";
    public static final String KINGDOM_SPYMASTER    = "kingdom_spymaster";
    public static final String KINGDOM_DIPLOMAT     = "kingdom_diplomat";
    public static final String KINGDOM_COUNCIL_SEAT = "kingdom_council_seat";

    public static final String TEMPLE_HIGH_PRIEST = "temple_high_priest";

    // ── Public API ─────────────────────────────────────────────────────────

    public static OfficeDefinition get(String officeId) {
        ensureInit();
        return DEFINITIONS.get(officeId);
    }

    public static List<OfficeDefinition> all() {
        ensureInit();
        return List.copyOf(DEFINITIONS.values());
    }

    public static List<OfficeDefinition> forOrgType(OrgType orgType) {
        ensureInit();
        List<OfficeDefinition> out = new ArrayList<>();
        for (OfficeDefinition def : DEFINITIONS.values()) {
            if (def.orgType() == orgType) out.add(def);
        }
        return Collections.unmodifiableList(out);
    }

    /** Walker result tuple. */
    public record Match(OrgType orgType, UUID orgId, String orgDisplayName,
                        OfficeHolding holding) {}

    /**
     * Returns every office currently held by the given UUID across all
     * orgs persisted in this level. Walks villages, guilds (via
     * {@link VillageSavedData}), kingdoms, and companies (via
     * {@link CompanySavedData}). Temples are stubbed in v1 and produce
     * no matches.
     */
    public static List<Match> findOfficesHeldBy(UUID uuid, ServerLevel level) {
        if (uuid == null) return List.of();
        List<Match> out = new ArrayList<>();

        VillageSavedData vdata = VillageSavedData.get(level);
        for (Village village : vdata.getAllVillages()) {
            collectMatches(uuid, village.getOffices(), OrgType.VILLAGE,
                    village.getId(), village.getName(), out);
        }
        for (GuildData guild : vdata.getAllGuilds()) {
            collectMatches(uuid, guild.offices(), OrgType.GUILD,
                    guild.guildId(), "Guild " + guild.guildId(), out);
        }
        for (Kingdom kingdom : vdata.getAllKingdoms()) {
            collectMatches(uuid, kingdom.getOffices(), OrgType.KINGDOM,
                    kingdom.getId(), kingdom.getName(), out);
        }

        CompanySavedData cdata = CompanySavedData.get(level);
        for (Company company : cdata.getAllCompanies()) {
            collectMatches(uuid, company.getOffices(), OrgType.COMPANY,
                    company.getCompanyId(), company.getName(), out);
        }

        return out;
    }

    private static void collectMatches(UUID uuid, OfficeState state,
                                       OrgType orgType, UUID orgId, String displayName,
                                       List<Match> out) {
        if (state == null) return;
        for (OfficeHolding h : state.snapshot().values()) {
            if (h.isHeldBy(uuid)) {
                out.add(new Match(orgType, orgId, displayName, h));
            }
        }
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    private static synchronized void ensureInit() {
        if (initialised) return;
        try {
            registerVillageOffices();
            registerGuildOffices();
            registerCompanyOffices();
            registerKingdomOffices();
            registerTempleOffices();
            initialised = true;
            LOGGER.info("[OfficeRegistry] Registered {} offices", DEFINITIONS.size());
        } catch (Throwable t) {
            // Don't half-init.
            DEFINITIONS.clear();
            LOGGER.error("[OfficeRegistry] Init failed; office state will be empty", t);
            throw t;
        }
    }

    private static void register(OfficeDefinition def) {
        if (DEFINITIONS.containsKey(def.id())) {
            LOGGER.warn("[OfficeRegistry] Duplicate office id: {}", def.id());
        }
        DEFINITIONS.put(def.id(), def);
    }

    /**
     * Eligibility-list note: the spec references {@code SCRIBE} and
     * {@code HEALER} professions that aren't in the current Profession
     * enum (those land in Phase 2). This Phase 0 implementation
     * substitutes {@code SCHOLAR} where SCRIBE was specified, leaves
     * HEALER offices with empty eligibility, and documents the
     * substitution in 06 Revision Notes. Offices with empty eligibility
     * stay vacant until the matching profession ships.
     */
    private static void registerVillageOffices() {
        register(OfficeDefinition.of(VILLAGE_LEADER, OrgType.VILLAGE, "Village Leader",
                List.of(Profession.VILLAGE_LEADER),
                Map.of(Skill.SOCIAL, 30),
                SelectionMethod.ASCENSION,
                365,
                List.of(OfficePower.ENACT_LAW, OfficePower.REPEAL_LAW,
                        OfficePower.SET_TAX_RATE, OfficePower.APPOINT_SUBORDINATE,
                        OfficePower.ISSUE_DECREE, OfficePower.COMMAND_CITIZENS,
                        OfficePower.VIEW_BUDGET, OfficePower.SET_BUDGET),
                new Competence(Skill.SOCIAL, 30, 70, 1.15f, -0.10f)));

        register(OfficeDefinition.of(VILLAGE_TREASURER, OrgType.VILLAGE, "Village Treasurer",
                List.of(Profession.MERCHANT, Profession.SCHOLAR),
                Map.of(Skill.COMMERCE, 30, Skill.LITERACY, 30),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.VIEW_BUDGET, OfficePower.ACCESS_TREASURY,
                        OfficePower.SET_BUDGET),
                new Competence(Skill.COMMERCE, 30, 75, 1.20f, -0.08f)));

        register(OfficeDefinition.of(VILLAGE_CONSTABLE, OrgType.VILLAGE, "Village Constable",
                List.of(Profession.GUARD),
                Map.of(Skill.COMBAT, 30, Skill.SOCIAL, 20),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.INVESTIGATE_CRIME, OfficePower.TRY_ACCUSED),
                new Competence(Skill.COMBAT, 30, 70, 1.15f, -0.12f)));

        register(OfficeDefinition.of(VILLAGE_BAILIFF, OrgType.VILLAGE, "Village Bailiff",
                List.of(Profession.GUARD),
                Map.of(Skill.SOCIAL, 25, Skill.LITERACY, 25),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.INVESTIGATE_CRIME, OfficePower.COMMAND_CITIZENS),
                new Competence(Skill.SOCIAL, 25, 65, 1.10f, -0.08f)));

        register(OfficeDefinition.of(VILLAGE_SCRIBE, OrgType.VILLAGE, "Village Scribe",
                List.of(Profession.SCHOLAR),
                Map.of(Skill.LITERACY, 60),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.POST_REQUEST),
                new Competence(Skill.LITERACY, 60, 90, 1.25f, -0.05f)));

        register(OfficeDefinition.of(VILLAGE_PRIEST, OrgType.VILLAGE, "Village Priest",
                List.of(Profession.PRIEST),
                Map.of(Skill.SOCIAL, 30, Skill.LITERACY, 30),
                SelectionMethod.ASCENSION,
                0,
                List.of(OfficePower.OFFICIATE_RITE, OfficePower.BLESS),
                new Competence(Skill.SOCIAL, 30, 70, 1.15f, -0.07f)));

        register(OfficeDefinition.of(VILLAGE_HEALER, OrgType.VILLAGE, "Village Healer",
                List.of(Profession.HEALER),
                Map.of(Skill.MEDICINE, 40, Skill.SURVIVAL, 20),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.QUARANTINE_VILLAGE, OfficePower.REQUISITION_REMEDIES),
                new Competence(Skill.MEDICINE, 40, 80, 1.20f, -0.08f)));
    }

    private static void registerGuildOffices() {
        // guild_master is profession-agnostic at the registry level —
        // eligibility-by-guild-type is a Phase 3 selection-method concern.
        register(new OfficeDefinition(GUILD_MASTER, OrgType.GUILD, "Guild Master",
                List.of(),
                Map.of(),
                SelectionMethod.MERITOCRATIC,
                730,
                List.of(OfficePower.APPOINT_SUBORDINATE, OfficePower.RECRUIT_APPRENTICE,
                        OfficePower.CERTIFY_MASTERPIECE, OfficePower.POST_REQUEST,
                        OfficePower.ACCEPT_REQUEST),
                new Competence(Skill.SOCIAL, 70, 95, 1.30f, -0.15f),
                List.of(MASTER_OF_APPRENTICES),
                365));

        register(OfficeDefinition.of(GUILD_TREASURER, OrgType.GUILD, "Guild Treasurer",
                List.of(Profession.MERCHANT),
                Map.of(Skill.COMMERCE, 30),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.VIEW_BUDGET, OfficePower.ACCESS_TREASURY),
                new Competence(Skill.COMMERCE, 40, 80, 1.20f, -0.08f)));

        register(OfficeDefinition.of(GUILD_REGISTRAR, OrgType.GUILD, "Guild Registrar",
                List.of(Profession.SCHOLAR),
                Map.of(Skill.LITERACY, 50),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.RECRUIT_APPRENTICE),
                new Competence(Skill.LITERACY, 50, 85, 1.15f, -0.05f)));

        register(OfficeDefinition.of(MASTER_OF_APPRENTICES, OrgType.GUILD, "Master of Apprentices",
                List.of(),
                Map.of(),
                SelectionMethod.MERITOCRATIC,
                0,
                List.of(OfficePower.RECRUIT_APPRENTICE, OfficePower.CERTIFY_MASTERPIECE),
                new Competence(Skill.CRAFTING, 60, 90, 1.20f, -0.05f)));
    }

    private static void registerCompanyOffices() {
        register(OfficeDefinition.of(COMPANY_OWNER, OrgType.COMPANY, "Company Owner",
                List.of(),
                Map.of(),
                SelectionMethod.HEREDITARY,
                0,
                List.of(OfficePower.VIEW_BUDGET, OfficePower.SET_BUDGET,
                        OfficePower.APPOINT_SUBORDINATE, OfficePower.DISPATCH_CARAVAN,
                        OfficePower.ACCESS_TREASURY),
                new Competence(Skill.COMMERCE, 0, 50, 1.05f, 0.0f)));

        register(OfficeDefinition.of(COMPANY_FOREMAN, OrgType.COMPANY, "Company Foreman",
                List.of(),
                Map.of(),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.COMMAND_CITIZENS),
                new Competence(Skill.CRAFTING, 50, 85, 1.20f, -0.08f)));

        register(OfficeDefinition.of(COMPANY_BOOKKEEPER, OrgType.COMPANY, "Company Bookkeeper",
                List.of(Profession.MERCHANT),
                Map.of(Skill.COMMERCE, 40, Skill.LITERACY, 40),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.VIEW_BUDGET, OfficePower.ACCESS_TREASURY),
                new Competence(Skill.COMMERCE, 40, 80, 1.15f, -0.05f)));
    }

    private static void registerKingdomOffices() {
        register(OfficeDefinition.of(KINGDOM_KING, OrgType.KINGDOM, "King",
                List.of(Profession.KINGDOM_RULER),
                Map.of(Skill.SOCIAL, 50),
                SelectionMethod.HEREDITARY,
                0,
                List.of(OfficePower.ENACT_LAW, OfficePower.REPEAL_LAW,
                        OfficePower.ISSUE_DECREE, OfficePower.APPOINT_SUBORDINATE,
                        OfficePower.SET_TAX_RATE, OfficePower.SET_BUDGET,
                        OfficePower.ACCESS_TREASURY, OfficePower.COMMAND_CITIZENS),
                new Competence(Skill.SOCIAL, 50, 85, 1.20f, -0.15f)));

        register(OfficeDefinition.of(KINGDOM_CHANCELLOR, OrgType.KINGDOM, "Chancellor",
                List.of(Profession.CHANCELLOR),
                Map.of(Skill.LITERACY, 50, Skill.SOCIAL, 40),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.APPOINT_SUBORDINATE, OfficePower.ISSUE_DECREE,
                        OfficePower.VIEW_BUDGET),
                new Competence(Skill.LITERACY, 50, 85, 1.15f, -0.08f)));

        register(OfficeDefinition.of(KINGDOM_TREASURER, OrgType.KINGDOM, "Kingdom Treasurer",
                List.of(Profession.MERCHANT),
                Map.of(Skill.COMMERCE, 50, Skill.LITERACY, 40),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.VIEW_BUDGET, OfficePower.SET_BUDGET,
                        OfficePower.ACCESS_TREASURY, OfficePower.SET_TAX_RATE),
                new Competence(Skill.COMMERCE, 50, 85, 1.20f, -0.10f)));

        // Council seat is "stubbed in v1" per spec. Single-seat representation.
        register(OfficeDefinition.of(KINGDOM_COUNCIL_SEAT, OrgType.KINGDOM, "Council Seat",
                List.of(Profession.VILLAGE_LEADER),
                Map.of(Skill.SOCIAL, 30),
                SelectionMethod.COUNCIL,
                365,
                List.of(),
                new Competence(Skill.SOCIAL, 30, 70, 1.05f, 0.0f)));

        // ── Track D1 (Phase 0) — five new kingdom-tier office stubs ─────
        // No goals, no behaviour, no inhabitant populator yet. Powers
        // sets are placeholders that match each office's intended
        // domain; D3 wires the actual capabilities.

        register(OfficeDefinition.of(KINGDOM_SCHOLAR, OrgType.KINGDOM, "Royal Scholar",
                List.of(Profession.SCHOLAR),
                Map.of(Skill.LITERACY, 60),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.ISSUE_DECREE, OfficePower.VIEW_BUDGET),
                new Competence(Skill.LITERACY, 60, 90, 1.10f, -0.05f)));

        register(OfficeDefinition.of(KINGDOM_GENERAL, OrgType.KINGDOM, "Kingdom General",
                List.of(Profession.GENERAL, Profession.GUARD),
                Map.of(Skill.COMBAT, 60, Skill.SOCIAL, 30),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.COMMAND_CITIZENS, OfficePower.APPOINT_SUBORDINATE,
                        OfficePower.ISSUE_DECREE),
                new Competence(Skill.COMBAT, 60, 90, 1.20f, -0.12f)));

        register(OfficeDefinition.of(KINGDOM_MAGISTRATE, OrgType.KINGDOM, "Kingdom Magistrate",
                List.of(Profession.MAGISTRATE),
                Map.of(Skill.LITERACY, 50, Skill.SOCIAL, 40),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.ENACT_LAW, OfficePower.REPEAL_LAW,
                        OfficePower.ISSUE_DECREE),
                new Competence(Skill.LITERACY, 50, 85, 1.10f, -0.08f)));

        register(OfficeDefinition.of(KINGDOM_SPYMASTER, OrgType.KINGDOM, "Kingdom Spymaster",
                List.of(Profession.SPYMASTER),
                Map.of(Skill.SOCIAL, 50, Skill.LITERACY, 30),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.ISSUE_DECREE, OfficePower.APPOINT_SUBORDINATE),
                new Competence(Skill.SOCIAL, 50, 85, 1.10f, -0.10f)));

        register(OfficeDefinition.of(KINGDOM_DIPLOMAT, OrgType.KINGDOM, "Kingdom Diplomat",
                List.of(Profession.DIPLOMAT, Profession.HERALD),
                Map.of(Skill.SOCIAL, 60, Skill.COMMERCE, 30),
                SelectionMethod.APPOINTED,
                0,
                List.of(OfficePower.ISSUE_DECREE),
                new Competence(Skill.SOCIAL, 60, 90, 1.15f, -0.08f)));
    }

    private static void registerTempleOffices() {
        register(OfficeDefinition.of(TEMPLE_HIGH_PRIEST, OrgType.TEMPLE, "Temple High Priest",
                List.of(Profession.PRIEST),
                Map.of(Skill.SOCIAL, 50, Skill.LITERACY, 40),
                SelectionMethod.ASCENSION,
                0,
                List.of(OfficePower.OFFICIATE_RITE, OfficePower.BLESS, OfficePower.CURSE),
                new Competence(Skill.SOCIAL, 50, 85, 1.20f, -0.10f)));
    }
}
