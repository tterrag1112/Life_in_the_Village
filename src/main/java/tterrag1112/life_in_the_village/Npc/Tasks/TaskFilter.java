package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Eligibility data for a {@link Task}: who may claim it. All criteria
 * are optional and AND-ed; an empty filter is eligible to everyone.
 *
 * <p>{@link #eligible} consults {@link TaskContext} hooks for the
 * situational checks (skill level, profession, role, membership,
 * workstation). The hooks are honest — they read real per-actor state,
 * so a missing membership or an under-leveled skill genuinely excludes
 * the actor rather than silently passing.</p>
 *
 * @param skill          required skill, if any
 * @param minLevel       minimum level of {@code skill} (only meaningful when {@code skill} present)
 * @param roleId         required role id, if any
 * @param profession     required profession, if any
 * @param membership     an {@link IssuerRef} the actor must belong to, if any
 * @param needsWorkstation whether the actor must have a usable workstation
 */
public record TaskFilter(Optional<Skill> skill,
                         int minLevel,
                         Optional<String> roleId,
                         Optional<Profession> profession,
                         Optional<IssuerRef> membership,
                         boolean needsWorkstation) {

    private static final Codec<Profession> PROFESSION_CODEC =
            Codec.STRING.xmap(Profession::valueOf, Profession::name);

    /** An empty filter — eligible to any actor. */
    public static final TaskFilter ANY =
            new TaskFilter(Optional.empty(), 0, Optional.empty(),
                    Optional.empty(), Optional.empty(), false);

    public static final Codec<TaskFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Skill.CODEC.optionalFieldOf("skill").forGetter(TaskFilter::skill),
            Codec.INT.optionalFieldOf("minLevel", 0).forGetter(TaskFilter::minLevel),
            Codec.STRING.optionalFieldOf("roleId").forGetter(TaskFilter::roleId),
            PROFESSION_CODEC.optionalFieldOf("profession").forGetter(TaskFilter::profession),
            IssuerRef.CODEC.optionalFieldOf("membership").forGetter(TaskFilter::membership),
            Codec.BOOL.optionalFieldOf("needsWorkstation", false).forGetter(TaskFilter::needsWorkstation)
    ).apply(i, TaskFilter::new));

    /** True if {@code actor} (in {@code ctx}) satisfies every present criterion. */
    public boolean eligible(TaskActor actor, TaskContext ctx) {
        if (skill.isPresent() && ctx.skillLevel(skill.get()) < minLevel) {
            return false;
        }
        if (roleId.isPresent() && !roleId.equals(ctx.roleId())) {
            return false;
        }
        if (profession.isPresent() && !profession.equals(ctx.profession())) {
            return false;
        }
        if (membership.isPresent() && !ctx.isMemberOf(membership.get())) {
            return false;
        }
        if (needsWorkstation && !ctx.hasWorkstation()) {
            return false;
        }
        return true;
    }
}
