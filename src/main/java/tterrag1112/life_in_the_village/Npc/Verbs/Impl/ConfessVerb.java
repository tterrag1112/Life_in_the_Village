package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Spec line 157. Player confesses to a priest — schedules a
 * {@link Rite#CONFESSION} which records a sensitive
 * {@link tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry}
 * naming the player. Eligibility gate: priest's LITERACY ≥ 30
 * (priest needs the literacy to keep a confession ledger; spec).
 */
public final class ConfessVerb implements PlayerVerb {

    public static final String VERB_ID = "confess";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Confess"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Speak privately with the priest."));
    }
    @Override public int displayOrder() { return 67; }
    @Override public int cooldownTicks() { return 6000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) return false;
        return ctx.npc().getSkills().getLevel(Skill.LITERACY) >= 30;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) {
            return VerbResult.failure("They are not a priest.");
        }
        if (ctx.npc().getSkills().getLevel(Skill.LITERACY) < 30) {
            return VerbResult.failure("They are not a confessor.");
        }
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(VillageSavedData.get(ctx.level())::getVillageByName).orElse(null);
        if (village == null) return VerbResult.failure("No village context.");
        RiteScheduler.schedule(ctx.level(), village, Rite.CONFESSION,
                List.of(ctx.player().getUUID()), 0L);
        return VerbResult.success("constable.investigation");
    }
}
