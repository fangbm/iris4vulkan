package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IrisVulkanCustomTextures {
	private static final Set<String> WARNED_UNSUPPORTED = ConcurrentHashMap.newKeySet();
	private static final TextureFilteringData LINEAR_REPEAT = new TextureFilteringData(true, false);
	private static final Pattern SAMPLER_3D = Pattern.compile(
		"(?m)^(\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+)sampler3D(\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;)");

	private static ShaderPack loadedPack;
	private static EnumMap<TextureStage, Map<String, Binding>> stageTextures = new EnumMap<>(TextureStage.class);
	private static Map<String, Binding> globalTextures = Map.of();
	private static Binding customNoiseTexture;
	private static Binding generatedNoiseTexture;
	private static int generatedNoiseResolution = -1;

	private IrisVulkanCustomTextures() {
	}

	static Binding find(TextureStage stage, String sampler) {
		ensureLoaded();

		Map<String, Binding> stageBindings = stageTextures.get(stage);
		if (stageBindings != null) {
			Binding binding = stageBindings.get(sampler);

			if (binding != null) {
				return binding;
			}
		}

		return globalTextures.get(sampler);
	}

	static Binding noise(int resolution) {
		ensureLoaded();

		if (customNoiseTexture != null) {
			return customNoiseTexture;
		}

		if (generatedNoiseTexture == null || generatedNoiseTexture.closed() || generatedNoiseResolution != resolution) {
			closeGeneratedNoiseTexture();
			generatedNoiseTexture = createGeneratedNoiseTexture(resolution);
			generatedNoiseResolution = resolution;
		}

		return generatedNoiseTexture;
	}

	static boolean supports(ShaderPack pack, TextureStage stage, String sampler) {
		if (pack == null) {
			return false;
		}

		Object2ObjectMap<String, CustomTextureData> stageData = pack.getCustomTextureDataMap().get(stage);
		if (stageData != null && supports(stageData.get(sampler))) {
			return true;
		}

		return supports(pack.getIrisCustomTextureDataMap().get(sampler));
	}

	static String patchShaderSource(ShaderPack pack, TextureStage stage, String source) {
		if (pack == null || source == null || !source.contains("sampler3D")) {
			return source;
		}

		Matcher declarations = SAMPLER_3D.matcher(source);
		Map<String, CustomTextureData.RawData3D> flattened = new HashMap<>();
		while (declarations.find()) {
			String sampler = declarations.group(3);
			CustomTextureData data = findData(pack, stage, sampler);
			if (data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw)) {
				flattened.put(sampler, raw);
			}
		}

		if (flattened.isEmpty()) {
			return source;
		}

		String patched = source;
		for (Map.Entry<String, CustomTextureData.RawData3D> entry : flattened.entrySet()) {
			String sampler = entry.getKey();
			CustomTextureData.RawData3D raw = entry.getValue();
			String function = "iris_vulkan_sample3d_" + sampler;
			patched = Pattern.compile("\\btexture\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,")
				.matcher(patched).replaceAll(Matcher.quoteReplacement(function + "("));
			patched = Pattern.compile("\\btextureSize\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,\\s*[^)]+\\)")
				.matcher(patched).replaceAll("ivec3(" + raw.getSizeX() + ", " + raw.getSizeY() + ", " + raw.getSizeZ() + ")");
		}

		Matcher matcher = SAMPLER_3D.matcher(patched);
		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			String sampler = matcher.group(3);
			CustomTextureData.RawData3D raw = flattened.get(sampler);
			if (raw == null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
				continue;
			}

			String replacement = matcher.group(1) + "sampler2D" + matcher.group(2)
				+ flattenedSamplerFunction(sampler, raw);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	static void close() {
		for (Map<String, Binding> bindings : stageTextures.values()) {
			bindings.values().forEach(Binding::close);
		}

		globalTextures.values().forEach(Binding::close);
		closeCustomNoiseTexture();
		closeGeneratedNoiseTexture();

		loadedPack = null;
		stageTextures = new EnumMap<>(TextureStage.class);
		globalTextures = Map.of();
	}

	private static void ensureLoaded() {
		Optional<ShaderPack> currentPack = Iris.getCurrentPack();

		if (currentPack.isEmpty()) {
			if (loadedPack != null) {
				close();
			}

			return;
		}

		ShaderPack pack = currentPack.get();
		if (pack == loadedPack) {
			return;
		}

		close();
		loadedPack = pack;
		stageTextures = new EnumMap<>(TextureStage.class);

		pack.getCustomTextureDataMap().forEach((stage, textures) -> {
			Map<String, Binding> bindings = new HashMap<>();

			textures.forEach((sampler, data) -> {
				Binding binding = createTexture("Iris native Vulkan custom texture " + stage.name().toLowerCase() + "/" + sampler,
					data, sampler);

				if (binding != null) {
					bindings.put(sampler, binding);
				}
			});

			if (!bindings.isEmpty()) {
				stageTextures.put(stage, bindings);
			}
		});

		Map<String, Binding> globals = new HashMap<>();
		pack.getIrisCustomTextureDataMap().forEach((sampler, data) -> {
			Binding binding = createTexture("Iris native Vulkan custom texture global/" + sampler, data, sampler);

			if (binding != null) {
				globals.put(sampler, binding);
			}
		});
		globalTextures = Map.copyOf(globals);

		customNoiseTexture = createTexture("Iris native Vulkan custom noisetex", pack.getCustomNoiseTexture(), "noisetex");
		Iris.logger.info("Loaded native Vulkan custom textures: {} stage texture(s), {} global texture(s), customNoise={}.",
			stageTextures.values().stream().mapToInt(Map::size).sum(), globalTextures.size(), customNoiseTexture != null);
	}

	private static Binding createTexture(String label, CustomTextureData data, String sampler) {
		if (data == null) {
			return null;
		}

		if (data instanceof CustomTextureData.PngData png) {
			try {
				return createPngTexture(label, png);
			} catch (IOException | RuntimeException e) {
				Iris.logger.warn("Failed to load native Vulkan custom texture {}: {}", sampler, e.getMessage());
				return null;
			}
		}

		if (data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw)) {
			try {
				return createFlattenedRaw3DTexture(label, raw);
			} catch (RuntimeException e) {
				Iris.logger.warn("Failed to load native Vulkan flattened 3D custom texture {}: {}", sampler, e.getMessage());
				return null;
			}
		}

		if (WARNED_UNSUPPORTED.add(sampler + ":" + data.getClass().getName())) {
			Iris.logger.warn("Native Vulkan custom texture {} uses unsupported data type {}; using fallback binding if sampled.",
				sampler, data.getClass().getSimpleName());
		}

		return null;
	}

	private static Binding createPngTexture(String label, CustomTextureData.PngData data) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocateDirect(data.getContent().length);
		buffer.put(data.getContent());
		buffer.flip();

		try (NativeImage image = NativeImage.read(NativeImage.Format.RGBA, buffer)) {
			return createImageTexture(label, image, data.getFilteringData());
		}
	}

	private static Binding createGeneratedNoiseTexture(int resolution) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);
		Random random = new Random(0);

		for (int x = 0; x < resolution; x++) {
			for (int y = 0; y < resolution; y++) {
				image.setPixel(x, y, random.nextInt() | (255 << 24));
			}
		}

		try (image) {
			Binding binding = createImageTexture("Iris native Vulkan generated noisetex " + resolution, image, LINEAR_REPEAT);
			Iris.logger.info("Created native Vulkan generated noisetex at {}x{}.", resolution, resolution);
			return binding;
		}
	}

	private static Binding createImageTexture(String label, NativeImage image, TextureFilteringData filteringData) {
		GpuTexture texture = RenderSystem.getDevice().createTexture(() -> label,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			formatFor(image), image.getWidth(), image.getHeight(), 1, 1);
		GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);

		AddressMode addressMode = filteringData.shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		FilterMode filterMode = filteringData.shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST;
		GpuSampler sampler = RenderSystem.getDevice().createSampler(addressMode, addressMode,
			filterMode, filterMode, 1, OptionalDouble.empty());

		return new Binding(texture, view, sampler);
	}

	private static Binding createFlattenedRaw3DTexture(String label, CustomTextureData.RawData3D data) {
		int width = data.getSizeX();
		int height = Math.multiplyExact(data.getSizeY(), data.getSizeZ());
		GpuTexture texture = RenderSystem.getDevice().createTexture(() -> label + " (flattened 3D)",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA32_FLOAT, width, height, 1, 1);
		GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
		ByteBuffer content = ByteBuffer.allocateDirect(data.getContent().length);
		content.put(data.getContent()).flip();
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, content, 0, 0, 0, 0, width, height);

		AddressMode addressMode = data.getFilteringData().shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		FilterMode filterMode = data.getFilteringData().shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST;
		// Each Z slice occupies one vertical atlas tile, so V must never wrap into another slice.
		GpuSampler sampler = RenderSystem.getDevice().createSampler(addressMode, AddressMode.CLAMP_TO_EDGE,
			filterMode, filterMode, 1, OptionalDouble.empty());
		Iris.logger.info("Loaded native Vulkan flattened 3D texture {}x{}x{} as {}x{} RGBA32_FLOAT.",
			data.getSizeX(), data.getSizeY(), data.getSizeZ(), width, height);
		return new Binding(texture, view, sampler);
	}

	private static GpuFormat formatFor(NativeImage image) {
		return switch (image.format()) {
			case RGBA -> GpuFormat.RGBA8_UNORM;
			case RGB -> GpuFormat.RGB8_UNORM;
			case LUMINANCE_ALPHA -> GpuFormat.RG8_UNORM;
			case LUMINANCE -> GpuFormat.R8_UNORM;
		};
	}

	private static boolean supports(CustomTextureData data) {
		return data instanceof CustomTextureData.PngData
			|| data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw);
	}

	private static boolean supportsRaw3D(CustomTextureData.RawData3D data) {
		return data.getInternalFormat() == InternalTextureFormat.RGBA32F
			&& data.getPixelFormat() == PixelFormat.RGBA
			&& data.getPixelType() == PixelType.FLOAT;
	}

	private static CustomTextureData findData(ShaderPack pack, TextureStage stage, String sampler) {
		Object2ObjectMap<String, CustomTextureData> stageData = pack.getCustomTextureDataMap().get(stage);
		if (stageData != null && stageData.containsKey(sampler)) {
			return stageData.get(sampler);
		}
		return pack.getIrisCustomTextureDataMap().get(sampler);
	}

	private static String flattenedSamplerFunction(String sampler, CustomTextureData.RawData3D data) {
		int sliceHeight = data.getSizeY();
		int slices = data.getSizeZ();
		return "\nvec4 iris_vulkan_sample3d_" + sampler + "(vec3 coord) {\n"
			+ "    float iris_z = clamp(coord.z * " + slices + ".0 - 0.5, 0.0, " + (slices - 1) + ".0);\n"
			+ "    float iris_z0 = floor(iris_z);\n"
			+ "    float iris_z1 = min(iris_z0 + 1.0, " + (slices - 1) + ".0);\n"
			+ "    float iris_y = clamp(coord.y, 0.5 / " + sliceHeight + ".0, 1.0 - 0.5 / " + sliceHeight + ".0);\n"
			+ "    vec2 iris_uv0 = vec2(coord.x, (iris_y + iris_z0) / " + slices + ".0);\n"
			+ "    vec2 iris_uv1 = vec2(coord.x, (iris_y + iris_z1) / " + slices + ".0);\n"
			+ "    return mix(texture(" + sampler + ", iris_uv0), texture(" + sampler + ", iris_uv1), fract(iris_z));\n"
			+ "}\n";
	}

	private static void closeCustomNoiseTexture() {
		if (customNoiseTexture != null) {
			customNoiseTexture.close();
			customNoiseTexture = null;
		}
	}

	private static void closeGeneratedNoiseTexture() {
		if (generatedNoiseTexture != null) {
			generatedNoiseTexture.close();
			generatedNoiseTexture = null;
		}

		generatedNoiseResolution = -1;
	}

	record Binding(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
		private boolean closed() {
			return texture == null || texture.isClosed()
				|| view == null || view.isClosed();
		}

		private void close() {
			if (view != null && !view.isClosed()) {
				view.close();
			}

			if (texture != null && !texture.isClosed()) {
				texture.close();
			}

			if (sampler != null) {
				sampler.close();
			}
		}
	}
}
