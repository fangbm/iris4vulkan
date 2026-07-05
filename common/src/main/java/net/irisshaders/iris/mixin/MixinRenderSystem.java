package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class MixinRenderSystem {
	@Inject(method = "initRenderer", at = @At("RETURN"), remap = false)
	private static void iris$onRendererInit(GpuDevice device, CallbackInfo ci) {
		if (!IrisBackend.isOpenGl(device)) {
			IrisNativeVulkan.onRendererInitialized(device);
			return;
		}

		Iris.duringRenderSystemInit();
		GLDebug.reloadDebugState();
		IrisRenderSystem.initRenderer();
		IrisSamplers.initRenderer();
		Iris.onRenderSystemInit();
	}
}
