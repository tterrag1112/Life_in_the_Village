package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

/**
 * One side of a money settlement — a payer or a receiver (economy
 * Phase 1b). Lets the single {@link NpcEconomy#settlePurchase} /
 * {@link NpcEconomy#settleSale} functions debit/credit any combination of
 * party types so the player trade path and the NPC channel path move coins
 * through one place.
 *
 * <p>Transient and lookup-only — a party lives for the duration of one
 * settle call and is never persisted, so it carries no codec.</p>
 *
 * <ul>
 *   <li>{@link Player} — physical coins in a player inventory, moved via
 *       {@link CoinHelper#playerPay} / {@link CoinHelper#playerReceive}.</li>
 *   <li>{@link Npc} — a virtual {@code NpcWallet}.</li>
 *   <li>{@link StallChest} — a player-owned stall's chest; credited by
 *       depositing physical coins into the chest. Receive-only (a stall
 *       chest never pays).</li>
 *   <li>{@link None} — no coin source/sink: a debit mints nothing and
 *       always "succeeds"; a credit burns nothing. Used for the
 *       spend-into-void merchant-less case and the mint-on-sale NPC path,
 *       both of which preserve pre-1b behaviour.</li>
 * </ul>
 */
public sealed interface SettlementParty {

    /** A player paying/receiving physical coins. */
    record Player(ServerPlayer player) implements SettlementParty {}

    /** An NPC paying/receiving from their virtual wallet. */
    record Npc(TownspersonMob npc) implements SettlementParty {}

    /** A player-owned stall chest receiving coins (receive-only). */
    record StallChest(MarketStall stall) implements SettlementParty {}

    /** No real coin source/sink (mint source / burn sink). */
    record None() implements SettlementParty {}

    // ── Factories ────────────────────────────────────────────────────────

    static SettlementParty player(ServerPlayer player) { return new Player(player); }
    static SettlementParty npc(TownspersonMob npc)      { return new Npc(npc); }
    static SettlementParty stallChest(MarketStall s)    { return new StallChest(s); }
    static SettlementParty none()                       { return new None(); }
}
