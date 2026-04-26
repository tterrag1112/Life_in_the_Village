package tterrag1112.life_in_the_village.Npc.Laws;

import tterrag1112.life_in_the_village.Npc.Laws.effects.BanExecutionEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.CompulsorySchoolingEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.CurfewEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.DoublePunishmentEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.FestivalMandatoryEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.ForeignTraderBanEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.GuildMembershipRequiredEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.MarketTaxDoubleEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.MarketTaxReducedEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PardonFirstOffenseEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PilgrimWelcomeBonusEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PriceCeilingFoodEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PriceFloorFoodEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.ProfitsToTreasuryEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PropertyTaxDoubleEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.PropertyTaxWaivedEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.SubsidizeBlacksmithEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.SubsidizeFarmerEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.SubsidizeHealerEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.SubsidizeScholarEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.TempleAttendanceExpectedEffect;
import tterrag1112.life_in_the_village.Npc.Laws.effects.TollEntryEffect;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static {@link VillageLaw} → {@link LawEffect} lookup. Spec line 95:
 * "Each law implements LawEffect as a small class registered at mod
 * init."
 *
 * <p>v1 ships an effect for every law in the enum. Where the spec
 * defers behaviour to a Phase 4 subsystem (visitor flux, child schooling)
 * the effect's hook methods document the deferral and return a no-op
 * default — the registry stays complete so future sessions only have to
 * fill the body.</p>
 */
public final class LawEffectRegistry {

    private static final Map<VillageLaw, LawEffect> EFFECTS = build();

    private LawEffectRegistry() {}

    private static Map<VillageLaw, LawEffect> build() {
        EnumMap<VillageLaw, LawEffect> m = new EnumMap<>(VillageLaw.class);
        // Economy
        m.put(VillageLaw.TOLL_ENTRY,                 new TollEntryEffect());
        m.put(VillageLaw.MARKET_TAX_DOUBLE,          new MarketTaxDoubleEffect());
        m.put(VillageLaw.MARKET_TAX_REDUCED,         new MarketTaxReducedEffect());
        m.put(VillageLaw.SUBSIDIZE_FARMER,           new SubsidizeFarmerEffect());
        m.put(VillageLaw.SUBSIDIZE_BLACKSMITH,       new SubsidizeBlacksmithEffect());
        m.put(VillageLaw.SUBSIDIZE_SCHOLAR,          new SubsidizeScholarEffect());
        m.put(VillageLaw.SUBSIDIZE_HEALER,           new SubsidizeHealerEffect());
        m.put(VillageLaw.PROFITS_TO_TREASURY,        new ProfitsToTreasuryEffect());
        m.put(VillageLaw.PROPERTY_TAX_DOUBLE,        new PropertyTaxDoubleEffect());
        m.put(VillageLaw.PROPERTY_TAX_WAIVED,        new PropertyTaxWaivedEffect());
        // Crime
        m.put(VillageLaw.CURFEW,                     new CurfewEffect());
        m.put(VillageLaw.DOUBLE_PUNISHMENT,          new DoublePunishmentEffect());
        m.put(VillageLaw.PARDON_FIRST_OFFENSE,       new PardonFirstOffenseEffect());
        m.put(VillageLaw.BAN_EXECUTION,              new BanExecutionEffect());
        // Social
        m.put(VillageLaw.FOREIGN_TRADER_BAN,         new ForeignTraderBanEffect());
        m.put(VillageLaw.PILGRIM_WELCOME_BONUS,      new PilgrimWelcomeBonusEffect());
        m.put(VillageLaw.COMPULSORY_SCHOOLING,       new CompulsorySchoolingEffect());
        m.put(VillageLaw.TEMPLE_ATTENDANCE_EXPECTED, new TempleAttendanceExpectedEffect());
        m.put(VillageLaw.FESTIVAL_MANDATORY,         new FestivalMandatoryEffect());
        // Economic restrictions
        m.put(VillageLaw.GUILD_MEMBERSHIP_REQUIRED,  new GuildMembershipRequiredEffect());
        m.put(VillageLaw.PRICE_CEILING_FOOD,         new PriceCeilingFoodEffect());
        m.put(VillageLaw.PRICE_FLOOR_FOOD,           new PriceFloorFoodEffect());
        return m;
    }

    public static LawEffect get(VillageLaw law) {
        if (law == null) return null;
        return EFFECTS.get(law);
    }
}
