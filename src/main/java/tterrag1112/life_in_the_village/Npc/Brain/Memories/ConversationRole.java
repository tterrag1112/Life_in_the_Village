package tterrag1112.life_in_the_village.Npc.Brain.Memories;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum ConversationRole implements StringRepresentable {
    SPEAKER("speaker"),
    LISTENER("listener");

    public static final Codec<ConversationRole> CODEC =
            StringRepresentable.fromEnum(ConversationRole::values);

    private final String name;

    ConversationRole(String name) { this.name = name; }

    @Override
    public String getSerializedName() { return name; }
}
