package tterrag1112.life_in_the_village.Npc.Specialization;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Npc.Skills.SkillRequirement;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;

/**
 * Phase 6.3.2.c — descriptor for a single specialization variant.
 *
 * <p>Codec round-trips by {@link #name() Identifier} via the
 * {@link NpcSpecializationTypes} registry lookup — the on-disk
 * representation is just the identifier; the full definition is
 * resolved at decode time.
 */
public record SpecializationDef(Identifier name,
                                Profession profession,
                                Component displayName,
                                List<SkillRequirement> requirements,
                                boolean isGeneralist,
                                SpecializationData extra) {

    public SpecializationDef {
        requirements = List.copyOf(requirements);
    }

    public static final Codec<SpecializationDef> CODEC = Identifier.CODEC.xmap(
            id -> NpcSpecializationTypes.byId(id).orElseThrow(
                    () -> new IllegalArgumentException(
                            "Unknown specialization: " + id)),
            SpecializationDef::name);
}
