package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class VKOnly_MixinLevelRenderer_NativeVulkan {
	@Inject(method = "render", at = @At("HEAD"))
	private void iris$beginNativeVulkanFrame(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
											 boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
											 GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
											 CallbackInfo ci) {
		IrisTimeUniforms.updateTime();
		CapturedRenderingState.INSTANCE.setGbufferModelView(modelViewMatrix);
		CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f(((GameRendererStorage) Minecraft.getInstance().gameRenderer).sodium$getProjectionMatrix()));
		CapturedRenderingState.INSTANCE.setTickDelta(deltaTracker.getGameTimeDeltaPartialTick(false));
		Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());

		if (IrisNativeVulkan.beginFinalPassFrame()) {
			IrisVulkanGbufferTargets.beginFrameCapture();
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$runNativeVulkanFinalPass(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
											   boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
											   GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
											   CallbackInfo ci) {
		IrisNativeVulkan.renderFinalPassIfReady();
	}
}
