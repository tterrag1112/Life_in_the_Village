package tterrag1112.life_in_the_village.Npc.Visitor;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Locale;

/**
 * Phase 4 doc 29 — visitor archetype. Ephemeral NPCs that arrive at
 * a village edge, walk an {@link Activity}-driven itinerary, spend
 * coin into local treasuries, and leave.
 *
 * <p>Per spec line 334 the existing {@link Profession#WANDERING_TRADER}
 * is the same conceptual entity as {@link #MERCHANT_ITINERANT}; the
 * mapping lets the visitor flux engine pick the wandering-trader
 * profession when it spawns one of these so the existing trader
 * goal + dialogue + GUI flow continues to work.</p>
 */
public enum VisitorType {
    PILGRIM,
    MERCHANT_ITINERANT,
    TRAVELER,
    STUDENT,
    ENVOY,
    REFUGEE,
    SCHOLAR_VISITING,
    MINSTREL;

    /** Spec line 168 — wallet bronze range carried by an arriving
     *  visitor. Caller picks a value in [min, max] inclusive. */
    public int walletMin() {
        return switch (this) {
            case REFUGEE       -> 0;
            case STUDENT       -> 5;
            case TRAVELER      -> 10;
            case PILGRIM       -> 15;
            case SCHOLAR_VISITING, MINSTREL -> 15;
            case ENVOY         -> 20;
            case MERCHANT_ITINERANT -> 25;
        };
    }

    public int walletMax() {
        return switch (this) {
            case REFUGEE       -> 5;
            case STUDENT       -> 15;
            case TRAVELER      -> 25;
            case PILGRIM       -> 30;
            case SCHOLAR_VISITING -> 35;
            case MINSTREL      -> 35;
            case ENVOY         -> 50;
            case MERCHANT_ITINERANT -> 50;
        };
    }

    /** When spawning a visitor, which underlying profession should the
     *  TownspersonMob carry so existing AI / dialogue / GUI all behave
     *  reasonably? MERCHANT_ITINERANT inherits the existing
     *  wandering-trader path (spec "Things to flag" #4). */
    public Profession underlyingProfession() {
        return switch (this) {
            case MERCHANT_ITINERANT -> Profession.WANDERING_TRADER;
            case SCHOLAR_VISITING   -> Profession.SCHOLAR;
            case STUDENT            -> Profession.SCHOLAR;
            case ENVOY              -> Profession.HERALD;
            case PILGRIM            -> Profession.PRIEST;
            case MINSTREL           -> Profession.CITIZEN;
            case REFUGEE, TRAVELER  -> Profession.CITIZEN;
        };
    }

    /** Whether this visitor type carries goods that a buyer-side
     *  intent could quote against via the channel router. Spec line
     *  279 — only itinerant merchants and visiting scholars (book
     *  exchange) carry sale goods in v1. */
    public boolean carriesGoods() {
        return this == MERCHANT_ITINERANT || this == SCHOLAR_VISITING;
    }

    public static final Codec<VisitorType> CODEC = Codec.STRING.xmap(
            s -> VisitorType.valueOf(s.toUpperCase(Locale.ROOT)),
            VisitorType::name);
}
