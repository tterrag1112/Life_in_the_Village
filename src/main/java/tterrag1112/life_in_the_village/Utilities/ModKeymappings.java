package tterrag1112.life_in_the_village.Utilities;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.apache.logging.log4j.util.Lazy;
import org.lwjgl.glfw.GLFW;

public class ModKeymappings {
    private static final KeyMapping KEY_MAPPING_V = new KeyMapping("key.liv.v", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC);
    private static final KeyMapping KEY_MAPPING_C = new KeyMapping("key.liv.c", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KeyMapping.Category.MISC);
    private static final KeyMapping KEY_MAPPING_X = new KeyMapping("key.liv.x", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, KeyMapping.Category.MISC);


    public static final Lazy<KeyMapping> PRESS_V = Lazy.lazy(() -> KEY_MAPPING_V);
    public static final Lazy<KeyMapping> PRESS_C = Lazy.lazy(() -> KEY_MAPPING_C);
    public static final Lazy<KeyMapping> PRESS_X = Lazy.lazy(() -> KEY_MAPPING_X);



}
