package tterrag1112.life_in_the_village.Village.Economy.Currency;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.io.InputStreamReader;

/**
 * Datapack reload-listener for the {@link EconomyBalance} tuning config
 * (economy Phase 1d). Mirrors {@code MarketPriceRegistry}: a singleton
 * {@link #INSTANCE}, a {@code prepare}/{@code apply} pair, and a
 * {@link #loadFromServer} hook for the {@code ServerStarting} early load.
 * Registered as a server reload listener so {@code /reload} re-reads it.
 *
 * <p>Loads {@code data/<modid>/economy_balance/*.json}. The current value
 * is seeded to {@link EconomyBalance#DEFAULTS} so any read before the
 * first load (or with no file present) returns behaviour-preserving
 * defaults — never null, never an NPE.</p>
 */
public class EconomyBalanceRegistry
        extends SimplePreparableReloadListener<EconomyBalance> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EconomyBalanceRegistry.class);
    private static final Gson GSON = new Gson();

    public static final EconomyBalanceRegistry INSTANCE = new EconomyBalanceRegistry();

    private volatile EconomyBalance current = EconomyBalance.DEFAULTS;

    private EconomyBalanceRegistry() {}

    @Override
    protected EconomyBalance prepare(ResourceManager manager, ProfilerFiller profiler) {
        EconomyBalance result = EconomyBalance.DEFAULTS;

        var resources = manager.listResources("economy_balance",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".json"));

        for (var entry : resources.entrySet()) {
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                // Per-field optionalFieldOf defaults mean a partial config
                // parses fine; a structurally-malformed one logs and keeps
                // the last good value (defaults on first pass).
                DataResult<EconomyBalance> parsed =
                        EconomyBalance.CODEC.parse(JsonOps.INSTANCE, json);
                var loaded = parsed.resultOrPartial(err -> LOGGER.error(
                        "Failed to parse economy balance {}: {} — keeping defaults",
                        entry.getKey(), err));
                if (loaded.isPresent()) {
                    // Last file wins; a single economy_balance.json is expected.
                    result = loaded.get();
                    LOGGER.info("Loaded economy balance from {}", entry.getKey());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read economy balance {}: {} — keeping defaults",
                        entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    @Override
    protected void apply(EconomyBalance prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.current = prepared == null ? EconomyBalance.DEFAULTS : prepared;
    }

    /** Early load on ServerStarting, mirroring MarketPriceRegistry. */
    public void loadFromServer(ResourceManager manager) {
        apply(prepare(manager, null), manager, null);
    }

    /** Current balance — defaults until/unless a config is loaded. */
    public EconomyBalance get() {
        return current;
    }

    /** Convenience accessor for call sites. */
    public static EconomyBalance balance() {
        return INSTANCE.current;
    }
}
