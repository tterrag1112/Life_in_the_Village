package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static per-hobby definition. Spec line 26.
 *
 * <p>{@code requiresProfession} is honoured by
 * {@link HobbyCatalogue#availableFor}; {@code skillGain} drives the
 * per-session XP award. Codec is shipped for forward compatibility
 * with the Phase 6 JSON-pack migration; v1 only registers in code.</p>
 */
public record HobbyDefinition(
        String id,
        String displayName,
        HobbyLocation location,
        List<HobbyActivity> activities,
        int durationTicks,
        TraitWeight traitWeight,
        Optional<Skill> skillGain,
        int skillXpPerSession,
        Optional<Profession> requiresProfession,
        int requiredSkillLevel,
        Optional<Skill> requiredSkill
) {
    /** Default session length when none specified — 4 in-game minutes. */
    public static final int DEFAULT_DURATION = 1600;

    public HobbyDefinition {
        if (id == null || id.isEmpty())
            throw new IllegalArgumentException("HobbyDefinition.id is required");
        if (displayName == null) displayName = id;
        if (location == null)
            throw new IllegalArgumentException("HobbyDefinition.location is required");
        activities = List.copyOf(activities);
        if (activities.isEmpty())
            throw new IllegalArgumentException("HobbyDefinition.activities is empty");
        if (durationTicks <= 0) durationTicks = DEFAULT_DURATION;
        if (traitWeight == null) traitWeight = TraitWeight.of(java.util.Map.of());
        if (skillGain == null) skillGain = Optional.empty();
        if (requiresProfession == null) requiresProfession = Optional.empty();
        if (requiredSkill == null) requiredSkill = Optional.empty();
    }

    private static final Codec<Profession> PROFESSION_CODEC =
            Codec.STRING.xmap(Profession::valueOf, Profession::name);

    public static final Codec<HobbyDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(HobbyDefinition::id),
            Codec.STRING.optionalFieldOf("displayName", "").forGetter(HobbyDefinition::displayName),
            HobbyLocation.CODEC.fieldOf("location").forGetter(HobbyDefinition::location),
            HobbyActivity.CODEC.listOf().fieldOf("activities").forGetter(HobbyDefinition::activities),
            Codec.INT.optionalFieldOf("durationTicks", DEFAULT_DURATION)
                    .forGetter(HobbyDefinition::durationTicks),
            TraitWeight.CODEC.optionalFieldOf("traitWeight", TraitWeight.of(java.util.Map.of()))
                    .forGetter(HobbyDefinition::traitWeight),
            Skill.CODEC.optionalFieldOf("skillGain").forGetter(HobbyDefinition::skillGain),
            Codec.INT.optionalFieldOf("skillXpPerSession", 0)
                    .forGetter(HobbyDefinition::skillXpPerSession),
            PROFESSION_CODEC.optionalFieldOf("requiresProfession")
                    .forGetter(HobbyDefinition::requiresProfession),
            Codec.INT.optionalFieldOf("requiredSkillLevel", 0)
                    .forGetter(HobbyDefinition::requiredSkillLevel),
            Skill.CODEC.optionalFieldOf("requiredSkill").forGetter(HobbyDefinition::requiredSkill)
    ).apply(i, HobbyDefinition::new));

    /** Convenience builder — code-side registration uses {@link Builder}. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private HobbyLocation location;
        private final java.util.List<HobbyActivity> activities = new java.util.ArrayList<>();
        private int durationTicks = DEFAULT_DURATION;
        private final java.util.Map<tterrag1112.life_in_the_village.Npc.Traits.TraitAxis, Float>
                weights = new java.util.LinkedHashMap<>();
        private Optional<Skill> skillGain = Optional.empty();
        private int skillXpPerSession = 0;
        private Optional<Profession> requiresProfession = Optional.empty();
        private int requiredSkillLevel = 0;
        private Optional<Skill> requiredSkill = Optional.empty();

        private Builder(String id) { this.id = id; this.displayName = id; }

        public Builder display(String name) { this.displayName = name; return this; }
        public Builder at(HobbyLocation loc) { this.location = loc; return this; }
        public Builder activity(HobbyActivity a) { this.activities.add(a); return this; }
        public Builder duration(int t) { this.durationTicks = t; return this; }
        public Builder weight(tterrag1112.life_in_the_village.Npc.Traits.TraitAxis ax, float w) {
            this.weights.put(ax, w); return this;
        }
        public Builder grants(Skill s, int xp) {
            this.skillGain = Optional.of(s); this.skillXpPerSession = xp; return this;
        }
        public Builder requiresProfession(Profession p) {
            this.requiresProfession = Optional.of(p); return this;
        }
        public Builder requiresSkill(Skill s, int level) {
            this.requiredSkill = Optional.of(s); this.requiredSkillLevel = level; return this;
        }

        public HobbyDefinition build() {
            return new HobbyDefinition(id, displayName, location, activities, durationTicks,
                    TraitWeight.of(weights), skillGain, skillXpPerSession,
                    requiresProfession, requiredSkillLevel, requiredSkill);
        }
    }
}
