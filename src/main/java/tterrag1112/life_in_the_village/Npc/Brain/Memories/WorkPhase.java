package tterrag1112.life_in_the_village.Npc.Brain.Memories;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum WorkPhase implements StringRepresentable {
    FETCH("fetch"),
    PROCESS("process"),
    DEPOSIT("deposit"),
    SELL("sell"),
    IDLE("idle");

    public static final Codec<WorkPhase> CODEC = StringRepresentable.fromEnum(WorkPhase::values);

    private final String name;

    WorkPhase(String name) { this.name = name; }

    @Override
    public String getSerializedName() { return name; }
}
