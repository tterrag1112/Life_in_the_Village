// src/main/java/tterrag1112/life_in_the_village/Kingdom/WorldGenKingdomSeeder.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.*;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class WorldgenKingdomSeeder {

    private static final int MIN_KINGDOM_DIST  = 2_000;
    private static final int MAX_KINGDOM_DIST  = 4_000;
    private static final int MIN_KINGDOMS      = 2;
    private static final int MAX_KINGDOMS      = 4;
    private static final int SPAWN_INTERVAL    = 600;
    private static final int CHUNK_LOAD_RADIUS = 4;

    // {cultureName, capitalVillageType}
    private static final String[][] CULTURES = {
            { "default",  "royal_capital"    },
            { "highland", "highland_capital" },
            { "imperial", "imperial_capital" },
    };

    private static boolean seederRan  = false;
    private static final List<ScheduledKingdom> scheduled = new ArrayList<>();
    private static int scheduledDelay = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();

        if (!seederRan) {
            seederRan = true;
            VillageSavedData data = VillageSavedData.get(overworld);
            if (data.getAllKingdoms().isEmpty()
                    && !VillageTypeRegistry.INSTANCE.getAvailableTypes().isEmpty()) {
                planKingdoms(overworld, tick);
            }
        }

        if (scheduled.isEmpty()) return;
        scheduledDelay--;
        if (scheduledDelay > 0) return;

        if (event.getServer().getAverageTickTimeNanos() > 100_000_000L) {
            scheduledDelay = 100;
            return;
        }

        ScheduledKingdom next = scheduled.remove(0);
        processSpawn(overworld, next);
        scheduledDelay = SPAWN_INTERVAL;
    }

    private static void planKingdoms(ServerLevel level, long tick) {
        Random rng = new Random(level.getSeed() * 6364136223846793005L + 1442695040888963407L);
        int count = MIN_KINGDOMS + rng.nextInt(MAX_KINGDOMS - MIN_KINGDOMS + 1);
        System.out.println("[WorldGenKingdomSeeder] Planning " + count
                + " kingdoms for seed " + level.getSeed());

        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i / count) + (rng.nextDouble() - 0.5) * 0.4;
            int dist     = MIN_KINGDOM_DIST + rng.nextInt(MAX_KINGDOM_DIST - MIN_KINGDOM_DIST);
            int cx       = (int)(Math.cos(angle) * dist);
            int cz       = (int)(Math.sin(angle) * dist);
            String[] culture = chooseCulture(rng, i);
            String name      = generateKingdomName(rng);
            List<String> comp = buildComposition(rng, culture[0], culture[1]);
            scheduled.add(new ScheduledKingdom(cx, cz, name, culture[0], comp));
            System.out.println("[WorldGenKingdomSeeder] Queued '" + name
                    + "' (" + culture[0] + ") at " + cx + "," + cz);
        }
        scheduledDelay = 20;
    }

    private static void processSpawn(ServerLevel level, ScheduledKingdom sk) {
        if (level.getServer() == null) return;
        System.out.println("[WorldGenKingdomSeeder] Loading chunks around "
                + sk.cx + "," + sk.cz + " for '" + sk.name + "'...");
        ensureChunksLoaded(level, sk.cx, sk.cz, CHUNK_LOAD_RADIUS);
        int surfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sk.cx, sk.cz);
        if (surfY <= level.getMinY() + 8) {
            System.out.println("[WorldGenKingdomSeeder] '" + sk.name
                    + "' skipped — ocean/void at " + sk.cx + "," + sk.cz
                    + " (surfY=" + surfY + ")");
            return;
        }
        BlockPos origin = new BlockPos(sk.cx, surfY, sk.cz);
        System.out.println("[WorldGenKingdomSeeder] Spawning '" + sk.name
                + "' (" + sk.culture + ") at " + origin.toShortString()
                + " | " + sk.composition);
        KingdomSpawner.spawnComposed(level, origin, sk.name, sk.culture, sk.composition,
                msg -> System.out.println("  " + msg));
    }

    // First kingdom always gets default culture so there is always a royal capital.
    // Others are weighted 50% default, 25% highland, 25% imperial.
    private static String[] chooseCulture(Random rng, int index) {
        Set<String> av = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        if (index == 0 && av.contains("royal_capital"))    return CULTURES[0];
        int roll = rng.nextInt(4);
        if (roll <= 1 && av.contains("royal_capital"))     return CULTURES[0];
        if (roll == 2 && av.contains("highland_capital"))  return CULTURES[1];
        if (roll == 3 && av.contains("imperial_capital"))  return CULTURES[2];
        return CULTURES[0];
    }

    private static List<String> buildComposition(Random rng, String culture, String capitalType) {
        Set<String> av = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        List<String> c = new ArrayList<>();
        c.add(has(av, capitalType,        "default"));          // 0 — capital
        c.add(has(av, "farming_village",  "default"));          // 1 — food
        c.add(has(av, "farming_village",  "default"));          // 2 — food
        c.add(has(av, "mining_camp",      "default"));          // 3 — materials
        String specialist = switch (culture) {                   // 4 — specialist
            case "highland" -> has(av, "fortress_town",   "default");
            case "imperial" -> has(av, "trade_hub",       "default");
            default         -> {
                String[] opts = {"noble_estate","trade_hub","riverside_town","farming_village"};
                yield has(av, opts[rng.nextInt(opts.length)], "default");
            }
        };
        c.add(specialist);
        return c;
    }

    private static String has(Set<String> av, String pref, String fallback) {
        return av.contains(pref) ? pref : fallback;
    }

    private static void ensureChunksLoaded(ServerLevel level, int worldX, int worldZ, int r) {
        int cx = worldX >> 4, cz = worldZ >> 4;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true);
    }

    // Name tables — indexed by style (independent of actual culture for variety)
    private static final String[][] PREFIXES = {
            {"Iron","Stone","Gold","Silver","Oak","Dawn","Ember","Ridge","Crest","Vale"},
            {"Dun","Glen","Crag","Fern","Ash","Raven","Storm","Mist","Frost","Cairn"},
            {"Aur","Ferr","Caer","Vect","Mons","Ripa","Petra","Vela","Lux","Sal"},
    };
    private static final String[][] SUFFIXES = {
            {"realm","domain","lands","hold","march","shire","ward","keep","mark","reach"},
            {"clan","hold","moor","vale","ridge","peak","croft","tor","brae","fen"},
            {"ium","ia","us","atis","ensis","orum","anum","icus","inus","um"},
    };

    private static String generateKingdomName(Random rng) {
        int style = rng.nextInt(PREFIXES.length);
        return PREFIXES[style][rng.nextInt(PREFIXES[style].length)]
                + SUFFIXES[style][rng.nextInt(SUFFIXES[style].length)];
    }

    private record ScheduledKingdom(int cx, int cz, String name,
                                    String culture, List<String> composition) {}
}