package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static catalogue of {@link HobbyDefinition}s. Idempotent lazy init.
 *
 * <p>Phase 2 ships 17 starter hobbies (the 15 from spec line 127's table
 * plus {@code visit_grave} and {@code tell_story} called out elsewhere
 * in the spec). Phase 5 content pass extends.</p>
 */
public final class HobbyCatalogue {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, HobbyDefinition> HOBBIES = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    private HobbyCatalogue() {}

    public static synchronized void register(HobbyDefinition hobby) {
        if (HOBBIES.containsKey(hobby.id())) {
            LOGGER.warn("[HobbyCatalogue] Duplicate id: {}", hobby.id());
        }
        HOBBIES.put(hobby.id(), hobby);
    }

    public static Optional<HobbyDefinition> get(String id) {
        ensureInit();
        return Optional.ofNullable(HOBBIES.get(id));
    }

    public static List<HobbyDefinition> all() {
        ensureInit();
        return List.copyOf(HOBBIES.values());
    }

    /**
     * Filters the catalogue to hobbies the NPC can actually perform
     * given current profession, skill, and the village's available
     * locations. Spec line 156.
     */
    public static List<HobbyDefinition> availableFor(TownspersonMob npc, ServerLevel level) {
        ensureInit();
        List<HobbyDefinition> out = new ArrayList<>();
        for (HobbyDefinition def : HOBBIES.values()) {
            if (def.requiresProfession().isPresent()
                    && def.requiresProfession().get() != npc.getProfession()) continue;
            if (def.requiredSkill().isPresent()
                    && npc.getSkills().getLevel(def.requiredSkill().get()) < def.requiredSkillLevel()) continue;
            if (HobbyLocationResolver.resolve(def.location(), npc, level).isEmpty()) continue;
            out.add(def);
        }
        return out;
    }

    public static synchronized void ensureInit() {
        if (initialised) return;
        registerDefaults();
        initialised = true;
        LOGGER.info("[HobbyCatalogue] Registered {} hobbies", HOBBIES.size());
    }

    /** For tests/debug — reset the catalogue and reinitialise. */
    public static synchronized void reload() {
        HOBBIES.clear();
        initialised = false;
        ensureInit();
    }

    // =========================================================================
    // Phase 2 starter set — 17 hobbies (spec table + visit_grave + tell_story)
    // =========================================================================

    private static void registerDefaults() {
        register(HobbyDefinition.builder("read_at_library")
                .display("Reading at the library")
                .at(HobbyLocation.LIBRARY)
                .activity(HobbyActivity.SIT_AND_READ)
                .duration(2400)
                .weight(TraitAxis.TEMPERANCE, 0.6f)
                .weight(TraitAxis.COMPASSION, 0.3f)
                .grants(Skill.LITERACY, 2)
                .build());

        register(HobbyDefinition.builder("carve_at_home")
                .display("Carving at home")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.SIT_AND_CARVE)
                .duration(2000)
                .weight(TraitAxis.TEMPERANCE, 0.5f)
                .weight(TraitAxis.SOCIABILITY, -0.4f)
                .grants(Skill.CRAFTING, 1)
                .build());

        register(HobbyDefinition.builder("inn_drink_talk")
                .display("Drinks at the inn")
                .at(HobbyLocation.INN)
                .activity(HobbyActivity.DRINK_AND_TALK)
                .duration(2400)
                .weight(TraitAxis.SOCIABILITY, 0.7f)
                .grants(Skill.SOCIAL, 1)
                .build());

        register(HobbyDefinition.builder("cards_at_inn")
                .display("Cards at the inn")
                .at(HobbyLocation.INN)
                .activity(HobbyActivity.CARD_GAMES)
                .duration(2400)
                .weight(TraitAxis.SOCIABILITY, 0.5f)
                .weight(TraitAxis.TEMPERANCE, -0.3f)
                .build());

        register(HobbyDefinition.builder("fishing")
                .display("Fishing")
                .at(HobbyLocation.WATER_EDGE)
                .activity(HobbyActivity.FISH)
                .duration(2400)
                .weight(TraitAxis.TEMPERANCE, 0.6f)
                .weight(TraitAxis.SOCIABILITY, -0.3f)
                .grants(Skill.SURVIVAL, 2)
                .build());

        register(HobbyDefinition.builder("tend_garden")
                .display("Tending the garden")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.GARDEN)
                .duration(1600)
                .weight(TraitAxis.COMPASSION, 0.5f)
                .weight(TraitAxis.INDUSTRY, 0.4f)
                .grants(Skill.FARMING, 1)
                .build());

        register(HobbyDefinition.builder("flowers")
                .display("Tending flowers")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.TEND_FLOWERS)
                .duration(1200)
                .weight(TraitAxis.COMPASSION, 0.6f)
                .weight(TraitAxis.COURAGE, -0.2f)
                // Phase 6.4.4.2 — modest FARMING drift. Flower-tenders
                // accumulate plant-care knowledge that crosses into
                // crops over time. Same magnitude as tend_garden so
                // either-or hobby choice doesn't create a progression gap.
                .grants(Skill.FARMING, 1)
                .build());

        register(HobbyDefinition.builder("pray_shrine")
                .display("Prayer at the shrine")
                .at(HobbyLocation.TEMPLE_OR_SHRINE)
                .activity(HobbyActivity.PRAY)
                .duration(1200)
                .weight(TraitAxis.COMPASSION, 0.6f)
                .build());

        register(HobbyDefinition.builder("meditate")
                .display("Meditating")
                .at(HobbyLocation.TEMPLE_OR_SHRINE)
                .activity(HobbyActivity.MEDITATE)
                .duration(1600)
                .weight(TraitAxis.TEMPERANCE, 0.7f)
                .build());

        register(HobbyDefinition.builder("sword_practice")
                .display("Sword practice")
                .at(HobbyLocation.TOWN_SQUARE)
                .activity(HobbyActivity.SWORD_PRACTICE)
                .duration(1600)
                .weight(TraitAxis.COURAGE, 0.6f)
                .weight(TraitAxis.AMBITION, 0.3f)
                .grants(Skill.COMBAT, 2)
                .build());

        register(HobbyDefinition.builder("archery")
                .display("Archery practice")
                .at(HobbyLocation.NATURE_TRAIL)
                .activity(HobbyActivity.ARCHERY_PRACTICE)
                .duration(2000)
                .weight(TraitAxis.COURAGE, 0.6f)
                .grants(Skill.COMBAT, 2)
                .build());

        register(HobbyDefinition.builder("long_walk")
                .display("Walking")
                .at(HobbyLocation.NATURE_TRAIL)
                .activity(HobbyActivity.WALK)
                .duration(2400)
                .weight(TraitAxis.TEMPERANCE, 0.5f)
                .build());

        register(HobbyDefinition.builder("home_cooking")
                .display("Cooking at home")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.COOK)
                .duration(1600)
                .weight(TraitAxis.COMPASSION, 0.5f)
                // Phase 6.4.4.2 — drift toward BAKING. Home cooks
                // become emergent backup bakers over years of practice
                // without needing the BAKER profession. Prepares the
                // ground for the deferred homestead-skill framework.
                .grants(Skill.BAKING, 1)
                .build());

        // Phase 6.6.6.4 — homestead-skill drift hobbies, siblings to
        // home_cooking. Each trains a profession-adjacent CRAFTING
        // sub-skill via the same +1 XP/session magnitude, so emergent
        // backup weavers / chandlers develop over years of practice.
        // Activity visual reuses SIT_AND_CARVE (handwork pose) — both
        // are "sitting calmly working with the hands" in animation.
        register(HobbyDefinition.builder("home_weaving")
                .display("Spinning at home")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.SIT_AND_CARVE)
                .duration(600)
                .weight(TraitAxis.COMPASSION, 0.4f)
                .weight(TraitAxis.INDUSTRY, -0.2f)
                .grants(Skill.WEAVING, 1)
                .build());

        register(HobbyDefinition.builder("home_chandlery")
                .display("Making candles at home")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.SIT_AND_CARVE)
                .duration(500)
                .weight(TraitAxis.TEMPERANCE, 0.4f)
                .weight(TraitAxis.COMPASSION, 0.3f)
                .grants(Skill.CANDLEMAKING, 1)
                .build());

        register(HobbyDefinition.builder("write_letter")
                .display("Writing letters")
                .at(HobbyLocation.HOME)
                .activity(HobbyActivity.WRITE_LETTER)
                .duration(1600)
                .weight(TraitAxis.SOCIABILITY, 0.5f)
                .grants(Skill.LITERACY, 3)
                .requiresSkill(Skill.LITERACY, 30)
                .build());

        register(HobbyDefinition.builder("visit_friend")
                .display("Visiting a friend")
                .at(HobbyLocation.FRIEND_HOUSE)
                .activity(HobbyActivity.VISIT_FRIEND)
                .duration(1600)
                .weight(TraitAxis.SOCIABILITY, 0.6f)
                .weight(TraitAxis.COMPASSION, 0.3f)
                .build());

        register(HobbyDefinition.builder("shop_aimless")
                .display("Browsing the market")
                .at(HobbyLocation.MARKET)
                .activity(HobbyActivity.SHOP_AIMLESSLY)
                .duration(1600)
                .weight(TraitAxis.SOCIABILITY, 0.4f)
                .build());

        register(HobbyDefinition.builder("visit_grave")
                .display("Visiting the graveyard")
                .at(HobbyLocation.GRAVEYARD)
                .activity(HobbyActivity.VISIT_GRAVE)
                .duration(1200)
                .weight(TraitAxis.COMPASSION, 0.6f)
                .build());

        register(HobbyDefinition.builder("tell_story_inn")
                .display("Telling stories at the inn")
                .at(HobbyLocation.INN)
                .activity(HobbyActivity.TELL_STORY)
                .duration(2000)
                .weight(TraitAxis.SOCIABILITY, 0.6f)
                .grants(Skill.SOCIAL, 1)
                .build());
    }
}
