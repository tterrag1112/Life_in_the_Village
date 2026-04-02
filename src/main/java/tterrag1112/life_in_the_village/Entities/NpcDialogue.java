package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeed;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

/**
 * Generates contextual one-liner dialogue for NPCs when interacted with.
 *
 * <h3>Why this exists</h3>
 * NPCs had personality traits and profession states, but no way to express
 * them to the player. This system picks a single line based on weighted
 * context — the most "interesting" fact about the NPC's current state wins.
 *
 * <h3>Priority order</h3>
 * <ol>
 *   <li>Active village event (festival, market day)</li>
 *   <li>Reputation-based reaction (hostile, distrustful, warm)</li>
 *   <li>Village need (food shortage, material crisis)</li>
 *   <li>Season/weather comment</li>
 *   <li>Profession-specific idle line</li>
 *   <li>Personality trait flavour</li>
 *   <li>Generic fallback</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * Called from {@code TownspersonMob.mobInteract} for NPCs that don't
 * have a dedicated interaction screen (non-merchant, non-guild, etc.),
 * and as a prefix greeting before opening trade/guild screens.
 */
public final class NpcDialogue {

    private NpcDialogue() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns a contextual greeting line from the NPC to the player.
     * Never returns null — worst case is a generic fallback.
     */
    public static String getGreeting(TownspersonMob npc,
                                     ServerPlayer player,
                                     ServerLevel level) {
        RandomSource rng = npc.getRandom();

        // 1. Active event
        Optional<String> eventLine = getEventLine(npc, level, rng);
        if (eventLine.isPresent()) return eventLine.get();

        // 2. Reputation reaction
        Optional<String> repLine = getReputationLine(npc, player, level, rng);
        if (repLine.isPresent()) return repLine.get();

        // 3. Village need
        Optional<String> needLine = getNeedLine(npc, level, rng);
        if (needLine.isPresent() && rng.nextInt(3) == 0) return needLine.get();

        // 4. Season/time
        Optional<String> seasonLine = getSeasonLine(level, rng);
        if (seasonLine.isPresent() && rng.nextInt(4) == 0) return seasonLine.get();

        // 5. Profession idle
        Optional<String> profLine = getProfessionLine(npc, rng);
        if (profLine.isPresent() && rng.nextInt(2) == 0) return profLine.get();

        // 6. Personality
        Optional<String> traitLine = getTraitLine(npc, rng);
        if (traitLine.isPresent()) return traitLine.get();

        // 7. Fallback
        return pick(rng, FALLBACK);
    }

    /**
     * Returns a short activity description for the NPC's current state.
     * Used for hover tooltips or overhead labels.
     */
    public static String getActivityLabel(TownspersonMob npc) {
        if (npc.isSleepTime()) return "Sleeping";
        if (npc.isWorkTime()) return "Working";
        if (npc.isSocialTime()) return "Relaxing";
        return "";
    }

    // =========================================================================
    // Context generators
    // =========================================================================

    private static Optional<String> getEventLine(TownspersonMob npc,
                                                 ServerLevel level,
                                                 RandomSource rng) {
        return npc.getEventOverride().map(event -> switch (event) {
            case HARVEST_FESTIVAL -> pick(rng,
                    "What a harvest! The granary is overflowing.",
                    "The harvest festival is the best time of year.",
                    "Have you tried the fresh bread? It's wonderful.");
            case FESTIVAL_OF_LIGHTS -> pick(rng,
                    "The lanterns are beautiful tonight, aren't they?",
                    "Winter festivals keep the spirits up.",
                    "Come warm yourself by the fire.");
            case MARKET_DAY -> pick(rng,
                    "Market day! Best prices you'll find all week.",
                    "The square is lively today. Good for business.",
                    "Come browse the stalls, plenty to see.");
            case VILLAGE_FAIR -> pick(rng,
                    "The fair brings visitors from far away.",
                    "I love fair days. Reminds me why I live here.",
                    "Have you played any of the games yet?");
            case TRAINING_DAY -> pick(rng,
                    "The guards are drilling today. Best stay clear.",
                    "Training day. The whole village feels on edge.",
                    "Good to see the guards keeping sharp.");
            default -> "Quite the event today!";
        });
    }

    private static Optional<String> getReputationLine(TownspersonMob npc,
                                                      ServerPlayer player,
                                                      ServerLevel level,
                                                      RandomSource rng) {
        return npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(village -> {
                    VillageReputation.Tier tier = ReputationManager.getTier(
                            player, village.getId(), level);
                    return switch (tier) {
                        case OUTCAST -> pick(rng,
                                "You're not welcome here.",
                                "I have nothing to say to you.",
                                "Leave. Now.");
                        case DISTRUSTED -> pick(rng,
                                "I'm keeping my eye on you.",
                                "Don't cause any trouble.",
                                "Hmph. What do you want?");
                        case PILLAR -> pick(rng,
                                "Good to see you! The village owes you much.",
                                "Ah, our most valued friend! How can I help?",
                                "Everyone speaks well of you around here.");
                        case TRUSTED -> pick(rng,
                                "Welcome back, friend.",
                                "Always glad to see a familiar face.",
                                "How are things? You're well-liked here.");
                        default -> null; // NEUTRAL — fall through to other lines
                    };
                });
    }

    private static Optional<String> getNeedLine(TownspersonMob npc,
                                                ServerLevel level,
                                                RandomSource rng) {
        return npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village -> {
                    VillageNeed food = village.getNeeds().get(NeedCategory.FOOD);
                    if (food != null && food.getLevel() == NeedLevel.CRITICAL) {
                        return Optional.of(pick(rng,
                                "We're running low on food. It's getting dire.",
                                "The granary is nearly empty. We need help.",
                                "If someone doesn't bring food soon..."));
                    }
                    if (food != null && food.getLevel() == NeedLevel.LOW) {
                        return Optional.of(pick(rng,
                                "Food stores are getting thin. Hope the farmers pull through.",
                                "We could use more wheat in the stockpile.",
                                "Wouldn't mind seeing a few more crops planted."));
                    }

                    VillageNeed materials = village.getNeeds().get(NeedCategory.BUILDING_MATERIALS);
                    if (materials != null && materials.getLevel() == NeedLevel.CRITICAL) {
                        return Optional.of(pick(rng,
                                "We're out of building materials. Nothing can get repaired.",
                                "The stockpile is bare. We need stone and wood.",
                                "Without materials, this village will fall apart."));
                    }

                    if (food != null && food.getLevel() == NeedLevel.SURPLUS) {
                        return Optional.of(pick(rng,
                                "The stores are full! Good times.",
                                "Plenty to eat. Life is good.",
                                "Haven't seen the granary this full in ages."));
                    }

                    return Optional.empty();
                });
    }

    private static Optional<String> getSeasonLine(ServerLevel level,
                                                  RandomSource rng) {
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);
        if (season == null) return Optional.empty();

        return Optional.of(switch (season) {
            case SPRING -> pick(rng,
                    "Spring at last. Good to feel the warmth again.",
                    "The fields are coming alive. I can smell the earth.",
                    "Perfect weather for planting.");
            case SUMMER -> pick(rng,
                    "Hot day. I could use some shade.",
                    "Summer days are long. Good for getting work done.",
                    "The crops are growing well in this heat.");
            case AUTUMN -> pick(rng,
                    "Leaves are turning. Harvest will be soon.",
                    "Autumn air. My favourite time of year.",
                    "Better get the stores ready for winter.");
            case WINTER -> pick(rng,
                    "Brr. Stay close to the fires.",
                    "Winter's grip is strong this year.",
                    "Not much grows in this cold. We'll manage.");
        });
    }

    private static Optional<String> getProfessionLine(TownspersonMob npc,
                                                      RandomSource rng) {
        Profession prof = npc.getProfession();
        if (prof == Profession.NONE || prof == Profession.CITIZEN) {
            return Optional.empty();
        }

        String line = switch (prof) {
            case FARMER -> pick(rng,
                    "The soil's good here. Crops come in strong.",
                    "Another day in the fields.",
                    "I keep the village fed. It's honest work.");
            case BLACKSMITH -> pick(rng,
                    "The forge is hot today.",
                    "Need anything repaired? I'm your man.",
                    "Iron doesn't shape itself.");
            case MERCHANT -> pick(rng,
                    "Looking to buy? I've got good stock.",
                    "Fair prices, always. That's my promise.",
                    "Trade keeps this village alive.");
            case GUARD -> pick(rng,
                    "Stay alert. These roads aren't always safe.",
                    "All quiet on the watch.",
                    "I've got my eye on the perimeter.");
            case BUILDER -> pick(rng,
                    "Always something to build or fix around here.",
                    "I've been eyeing a spot for a new building.",
                    "Good foundations make good villages.");
            case INNKEEPER -> pick(rng,
                    "Come in, rest your feet. I'll pour you something.",
                    "The inn's always open for travellers.",
                    "You look like you could use a meal.");
            case MINER -> pick(rng,
                    "The tunnels run deep. Good ore down there.",
                    "Dark work, but the village needs the stone.",
                    "Found some promising veins yesterday.");
            case CARPENTER -> pick(rng,
                    "Wood's the bones of every building here.",
                    "I can smell fresh-cut oak from a mile away.",
                    "Need planks? Fences? I'm your carpenter.");
            case STOCKPILE_KEEPER -> pick(rng,
                    "Everything's accounted for. Don't touch anything.",
                    "The stockpile is organized. I like it that way.",
                    "In and out, that's how it goes.");
            case VILLAGE_LEADER -> pick(rng,
                    "The village is in good shape. We'll keep it that way.",
                    "I oversee things around here. What do you need?",
                    "There's always something that needs deciding.");
            case SCHOLAR -> pick(rng,
                    "Knowledge is the foundation of civilization.",
                    "I've been reading about the old kingdoms.",
                    "The library has more to offer than you'd think.");
            case PRIEST -> pick(rng,
                    "May your journey be blessed.",
                    "The temple is always open to those who seek peace.",
                    "I tend to the spirit of this village.");
            case GUILDWORKER -> pick(rng,
                    "The guild board has contracts, if you're interested.",
                    "Adventurers come and go. The guild endures.",
                    "Looking for work? Talk to the guildmaster.");
            default -> null;
        };

        return Optional.ofNullable(line);
    }

    private static Optional<String> getTraitLine(TownspersonMob npc,
                                                 RandomSource rng) {
        // Pick from the NPC's actual traits
        List<AppearanceComponent.PersonalityTrait> traits = npc.getTraits();
        if (traits.isEmpty()) return Optional.empty();

        AppearanceComponent.PersonalityTrait trait = traits.get(rng.nextInt(traits.size()));
        String line = switch (trait) {
            case FRIENDLY -> pick(rng,
                    "It's nice to see a new face!",
                    "I hope you're enjoying our village.",
                    "Let me know if you need anything.");
            case SUSPICIOUS -> pick(rng,
                    "I don't know you. Keep your hands where I can see them.",
                    "Hmm. You're not from around here.",
                    "I'll be watching.");
            case GREEDY -> pick(rng,
                    "Everything has a price, friend.",
                    "Business is business.",
                    "I don't work for free, you know.");
            case GENEROUS -> pick(rng,
                    "Take your time. No rush here.",
                    "If you need help, just ask. Happy to lend a hand.",
                    "Sharing's what makes a village a village.");
            case BRAVE -> pick(rng,
                    "Monsters don't scare me. Let them come.",
                    "I'd rather face danger than hide from it.",
                    "A little courage goes a long way.");
            case TIMID -> pick(rng,
                    "I... I hope nothing bad happens today.",
                    "Did you hear something? Probably nothing.",
                    "I prefer staying close to the village.");
            case CHEERFUL -> pick(rng,
                    "Beautiful day, isn't it?",
                    "I woke up with a good feeling today!",
                    "Life is good when the village is peaceful.");
            case GRUMPY -> pick(rng,
                    "What? Make it quick.",
                    "I've got things to do.",
                    "Hmph.");
            case DILIGENT -> pick(rng,
                    "No rest for the willing.",
                    "There's always more work to be done.",
                    "I take pride in my craft.");
            case LAZY -> pick(rng,
                    "I was just about to take a break...",
                    "Work will still be there tomorrow.",
                    "I'm pacing myself.");
        };

        return Optional.of(line);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static final String[] FALLBACK = {
            "Good day.",
            "Hello there.",
            "How do you do?",
            "Fine weather, isn't it?",
            "Welcome to our village."
    };

    private static String pick(RandomSource rng, String... options) {
        return options[rng.nextInt(options.length)];
    }
}