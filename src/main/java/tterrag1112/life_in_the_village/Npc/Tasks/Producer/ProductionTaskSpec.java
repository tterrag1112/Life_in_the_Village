package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The per-profession <b>data contract</b> that drives the generalized
 * producer infrastructure ({@link ProductionTaskSource} +
 * {@link CraftOutputFulfillment} + {@link SellSurplusFulfillment}).
 *
 * <p>This is the seam the profession sweep uses: adding a production
 * profession to the Task System is implementing one of these (its recipes,
 * quotas, buffers, building, workstation, skills, buy basket, and the
 * intermediate-vs-final split) and registering it &mdash; <i>not</i> writing
 * new task-source / fulfillment classes. The blacksmith pilot's bespoke
 * classes were collapsed into this contract; anything genuinely
 * profession-specific (the blacksmith's smelt-vs-buy intermediate
 * acquisition) is a separate per-profession {@code Fulfillment} keyed to
 * {@code Acquire}/{@code MaintainStock}.</p>
 *
 * <h3>Output taxonomy</h3>
 * <ul>
 *   <li><b>Finals</b> ({@link #finalOutputs}) &mdash; the goods the profession
 *       exists to make (tools/armor/weapons for the smith). The source
 *       emits a {@code ProvideItem(final)} at NORMAL, urgency from the
 *       deficit. {@link #craftPlan} builds the produce {@link Plan}.</li>
 *   <li><b>Intermediates</b> ({@link #intermediateOutputs}) &mdash; inputs the
 *       profession can itself produce (iron ingots for the smith). The
 *       source emits a {@code MaintainStock(intermediate)} at LOW (a
 *       reserve, never a gate). A final short on an intermediate lazily
 *       spawns an {@code Acquire(intermediate)} (see {@link CraftOutputFulfillment}).
 *       A profession with no intermediate step returns an empty list and
 *       {@link #intermediatePlan}/{@link #intermediateInputsOf} are never
 *       consulted.</li>
 * </ul>
 */
public interface ProductionTaskSpec {

    /** The profession this spec drives (the migration-gate match). */
    Profession profession();

    /** The building type the worker produces in (resolved via the source). */
    BuildingType buildingType();

    /** The profession's final outputs &mdash; one {@code ProvideItem} task each. */
    List<Item> finalOutputs();

    /** The profession's self-produced intermediates &mdash; one {@code MaintainStock}
     *  reserve each; empty for professions with no intermediate step. */
    List<Item> intermediateOutputs();

    /** Keep floor (production target) for {@code output}; 0 means untracked. */
    int quota(Item output);

    /** Refill buffer for an intermediate reserve (the MaintainStock buffer and
     *  the surplus headroom above quota before selling kicks in). */
    int buffer(Item output);

    /**
     * Build the produce {@link Plan} for a <b>final</b> {@code output}, or
     * empty if it can't run right now (under-skilled, no workstation, inputs
     * absent). Reuses the shared production state machine via the executor.
     */
    Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc, Building building, Item output);

    /**
     * Build the produce {@link Plan} for an <b>intermediate</b> {@code output}
     * (the profession making its own input &mdash; e.g. smelting), or empty.
     * Default empty: a profession with no self-produced intermediate never
     * plans one.
     */
    default Optional<Plan> intermediatePlan(ServerLevel level, TownspersonMob npc,
                                            Building building, Item output) {
        return Optional.empty();
    }

    /**
     * The per-batch inputs of the recipe that makes final {@code output} for
     * {@code npc} (skill-gated), used by the generic lazy-Acquire mechanism to
     * detect which intermediate a final is short on. Empty means no resolvable
     * recipe (treated as "no intermediate dependency").
     */
    Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc);

    /**
     * Which of {@code output}'s recipe inputs are self-produced intermediates
     * the producer should lazily {@code Acquire} when short. Default: the
     * intersection of {@link #finalRecipeInputs} keys and
     * {@link #intermediateOutputs}.
     */
    default List<Item> intermediateInputsOf(Item output, TownspersonMob npc) {
        List<Item> inter = intermediateOutputs();
        return finalRecipeInputs(output, npc).keySet().stream()
                .filter(inter::contains)
                .toList();
    }

    /**
     * The complete set of items this spec will buy via {@link BuyFulfillment}:
     * {@link #intermediateOutputs()} (self-produced intermediates the smith
     * smelts <em>or</em> buys) plus, for every final output, all of
     * {@link #intermediateInputsOf} (recipe inputs the four buy-only professions
     * purchase directly).
     *
     * <p>Used by {@link BuyFulfillment#canFulfill} to gate which
     * {@code Acquire}/{@code MaintainStock} tasks this spec's fulfillment will
     * accept — ensuring a producer never matches the household
     * {@code MaintainStock(BREAD)} task or any other unrelated acquisition.</p>
     */
    default Set<Item> acquirableInputs(TownspersonMob npc) {
        Set<Item> result = new LinkedHashSet<>(intermediateOutputs());
        for (Item final_ : finalOutputs()) {
            result.addAll(intermediateInputsOf(final_, npc));
        }
        return result;
    }

    /** Items eligible for surplus sale (typically finals ++ intermediates). */
    List<Item> sellableOutputs();

    /** Day-tick after which surplus may be sold (the sell window opens). */
    int sellWindowDayTick();
}
