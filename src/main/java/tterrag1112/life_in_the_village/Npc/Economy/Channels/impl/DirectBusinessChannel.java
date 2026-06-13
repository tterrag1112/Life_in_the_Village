package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionSupplyChain;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.Set;

/**
 * NPC-to-NPC trade at a producing workshop. Spec line 116. Critical for
 * market-less villages — without this channel they'd starve. The channel
 * walks every loaded NPC in the village, picks the closest profession-
 * matched producer who has stock of the target item, and quotes a price
 * adjusted by the buyer↔seller relationship.
 *
 * <p>Pricing formula: {@code dynamicSellPrice × (1 + relMod × 0.05)},
 * where {@code relMod} is in [-1, +1] derived from the seller's
 * NPC↔NPC ledger entry for the buyer (range −100..+100). Friends pay
 * up to 5% less; rivals up to 5% more. Spec line 222.</p>
 *
 * <p>Travel-time estimate: linear distance to the workshop divided by
 * walking speed (3 blocks/sec → ~5 ticks per block). Used by
 * {@link tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter}
 * to penalise far-away producers when a closer option exists.</p>
 */
public final class DirectBusinessChannel implements EconomicChannel {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Phase 6.3.4.3.1 — one-shot diagnostic flags per JVM session.
    // Each fires at most once across all NPCs/items so logs stay
    // readable but every rejection path surfaces at least once.
    private static volatile boolean LOGGED_NO_WORKSHOP_MAPPING = false;
    private static volatile boolean LOGGED_NO_PRODUCER         = false;
    private static volatile boolean LOGGED_CEILING_REJECT      = false;
    private static volatile boolean LOGGED_QUOTE_ACCEPTED      = false;
    // Diagnostic cause-of-miss counters from the most recent findProducer call,
    // surfaced in the one-shot no-producer WARN so "no producer" distinguishes
    // "empty producer storage" from "no producer NPC present".
    private static volatile int LAST_ELIGIBLE   = 0;
    private static volatile int LAST_WITH_STOCK = 0;
    private static volatile int LAST_WITH_NPC   = 0;

    @Override public ChannelType type() { return ChannelType.DIRECT_BUSINESS; }

    @Override public int basePriority() { return 70; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        // Always available — the cheap test is "does the village have
        // any NPC?"; the per-intent producer search runs in quote.
        return village != null && !village.getBuildingIds().isEmpty();
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        if (intent.direction() != TradeDirection.BUY) {
            // SELL via direct business is rare (the producer is the
            // seller, not the buyer); spec doesn't list it. v1 only
            // handles BUY here.
            return Optional.empty();
        }
        // Phase 6.4.6.1 — early exit when no profession claims the item
        // as an output. Replaces the workshopForItem null-check; same
        // semantic, single source of truth.
        Set<Profession> producingProfessions =
                ProfessionSupplyChain.findProducersOf(intent.item());
        if (producingProfessions.isEmpty()) {
            if (!LOGGED_NO_WORKSHOP_MAPPING) {
                LOGGER.warn("[DirectBusinessChannel] no producing profession for " +
                        "item={} (ProfessionSupplyChain.findProducersOf empty); " +
                        "channel declines.", intent.item());
                LOGGED_NO_WORKSHOP_MAPPING = true;
            }
            return Optional.empty();
        }
        ProducerMatch match = findProducer(intent, village, data, level);
        if (match == null) {
            if (!LOGGED_NO_PRODUCER) {
                LOGGER.warn("[DirectBusinessChannel] no producer found for item={} " +
                        "(eligible professions={}). Cause breakdown — eligible " +
                        "buildings={}, of those WITH stock={}, of those with a " +
                        "producer NPC={}. So: {}. Village={}.",
                        intent.item(), producingProfessions,
                        LAST_ELIGIBLE, LAST_WITH_STOCK, LAST_WITH_NPC,
                        LAST_ELIGIBLE == 0 ? "no building of an eligible type"
                          : LAST_WITH_STOCK == 0 ? "buildings exist but their "
                              + "storage holds none of this item (production/"
                              + "stocking gap, not a lookup bug)"
                          : "stock exists but no producer NPC was loaded near it",
                        village.getName());
                LOGGED_NO_PRODUCER = true;
            }
            return Optional.empty();
        }

        long base = MarketPriceHelper.getDynamicSellPrice(level, village, intent.item());
        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        double relMod = relationshipModifier(match.producer, buyer);
        // Phase 6.4.1.2.B — GENEROSITY on the seller side modulates
        // their asking price. Generous producer (GENEROSITY ≈ +1) →
        // 0.875 (12.5% discount); greedy/miserly (GENEROSITY ≈ -1) →
        // 1.125 (12.5% markup). Replaces dead code in
        // AppearanceComponent.getPriceModifier (GREEDY / GENEROUS /
        // FRIENDLY / SUSPICIOUS), continuous gradient version.
        // Stacks with the existing ±5% relationshipModifier.
        double sellerGenerosity = match.producer.getTraitVector()
                .get(tterrag1112.life_in_the_village.Npc.Traits.TraitAxis.GENEROSITY);
        // Phase 1d: ±generosity and ±relationship bands externalized
        // (defaults 0.125 and 0.05).
        var channelBalance = tterrag1112.life_in_the_village.Village.Economy.Currency
                .EconomyBalanceRegistry.balance().channels();
        double traitMod = 1.0 - (sellerGenerosity * channelBalance.directGenerosityBand());
        long raw = Math.round(base * traitMod
                * (1.0 + relMod * channelBalance.directRelationshipBand())
                * LawPriceHooks.sellMultiplier(village, ChannelType.DIRECT_BUSINESS, intent.item()));
        long floor = LawPriceHooks.priceFloor(village, ChannelType.DIRECT_BUSINESS, intent.item());
        long withFloor = floor > 0 ? Math.max(floor, raw) : raw;
        long policied = LawPriceHooks.priceCeiling(village, ChannelType.DIRECT_BUSINESS, intent.item())
                .map(cap -> Math.min(cap, withFloor))
                .orElse(withFloor);
        if (policied > intent.maxPrice()) {
            if (!LOGGED_CEILING_REJECT) {
                LOGGER.warn("[DirectBusinessChannel] CEILING REJECT item={} quote={}br/unit " +
                        "EXCEEDS intent.maxPrice={}br/unit (base={}br relMod={} producer={}). " +
                        "Buyer's perUnitCeiling is from getItemBuyPrice (raw base price); " +
                        "DirectBusinessChannel quotes at dynamic sell price + mods. " +
                        "Systemic mismatch — see 6.3.4.3 analysis.",
                        intent.item(), policied, intent.maxPrice(), base, relMod,
                        match.producer.getNpcName());
                LOGGED_CEILING_REJECT = true;
            }
            return Optional.empty();
        }

        int travelTicks = estimateTravelTicks(buyer, match.location);
        long validUntil = level.getGameTime() + 6000L; // 5 in-game minutes
        int qty = Math.min(intent.quantity(), match.availableQuantity);
        if (!LOGGED_QUOTE_ACCEPTED) {
            LOGGER.warn("[DirectBusinessChannel] QUOTE OK item={} {}br/unit qty={} " +
                    "producer={} workshop={}", intent.item(), policied, qty,
                    match.producer.getNpcName(), match.workshop.getName());
            LOGGED_QUOTE_ACCEPTED = true;
        }
        return Optional.of(new ChannelQuote(ChannelType.DIRECT_BUSINESS, intent,
                policied, qty, travelTicks, validUntil, match.location));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageById(intent.villageId()).orElse(null);
        if (village == null) return TradeResult.fail("village missing");
        if (intent.direction() != TradeDirection.BUY) return TradeResult.fail("DIRECT_BUSINESS sell not implemented");

        // Re-resolve producer at execute time — the quote may be stale.
        ProducerMatch match = findProducer(intent, village, data, level);
        if (match == null) return TradeResult.fail("producer no longer available");

        int qty = Math.min(quote.availableQuantity(), match.availableQuantity);
        qty = Math.min(qty, intent.quantity());
        if (qty <= 0) return TradeResult.fail("nothing to trade");

        // Take from producer's workshop chest.
        if (!BuildingStorageAccess.takeItem(level, match.workshop, intent.item(), qty)) {
            return TradeResult.fail("workshop chest empty");
        }
        long total = quote.pricePerUnit() * qty;
        CurrencyValue cost = CurrencyValue.of(total);

        // Move bronze: buyer → producer. Player buyers get charged via
        // the upstream UI path; NPC buyers spend their own wallet.
        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        if (buyer != null) {
            if (!buyer.getWallet().canAfford(cost)) {
                BuildingStorageAccess.storeItem(level, match.workshop,
                        new ItemStack(intent.item(), qty));
                return TradeResult.fail("buyer cannot pay");
            }
            buyer.getWallet().spend(cost);
        }
        match.producer.getWallet().receive(cost);

        // Spec "Open decisions" #3: fire NpcLifeEventBus.Trade so memory
        // / mood / relationship producers see the same surface as
        // MarketChannel trades.
        if (buyer != null) {
            NpcLifeEventBus.fire(new NpcLifeEvent.Trade(
                    match.producer, buyer.getUUID(), false,
                    new ItemStack(intent.item(), qty), total, false));
        }
        return TradeResult.ok(qty, total);
    }

    // ── Producer search ────────────────────────────────────────────────────

    private record ProducerMatch(TownspersonMob producer, Building workshop,
                                 BlockPos location, int availableQuantity) {}

    private static ProducerMatch findProducer(TradeIntent intent, Village village,
                                              VillageSavedData data, ServerLevel level) {
        Item item = intent.item();
        // Phase 6.4.6.1 — single source of truth via ProfessionSupplyChain.
        // Pre-6.4.6 used a hardcoded workshopForItem switch with ~70 item
        // path-strings; now the same lookup flows through the
        // declarative OUTPUTS map. Item → producing professions, then
        // walk village buildings filtering on Profession.professionFor.
        Set<Profession> producingProfessions = ProfessionSupplyChain.findProducersOf(item);
        if (producingProfessions.isEmpty()) return null;

        ProducerMatch best = null;
        int bestStock = 0;
        int eligibleBuildings = 0, withStock = 0, withNpc = 0;
        for (var bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            Profession buildingProf = Profession.professionFor(b.getType());
            if (!producingProfessions.contains(buildingProf)) continue;
            eligibleBuildings++;
            int stock = BuildingStorageAccess.countItem(level, b, item);
            if (stock <= 0) continue;
            withStock++;
            TownspersonMob producer = findProducerNpc(level, b, b.getType());
            if (producer == null) continue;
            withNpc++;
            if (stock > bestStock) {
                bestStock = stock;
                best = new ProducerMatch(producer, b, b.getShape().getOrigin(),
                        Math.min(intent.quantity(), stock));
            }
        }
        // Stash the cause counters for the one-shot WARN in quote().
        LAST_ELIGIBLE = eligibleBuildings;
        LAST_WITH_STOCK = withStock;
        LAST_WITH_NPC = withNpc;
        return best;
    }

    private static TownspersonMob findProducerNpc(ServerLevel level, Building b,
                                                  BuildingType type) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                b.getShape().toAABB().inflate(24),
                mob -> mob.getProfession() == Profession.professionFor(type)
                        || mob.getAssignedBuildingId().filter(id -> id.equals(b.getId())).isPresent()
        ).stream().findFirst().orElse(null);
    }

    // Phase 6.4.6.1 — workshopForItem switch removed. Item → producer
    // lookup now flows through ProfessionSupplyChain.findProducersOf
    // (see findProducer above). The OUTPUTS map in ProfessionSupplyChain
    // is the single source of truth; previously the switch + OUTPUTS
    // duplicated this knowledge with subtle drift (e.g. switch had
    // seeds → FARMHOUSE but FARMER's OUTPUTS list correctly excludes
    // them as they're inputs).
    //
    // Items the switch covered but ProfessionSupplyChain doesn't:
    //   - wheat_seeds / carrot_seeds / potato_seeds / beetroot_seeds:
    //     these are FARMER inputs, not outputs; switching means seed
    //     buyers route through MarketChannel / CaravanChannel instead.
    //     Correct semantic.
    //   - milk_bucket: FARMER cow roster not yet implemented; deferred.
    //   - oak_log / oak_planks / stick: CARPENTER's inputs vs outputs
    //     are split correctly in ProfessionSupplyChain.

    // ── Relationship / travel ──────────────────────────────────────────────

    private static double relationshipModifier(TownspersonMob seller, TownspersonMob buyer) {
        if (seller == null || buyer == null) return 0.0;
        int score = seller.getNpcRelationships().getScore(buyer.getUUID());
        return Math.max(-1.0, Math.min(1.0, score / 100.0));
    }

    private static int estimateTravelTicks(TownspersonMob buyer, BlockPos to) {
        if (buyer == null || to == null) return 100;
        double dx = buyer.getX() - to.getX();
        double dz = buyer.getZ() - to.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        // ~3 blocks/s → 5 ticks per block
        return (int) Math.min(2400, Math.round(dist * 5));
    }
}
