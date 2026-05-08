package tterrag1112.life_in_the_village.Entities.Goals.Homestead;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.BeehivesHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.ChickenCoopHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.GenericChoresHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.OrchardHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.PenHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.VegetableGardenHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.WoodshedHandler;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers.WorkshopHandler;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlotType;

import java.util.EnumMap;
import java.util.Map;

/**
 * B2.6 — registry mapping each {@code HOMESTEAD_*}
 * {@link AdjunctPlotType} to its {@link HomesteadHandler}.
 *
 * <p>Static initialization wires the 7 shipping handlers (coop,
 * garden, pen, bees, workshop, orchard, woodshed) plus the
 * {@link GenericChoresHandler} fallback used when a household has
 * no rolled plot — the prompt's "SPOUSE / CHILD still tend the
 * household" idiom.</p>
 */
public final class HomesteadHandlerRegistry {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HomesteadHandlerRegistry.class);

    private static final Map<AdjunctPlotType, HomesteadHandler> HANDLERS =
            new EnumMap<>(AdjunctPlotType.class);

    /** Returned by {@link #getOrFallback} when the household has no
     *  homesteadPlotType; SPOUSE / CHILD still run idle chores in
     *  and around the house. */
    public static final HomesteadHandler GENERIC_CHORES = new GenericChoresHandler();

    static {
        register(AdjunctPlotType.HOMESTEAD_COOP,     new ChickenCoopHandler());
        register(AdjunctPlotType.HOMESTEAD_GARDEN,   new VegetableGardenHandler());
        register(AdjunctPlotType.HOMESTEAD_PEN,      new PenHandler());
        register(AdjunctPlotType.HOMESTEAD_BEES,     new BeehivesHandler());
        register(AdjunctPlotType.HOMESTEAD_WORKSHOP, new WorkshopHandler());
        register(AdjunctPlotType.HOMESTEAD_ORCHARD,  new OrchardHandler());
        register(AdjunctPlotType.HOMESTEAD_WOODSHED, new WoodshedHandler());
    }

    private HomesteadHandlerRegistry() {}

    public static void register(AdjunctPlotType type, HomesteadHandler handler) {
        if (type == null || handler == null) return;
        HomesteadHandler prior = HANDLERS.put(type, handler);
        if (prior != null) {
            LOGGER.warn("HomesteadHandlerRegistry: replaced handler for {} "
                    + "(was {}, now {})", type, prior.getClass().getSimpleName(),
                    handler.getClass().getSimpleName());
        }
    }

    /** Returns the registered handler for {@code type}, falling back
     *  to {@link #GENERIC_CHORES} when {@code type} is null or has
     *  no registration. */
    public static HomesteadHandler getOrFallback(AdjunctPlotType type) {
        if (type == null) return GENERIC_CHORES;
        HomesteadHandler h = HANDLERS.get(type);
        return h != null ? h : GENERIC_CHORES;
    }
}
