package tterrag1112.life_in_the_village.Village.History;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.UUID;

/**
 * Phase 4 doc 30 lines 248-266 — kingdom-level "remembered" record
 * for an NPC who reached legendary status during their life.
 *
 * <p>The {@link #npcName} snapshot is mandatory because the
 * {@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob}
 * may be evicted long after the entry is written (per spec line
 * 345). Every accessor reads off this record so the original NPC
 * doesn't need to be loaded for queries.</p>
 *
 * @param npcId               original UUID — for cross-referencing
 *                            history entries that name this person
 * @param npcName             cached display name at peak notability
 * @param notabilityReason    what they're remembered for ("master scholar",
 *                            "long-serving leader", "founder")
 * @param peakAchievementTick game tick of the achievement that
 *                            registered the entry
 * @param relevantOffices     office ids the person held at any point
 *                            (e.g. ["village_leader", "guild_master"])
 * @param achievements        free-form list of one-line achievement
 *                            strings — the full "career" that drove
 *                            the notability flag
 */
public record NotablePerson(
        UUID npcId,
        String npcName,
        String notabilityReason,
        long peakAchievementTick,
        List<String> relevantOffices,
        List<String> achievements
) {
    public NotablePerson {
        if (npcId == null) throw new IllegalArgumentException("npcId required");
        if (npcName == null) npcName = "";
        if (notabilityReason == null) notabilityReason = "";
        relevantOffices = List.copyOf(relevantOffices != null ? relevantOffices : List.of());
        achievements    = List.copyOf(achievements    != null ? achievements    : List.of());
    }

    public NotablePerson withAchievement(String line) {
        if (line == null || line.isEmpty()) return this;
        var copy = new java.util.ArrayList<>(achievements);
        copy.add(line);
        return new NotablePerson(npcId, npcName, notabilityReason,
                peakAchievementTick, relevantOffices, copy);
    }

    public NotablePerson withOffice(String officeId) {
        if (officeId == null || officeId.isEmpty()) return this;
        if (relevantOffices.contains(officeId)) return this;
        var copy = new java.util.ArrayList<>(relevantOffices);
        copy.add(officeId);
        return new NotablePerson(npcId, npcName, notabilityReason,
                peakAchievementTick, copy, achievements);
    }

    public static final Codec<NotablePerson> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("npcId").forGetter(NotablePerson::npcId),
            Codec.STRING.fieldOf("npcName").forGetter(NotablePerson::npcName),
            Codec.STRING.optionalFieldOf("notabilityReason", "")
                    .forGetter(NotablePerson::notabilityReason),
            Codec.LONG.optionalFieldOf("peakAchievementTick", 0L)
                    .forGetter(NotablePerson::peakAchievementTick),
            Codec.STRING.listOf().optionalFieldOf("relevantOffices", List.of())
                    .forGetter(NotablePerson::relevantOffices),
            Codec.STRING.listOf().optionalFieldOf("achievements", List.of())
                    .forGetter(NotablePerson::achievements)
    ).apply(i, NotablePerson::new));
}
