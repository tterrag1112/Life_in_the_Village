package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 4 doc 27 — high-level guild taxonomy. The existing
 * {@code GuildData} stays as the adventurer-specific record;
 * {@link AbstractGuild} extends across all six categories so the
 * rest of the redesign (request board, NPC companies, master-of-
 * apprentices) can talk to a uniform surface.
 *
 * <p>Profession membership tables: a profession contributes to the
 * implicit guild whose set contains it. NPCs hold one guild slot at
 * a time per spec "Open decisions" #3 — primary profession decides.
 * Overlapping types (e.g. SCHOLAR is both scholarly and religious)
 * resolve via {@link #primaryFor}.</p>
 */
public enum GuildType {

    ADVENTURER(BuildingType.GUILD_HALL,
            EnumSet.of(Profession.ADVENTURER, Profession.GUILDWORKER, Profession.GUILDMASTER)),
    CRAFTSMEN(null /* placeholder, set after BuildingType variants land */,
            EnumSet.of(Profession.BLACKSMITH, Profession.CARPENTER,
                    Profession.WEAVER, Profession.STONEMASON,
                    Profession.CANDLEMAKER)),
    MERCHANTS(null,
            EnumSet.of(Profession.MERCHANT, Profession.WANDERING_TRADER,
                    Profession.INNKEEPER, Profession.STOCKPILE_KEEPER)),
    AGRICULTURAL(null,
            EnumSet.of(Profession.FARMER, Profession.FARMHAND, Profession.BAKER, Profession.MILLER)),
    RELIGIOUS(null,
            EnumSet.of(Profession.PRIEST)),
    SCHOLARLY(null,
            EnumSet.of(Profession.SCHOLAR, Profession.SCRIBE, Profession.LIBRARIAN));

    private final BuildingType hallType;
    private final Set<Profession> memberProfessions;

    GuildType(BuildingType hallType, Set<Profession> memberProfessions) {
        this.hallType = hallType;
        this.memberProfessions = EnumSet.copyOf(memberProfessions);
    }

    /** The building type whose construction in a village upgrades the
     *  matching implicit guild from L0 to L1. May be null when the
     *  hall variant hasn't shipped yet — resolved lazily via
     *  {@link GuildHallTypes#hallFor}. */
    public BuildingType hallType() {
        BuildingType resolved = hallType;
        return resolved != null ? resolved : GuildHallTypes.hallFor(this);
    }

    public Set<Profession> memberProfessions() {
        return memberProfessions;
    }

    public boolean accepts(Profession profession) {
        return profession != null && memberProfessions.contains(profession);
    }

    /** Resolves the canonical guild type for a profession when more
     *  than one type would accept it. Order: ADVENTURER > CRAFTSMEN >
     *  MERCHANTS > AGRICULTURAL > RELIGIOUS > SCHOLARLY (priority of
     *  declaration). The first match wins, so SCHOLAR lands in
     *  SCHOLARLY before RELIGIOUS even if both accepted it. */
    public static GuildType primaryFor(Profession profession) {
        if (profession == null) return null;
        for (GuildType t : values()) {
            if (t.accepts(profession)) return t;
        }
        return null;
    }

    public static final Codec<GuildType> CODEC = Codec.STRING.xmap(
            s -> GuildType.valueOf(s.toUpperCase(Locale.ROOT)),
            GuildType::name);
}
