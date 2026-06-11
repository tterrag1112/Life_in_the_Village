// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/PartyConversationGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.*;

/**
 * Gives party members a voice — they comment on surroundings,
 * weather, quest progress, biomes, and each other.
 *
 * <h3>Comment triggers</h3>
 * <ul>
 *   <li><b>Weather</b> — raining, thundering, clear after rain</li>
 *   <li><b>Biome</b> — entering jungle, desert, swamp, mushroom fields</li>
 *   <li><b>Night</b> — dusk approach comments</li>
 *   <li><b>Combat aftermath</b> — after a fight (player took damage)</li>
 *   <li><b>Quest progress</b> — milestone comments (50%, complete)</li>
 *   <li><b>Idle chatter</b> — periodic role-flavoured remarks</li>
 * </ul>
 *
 * Comments are sent as chat messages prefixed with the NPC's name,
 * visible only to the party leader. They also update
 * {@link TownspersonMob#setCurrentActivity} briefly.
 *
 * Only one member speaks at a time — a shared cooldown stored
 * per-party in a static map prevents everyone talking at once.
 */
public class PartyConversationGoal extends Goal {

    // Ticks between any two party members speaking
    private static final long  PARTY_COOLDOWN     = 1200L; // 1 min
    // Ticks between this specific member speaking
    private static final long  MEMBER_COOLDOWN    = 2400L; // 2 min
    // How long the activity label lingers
    private static final int   ACTIVITY_DURATION  = 100;   // 5 sec

    // Shared cooldown: partyId → last speak tick
    private static final java.util.Map<java.util.UUID, Long>
            partyCooldowns = new java.util.HashMap<>();

    private final TownspersonMob entity;
    private long lastSpeakTick   = -1L;
    private int  activityTimer   = 0;
    private String lastComment   = "";

    // State tracking for trigger detection
    private boolean wasRaining   = false;
    private String  lastBiome    = "";
    private float   lastPlayerHp = 20.0f;

    public PartyConversationGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class)); // non-exclusive
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getCombatRole() == null) return false;
        return getParty(level).isPresent();
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        long currentTick = level.getGameTime();

        // Count down activity label
        if (activityTimer > 0) {
            activityTimer--;
            if (activityTimer == 0) {
                entity.setCurrentActivity("");
            }
        }

        // Don't speak if on member cooldown
        if (currentTick - lastSpeakTick < MEMBER_COOLDOWN) return;

        Optional<PlayerParty> partyOpt = getParty(level);
        if (partyOpt.isEmpty()) return;

        PlayerParty party = partyOpt.get();
        ServerPlayer player = getPlayer(level, party);
        if (player == null) return;

        // Don't speak if party cooldown active
        long partyLastSpeak = partyCooldowns.getOrDefault(
                party.getPartyId(), -9999L);
        if (currentTick - partyLastSpeak < PARTY_COOLDOWN) return;

        // Try to find something to say
        String comment = buildComment(level, party, player, currentTick);
        if (comment == null) return;

        speak(player, comment, level, party, currentTick);
    }

    // =========================================================================
    // Comment building — ordered by priority
    // =========================================================================

    private String buildComment(ServerLevel level, PlayerParty party,
                                ServerPlayer player, long currentTick) {
        // 1. Weather change
        String weather = checkWeather(level);
        if (weather != null) return weather;

        // 2. Biome change
        String biome = checkBiome(level, player);
        if (biome != null) return biome;

        // 3. Player took damage recently
        String combat = checkCombatAftermath(player);
        if (combat != null) return combat;

        // 4. Quest milestone
        String quest = checkQuestMilestone(party);
        if (quest != null) return quest;

        // 5. Dusk/night approach
        String time = checkTimeOfDay(level);
        if (time != null) return time;

        // 6. Nearby threat (mobs within 20 blocks)
        String threat = checkNearbyThreat(level, player);
        if (threat != null) return threat;

        // 7. Idle chatter — only occasionally, weighted by role
        if (level.getRandom().nextInt(20) == 0) {
            return idleChatter(level, party, player);
        }

        return null;
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    private String checkWeather(ServerLevel level) {
        boolean isRaining = level.isRaining();

        if (isRaining && !wasRaining) {
            wasRaining = true;
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "Rain won't slow us down. Keep moving.";
                case ARCHER    -> "Wet bowstring... range will be off.";
                case MAGE      -> "Fascinating — the ambient mana density rises in rain.";
                case HEALER    -> "Stay dry if you can. Wet wounds fester.";
                case SCOUT     -> "Rain covers sound. Could be an ambush. Stay sharp.";
            };
        }

        if (!isRaining && wasRaining) {
            wasRaining = false;
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "Rain's let up. Good.";
                case ARCHER    -> "Finally. String's been useless in that downpour.";
                case MAGE      -> "The mana settles. We can proceed more safely now.";
                case HEALER    -> "Good. Damp air was making my potions unstable.";
                case SCOUT     -> "Visibility's back. I'll range ahead.";
            };
        }

        if (level.isThundering() && level.getRandom().nextInt(100) == 0) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "I've fought in worse storms than this.";
                case ARCHER    -> "Can't shoot in this wind!";
                case MAGE      -> "The lightning... I could channel that.";
                case HEALER    -> "Keep together. Lightning is unpredictable.";
                case SCOUT     -> "Thunder masks our approach. Useful.";
            };
        }

        return null;
    }

    // ── Biome ─────────────────────────────────────────────────────────────────

    private String checkBiome(ServerLevel level, ServerPlayer player) {
        String currentBiome = level.getBiome(player.blockPosition())
                .unwrapKey()
                .map(k -> k.registry().toString())
                .orElse("");

        if (currentBiome.equals(lastBiome)) return null;
        String prev = lastBiome;
        lastBiome = currentBiome;
        if (prev.isEmpty()) return null; // first tick, don't comment

        return switch (currentBiome) {
            case "minecraft:jungle",
                 "minecraft:bamboo_jungle",
                 "minecraft:sparse_jungle" ->
                    biomeComment("Dense canopy ahead. I can barely see.",
                            "Arrows will deflect off the vegetation in here.",
                            "The jungle hums with life. And death.",
                            "Keep wounds clean in here. Everything rots fast.",
                            "Perfect ambush terrain. I'll stay close.");
            case "minecraft:desert",
                 "minecraft:badlands" ->
                    biomeComment("No cover. We're exposed here.",
                            "Wind and sand will ruin my bowstring.",
                            "Ancient power sleeps beneath this sand.",
                            "Watch for dehydration. Water is precious here.",
                            "Open ground. Nothing can sneak up on us. Good.");
            case "minecraft:swamp",
                 "minecraft:mangrove_swamp" ->
                    biomeComment("I hate swamps.",
                            "Damp air is a nightmare for fletching.",
                            "The swamp holds old magic. Dark magic.",
                            "Mosquitoes, rot, disease. Ideal.",
                            "Footing is treacherous. Don't rush.");
            case "minecraft:mushroom_fields" ->
                    biomeComment("What manner of place is this?",
                            "I've never had to shoot through giant mushrooms before.",
                            "The spores here... interesting.",
                            "Strangely calming. Mushrooms can heal, you know.",
                            "Nothing hostile lives here. First time for everything.");
            case "minecraft:deep_dark" ->
                    biomeComment("Something watches us. I feel it.",
                            "I can't see a thing. My arrows are useless.",
                            "Ancient, terrible power sleeps here. Do not wake it.",
                            "Silence. Complete silence. That's wrong.",
                            "Move slowly. Whatever lives here hunts by sound.");
            case "minecraft:snowy_plains",
                 "minecraft:frozen_peaks",
                 "minecraft:ice_spikes" ->
                    biomeComment("Fingers are going numb. This is fine.",
                            "Bowstring contracts in the cold. Short shots only.",
                            "Cold sharpens the mind.",
                            "Frostbite is a real danger. Tell me if you feel numb.",
                            "Tracks are clear in snow. Easy to follow.");
            default -> null;
        };
    }

    private String biomeComment(String swordsmanLine, String archerLine,
                                String mageLine, String healerLine,
                                String scoutLine) {
        return switch (getRole()) {
            case SWORDSMAN, SPEARMAN -> swordsmanLine;
            case ARCHER    -> archerLine;
            case MAGE      -> mageLine;
            case HEALER    -> healerLine;
            case SCOUT     -> scoutLine;
        };
    }

    // ── Combat aftermath ──────────────────────────────────────────────────────

    private String checkCombatAftermath(ServerPlayer player) {
        float currentHp = player.getHealth();
        float prev = lastPlayerHp;
        lastPlayerHp = currentHp;

        float maxHp = player.getMaxHealth();
        float fraction = currentHp / maxHp;

        // Player just took significant damage (>20% of max)
        if (currentHp < prev - (maxHp * 0.2f)) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "I've got them! Stay back!";
                case ARCHER    -> "I see it — covering!";
                case MAGE      -> "Hold — I'll bind them!";
                case HEALER    -> currentHp < maxHp * 0.4f
                        ? "You're badly hurt! Let me help!"
                        : "You're wounded — stay close.";
                case SCOUT     -> "Ambush! They came from the "
                        + getCardinalDirection(player) + "!";
            };
        }

        // Player is very low health
        if (fraction < 0.25f && prev / maxHp >= 0.25f) {
            return getRole() == CombatRole.HEALER
                    ? "You need healing NOW!"
                    : "You're in danger! Fall back!";
        }

        // Player healed back up after being low
        if (fraction > 0.7f && prev / maxHp < 0.35f) {
            return switch (getRole()) {
                case HEALER    -> "Good — colour's back in your face.";
                case SWORDSMAN -> "Glad you're still standing.";
                default        -> "You made it through. Stay sharp.";
            };
        }

        return null;
    }

    // ── Quest milestone ───────────────────────────────────────────────────────

    private String checkQuestMilestone(PlayerParty party) {
        if (!(entity.level() instanceof ServerLevel level)) return null;

        ServerPlayer player = getPlayer(level, party);
        if (player == null) return null;

        // F2b-2 — read the leader's first active guild quest from the F2 store and report
        // progress on its Hunt objective (the only kind with running count progress).
        var quest = tterrag1112.life_in_the_village.Quests.QuestSavedData.get(level)
                .activeGuildQuests(player.getUUID()).stream().findFirst().orElse(null);
        if (quest == null) return null;

        tterrag1112.life_in_the_village.Quests.Objective.Hunt hunt = quest.objectives().stream()
                .filter(o -> o instanceof tterrag1112.life_in_the_village.Quests.Objective.Hunt)
                .map(o -> (tterrag1112.life_in_the_village.Quests.Objective.Hunt) o)
                .findFirst().orElse(null);
        if (hunt == null || hunt.target() <= 0) return null;

        int current = hunt.current();
        int target = hunt.target();
        float prog = (float) current / target;

        // Comment at 50% and near completion
        if (prog >= 0.5f && prog < 0.55f) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "Halfway there. Keep the pace.";
                case ARCHER    -> "Good progress. " + current + " down.";
                case MAGE      -> "The contract proceeds satisfactorily.";
                case HEALER    -> "We're doing well. Stay healthy.";
                case SCOUT     -> "Halfway. Watch for patrols.";
            };
        }

        if (prog >= 0.9f && prog < 0.95f) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "Almost done. One last push.";
                case ARCHER    -> "Nearly there — " + (target - current) + " left.";
                case MAGE      -> "The contract nears completion.";
                case HEALER    -> "Almost finished. Then we rest.";
                case SCOUT     -> "Close. Don't get sloppy now.";
            };
        }

        return null;
    }

    // ── Time of day ───────────────────────────────────────────────────────────

    private String checkTimeOfDay(ServerLevel level) {
        long timeOfDay = level.getDayTime() % 24000L;

        // Dusk (11500–12500)
        if (timeOfDay >= 11500 && timeOfDay < 12000
                && level.getRandom().nextInt(5) == 0) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "Sun's going down. We should camp soon.";
                case ARCHER    -> "Can't shoot what I can't see. "
                        + "Find us a camp.";
                case MAGE      -> "Night amplifies certain... energies.";
                case HEALER    -> "Night travel is dangerous. "
                        + "Let's be cautious.";
                case SCOUT     -> "I'll scout ahead before we lose the light.";
            };
        }

        // Dawn (23000–24000)
        if (timeOfDay >= 23000
                && level.getRandom().nextInt(5) == 0) {
            return switch (getRole()) {
                case SWORDSMAN, SPEARMAN -> "First light. Time to move.";
                case ARCHER    -> "Good light. My aim is best at dawn.";
                case MAGE      -> "The night's power fades. "
                        + "A new day begins.";
                case HEALER    -> "Everyone slept? Good. "
                        + "We're ready to march.";
                case SCOUT     -> "I've already checked the path ahead. "
                        + "We're clear.";
            };
        }

        return null;
    }

    // ── Nearby threats ────────────────────────────────────────────────────────

    private String checkNearbyThreat(ServerLevel level,
                                     ServerPlayer player) {
        // Only scout and mage comment on threats proactively
        if (getRole() != CombatRole.SCOUT
                && getRole() != CombatRole.ARCHER) return null;
        if (level.getRandom().nextInt(10) != 0) return null;

        List<Monster> nearby = level.getEntitiesOfClass(
                Monster.class,
                player.getBoundingBox().inflate(20));

        if (nearby.size() >= 3) {
            return getRole() == CombatRole.SCOUT
                    ? nearby.size() + " hostiles nearby. "
                    + "To the " + getCardinalDirection(player)
                    + ". Recommend engaging now."
                    : "I count " + nearby.size()
                    + " targets. I'll prioritise the closest.";
        }

        return null;
    }

    // ── Idle chatter ──────────────────────────────────────────────────────────

    private String idleChatter(ServerLevel level, PlayerParty party,
                               ServerPlayer player) {
        // Role-flavoured idle lines referencing party composition,
        // past quests, and personality
        int questsDone = party.getTotalQuestsCompleted();
        String playerName = player.getName().getString();

        String[] lines = switch (getRole()) {
            case SWORDSMAN, SPEARMAN -> new String[]{
                    "Eyes forward.",
                    questsDone > 0
                            ? questsDone + " contracts done. "
                            + "Not bad, " + playerName + "."
                            : "First contract. I've had worse starts.",
                    party.hasRole(CombatRole.HEALER)
                            ? "Glad we have a healer. Last party I was in didn't."
                            : "We could use a healer.",
                    "My sword arm's ready. Whenever you are.",
                    "Quiet. Too quiet.",
            };
            case ARCHER -> new String[]{
                    "Wind's from the north. Good.",
                    "I've been counting. " + questsDone
                            + " marks taken. Clean shots, all of them.",
                    "Distance is safety. Remember that.",
                    party.hasRole(CombatRole.SCOUT)
                            ? "That scout is earning their keep."
                            : "I wish we had a scout.",
                    "String tension's good today.",
            };
            case MAGE -> new String[]{
                    "The ley lines run strong here.",
                    questsDone > 5
                            ? "Our reputation grows, " + playerName + ". "
                            + "The arcane councils will take notice."
                            : "We are building something here. I can feel it.",
                    "Power is nothing without discipline.",
                    party.hasRole(CombatRole.HEALER)
                            ? "The healer and I work well together. "
                            + "Balance of offense and recovery."
                            : "I should not be the one keeping us alive. "
                            + "We need a healer.",
                    "I catalogued three new reagent sources on the way here.",
            };
            case HEALER -> new String[]{
                    "Everyone's vitals look good.",
                    "I have " + (3 + (int)(Math.random() * 5))
                            + " healing draughts left. Use them wisely.",
                    questsDone > 3
                            ? "I've patched you up more than I expected, "
                            + playerName + ". "
                            + "But we're still here."
                            : "Stay safe and I won't have to work too hard.",
                    "The {biome} air has medicinal properties, actually.",
                    "I worry about that swordsman's shoulder. "
                            + "Old injury, I think.",
            };
            case SCOUT -> new String[]{
                    "Path ahead is clear. For now.",
                    "I spotted a campsite two hundred paces east. "
                            + "Abandoned, probably old.",
                    questsDone > 2
                            ? "We move like a proper outfit now, "
                            + playerName + "."
                            : "Still finding our rhythm. "
                            + "We'll be faster soon.",
                    party.hasRole(CombatRole.SWORDSMAN)
                            ? "The swordsman and I have a system. "
                            + "I find them, they finish them."
                            : "Without a frontliner I have to be careful "
                            + "what I pull.",
                    "I can hear water to the west. "
                            + "Might be worth checking.",
            };
        };

        return lines[level.getRandom().nextInt(lines.length)]
                .replace("{biome}", lastBiome.replace("minecraft:", "")
                        .replace("_", " "));
    }

    // =========================================================================
    // Speak
    // =========================================================================

    private void speak(ServerPlayer player, String comment,
                       ServerLevel level, PlayerParty party,
                       long currentTick) {
        if (comment == null || comment.isEmpty()) return;

        // Send chat message to party leader only
        player.displayClientMessage(
                Component.literal("[" + entity.getNpcName()
                        + "] " + comment),
                false);

        // Show briefly on nametag
        entity.setCurrentActivity(truncateForTag(comment));
        activityTimer = ACTIVITY_DURATION;
        lastComment   = comment;
        lastSpeakTick = currentTick;

        // Update party cooldown
        partyCooldowns.put(party.getPartyId(), currentTick);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CombatRole getRole() {
        return entity.getCombatRole() != null
                ? entity.getCombatRole() : CombatRole.SWORDSMAN;
    }

    private Optional<PlayerParty> getParty(ServerLevel level) {
        return PlayerPartySavedData.get(level)
                .getPartyContaining(entity.getUUID());
    }

    private ServerPlayer getPlayer(ServerLevel level,
                                   PlayerParty party) {
        return level.getServer().getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
    }

    private String getCardinalDirection(ServerPlayer player) {
        double dx = entity.getX() - player.getX();
        double dz = entity.getZ() - player.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double n = ((yaw % 360) + 360) % 360;
        if (n < 45 || n >= 315) return "north";
        if (n < 135) return "east";
        if (n < 225) return "south";
        return "west";
    }

    /** Truncates a line to fit the nametag without being annoying. */
    private String truncateForTag(String s) {
        if (s.length() <= 28) return s;
        return s.substring(0, 25) + "...";
    }
}