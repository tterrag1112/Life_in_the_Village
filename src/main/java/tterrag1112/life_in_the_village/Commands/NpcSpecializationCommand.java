package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationComponent;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.7.1 — admin command surface for setting / locking NPC
 * specializations.
 *
 * <ul>
 *   <li>{@code /liv npc specialization set <target> <specId> [--locked]}
 *       — set the spec via force=true (bypasses skill gates). When
 *       {@code --locked} is appended, the lock flag is also set so
 *       subsequent non-forced reassignments are refused.</li>
 *   <li>{@code /liv npc lockspec <target> <true|false>} — pure lock
 *       toggle. Doesn't touch the current spec; useful for unlocking
 *       a previously-pinned NPC so they can drift / be reassigned by
 *       SpecializationManager.trySetSpecialization.</li>
 * </ul>
 *
 * <p>Target resolution mirrors {@code NpcInfoCommand}: nearest /
 * UUID prefix / full UUID. Spec ids are resolved via
 * {@link NpcSpecializationTypes#byId(Identifier)}.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class NpcSpecializationCommand {

    private static final double NEAREST_RADIUS = 64.0;

    private NpcSpecializationCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("npc")
                                .then(Commands.literal("specialization")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("target", StringArgumentType.string())
                                                        .then(Commands.argument("specId", StringArgumentType.string())
                                                                .executes(ctx -> setSpec(ctx, false))
                                                                .then(Commands.literal("--locked")
                                                                        .executes(ctx -> setSpec(ctx, true)))))))
                                .then(Commands.literal("lockspec")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .then(Commands.argument("locked", BoolArgumentType.bool())
                                                        .executes(NpcSpecializationCommand::setLock)))))
        );
    }

    private static int setSpec(CommandContext<CommandSourceStack> ctx, boolean lockAfter) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv npc specialization must be run on a server level."));
            return 0;
        }
        String targetArg = StringArgumentType.getString(ctx, "target");
        String specArg   = StringArgumentType.getString(ctx, "specId");
        TownspersonMob npc = resolveNpc(ctx, level, targetArg);
        if (npc == null) return 0;

        Identifier specId;
        try {
            specId = parseSpecId(specArg);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Invalid specialization id: '" + specArg + "'"));
            return 0;
        }
        SpecializationDef def = NpcSpecializationTypes.byId(specId).orElse(null);
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown specialization id: " + specId));
            return 0;
        }

        NpcSpecializationComponent comp = npc.getSpecializationComponent();
        // Force=true bypasses the skill gate (admin intent) but
        // a pre-existing lock still blocks reassignment unless we
        // also force past the lock. This is intentional: admin must
        // explicitly clear the lock before swapping the spec, OR
        // pass --locked on the new spec to overwrite cleanly.
        boolean ok = comp.assign(def, npc, true);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to assign specialization (component refused)."));
            return 0;
        }
        if (lockAfter) comp.setLocked(true);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set " + npc.getNpcName() + " specialization to " + specId
                        + (lockAfter ? " [LOCKED]" : "")), false);
        return 1;
    }

    private static int setLock(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv npc lockspec must be run on a server level."));
            return 0;
        }
        String targetArg = StringArgumentType.getString(ctx, "target");
        boolean locked   = BoolArgumentType.getBool(ctx, "locked");
        TownspersonMob npc = resolveNpc(ctx, level, targetArg);
        if (npc == null) return 0;

        npc.getSpecializationComponent().setLocked(locked);
        ctx.getSource().sendSuccess(() -> Component.literal(
                npc.getNpcName() + " specialization lock = " + locked), false);
        return 1;
    }

    private static Identifier parseSpecId(String raw) {
        if (raw.contains(":")) {
            return Identifier.parse(raw);
        }
        return Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, raw);
    }

    // ── NPC resolution (mirrors NpcInfoCommand.resolveNpc) ─────────────────

    private static TownspersonMob resolveNpc(CommandContext<CommandSourceStack> ctx,
                                             ServerLevel level, String arg) {
        if ("nearest".equalsIgnoreCase(arg)) {
            var pos = ctx.getSource().getPosition();
            net.minecraft.world.phys.AABB area =
                    new net.minecraft.world.phys.AABB(pos, pos).inflate(NEAREST_RADIUS);
            List<TownspersonMob> hits = level.getEntitiesOfClass(
                    TownspersonMob.class, area, npc -> npc.isAlive());
            if (hits.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal(
                        "No TownspersonMob within " + (int) NEAREST_RADIUS + " blocks."));
                return null;
            }
            TownspersonMob closest = hits.get(0);
            double bestSq = closest.distanceToSqr(pos);
            for (TownspersonMob h : hits) {
                double sq = h.distanceToSqr(pos);
                if (sq < bestSq) { bestSq = sq; closest = h; }
            }
            return closest;
        }
        try {
            UUID id = UUID.fromString(arg);
            var entity = level.getEntity(id);
            if (!(entity instanceof TownspersonMob t)) {
                ctx.getSource().sendFailure(Component.literal(
                        "UUID " + id + " is not a loaded TownspersonMob in this level."));
                return null;
            }
            return t;
        } catch (IllegalArgumentException ignore) { /* fall through to prefix */ }
        var pos = ctx.getSource().getPosition();
        net.minecraft.world.phys.AABB area =
                new net.minecraft.world.phys.AABB(pos, pos).inflate(1024.0);
        List<TownspersonMob> all = level.getEntitiesOfClass(
                TownspersonMob.class, area,
                npc -> npc.getUUID().toString().startsWith(arg));
        if (all.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No NPC UUID matches '" + arg + "' within 1024 blocks of source."));
            return null;
        }
        if (all.size() > 1) {
            StringBuilder m = new StringBuilder("Ambiguous prefix '" + arg + "' — matches:");
            for (TownspersonMob n : all) m.append("\n  ").append(n.getUUID());
            ctx.getSource().sendFailure(Component.literal(m.toString()));
            return null;
        }
        return all.get(0);
    }
}
