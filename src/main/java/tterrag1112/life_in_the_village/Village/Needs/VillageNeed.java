package tterrag1112.life_in_the_village.Village.Needs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

public class VillageNeed {

    public static final Codec<VillageNeed> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(NeedCategory::valueOf, NeedCategory::name)
                            .fieldOf("category").forGetter(VillageNeed::getCategory),
                    Codec.STRING.xmap(NeedLevel::valueOf, NeedLevel::name)
                            .fieldOf("level").forGetter(VillageNeed::getLevel),
                    Codec.INT.fieldOf("available").forGetter(VillageNeed::getAvailable),
                    Codec.INT.fieldOf("required").forGetter(VillageNeed::getRequired),
                    Codec.unboundedMap(
                            BuiltInRegistries.ITEM.byNameCodec(),
                            Codec.INT
                    ).fieldOf("itemBreakdown").forGetter(VillageNeed::getItemBreakdown)
            ).apply(instance, VillageNeed::new)
    );

    private final NeedCategory category;
    private NeedLevel level;
    private int available;
    private int required;
    // Per-item breakdown so farmer knows exactly what's needed
    private final Map<Item, Integer> itemBreakdown;

    public VillageNeed(NeedCategory category, NeedLevel level,
                       int available, int required, Map<Item, Integer> itemBreakdown) {
        this.category = category;
        this.level = level;
        this.available = available;
        this.required = required;
        this.itemBreakdown = new HashMap<>(itemBreakdown);
    }

    public NeedCategory getCategory()              { return category; }
    public NeedLevel getLevel()                    { return level; }
    public int getAvailable()                      { return available; }
    public int getRequired()                       { return required; }
    public Map<Item, Integer> getItemBreakdown()   { return itemBreakdown; }

    public void setLevel(NeedLevel level)          { this.level = level; }
    public void setAvailable(int available)        { this.available = available; }
    public void setRequired(int required)          { this.required = required; }

    public double getRatio() {
        if (required == 0) return 2.0; // no requirement = surplus
        return (double) available / required;
    }

    // How much more is needed to reach SATISFIED
    public int getDeficit() {
        return Math.max(0, required - available);
    }

    // How much of a specific item is still needed
    public int getDeficitFor(Item item) {
        return itemBreakdown.getOrDefault(item, 0);
    }
}
