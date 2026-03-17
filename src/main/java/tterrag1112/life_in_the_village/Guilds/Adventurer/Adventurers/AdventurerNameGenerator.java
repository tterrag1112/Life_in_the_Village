package tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers;

import java.util.UUID;

public class AdventurerNameGenerator {

    private static final String[] FIRST_NAMES = {
            "Aldric", "Brynn", "Calder", "Dara", "Edwyn",
            "Fiona", "Gareth", "Hilda", "Ivor", "Jessa",
            "Kael", "Lira", "Maren", "Nolan", "Oryn",
            "Petra", "Quinn", "Renna", "Soren", "Talia",
            "Ulric", "Vera", "Wynn", "Xara", "Yoren", "Zara"
    };

    private static final String[] SURNAMES = {
            "Ashwood", "Blackthorn", "Crestfall", "Dawnridge",
            "Embervale", "Frostmantle", "Goldmere", "Hawkridge",
            "Ironhollow", "Jadestone", "Kindlebrook", "Longstride",
            "Moorwatch", "Nighthollow", "Oakenshield", "Pinecrest",
            "Quickblade", "Ravenscar", "Stonemark", "Thornwall",
            "Unyielding", "Vantablack", "Whitmore", "Yarrow"
    };

    private static final String[] TITLES = {
            "", "", "", "",       // weight toward no title
            "the Bold", "the Swift", "the Scarred",
            "Ironheart", "Stonefist", "Dawnblade",
            "of the North", "of the Vale"
    };

    /**
     * Generates a stable name for a given UUID.
     * The same UUID will always produce the same name.
     */
    public static String getName(UUID id) {
        long most  = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();

        String first   = FIRST_NAMES[(int) Math.abs(most  % FIRST_NAMES.length)];
        String surname = SURNAMES   [(int) Math.abs(least % SURNAMES.length)];
        String title   = TITLES     [(int) Math.abs((most + least) % TITLES.length)];

        return title.isEmpty()
                ? first + " " + surname
                : first + " " + surname + " " + title;
    }
}