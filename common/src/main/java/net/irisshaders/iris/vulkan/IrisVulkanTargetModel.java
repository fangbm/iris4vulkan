package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the native Vulkan main/alt target pairs and their shaderpack configuration. */
public final class IrisVulkanTargetModel implements AutoCloseable {
	private static final int TARGET_COUNT = IrisVulkanGbufferTargets.COLOR_TARGET_COUNT;

	private final IrisVulkanTargetPair[] targets = new IrisVulkanTargetPair[TARGET_COUNT];
	private List<IrisVulkanTargetSpec> requestedSpecs = List.of();
	private List<IrisVulkanTargetSpec> specs = List.of();
	private Map<String, Map<Integer, Boolean>> explicitFlips = Map.of();

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
			requestedSpecs.add(new IrisVulkanTargetSpec(i, format, width, height, true,
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

	public boolean seed(CommandEncoder encoder, GpuTexture source) {
		if (!ready()) {
			return false;
		}

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
			} else if (spec.seedPolicy() == IrisVulkanTargetSpec.SeedPolicy.COPY_MAIN_COLOR) {
				encoder.clearColorTexture(target.currentTexture(), clearColor);
				Iris.logger.warn("Cannot seed colortex{} from the main color texture because its format or dimensions differ; keeping its deterministic clear value.", spec.index());
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
		for (int i = 0; i < TARGET_COUNT; i++) {
			PackRenderTargetDirectives.RenderTargetSettings settings = renderTargets.getRenderTargetSettings().get(i);
			if (settings == null) {
				Iris.logger.warn("Shaderpack did not provide settings for colortex{}; using a safe default target.", i);
				settings = new PackRenderTargetDirectives.RenderTargetSettings();
			}
			var dimensions = directives.getTextureScaleOverride(i, width, height);
			specs.add(IrisVulkanTargetSpec.fromSettings(i, settings, formats.get(i), dimensions.x, dimensions.y));
		}
		return List.copyOf(specs);
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

	private static final class IrisVulkanTargetPair implements AutoCloseable {
		private final IrisVulkanTargetSpec spec;
		private final GpuTexture[] textures = new GpuTexture[2];
		private final GpuTextureView[] views = new GpuTextureView[2];
		private int current;
		private boolean initialized;

		private IrisVulkanTargetPair(IrisVulkanTargetSpec spec) {
			this.spec = spec;
			try {
				int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
					| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
				textures[0] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + spec.index() + " main",
					usage, spec.format(), spec.width(), spec.height(), 1, 1);
				textures[1] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + spec.index() + " alt",
					usage, spec.format(), spec.width(), spec.height(), 1, 1);
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
		private void swap() { current = 1 - current; }
		private void select(boolean useAlt) { current = useAlt ? 1 : 0; }
		private boolean isAlt() { return current == 1; }
		private boolean initialized() { return initialized; }
		private void markInitialized() { initialized = true; }

		@Override
		public void close() {
			for (GpuTextureView view : views) if (view != null) view.close();
			for (GpuTexture texture : textures) if (texture != null) texture.close();
		}
	}
}
