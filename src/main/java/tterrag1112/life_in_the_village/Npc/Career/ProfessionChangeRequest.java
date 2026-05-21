package tterrag1112.life_in_the_village.Npc.Career;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Phase 6.3.3.a — describes a requested profession change. Built by
 * {@link CareerTransitions#changeProfession} and passed to every
 * {@link ProfessionChangeListener}.
 */
public record ProfessionChangeRequest(TownspersonMob npc,
                                      Profession from,
                                      Profession to,
                                      Reason reason,
                                      Source source) {

    /**
     * Why the change is happening. Listeners may filter on this to
     * enforce reason-specific rules (e.g. only veto BUSINESS_PROMOTION,
     * never veto RETIREMENT).
     */
    public enum Reason {
        /** TEEN → ADULT lifestage transition routed via ComingOfAgeHandler. */
        COMING_OF_AGE,
        /** Apprentice inherits master's profession at contract start. */
        APPRENTICESHIP,
        /** Apprentice promoted to full status at contract graduation. */
        APPRENTICESHIP_GRADUATION,
        /** NPC retires — usually to {@code NONE} or a reduced role. */
        RETIREMENT,
        /** Promotion via business / merchant / business path. */
        BUSINESS_PROMOTION,
        /** Culture rite or ceremony grants a profession. */
        CULTURE_RITE,
        /** Debug command / admin tool. Source should be DEBUG. */
        DEBUG,
        /** Content pack / external system requested the change. */
        CONTENT,
        /** Catch-all — entity construction, worldgen, edge cases. */
        OTHER
    }

    /**
     * Who initiated the change. Admin sources can bypass some checks
     * a system-initiated change would not (matches the {@code seatNpcForced}
     * pattern from 6.3.2.d).
     */
    public enum Source {
        /** Internal AI / lifecycle / save-load. */
        SYSTEM,
        /** Direct player action (verb, packet, command). */
        PLAYER,
        /** Registered content pack. */
        CONTENT_PACK,
        /** Debug command / operator console. */
        DEBUG
    }
}
