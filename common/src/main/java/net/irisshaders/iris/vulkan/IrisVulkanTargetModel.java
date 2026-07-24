package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Owns the native Vulkan main/alt target pairs and their shaderpack configuration. */
public final class IrisVulkanTargetModel implements AutoCloseable {
	private static final int TARGET_COUNT = IrisVulkanGbufferTargets.COLOR_TARGET_COUNT;
	private static final String SEED_COPY_LABEL = "seed/main_color";
	private static final String SEED_COPY_VERTEX = """
		#version 450 core
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		void main() {
		    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	private static final String SEED_COPY_FRAGMENT = """
		#version 450 core
		uniform sampler2D colortex3;
		layout(location = 0) out vec4 iris_fragColor;
		void main() {
		    vec2 uv = gl_FragCoord.xy / vec2(textureSize(colortex3, 0));
		    iris_fragColor = texture(colortex3, uv);
		}
		""";
	private static final String MIPMAP_VERTEX = """
		#version 450 core
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		layout(location = 0) out vec2 iris_uv;
		void main() {
		    iris_uv = UV0;
		    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	private static final String MIPMAP_FRAGMENT = """
		#version 450 core
		layout(location = 0) in vec2 iris_uv;
		uniform sampler2D InSampler;
		layout(location = 0) out vec4 iris_fragColor;
		void main() {
		    iris_fragColor = texture(InSampler, iris_uv);
		}
		""";

	private final IrisVulkanTargetPair[] targets = new IrisVulkanTargetPair[TARGET_COUNT];
	private List<IrisVulkanTargetSpec> requestedSpecs = List.of();
	private List<IrisVulkanTargetSpec> specs = List.of();
	private Map<String, Map<Integer, Boolean>> explicitFlips = Map.of();
	private RenderPipeline seedCopyPipeline;
	private GpuFormat seedCopyFormat;
	private final Map<GpuFormat, RenderPipeline> mipmapPipelines = new HashMap<>();
	private final Set<Integer> loggedMipmapTargets = new HashSet<>();
	private GpuSampler mipmapSampler;
	private boolean loggedSeedFailure;

	public boolean configure(ProgramSet programSet, int width, int height, GpuFormat sourceFormat) {
		if (programSet == null) {
			return configureDefaults(width, height, sourceFormat);
		}

		List<IrisVulkanTargetSpec> requestedSpecs = buildSpecs(programSet, width, height, sourceFormat);
		Map<String, Map<Integer, Boolean>> requestedFlips = buildExplicitFlips(programSet.getPackDirectives());
		if (requestedSpecs.equals(this.requestedSpecs) && requestedFlips.equals(explicitFlips) && ready()) {
			return false;
		}

		return install(requestedSpecs, requestedFlips);
	}

	public boolean configureDefaults(int width, int height, GpuFormat sourceFormat) {
		List<IrisVulkanTargetSpec> requestedSpecs = new ArrayList<>(TARGET_COUNT);
		for (int i = 0; i < TARGET_COUNT; i++) {
			GpuFormat format = IrisVulkanTargetFormat.defaultTargetFormat(i,
				IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET, sourceFormat);
			requestedSpecs.add(new IrisVulkanTargetSpec(i, format, width, height, 1, true,
				IrisVulkanTargetSpec.defaultClearColor(i), i == IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET
					? IrisVulkanTargetSpec.SeedPolicy.COPY_MAIN_COLOR : IrisVulkanTargetSpec.SeedPolicy.CLEAR));
		}
		if (requestedSpecs.equals(this.requestedSpecs) && explicitFlips.isEmpty() && ready()) {
			return false;
		}

		return install(requestedSpecs, Map.of());
	}

	public boolean ready() {
		return specs.size() == TARGET_COUNT && Arrays.stream(targets).allMatch(pair -> pair != null && pair.ready());
	}

	public boolean seed(CommandEncoder encoder, GpuTextureView sourceView) {
		if (!ready()) {
			return false;
		}
		GpuTexture source = sourceView == null ? null : sourceView.texture();

		boolean mainColorCopyAvailable = false;

		for (int i = 0; i < TARGET_COUNT; i++) {
			IrisVulkanTargetPair target = targets[i];
			IrisVulkanTargetSpec spec = specs.get(i);
			Vector4fc clearColor = spec.clearColor();
			if (!target.initialized()) {
				encoder.clearColorTexture(target.currentTexture(), clearColor);
				encoder.clearColorTexture(target.nextTexture(), clearColor);
				target.markInitialized();
			}
		}

		// Clear only the current side requested by the pack; the alternate side is history.
		clearConfiguredTargets(encoder);

		for (int i = 0; i < TARGET_COUNT; i++) {
			IrisVulkanTargetPair target = targets[i];
			IrisVulkanTargetSpec spec = specs.get(i);
			Vector4fc clearColor = spec.clearColor();
			if (spec.seedPolicy() == IrisVulkanTargetSpec.SeedPolicy.COPY_MAIN_COLOR
				&& canCopy(source, target.currentTexture())) {
				encoder.copyTextureToTexture(source, target.currentTexture(), 0, 0, 0, 0, 0,
					spec.width(), spec.height());
				if (spec.index() == IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET) {
					mainColorCopyAvailable = true;
				}
			} else if (spec.seedPolicy() == IrisVulkanTargetSpec.SeedPolicy.COPY_MAIN_COLOR
				&& canRenderCopy(sourceView, target.currentView())) {
				renderFormatConvertingCopy(encoder, sourceView, target.currentView());
				if (spec.index() == IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET) {
					mainColorCopyAvailable = true;
				}
			} else if (spec.seedPolicy() == IrisVulkanTargetSpec.SeedPolicy.COPY_MAIN_COLOR) {
				encoder.clearColorTexture(target.currentTexture(), clearColor);
				if (!loggedSeedFailure) {
					loggedSeedFailure = true;
					Iris.logger.warn("Cannot seed colortex{} from the main color texture because its format or dimensions differ; keeping its deterministic clear value.", spec.index());
				}
			}
		}

		return mainColorCopyAvailable;
	}

	public void clearConfiguredTargets(CommandEncoder encoder) {
		if (!ready()) {
			return;
		}
		for (int i = 0; i < TARGET_COUNT; i++) {
			IrisVulkanTargetSpec spec = specs.get(i);
			if (spec.shouldClear()) {
				Vector4fc clearColor = spec.clearColor();
				encoder.clearColorTexture(targets[i].currentTexture(), clearColor);
			}
		}
	}

	public boolean canCopyTo(int index, GpuTexture destination) {
		return ready() && index >= 0 && index < TARGET_COUNT && canCopy(currentTexture(index), destination);
	}

	public GpuTexture currentTexture(int index) {
		return targets[index].currentTexture();
	}

	public GpuTextureView currentView(int index) {
		return targets[index].currentView();
	}

	public GpuTextureView nextView(int index) {
		return targets[index].nextView();
	}

	/** Returns the allocated format, or the planned format while allocation is unavailable. */
	public GpuFormat effectiveFormat(int index, GpuFormat plannedFormat) {
		if (ready() && index >= 0 && index < specs.size()) {
			return specs.get(index).format();
		}
		return plannedFormat;
	}

	public void swap(int index) {
		targets[index].swap();
	}

	public void select(int index, boolean useAlt) {
		targets[index].select(useAlt);
	}

	public boolean isAlt(int index) {
		return targets[index].isAlt();
	}

	public void applyExplicitFlips(String pass) {
		if (pass == null) {
			return;
		}

		Map<Integer, Boolean> flips = explicitFlips.getOrDefault(pass, Map.of());
		flips.forEach((index, shouldFlip) -> {
			if (Boolean.TRUE.equals(shouldFlip) && index >= 0 && index < TARGET_COUNT) {
				swap(index);
			}
		});
	}

	@Override
	public void close() {
		if (seedCopyPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(seedCopyPipeline);
			seedCopyPipeline = null;
			seedCopyFormat = null;
		}
		mipmapPipelines.values().forEach(IrisNativeVulkan::unregisterCustomPipelineSource);
		mipmapPipelines.clear();
		loggedMipmapTargets.clear();
		if (mipmapSampler != null) {
			mipmapSampler.close();
			mipmapSampler = null;
		}
		for (int i = 0; i < targets.length; i++) {
			if (targets[i] != null) {
				targets[i].close();
				targets[i] = null;
			}
		}
	}

	private static List<IrisVulkanTargetSpec> buildSpecs(ProgramSet programSet, int width, int height, GpuFormat sourceFormat) {
		PackDirectives directives = programSet.getPackDirectives();
		PackRenderTargetDirectives renderTargets = directives.getRenderTargetDirectives();
		List<IrisVulkanTargetSpec> specs = new ArrayList<>(TARGET_COUNT);
		List<GpuFormat> formats = IrisVulkanTargetFormat.resolveTargetFormats(renderTargets, sourceFormat,
			TARGET_COUNT, IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET);
		Set<Integer> mipmappedTargets = collectMipmapTargets(programSet);
		for (int i = 0; i < TARGET_COUNT; i++) {
			PackRenderTargetDirectives.RenderTargetSettings settings = renderTargets.getRenderTargetSettings().get(i);
			if (settings == null) {
				Iris.logger.warn("Shaderpack did not provide settings for colortex{}; using a safe default target.", i);
				settings = new PackRenderTargetDirectives.RenderTargetSettings();
			}
			var dimensions = directives.getTextureScaleOverride(i, width, height);
			int mipLevels = mipmappedTargets.contains(i) ? mipLevels(dimensions.x, dimensions.y) : 1;
			specs.add(IrisVulkanTargetSpec.fromSettings(i, settings, formats.get(i), dimensions.x, dimensions.y, mipLevels));
		}
		return List.copyOf(specs);
	}

	private static Set<Integer> collectMipmapTargets(ProgramSet programSet) {
		Set<Integer> targets = new HashSet<>();
		for (ProgramArrayId array : List.of(ProgramArrayId.Begin, ProgramArrayId.Prepare,
			ProgramArrayId.Deferred, ProgramArrayId.Composite)) {
			for (ProgramSource source : programSet.getComposite(array)) {
				if (source != null) {
					targets.addAll(source.getDirectives().getMipmappedBuffers());
				}
			}
		}
		programSet.get(ProgramId.Final).ifPresent(source -> targets.addAll(source.getDirectives().getMipmappedBuffers()));
		targets.removeIf(index -> index < 0 || index >= TARGET_COUNT);
		return Set.copyOf(targets);
	}

	private static int mipLevels(int width, int height) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
	}

	private static Map<String, Map<Integer, Boolean>> buildExplicitFlips(PackDirectives directives) {
		Map<String, Map<Integer, Boolean>> flips = new HashMap<>();
		for (String pass : List.of("begin_pre", "prepare_pre", "deferred_pre", "composite_pre", "final_pre", "shadowcomp_pre")) {
			Map<Integer, Boolean> passFlips = directives.getExplicitFlips(pass);
			Map<Integer, Boolean> validFlips = new HashMap<>();
			passFlips.forEach((index, useAlt) -> {
				if (index != null && index >= 0 && index < TARGET_COUNT && useAlt != null) {
					validFlips.put(index, useAlt);
				}
			});
			if (!validFlips.isEmpty()) {
				flips.put(pass, Map.copyOf(validFlips));
			}
		}
		return Collections.unmodifiableMap(flips);
	}

	private boolean install(List<IrisVulkanTargetSpec> requestedSpecs, Map<String, Map<Integer, Boolean>> requestedFlips) {
		close();
		loggedSeedFailure = false;
		this.requestedSpecs = List.of();
		specs = List.of();
		explicitFlips = Map.of();

		List<IrisVulkanTargetSpec> effectiveSpecs = new ArrayList<>(requestedSpecs);
		try {
			for (IrisVulkanTargetSpec requestedSpec : requestedSpecs) {
				Iris.logger.info("Allocating native Vulkan colortex{} pair: format={}, size={}x{}.",
					requestedSpec.index(), requestedSpec.format(), requestedSpec.width(), requestedSpec.height());
				IrisVulkanTargetPair pair = createPair(requestedSpec);
				targets[requestedSpec.index()] = pair;
				effectiveSpecs.set(requestedSpec.index(), pair.spec());
			}
		} catch (RuntimeException exception) {
			close();
			Iris.logger.warn("Native Vulkan target allocation failed; disabling native render targets: {}", exception.toString());
			return false;
		}

		specs = List.copyOf(effectiveSpecs);
		this.requestedSpecs = List.copyOf(requestedSpecs);
		explicitFlips = requestedFlips;
		return true;
	}

	private static IrisVulkanTargetPair createPair(IrisVulkanTargetSpec spec) {
		try {
			return new IrisVulkanTargetPair(spec);
		} catch (RuntimeException exception) {
			if (spec.format() == IrisVulkanTargetFormat.defaultTargetFormat(spec.index(),
				IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET, null)) {
				throw exception;
			}
			GpuFormat fallback = IrisVulkanTargetFormat.defaultTargetFormat(spec.index(),
				IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET, null);
			Iris.logger.warn("Native Vulkan rejected format {} for colortex{}; retrying with {}.", spec.format(), spec.index(), fallback, exception);
			return new IrisVulkanTargetPair(spec.withFormat(fallback));
		}
	}

	private static boolean canCopy(GpuTexture source, GpuTexture destination) {
		return source != null && !source.isClosed() && destination != null && !destination.isClosed()
			&& source.getWidth(0) == destination.getWidth(0) && source.getHeight(0) == destination.getHeight(0)
			&& source.getFormat() == destination.getFormat()
			&& (source.usage() & GpuTexture.USAGE_COPY_SRC) != 0
			&& (destination.usage() & GpuTexture.USAGE_COPY_DST) != 0;
	}

	private static boolean canRenderCopy(GpuTextureView source, GpuTextureView destination) {
		return source != null && !source.isClosed() && destination != null && !destination.isClosed()
			&& source.getWidth(0) == destination.getWidth(0) && source.getHeight(0) == destination.getHeight(0)
			&& (source.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0
			&& (destination.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
	}

	private void renderFormatConvertingCopy(CommandEncoder encoder, GpuTextureView source, GpuTextureView destination) {
		RenderPipeline pipeline = seedCopyPipeline(destination.texture().getFormat());
		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
		IndexType indexType = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();
		try (RenderPass pass = encoder.createRenderPass(() -> "Iris native Vulkan seed main color", destination,
			Optional.empty())) {
			pass.setPipeline(pipeline);
			IrisVulkanRenderPassBindings.prepareScreenPassResources(pass, SEED_COPY_LABEL,
				TextureStage.COMPOSITE_AND_FINAL, null, source);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	private RenderPipeline seedCopyPipeline(GpuFormat format) {
		if (seedCopyPipeline != null && seedCopyFormat == format) {
			return seedCopyPipeline;
		}
		if (seedCopyPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(seedCopyPipeline);
		}
		seedCopyPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + SEED_COPY_LABEL))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
			.build();
		seedCopyFormat = format;
		IrisNativeVulkan.registerCustomPipelineSource(seedCopyPipeline, SEED_COPY_LABEL,
			SEED_COPY_VERTEX, SEED_COPY_FRAGMENT, true);
		Iris.logger.info("Using native Vulkan format-converting main-color seed pipeline for {}.", format);
		return seedCopyPipeline;
	}

	public void generateMipmaps(CommandEncoder encoder, int index) {
		if (!ready() || index < 0 || index >= TARGET_COUNT) {
			return;
		}
		IrisVulkanTargetPair target = targets[index];
		if (target.spec().mipLevels() <= 1) {
			throw new IllegalStateException("colortex" + index + " was not allocated with mip levels");
		}

		RenderPipeline pipeline = mipmapPipeline(target.spec().format());
		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
		IndexType indexType = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();
		for (int level = 1; level < target.spec().mipLevels(); level++) {
			GpuTextureView source = target.currentMipView(level - 1);
			GpuTextureView destination = target.currentMipView(level);
			int mipLevel = level;
			try (RenderPass pass = encoder.createRenderPass(
				() -> "Iris native Vulkan colortex" + index + " mip " + mipLevel,
				destination, Optional.empty())) {
				pass.setPipeline(pipeline);
				pass.bindTexture("InSampler", source, mipmapSampler());
				pass.setIndexBuffer(indices, indexType);
				pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		}

		if (loggedMipmapTargets.add(index)) {
			Iris.logger.info("Generated native Vulkan mip chain for colortex{} ({} levels).",
				index, target.spec().mipLevels());
		}
	}

	private RenderPipeline mipmapPipeline(GpuFormat format) {
		return mipmapPipelines.computeIfAbsent(format, targetFormat -> {
			String label = "mipmap/" + targetFormat.name().toLowerCase(java.util.Locale.ROOT);
			RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + label))
				.withVertexShader("core/screenquad")
				.withFragmentShader("core/blit_screen")
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withColorTargetState(0,
					new ColorTargetState(Optional.empty(), targetFormat, ColorTargetState.WRITE_ALL))
				.build();
			IrisNativeVulkan.registerCustomPipelineSource(pipeline, label, MIPMAP_VERTEX, MIPMAP_FRAGMENT, true);
			return pipeline;
		});
	}

	private GpuSampler mipmapSampler() {
		if (mipmapSampler == null) {
			mipmapSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,
				AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
		}
		return mipmapSampler;
	}

	private static final class IrisVulkanTargetPair implements AutoCloseable {
		private final IrisVulkanTargetSpec spec;
		private final GpuTexture[] textures = new GpuTexture[2];
		private final GpuTextureView[] views = new GpuTextureView[2];
		private final GpuTextureView[][] mipViews;
		private int current;
		private boolean initialized;

		private IrisVulkanTargetPair(IrisVulkanTargetSpec spec) {
			this.spec = spec;
			this.mipViews = new GpuTextureView[2][spec.mipLevels()];
			try {
				int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
					| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
				textures[0] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + spec.index() + " main",
					usage, spec.format(), spec.width(), spec.height(), 1, spec.mipLevels());
				textures[1] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + spec.index() + " alt",
					usage, spec.format(), spec.width(), spec.height(), 1, spec.mipLevels());
				views[0] = RenderSystem.getDevice().createTextureView(textures[0]);
				views[1] = RenderSystem.getDevice().createTextureView(textures[1]);
			} catch (RuntimeException exception) {
				close();
				throw exception;
			}
		}

		private IrisVulkanTargetSpec spec() { return spec; }

		private boolean ready() {
			return textures[0] != null && !textures[0].isClosed() && textures[1] != null && !textures[1].isClosed()
				&& views[0] != null && !views[0].isClosed() && views[1] != null && !views[1].isClosed();
		}

		private GpuTexture currentTexture() { return textures[current]; }
		private GpuTextureView currentView() { return views[current]; }
		private GpuTexture nextTexture() { return textures[1 - current]; }
		private GpuTextureView nextView() { return views[1 - current]; }
		private GpuTextureView currentMipView(int level) {
			GpuTextureView view = mipViews[current][level];
			if (view == null || view.isClosed()) {
				view = RenderSystem.getDevice().createTextureView(textures[current], level, 1);
				mipViews[current][level] = view;
			}
			return view;
		}
		private void swap() { current = 1 - current; }
		private void select(boolean useAlt) { current = useAlt ? 1 : 0; }
		private boolean isAlt() { return current == 1; }
		private boolean initialized() { return initialized; }
		private void markInitialized() { initialized = true; }

		@Override
		public void close() {
			for (GpuTextureView[] side : mipViews) for (GpuTextureView view : side) if (view != null) view.close();
			for (GpuTextureView view : views) if (view != null) view.close();
			for (GpuTexture texture : textures) if (texture != null) texture.close();
		}
	}
}
