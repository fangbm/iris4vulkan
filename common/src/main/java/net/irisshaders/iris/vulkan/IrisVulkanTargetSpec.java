package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Objects;

/** Immutable description of one shaderpack color target and its initial contents. */
public final class IrisVulkanTargetSpec {
	public enum SeedPolicy {
		CLEAR,
		COPY_MAIN_COLOR
	}

	private final int index;
	private final GpuFormat format;
	private final int width;
	private final int height;
	private final boolean shouldClear;
	private final Vector4f clearColor;
	private final SeedPolicy seedPolicy;

	public IrisVulkanTargetSpec(int index, GpuFormat format, int width, int height, boolean shouldClear,
							Vector4fc clearColor, SeedPolicy seedPolicy) {
		this.index = index;
		this.format = Objects.requireNonNull(format, "format");
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		this.shouldClear = shouldClear;
		this.clearColor = clearColor == null ? defaultClearColor(index) : new Vector4f(clearColor);
		this.seedPolicy = Objects.requireNonNull(seedPolicy, "seedPolicy");
	}

	public static IrisVulkanTargetSpec fromSettings(int index, PackRenderTargetDirectives.RenderTargetSettings settings,
										GpuFormat format, int width, int height) {
		Vector4f clearColor = settings.getClearColor().map(Vector4f::new).orElseGet(() -> defaultClearColor(index));
		SeedPolicy seedPolicy = index == IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET
			? SeedPolicy.COPY_MAIN_COLOR
			: SeedPolicy.CLEAR;
		return new IrisVulkanTargetSpec(index, format, width, height, settings.shouldClear(), clearColor, seedPolicy);
	}

	public static Vector4f defaultClearColor(int index) {
		if (index == 0) {
			// The fog color is not available at native target allocation time.
			return new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
		}
		if (index == 1) {
			return new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
		}
		return new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
	}

	public int index() {
		return index;
	}

	public GpuFormat format() {
		return format;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean shouldClear() {
		return shouldClear;
	}

	public Vector4f clearColor() {
		return new Vector4f(clearColor);
	}

	public SeedPolicy seedPolicy() {
		return seedPolicy;
	}

	IrisVulkanTargetSpec withFormat(GpuFormat replacement) {
		return new IrisVulkanTargetSpec(index, replacement, width, height, shouldClear, clearColor, seedPolicy);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof IrisVulkanTargetSpec that)) return false;
		return index == that.index && width == that.width && height == that.height && shouldClear == that.shouldClear
			&& format == that.format && clearColor.equals(that.clearColor) && seedPolicy == that.seedPolicy;
	}

	@Override
	public int hashCode() {
		return Objects.hash(index, format, width, height, shouldClear, clearColor, seedPolicy);
	}
}
