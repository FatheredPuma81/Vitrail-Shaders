package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pack's whole chain, drawn where the level renderer has just returned and nothing else has
 * touched the main target yet.
 * <p>
 * The one point of the frame this engine cannot do without, and on NeoForge it is a public event
 * posted from this exact line. Here it is the line itself: injected after the call rather than at
 * the return of the method, because the hand, the screen effects and the crosshair are all drawn
 * further down and the chain has to be under them.
 * <p>
 * Ordering vs the Upscaled mod (external DLSS/FSR3, default mixin priority 1000): Upscaled
 * injects its {@code upscaleWorldBeforeHand} evaluate at this same AFTER-
 * {@code LevelRenderer.render} point. This handler asks for priority 900 so the pack composites
 * while {@code mainRenderTarget()} still resolves to the low-res world target and DLSS then
 * upscales the composited image (tonemap-before-SR is not NVIDIA-ideal, but it is crash-free and
 * keeps any Swapper neural-rendering final stage operating on the shader image). Even if the
 * order ever inverts, {@link dev.vitrail.render.RenderScale} stands down while an external
 * upscaler is active, so the main target's fields stay window-sized and the GUI scissor that
 * used to crash (full-window rect on a DLSS-sized area) stays valid.
 */
@Mixin(value = GameRenderer.class, priority = 900)
public abstract class AfterLevelMixin {

	@Inject(method = "renderLevel",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;"
							+ "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
							+ "Lnet/minecraft/client/DeltaTracker;Z"
							+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
							+ "Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
							+ "Lorg/joml/Vector4f;Z)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void vitrail$afterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
		EngineStages.afterLevel();
	}
}
