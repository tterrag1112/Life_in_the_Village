package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.UUID;

/**
 * The desired OUTCOME of a {@link Task} — pure data, no behavior. A
 * scorer picks a {@link Fulfillment} strategy capable of producing the
 * outcome; the objective itself never executes anything.
 *
 * <p>These six variants are the seeds; the set grows per migration.
 * {@link PerformService} is the open extension point for non-production
 * work (services, ceremonies, patrols, ...) that don't reduce to an
 * item flow.</p>
 *
 * <p>Persistence uses a tag-dispatched codec ({@link #CODEC}): each
 * variant declares a {@link Type} carrying its {@link MapCodec}, and the
 * dispatch keys on the type name. New variants register by adding a
 * {@link Type} value — there is no exhaustive {@code switch} over
 * {@code Objective} in the core to keep in sync.</p>
 */
public sealed interface Objective
        permits Objective.ProvideItem, Objective.MaintainStock, Objective.Acquire,
                Objective.Deliver, Objective.Staff, Objective.PerformService {

    /** The dispatch tag for this variant. */
    Type type();

    /** Produce {@code qty} of {@code item} (into the issuer's storage). */
    record ProvideItem(Item item, int qty) implements Objective {
        public static final MapCodec<ProvideItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ProvideItem::item),
                Codec.INT.fieldOf("qty").forGetter(ProvideItem::qty)
        ).apply(i, ProvideItem::new));

        @Override public Type type() { return Type.PROVIDE_ITEM; }
    }

    /** Keep on-hand {@code item} at {@code target} (refill once it drops by {@code buffer}). */
    record MaintainStock(Item item, int target, int buffer) implements Objective {
        public static final MapCodec<MaintainStock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(MaintainStock::item),
                Codec.INT.fieldOf("target").forGetter(MaintainStock::target),
                Codec.INT.fieldOf("buffer").forGetter(MaintainStock::buffer)
        ).apply(i, MaintainStock::new));

        @Override public Type type() { return Type.MAINTAIN_STOCK; }
    }

    /** Obtain {@code qty} of {@code item} from elsewhere (buy / gather / requisition). */
    record Acquire(Item item, int qty) implements Objective {
        public static final MapCodec<Acquire> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Acquire::item),
                Codec.INT.fieldOf("qty").forGetter(Acquire::qty)
        ).apply(i, Acquire::new));

        @Override public Type type() { return Type.ACQUIRE; }
    }

    /** Carry {@code qty} of {@code item} to {@code destination}. */
    record Deliver(Item item, int qty, GlobalPos destination) implements Objective {
        public static final MapCodec<Deliver> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Deliver::item),
                Codec.INT.fieldOf("qty").forGetter(Deliver::qty),
                GlobalPos.CODEC.fieldOf("destination").forGetter(Deliver::destination)
        ).apply(i, Deliver::new));

        @Override public Type type() { return Type.DELIVER; }
    }

    /** Fill the {@code roleId} post at {@code buildingId}. */
    record Staff(String roleId, UUID buildingId) implements Objective {
        public static final MapCodec<Staff> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("roleId").forGetter(Staff::roleId),
                UUIDUtil.CODEC.fieldOf("buildingId").forGetter(Staff::buildingId)
        ).apply(i, Staff::new));

        @Override public Type type() { return Type.STAFF; }
    }

    /** Perform a non-production service identified by {@code kind} (open extension point). */
    record PerformService(String kind) implements Objective {
        public static final MapCodec<PerformService> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("kind").forGetter(PerformService::kind)
        ).apply(i, PerformService::new));

        @Override public Type type() { return Type.PERFORM_SERVICE; }
    }

    /**
     * Dispatch tag, one per variant. Adding a variant adds a value here
     * (and to {@link #permits}); both the codec and
     * {@link FulfillmentRegistry} key on this type so neither needs an
     * exhaustive switch.
     */
    enum Type {
        PROVIDE_ITEM(ProvideItem.CODEC),
        MAINTAIN_STOCK(MaintainStock.CODEC),
        ACQUIRE(Acquire.CODEC),
        DELIVER(Deliver.CODEC),
        STAFF(Staff.CODEC),
        PERFORM_SERVICE(PerformService.CODEC);

        private final MapCodec<? extends Objective> codec;

        Type(MapCodec<? extends Objective> codec) {
            this.codec = codec;
        }

        public MapCodec<? extends Objective> codec() {
            return codec;
        }
    }

    /** Tag-dispatched codec over all variants, keyed by {@link Type} name. */
    Codec<Objective> CODEC = Codec.STRING.dispatch(
            "objective",
            o -> o.type().name(),
            name -> Type.valueOf(name).codec()
    );
}
