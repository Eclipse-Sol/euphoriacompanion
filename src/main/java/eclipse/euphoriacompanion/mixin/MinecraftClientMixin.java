package eclipse.euphoriacompanion.mixin;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Shadow
    private volatile boolean pause;

    @Shadow
    public LocalPlayer player;

    @Shadow
    public Screen screen;

    @SuppressWarnings("UnresolvedMixinReference")
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        // Only process when the game is active
        if (player != null && screen == null && !pause && EuphoriaCompanion.ANALYZE_KEY != null) {
            // Check if our key was pressed
            if (EuphoriaCompanion.ANALYZE_KEY.consumeClick()) {
                EuphoriaCompanion.processShaderPacks();
            }
        }
    }
}