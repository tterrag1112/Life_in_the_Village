package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;

/**
 * Granular capabilities an office grants its holder. Phase 0 stores
 * powers per office definition but no consumer reads them yet — Phase 3
 * wires powers into the management UI / behavior hooks.
 *
 * <p>See {@code docs/npc_redesign/06-office-framework.md}.</p>
 */
public enum OfficePower {
    VIEW_BUDGET,
    SET_BUDGET,
    APPOINT_SUBORDINATE,
    ENACT_LAW,
    REPEAL_LAW,
    SET_TAX_RATE,
    ISSUE_DECREE,
    COMMAND_CITIZENS,
    ACCESS_TREASURY,
    INVESTIGATE_CRIME,
    TRY_ACCUSED,
    OFFICIATE_RITE,
    DISPATCH_CARAVAN,
    POST_REQUEST,
    ACCEPT_REQUEST,
    RECRUIT_APPRENTICE,
    CERTIFY_MASTERPIECE,
    BLESS,
    CURSE;

    public static final Codec<OfficePower> CODEC =
            Codec.STRING.xmap(OfficePower::valueOf, OfficePower::name);
}
