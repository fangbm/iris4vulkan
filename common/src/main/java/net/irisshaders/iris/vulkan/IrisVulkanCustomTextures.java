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
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class IrisVulkanCustomTextures {
	private static final Set<String> WARNED_UNSUPPORTED = ConcurrentHashMap.newKeySet();
	private static final TextureFilteringData LINEAR_REPEAT = new TextureFilteringData(true, false);

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

		if (WARNED_UNSUPPORTED.add(sampler + ":" + data.getClass().getName())) {
			Iris.logger.warn("Native Vulkan custom texture {} uses unsupported data type {}; using fallback binding if sampled.",
				sampler, data.getClass().getSimpleName());
		}

		return null;
	}

	private static Binding createPngTexture(String label, CustomTextureData.PngData data) throws IOException {
		try (NativeImage image = NativeImage.read(data.getContent())) {
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

	private static GpuFormat formatFor(NativeImage image) {
		return switch (image.format()) {
			case RGBA -> GpuFormat.RGBA8_UNORM;
			case RGB -> GpuFormat.RGB8_UNORM;
			case LUMINANCE_ALPHA -> GpuFormat.RG8_UNORM;
			case LUMINANCE -> GpuFormat.R8_UNORM;
		};
	}

	private static boolean supports(CustomTextureData data) {
		return data instanceof CustomTextureData.PngData;
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
