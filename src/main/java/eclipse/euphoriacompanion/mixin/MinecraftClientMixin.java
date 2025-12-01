package eclipse.euphoriacompanion.mixin;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Shadow
    private boolean paused;

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;

        // Only process when the game is active
        if (client.player != null && client.currentScreen == null && !paused && EuphoriaCompanion.ANALYZE_KEY != null) {
            // Check if our key was pressed
            if (EuphoriaCompanion.ANALYZE_KEY.wasPressed()) {
                EuphoriaCompanion.processShaderPacks();
            }
        }
    }
}