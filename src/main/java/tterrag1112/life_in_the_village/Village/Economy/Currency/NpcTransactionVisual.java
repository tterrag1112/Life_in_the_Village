package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;

/**
 * Produces the visual and audio feedback when an NPC sends or receives money.
 *
 * <h3>What it does</h3>
 * - Payer briefly holds a PurseItem in their main hand
 * - Both payer and receiver swing their arms
 * - A coin-clink sound plays at the transaction location
 *
 * <h3>What it does NOT do</h3>
 * - Move any actual coins (the NpcWallet handles that)
 * - Block or delay the transaction
 *
 * Call this immediately after transferring wallet values.
 */
public final class NpcTransactionVisual {

    private NpcTransactionVisual() {}

    private static final int HOLD_TICKS = 20; // purse held for 1 second

    /**
     * Play the full payment visual: payer holds purse, both swing arms,
     * coin sound plays between them.
     */
    public static void showPayment(TownspersonMob payer,
                                   TownspersonMob receiver,
                                   ServerLevel level) {
        showPurseHold(payer);
        receiver.swing(InteractionHand.MAIN_HAND);
        playCoinSound(level, payer);
    }

    /**
     * Minimal visual for when only the payer is known (e.g. paying into
     * a building treasury with no NPC present).
     */
    public static void showPayment(TownspersonMob payer, ServerLevel level) {
        showPurseHold(payer);
        playCoinSound(level, payer);
    }

    /**
     * Visual for a receiver getting paid with no payer present (e.g.
     * collecting wages from a chest or treasury).
     */
    public static void showReceive(TownspersonMob receiver, ServerLevel level) {
        receiver.swing(InteractionHand.MAIN_HAND);
        playCoinSound(level, receiver);
    }

    // ── Internal ──────────────────────────────────────────────────────

    private static void showPurseHold(TownspersonMob npc) {
        // Hold the purse item (if registered), otherwise a gold ingot as proxy
        ItemStack purseStack;
        try {
            purseStack = new ItemStack(ModItems.PURSE.get());
        } catch (Exception e) {
            purseStack = new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT);
        }
        npc.setItemInHand(InteractionHand.MAIN_HAND, purseStack);
        npc.swing(InteractionHand.MAIN_HAND);

        // Schedule clearing the held item after HOLD_TICKS
        // We store a countdown in the goal's tick instead of scheduling,
        // so goals that call this should clear the hand after ~20 ticks.
        // As a safety net, the item will be cleared by the next goIdle().
    }

    private static void playCoinSound(ServerLevel level,
                                      TownspersonMob source) {
        level.playSound(null,
                source.blockPosition(),
                SoundEvents.METAL_HIT,
                SoundSource.NEUTRAL,
                0.4f,
                1.2f + source.getRandom().nextFloat() * 0.3f);
    }
}