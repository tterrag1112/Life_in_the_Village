package tterrag1112.life_in_the_village.Kingdom.Houses;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Kingdom.Heraldry;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.2a — kingdom-tier noble dynasty record. Stored on the
 * owning {@code Kingdom} via {@code KingdomGovernanceData.houses};
 * referenced from each member NPC by {@code NobilityComponent.dynastyHouseId}.
 *
 * <p>Inert at D3.2a close: the record persists, the heraldry
 * generator ({@link tterrag1112.life_in_the_village.Kingdom.HeraldryGenerator#forHouse})
 * produces values, and the debug command lists them. D3.2b's
 * succession / fealty / marriage subsystems read {@code headUuid}
 * and {@code prestige} as authoritative.
 *
 * <h3>Naming clarification</h3>
 * NOT to be confused with {@code BuildingType.HOUSE} (a residential
 * building) or {@code FamilyComponent.houseId} (the building id of
 * the house an NPC lives in). A {@link House} is a noble dynasty —
 * a kingdom-tier social structure with a name, founder, current
 * head, heraldry, motto, and prestige score.
 *
 * @param id            stable UUID; matches {@code NobilityComponent.dynastyHouseId} on members.
 * @param name          dynasty name (e.g. "House Avenwood"); rendered in GUIs.
 * @param kingdomId     the {@code Kingdom} this house owes fealty to / belongs to.
 * @param founderUuid   the NPC who founded the house. Never reassigned.
 * @param foundingTick  server tick at founding; used by heraldry seed + chronicle.
 * @param headUuid      current head of house; null after the line dies out
 *                      (D3.2b will mark such houses extinct rather than delete).
 * @param heraldry      house arms; deterministically generated from
 *                      {@code (id, founderUuid)} via {@code HeraldryGenerator.forHouse}.
 * @param prestige      0..100 house-level prestige (averaged member prestige
 *                      at founding; later driven by D3.2b achievements).
 * @param motto         optional house motto; empty string when none set.
 */
public record House(
        UUID id,
        String name,
        UUID kingdomId,
        UUID founderUuid,
        long foundingTick,
        Optional<UUID> headUuid,
        Heraldry heraldry,
        int prestige,
        String motto
) {

    public static final Codec<House> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(House::id),
            Codec.STRING.fieldOf("name").forGetter(House::name),
            UUIDUtil.CODEC.fieldOf("kingdomId").forGetter(House::kingdomId),
            UUIDUtil.CODEC.fieldOf("founderUuid").forGetter(House::founderUuid),
            Codec.LONG.optionalFieldOf("foundingTick", 0L).forGetter(House::foundingTick),
            UUIDUtil.CODEC.optionalFieldOf("headUuid").forGetter(House::headUuid),
            Heraldry.CODEC.optionalFieldOf("heraldry", Heraldry.UNKNOWN).forGetter(House::heraldry),
            Codec.INT.optionalFieldOf("prestige", 0).forGetter(House::prestige),
            Codec.STRING.optionalFieldOf("motto", "").forGetter(House::motto)
    ).apply(i, House::new));

    /** True when no living NPC currently heads the house. D3.2b marks
     *  such houses extinct (sets headUuid to empty) without deleting
     *  the record so chronicles + dynasty trees retain history. */
    public boolean isExtinct() {
        return headUuid.isEmpty();
    }

    /** Returns a copy with a new head NPC. */
    public House withHead(UUID newHeadUuid) {
        return new House(id, name, kingdomId, founderUuid, foundingTick,
                Optional.ofNullable(newHeadUuid), heraldry, prestige, motto);
    }

    /** Returns a copy with the head cleared (extinction). */
    public House withoutHead() {
        return new House(id, name, kingdomId, founderUuid, foundingTick,
                Optional.empty(), heraldry, prestige, motto);
    }

    /** Returns a copy with a new prestige value (clamped 0..100). */
    public House withPrestige(int newPrestige) {
        int clamped = Math.max(0, Math.min(100, newPrestige));
        return new House(id, name, kingdomId, founderUuid, foundingTick,
                headUuid, heraldry, clamped, motto);
    }

    /** Returns a copy with a new motto. */
    public House withMotto(String newMotto) {
        return new House(id, name, kingdomId, founderUuid, foundingTick,
                headUuid, heraldry, prestige, newMotto == null ? "" : newMotto);
    }
}
