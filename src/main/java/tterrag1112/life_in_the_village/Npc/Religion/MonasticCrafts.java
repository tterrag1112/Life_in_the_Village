package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.ArrayList;
import java.util.List;

/**
 * Religion Rework R6c — the single source of the monastic-craft definitions,
 * shared by {@code MonkProductionBehavior} (need-priority production) and
 * {@link MonasteryDeveloper} (steering idle initiates toward needed crafts).
 * Extracted from R6b's behavior-private table so the behavior and the developer
 * agree on what the monastery can make, what it needs, and how a skill-less monk
 * develops toward it.
 *
 * <p>The monastery's <b>needs</b> are derived locally (no parallel needs system):
 * a craft's {@link #need} is its target {@code quota} minus the monastery store's
 * current stock of the good. Higher deficit ⇒ more needed. (Food-eating /
 * distribution is R6d; these are the producible role-goods.)</p>
 */
public final class MonasticCrafts {

    private MonasticCrafts() {}

    /**
     * One monastic craft. {@code quota} is the target stock (the need = quota −
     * store stock). {@code amenities} empty = no workstation (always supported).
     * {@code hobbyId} (nullable) lets the developer also steer the existing
     * hobby-drift path where a matching hobby exists.
     */
    public record MonasticCraft(Skill skill, int minLevel, List<AmenityType> amenities,
                                ProductionRecipe recipe, int quota,
                                int xpPerBatch, String activityLabel, String hobbyId) {}

    /** The monastic crafts — by SKILL × AMENITY (R6b), with their R6c targets.
     *  Candles need no workstation (always supported); the rest gate on an
     *  amenity. Only candlemaking has a matching home hobby today. */
    public static final List<MonasticCraft> CRAFTS = List.of(
            new MonasticCraft(Skill.CANDLEMAKING, 1, List.of(),
                    SkillRecipes.MAKE_CANDLE, 16, 2, "Making candles", "home_chandlery"),
            new MonasticCraft(Skill.BEEKEEPING, 1, List.of(AmenityType.APIARY),
                    SkillRecipes.HARVEST_HONEY, 16, 2, "Tending the apiary", null),
            new MonasticCraft(Skill.LITERACY, 1, List.of(AmenityType.LECTERN),
                    SkillRecipes.COPY_MANUSCRIPT, 8, 2, "Copying a manuscript", null),
            new MonasticCraft(Skill.VILLAGE_MEDICINE, 1, List.of(AmenityType.BREWING_STAND),
                    SkillRecipes.BREW_TONIC, 8, 2, "Brewing a tonic", null));

    /** The monastery's current shortfall of a craft's good: {@code quota − stock}
     *  (0 when stocked). The derived "need". */
    public static int need(ServerLevel level, Building monastery, MonasticCraft craft) {
        int stock = BuildingStorageAccess.countItem(level, monastery, craft.recipe().output());
        return Math.max(0, craft.quota() - stock);
    }

    /** The amenity position for a craft at the monastery, or null when the
     *  monastery doesn't support it (a needed amenity is absent). A no-amenity
     *  craft (candles) returns null but {@link #isSupported} is still true. */
    public static BlockPos amenityPos(ServerLevel level, Building monastery, MonasticCraft craft) {
        if (craft.amenities().isEmpty()) return null;
        return AmenityType.firstPresent(level, monastery, craft.amenities());
    }

    /** True when the monastery's facilities support the craft (no amenity needed,
     *  or its amenity is present). */
    public static boolean isSupported(ServerLevel level, Building monastery, MonasticCraft craft) {
        return craft.amenities().isEmpty()
                || AmenityType.firstPresent(level, monastery, craft.amenities()) != null;
    }

    /** The crafts the monastery's facilities support (amenity present / none needed). */
    public static List<MonasticCraft> supportedAt(ServerLevel level, Building monastery) {
        List<MonasticCraft> out = new ArrayList<>();
        for (MonasticCraft c : CRAFTS) {
            if (isSupported(level, monastery, c)) out.add(c);
        }
        return out;
    }

    /** Whether {@code monk} is already a viable producer of {@code craft}
     *  (skill ≥ the craft's min level) — i.e. no longer an "initiate" for it. */
    public static boolean isProducer(TownspersonMob monk, MonasticCraft craft) {
        return monk.getSkills().getLevel(craft.skill()) >= craft.minLevel();
    }
}
