package eclipse.euphoriacompanion.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public class ClientEventHandler {

    public static KeyBinding analyzeKey;

    public static void registerKeyBinding() {
        analyzeKey = new KeyBinding(
                "key.euphoriacompanion.analyze",
                Keyboard.KEY_F6,
                "key.categories.euphoriacompanion"
        );

        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(analyzeKey);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        
        if (mc.thePlayer != null && mc.currentScreen == null && !mc.isGamePaused()) {
            if (analyzeKey != null && analyzeKey.isPressed()) {
                EuphoriaCompanion.processShaderPacks();
            }
        }
    }
}