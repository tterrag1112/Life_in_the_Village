package tterrag1112.life_in_the_village.Village.Economy.Currency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Data-driven economy balance knobs (economy Phase 1d). Mirrors the
 * datapack-loaded shape of {@code MarketPriceData}: an immutable holder
 * with a {@link #CODEC} and behaviour-preserving {@link #DEFAULTS}.
 *
 * <p>Grouped into nested typed sub-records (treasury / pricing / markups /
 * channels) so no single codec approaches the 16-field ceiling and the
 * JSON reads sensibly. <b>Every field is {@code optionalFieldOf} with its
 * default = today's hardcoded value</b>, so a missing file, a missing
 * section, or a missing single field all fall back to current behaviour —
 * an absent config reproduces the pre-1d game exactly.</p>
 *
 * <p>Loaded by {@link EconomyBalanceRegistry}; read live at each (per-trade
 * / per-tick-event, never per-tick-hot-loop) call site so {@code /reload}
 * takes effect without a restart.</p>
 */
public record EconomyBalance(Treasury treasury,
                             Pricing pricing,
                             Markups markups,
                             Channels channels) {

    // ── Treasury: tax / wages / baseline income ───────────────────────────
    public record Treasury(long marketTaxDivisor,
                           long propertyTaxPerHouse,
                           long baselineIncomePerNpc,
                           long guardWage,
                           long keeperWage,
                           long innkeeperWage,
                           long merchantWage) {
        public static final Treasury DEFAULT =
                new Treasury(10L, 2L, 1L, 8L, 5L, 4L, 6L);
        public static final Codec<Treasury> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.optionalFieldOf("market_tax_divisor", DEFAULT.marketTaxDivisor()).forGetter(Treasury::marketTaxDivisor),
                Codec.LONG.optionalFieldOf("property_tax_per_house", DEFAULT.propertyTaxPerHouse()).forGetter(Treasury::propertyTaxPerHouse),
                Codec.LONG.optionalFieldOf("baseline_income_per_npc", DEFAULT.baselineIncomePerNpc()).forGetter(Treasury::baselineIncomePerNpc),
                Codec.LONG.optionalFieldOf("guard_wage", DEFAULT.guardWage()).forGetter(Treasury::guardWage),
                Codec.LONG.optionalFieldOf("keeper_wage", DEFAULT.keeperWage()).forGetter(Treasury::keeperWage),
                Codec.LONG.optionalFieldOf("innkeeper_wage", DEFAULT.innkeeperWage()).forGetter(Treasury::innkeeperWage),
                Codec.LONG.optionalFieldOf("merchant_wage", DEFAULT.merchantWage()).forGetter(Treasury::merchantWage)
        ).apply(i, Treasury::new));
    }

    // ── Pricing: dynamic-multiplier headline knobs ────────────────────────
    public record Pricing(double minMultiplier,
                          double maxMultiplier,
                          double buyMargin,
                          double perVillageStrength) {
        public static final Pricing DEFAULT =
                new Pricing(0.5, 3.0, 0.6, 0.4);
        public static final Codec<Pricing> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("min_multiplier", DEFAULT.minMultiplier()).forGetter(Pricing::minMultiplier),
                Codec.DOUBLE.optionalFieldOf("max_multiplier", DEFAULT.maxMultiplier()).forGetter(Pricing::maxMultiplier),
                Codec.DOUBLE.optionalFieldOf("buy_margin", DEFAULT.buyMargin()).forGetter(Pricing::buyMargin),
                Codec.DOUBLE.optionalFieldOf("per_village_strength", DEFAULT.perVillageStrength()).forGetter(Pricing::perVillageStrength)
        ).apply(i, Pricing::new));
    }

    // ── Markups: VillageEconomy listing markups by seller role ────────────
    public record Markups(double producer,
                          double merchant,
                          double stockpile,
                          double company) {
        public static final Markups DEFAULT =
                new Markups(0.85, 1.20, 1.00, 1.10);
        public static final Codec<Markups> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("producer", DEFAULT.producer()).forGetter(Markups::producer),
                Codec.DOUBLE.optionalFieldOf("merchant", DEFAULT.merchant()).forGetter(Markups::merchant),
                Codec.DOUBLE.optionalFieldOf("stockpile", DEFAULT.stockpile()).forGetter(Markups::stockpile),
                Codec.DOUBLE.optionalFieldOf("company", DEFAULT.company()).forGetter(Markups::company)
        ).apply(i, Markups::new));
    }

    // ── Channels: per-channel pricing knobs (basePriority stays in code) ──
    public record Channels(double caravanDiscount,
                           double directRelationshipBand,
                           double directGenerosityBand) {
        public static final Channels DEFAULT =
                new Channels(0.90, 0.05, 0.125);
        public static final Codec<Channels> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("caravan_discount", DEFAULT.caravanDiscount()).forGetter(Channels::caravanDiscount),
                Codec.DOUBLE.optionalFieldOf("direct_relationship_band", DEFAULT.directRelationshipBand()).forGetter(Channels::directRelationshipBand),
                Codec.DOUBLE.optionalFieldOf("direct_generosity_band", DEFAULT.directGenerosityBand()).forGetter(Channels::directGenerosityBand)
        ).apply(i, Channels::new));
    }

    /** Behaviour-preserving defaults = the pre-1d hardcoded values. */
    public static final EconomyBalance DEFAULTS = new EconomyBalance(
            Treasury.DEFAULT, Pricing.DEFAULT, Markups.DEFAULT, Channels.DEFAULT);

    public static final Codec<EconomyBalance> CODEC = RecordCodecBuilder.create(i -> i.group(
            Treasury.CODEC.optionalFieldOf("treasury", Treasury.DEFAULT).forGetter(EconomyBalance::treasury),
            Pricing.CODEC.optionalFieldOf("pricing", Pricing.DEFAULT).forGetter(EconomyBalance::pricing),
            Markups.CODEC.optionalFieldOf("markups", Markups.DEFAULT).forGetter(EconomyBalance::markups),
            Channels.CODEC.optionalFieldOf("channels", Channels.DEFAULT).forGetter(EconomyBalance::channels)
    ).apply(i, EconomyBalance::new));
}
