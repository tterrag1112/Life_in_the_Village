package tterrag1112.life_in_the_village.Npc.Skills;

import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Static profession → (primary, secondary) skill mapping.
 *
 * <p>The spec lists this table in {@code 05-skill-system.md} but covers
 * only a handful of canonical professions ({@code FARMER}, {@code BLACKSMITH},
 * {@code GUARD}, {@code MERCHANT}, etc.). Mappings for every existing
 * {@link Profession} value are filled in here following the spec's
 * description of which skills "lean on" each profession family.</p>
 */
public record ProfessionSkills(Skill primary, Skill secondary) {

    private static final Map<Profession, ProfessionSkills> TABLE = buildTable();

    private static Map<Profession, ProfessionSkills> buildTable() {
        EnumMap<Profession, ProfessionSkills> m = new EnumMap<>(Profession.class);

        // Spec-listed entries
        m.put(Profession.FARMER,           new ProfessionSkills(Skill.FARMING,  Skill.SURVIVAL));
        // Blacksmith-skill-fix — primary moves CRAFTING -> BLACKSMITHING so a
        // spawned smith seeds the gated sub-skill (BLACKSMITHING 15-35),
        // matching the four production professions below (CANDLEMAKING /
        // WEAVING / CARPENTRY / MASONRY) whose primary IS the gated axis.
        // Pre-fix the smith seeded CRAFTING 15-35 but the recipe gate was on
        // the unseeded specialty/BLACKSMITHING -> every recipe rejected -> idle.
        // BLACKSMITHING cascades 25% -> CRAFTING, so the parent still accrues.
        // No other profession uses BLACKSMITHING as primary, so apprenticeship
        // / childhood reverse-lookups gain a clean BLACKSMITHING -> BLACKSMITH
        // mapping (was NONE). Secondary stays COMMERCE.
        m.put(Profession.BLACKSMITH,       new ProfessionSkills(Skill.BLACKSMITHING, Skill.COMMERCE));
        m.put(Profession.GUARD,            new ProfessionSkills(Skill.COMBAT,   Skill.SURVIVAL));
        m.put(Profession.MERCHANT,         new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        m.put(Profession.SCHOLAR,          new ProfessionSkills(Skill.LITERACY, Skill.MEDICINE));

        // Family extensions (follow spec rationale: "Farmers, herders,
        // foresters lean on FARMING + SURVIVAL"; "Blacksmiths, carpenters,
        // weavers lean on CRAFTING"; etc.).
        m.put(Profession.FARMHAND,         new ProfessionSkills(Skill.FARMING,  Skill.SURVIVAL));
        m.put(Profession.WANDERING_TRADER, new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        // Phase 6.6.1.2 — four production professions move primary to
        // their dedicated sub-skill (CRAFTING children at 25% cascade).
        // Matches the MILLING/BAKING pattern from 6.3.4.9/.10. Secondary
        // unchanged — captures "career retention" axis for profession
        // switches (SURVIVAL for outdoor/material trades, COMMERCE for
        // sell-to-customer trades).
        m.put(Profession.CARPENTER,        new ProfessionSkills(Skill.CARPENTRY,    Skill.SURVIVAL));
        m.put(Profession.STONEMASON,       new ProfessionSkills(Skill.MASONRY,      Skill.SURVIVAL));
        m.put(Profession.WEAVER,           new ProfessionSkills(Skill.WEAVING,      Skill.COMMERCE));
        m.put(Profession.CANDLEMAKER,      new ProfessionSkills(Skill.CANDLEMAKING, Skill.COMMERCE));
        // Phase 6.3.4.9 — MILLER's primary moves to MILLING (CRAFTING
        // sub-skill). Secondary stays CRAFTING — somewhat redundant
        // since MILLING cascades to CRAFTING anyway, but secondary is
        // the "career retention" axis for profession switches and
        // CRAFTING matches MILLER's general processing-trade identity.
        m.put(Profession.MILLER,           new ProfessionSkills(Skill.MILLING,  Skill.CRAFTING));
        // Phase 6.3.4.10 — BAKER's primary moves to BAKING (CRAFTING
        // sub-skill). Secondary stays COMMERCE (BAKER sells bread to
        // households / market). BAKING cascades to CRAFTING at 25%
        // and PASTRY recipes route to PASTRY which cascades through
        // BAKING to CRAFTING — three-level propagation handled in
        // SkillComponent.addXp.
        m.put(Profession.BAKER,            new ProfessionSkills(Skill.BAKING,   Skill.COMMERCE));
        m.put(Profession.BUILDER,          new ProfessionSkills(Skill.CRAFTING, Skill.SURVIVAL));
        // Player P0 — MINING is now the primary skill for NPC MINER (seeded
        // at spawn via initializeFromProfession). Previously (SURVIVAL, CRAFTING).
        // MINING cascades 25% → SURVIVAL, so the parent still accrues passively.
        // This is the one accepted NPC-behavior change in P0: spawned miners
        // seed MINING[15-35] rather than SURVIVAL[15-35]. Secondary SURVIVAL
        // (outdoor/hazard axis, consistent with the FARMER pattern).
        m.put(Profession.MINER,            new ProfessionSkills(Skill.MINING,    Skill.SURVIVAL));
        m.put(Profession.STOCKPILE_KEEPER, new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        m.put(Profession.INNKEEPER,        new ProfessionSkills(Skill.SOCIAL,   Skill.COMMERCE));

        // Leadership / scribal (spec: "Village leaders, priests lean on
        // SOCIAL + LITERACY").
        m.put(Profession.VILLAGE_LEADER,   new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.KINGDOM_RULER,    new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.CHANCELLOR,       new ProfessionSkills(Skill.LITERACY, Skill.SOCIAL));
        m.put(Profession.HERALD,           new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.PRIEST,           new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        // R6a — monk: a multi-craft contemplative. CRAFTING primary (the parent
        // of the production sub-skills, so the monk's varied monastic crafts —
        // R6b — all cascade XP sensibly), LITERACY secondary (scriptorium /
        // study). Deliberately NOT (SOCIAL, LITERACY) like PRIEST: a monk is
        // craft+study, not a congregation-facing officiant.
        m.put(Profession.MONK,             new ProfessionSkills(Skill.CRAFTING, Skill.LITERACY));
        m.put(Profession.HEALER,           new ProfessionSkills(Skill.MEDICINE, Skill.SURVIVAL));

        // Scribal (Phase 2 task 17). LITERACY primary across the trio;
        // secondary differentiates the role: scribe writes for pay
        // (COMMERCE), librarian curates and teaches (SOCIAL), scholar
        // researches and authors (MEDICINE — captures the natural-
        // philosopher / herbalist archetype).
        m.put(Profession.SCRIBE,           new ProfessionSkills(Skill.LITERACY, Skill.COMMERCE));
        m.put(Profession.LIBRARIAN,        new ProfessionSkills(Skill.LITERACY, Skill.SOCIAL));

        // Adventurer / guild
        m.put(Profession.ADVENTURER,       new ProfessionSkills(Skill.COMBAT,   Skill.SURVIVAL));
        m.put(Profession.GUILDMASTER,      new ProfessionSkills(Skill.SOCIAL,   Skill.COMMERCE));
        m.put(Profession.GUILDWORKER,      new ProfessionSkills(Skill.CRAFTING, Skill.SOCIAL));
        m.put(Profession.COMPANY_WORKER,   new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));

        // Track D1 — kingdom-tier office professions. Inert this phase
        // (no NPC ever spawns with these professions until D3); the
        // entries are here so any code that walks the map in advance
        // doesn't trip on missing keys.
        m.put(Profession.GENERAL,    new ProfessionSkills(Skill.COMBAT,   Skill.SOCIAL));
        m.put(Profession.MAGISTRATE, new ProfessionSkills(Skill.LITERACY, Skill.SOCIAL));
        m.put(Profession.SPYMASTER,  new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.DIPLOMAT,   new ProfessionSkills(Skill.SOCIAL,   Skill.COMMERCE));

        // NONE / CITIZEN intentionally absent — no profession means no
        // bias at spawn-time skill init.
        return Map.copyOf(m);
    }

    public static Optional<ProfessionSkills> of(Profession profession) {
        return Optional.ofNullable(TABLE.get(profession));
    }
}
