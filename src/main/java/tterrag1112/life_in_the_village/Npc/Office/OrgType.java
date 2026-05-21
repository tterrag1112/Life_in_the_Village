package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;

/** Type of organization that hosts an {@link OfficeState}. */
public enum OrgType {
    KINGDOM,
    VILLAGE,
    GUILD,
    BUSINESS,
    TEMPLE;

    public static final Codec<OrgType> CODEC =
            Codec.STRING.xmap(OrgType::legacyCompat, OrgType::name);

    /**
     * Phase 6.3.3.b — backward-compat decoder: legacy saves contain
     * "COMPANY" as the OrgType enum name; map to {@link #BUSINESS}.
     */
    private static OrgType legacyCompat(String name) {
        if ("COMPANY".equals(name)) return BUSINESS;
        return OrgType.valueOf(name);
    }
}
