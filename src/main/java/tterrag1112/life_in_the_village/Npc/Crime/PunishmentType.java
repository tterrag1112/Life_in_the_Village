package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;

/** Spec line 105. */
public enum PunishmentType {
    WARNING,
    FINE,
    RESTITUTION,
    COMMUNITY_LABOR,
    DETENTION,
    EXILE,
    EXECUTION,
    ITEM_CONFISCATION,
    OFFICE_BAR;

    public static final Codec<PunishmentType> CODEC =
            Codec.STRING.xmap(PunishmentType::valueOf, PunishmentType::name);
}
