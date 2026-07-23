package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps shaderpack formats to the native GPU format vocabulary. */
public final class IrisVulkanTargetFormat {
	private static final GpuFormat SAFE_FALLBACK_FORMAT = GpuFormat.RGBA8_UNORM;

	private IrisVulkanTargetFormat() {
	}

	public static GpuFormat safeFallback(GpuFormat preferred) {
		return preferred != null && preferred.hasColorAspect() ? preferred : SAFE_FALLBACK_FORMAT;
	}

	public static GpuFormat defaultTargetFormat(int target, int sourceTarget, GpuFormat sourceFormat) {
		return target == sourceTarget ? safeFallback(sourceFormat) : SAFE_FALLBACK_FORMAT;
	}

	/** Resolves the format of every logical target without requiring native target allocation. */
	public static List<GpuFormat> resolveTargetFormats(PackRenderTargetDirectives directives, GpuFormat sourceFormat,
														 int targetCount, int sourceTarget) {
		List<GpuFormat> formats = new ArrayList<>(targetCount);
		for (int target = 0; target < targetCount; target++) {
			PackRenderTargetDirectives.RenderTargetSettings settings = directives == null ? null
				: directives.getRenderTargetSettings().get(target);
			InternalTextureFormat requested = settings == null ? InternalTextureFormat.RGBA : settings.getInternalFormat();
			formats.add(resolve(requested, defaultTargetFormat(target, sourceTarget, sourceFormat), target));
		}
		return List.copyOf(formats);
	}

	public static GpuFormat resolve(InternalTextureFormat requested, GpuFormat fallback, int target) {
		GpuFormat safeFallback = safeFallback(fallback);
		GpuFormat resolved = requested == null ? null : switch (requested) {
			case RGBA -> GpuFormat.RGBA8_UNORM;
			case R8 -> GpuFormat.R8_UNORM;
			case RG8 -> GpuFormat.RG8_UNORM;
			case RGB8 -> GpuFormat.RGB8_UNORM;
			case RGBA8 -> GpuFormat.RGBA8_UNORM;
			case R8_SNORM -> GpuFormat.R8_SNORM;
			case RG8_SNORM -> GpuFormat.RG8_SNORM;
			case RGB8_SNORM -> GpuFormat.RGB8_SNORM;
			case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
			case R16 -> GpuFormat.R16_UNORM;
			case RG16 -> GpuFormat.RG16_UNORM;
			case RGB16 -> GpuFormat.RGB16_UNORM;
			case RGBA16 -> GpuFormat.RGBA16_UNORM;
			case R16_SNORM -> GpuFormat.R16_SNORM;
			case RG16_SNORM -> GpuFormat.RG16_SNORM;
			case RGB16_SNORM -> GpuFormat.RGB16_SNORM;
			case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
			case R16F -> GpuFormat.R16_FLOAT;
			case RG16F -> GpuFormat.RG16_FLOAT;
			case RGB16F -> GpuFormat.RGB16_FLOAT;
			case RGBA16F -> GpuFormat.RGBA16_FLOAT;
			case R32F -> GpuFormat.R32_FLOAT;
			case RG32F -> GpuFormat.RG32_FLOAT;
			case RGB32F -> GpuFormat.RGB32_FLOAT;
			case RGBA32F -> GpuFormat.RGBA32_FLOAT;
			case R8I -> GpuFormat.R8_SINT;
			case RG8I -> GpuFormat.RG8_SINT;
			case RGB8I -> GpuFormat.RGB8_SINT;
			case RGBA8I -> GpuFormat.RGBA8_SINT;
			case R8UI -> GpuFormat.R8_UINT;
			case RG8UI -> GpuFormat.RG8_UINT;
			case RGB8UI -> GpuFormat.RGB8_UINT;
			case RGBA8UI -> GpuFormat.RGBA8_UINT;
			case R16I -> GpuFormat.R16_SINT;
			case RG16I -> GpuFormat.RG16_SINT;
			case RGB16I -> GpuFormat.RGB16_SINT;
			case RGBA16I -> GpuFormat.RGBA16_SINT;
			case R16UI -> GpuFormat.R16_UINT;
			case RG16UI -> GpuFormat.RG16_UINT;
			case RGB16UI -> GpuFormat.RGB16_UINT;
			case RGBA16UI -> GpuFormat.RGBA16_UINT;
			case R32I -> GpuFormat.R32_SINT;
			case RG32I -> GpuFormat.RG32_SINT;
			case RGB32I -> GpuFormat.RGB32_SINT;
			case RGBA32I -> GpuFormat.RGBA32_SINT;
			case R32UI -> GpuFormat.R32_UINT;
			case RG32UI -> GpuFormat.RG32_UINT;
			case RGB32UI -> GpuFormat.RGB32_UINT;
			case RGBA32UI -> GpuFormat.RGBA32_UINT;
			case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
			case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
			case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
			default -> null;
		};

		if (resolved != null && resolved.hasColorAspect()) {
			return renderAttachmentSafe(resolved);
		}

		String requestedName = requested == null ? "<missing>" : requested.name();
		Iris.logger.warn("Native Vulkan does not support shaderpack format {} for colortex{}; using {}.",
			requestedName.toLowerCase(Locale.ROOT), target, safeFallback);
		return safeFallback;
	}

	/** Vulkan drivers do not consistently expose three-component formats as color attachments. */
	private static GpuFormat renderAttachmentSafe(GpuFormat format) {
		return switch (format) {
			case RGB8_UNORM -> GpuFormat.RGBA8_UNORM;
			case RGB8_SNORM -> GpuFormat.RGBA8_SNORM;
			case RGB16_UNORM -> GpuFormat.RGBA16_UNORM;
			case RGB16_SNORM -> GpuFormat.RGBA16_SNORM;
			case RGB8_UINT -> GpuFormat.RGBA8_UINT;
			case RGB8_SINT -> GpuFormat.RGBA8_SINT;
			case RGB16_UINT -> GpuFormat.RGBA16_UINT;
			case RGB16_SINT -> GpuFormat.RGBA16_SINT;
			case RGB32_UINT -> GpuFormat.RGBA32_UINT;
			case RGB32_SINT -> GpuFormat.RGBA32_SINT;
			case RGB16_FLOAT -> GpuFormat.RGBA16_FLOAT;
			case RGB32_FLOAT -> GpuFormat.RGBA32_FLOAT;
			default -> format;
		};
	}
}
