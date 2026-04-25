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
        m.put(Profession.BLACKSMITH,       new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));
        m.put(Profession.GUARD,            new ProfessionSkills(Skill.COMBAT,   Skill.SURVIVAL));
        m.put(Profession.MERCHANT,         new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        m.put(Profession.SCHOLAR,          new ProfessionSkills(Skill.LITERACY, Skill.MEDICINE));

        // Family extensions (follow spec rationale: "Farmers, herders,
        // foresters lean on FARMING + SURVIVAL"; "Blacksmiths, carpenters,
        // weavers lean on CRAFTING"; etc.).
        m.put(Profession.FARMHAND,         new ProfessionSkills(Skill.FARMING,  Skill.SURVIVAL));
        m.put(Profession.WANDERING_TRADER, new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        m.put(Profession.CARPENTER,        new ProfessionSkills(Skill.CRAFTING, Skill.SURVIVAL));
        m.put(Profession.STONEMASON,       new ProfessionSkills(Skill.CRAFTING, Skill.SURVIVAL));
        m.put(Profession.WEAVER,           new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));
        m.put(Profession.CANDLEMAKER,      new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));
        m.put(Profession.MILLER,           new ProfessionSkills(Skill.FARMING,  Skill.CRAFTING));
        m.put(Profession.BAKER,            new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));
        m.put(Profession.BUILDER,          new ProfessionSkills(Skill.CRAFTING, Skill.SURVIVAL));
        m.put(Profession.MINER,            new ProfessionSkills(Skill.SURVIVAL, Skill.CRAFTING));
        m.put(Profession.STOCKPILE_KEEPER, new ProfessionSkills(Skill.COMMERCE, Skill.SOCIAL));
        m.put(Profession.INNKEEPER,        new ProfessionSkills(Skill.SOCIAL,   Skill.COMMERCE));

        // Leadership / scribal (spec: "Village leaders, priests lean on
        // SOCIAL + LITERACY").
        m.put(Profession.VILLAGE_LEADER,   new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.KINGDOM_RULER,    new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.CHANCELLOR,       new ProfessionSkills(Skill.LITERACY, Skill.SOCIAL));
        m.put(Profession.HERALD,           new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));
        m.put(Profession.PRIEST,           new ProfessionSkills(Skill.SOCIAL,   Skill.LITERACY));

        // Adventurer / guild
        m.put(Profession.ADVENTURER,       new ProfessionSkills(Skill.COMBAT,   Skill.SURVIVAL));
        m.put(Profession.GUILDMASTER,      new ProfessionSkills(Skill.SOCIAL,   Skill.COMMERCE));
        m.put(Profession.GUILDWORKER,      new ProfessionSkills(Skill.CRAFTING, Skill.SOCIAL));
        m.put(Profession.COMPANY_WORKER,   new ProfessionSkills(Skill.CRAFTING, Skill.COMMERCE));

        // NONE / CITIZEN intentionally absent — no profession means no
        // bias at spawn-time skill init.
        return Map.copyOf(m);
    }

    public static Optional<ProfessionSkills> of(Profession profession) {
        return Optional.ofNullable(TABLE.get(profession));
    }
}
