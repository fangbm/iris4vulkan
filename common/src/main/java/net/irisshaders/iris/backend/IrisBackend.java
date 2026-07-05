package net.irisshaders.iris.backend;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;

public final class IrisBackend {
	private IrisBackend() {
	}

	public static GpuDeviceBackend getBackend(GpuDevice device) {
		return ((GpuDeviceAccessor) device).getBackend();
	}

	public static boolean isOpenGl(GpuDevice device) {
		return getBackend(device) instanceof GlDevice;
	}

	public static boolean isVulkan(GpuDevice device) {
		return getBackend(device) instanceof VulkanDevice;
	}
}
