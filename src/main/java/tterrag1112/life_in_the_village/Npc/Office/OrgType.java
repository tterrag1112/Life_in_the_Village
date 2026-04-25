package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;

/** Type of organization that hosts an {@link OfficeState}. */
public enum OrgType {
    KINGDOM,
    VILLAGE,
    GUILD,
    COMPANY,
    TEMPLE;

    public static final Codec<OrgType> CODEC =
            Codec.STRING.xmap(OrgType::valueOf, OrgType::name);
}
