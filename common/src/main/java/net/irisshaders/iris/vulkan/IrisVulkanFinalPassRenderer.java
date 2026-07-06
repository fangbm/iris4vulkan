package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPass.RenderArea;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVulkanFinalPassRenderer {
	private static final int COLOR_TARGET_COUNT = IrisVulkanGbufferTargets.COLOR_TARGET_COUNT;
	private static final int FALLBACK_SCENE_TARGET = IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET;
	private static final int FINAL_SOURCE_TARGET = IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET;
	private static final Pattern DRAWBUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)");
	private static final Pattern OUTPUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*out\\s+[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
	private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+([iu]?sampler\\w+)\\s+(\\w+)\\s*(?:\\[[^]]+])?\\s*;");

	private final List<Pass> deferredPasses;
	private final List<Pass> compositePasses;
	private final Pass finalPass;
	private final Set<Pass> failedPasses = new HashSet<>();

	public IrisVulkanFinalPassRenderer(ProgramSet programSet) {
		this.finalPass = createFinalPass(programSet);

		if (finalPass == null) {
			this.deferredPasses = List.of();
			this.compositePasses = List.of();
			Iris.logger.info("Shaderpack has no supported native Vulkan final pass; screen pass chain is disabled.");
		} else {
			this.deferredPasses = createPasses(programSet, ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred");
			this.compositePasses = createPasses(programSet, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite");
			Iris.logger.info("Registered {} native Vulkan deferred pass(es), {} composite pass(es){}.",
				deferredPasses.size(), compositePasses.size(), " plus final");
		}
	}

	public void render() {
		if (!hasRunnablePasses()) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTexture colorTexture = main.getColorTexture();

		if (colorTexture == null || colorTexture.isClosed()) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		if ((colorTexture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
			Iris.logger.warn("Cannot run native Vulkan screen passes because the main color texture is not copyable.");
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		IrisVulkanGbufferTargets.ensureForFinalPass(encoder, colorTexture);

		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
		IndexType indexType = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();
		GpuTextureView depthView = main.getDepthTextureView();

		for (Pass screenPass : deferredPasses) {
			renderLogicalPassIfAvailable(encoder, screenPass, depthView, indices, indexType);
		}

		for (Pass screenPass : compositePasses) {
			renderLogicalPassIfAvailable(encoder, screenPass, depthView, indices, indexType);
		}

		boolean renderedFinal = finalPass != null && renderFinalPassIfAvailable(encoder, finalPass,
			main.getColorTextureView(), depthView, indices, indexType);

		if (!renderedFinal && (colorTexture.usage() & GpuTexture.USAGE_COPY_DST) != 0) {
			GpuTexture source = IrisVulkanGbufferTargets.currentTexture(FINAL_SOURCE_TARGET);
			encoder.copyTextureToTexture(source, colorTexture, 0, 0, 0, 0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
		}

		IrisVulkanGbufferTargets.finishFrame();
	}

	public void destroy() {
		for (Pass pass : deferredPasses) {
			IrisNativeVulkan.unregisterCustomPipelineSource(pass.pipeline());
		}

		for (Pass pass : compositePasses) {
			IrisNativeVulkan.unregisterCustomPipelineSource(pass.pipeline());
		}

		if (finalPass != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(finalPass.pipeline());
		}

	}

	public boolean hasRunnablePasses() {
		return finalPass != null;
	}

	private static List<Pass> createPasses(ProgramSet programSet, ProgramArrayId programArrayId,
										   TextureStage stage, String namespace) {
		List<Pass> passes = new ArrayList<>();
		ProgramSource[] sources = programSet.getComposite(programArrayId);

		for (int i = 0; i < sources.length; i++) {
			ProgramSource source = sources[i];

			if (source == null || !source.isValid()) {
				continue;
			}

			Pass pass = createPass(programSet, source, stage, namespace + "/" + i, false);

			if (pass != null) {
				passes.add(pass);
			}
		}

		return List.copyOf(passes);
	}

	private static Pass createFinalPass(ProgramSet programSet) {
		return programSet.get(ProgramId.Final)
			.map(source -> createPass(programSet, source, TextureStage.COMPOSITE_AND_FINAL, "final", true))
			.orElse(null);
	}

	private static Pass createPass(ProgramSet programSet, ProgramSource source, TextureStage stage,
								   String namespace, boolean collapseOutputs) {
		if (source.getGeometrySource().isPresent()) {
			Iris.logger.warn("Native Vulkan screen passes do not support geometry shaders yet; skipping {}.",
				source.getName());
			return null;
		}

		Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(
			source.getName(),
			source.getVertexSource().orElseThrow(NullPointerException::new),
			null,
			source.getFragmentSource().orElseThrow(NullPointerException::new),
			stage,
			programSet.getPackDirectives().getTextureMap());

		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		if (usesUnsupportedSamplerType(fragment)) {
			Iris.logger.warn("Native Vulkan screen pass {} uses unsupported non-2D sampler types; skipping it for now.",
				source.getName());
			return null;
		}

		List<String> unsupportedSamplers = unsupportedSamplers(fragment);
		if (!unsupportedSamplers.isEmpty()) {
			Iris.logger.warn("Native Vulkan screen pass {} needs unsupported texture sampler(s) {}; skipping it for now.",
				source.getName(), unsupportedSamplers);
			return null;
		}

		int[] drawBuffers = collapseOutputs ? new int[] { FALLBACK_SCENE_TARGET } : drawBuffers(fragment);

		if (!collapseOutputs && drawBuffers.length == 0) {
			drawBuffers = inferDrawBuffers(fragment);
		}

		if (!collapseOutputs && drawBuffers.length == 0) {
			Iris.logger.warn("Native Vulkan screen pass {} has no color outputs; skipping it.", source.getName());
			return null;
		}

		String safeName = sanitize(namespace + "/" + source.getName());
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + safeName))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS);

		int attachmentCount = collapseOutputs ? 1 : drawBuffers.length;
		for (int i = 0; i < attachmentCount; i++) {
			builder.withColorTargetState(i, ColorTargetState.DEFAULT);
		}

		RenderPipeline pipeline = builder.build();
		IrisNativeVulkan.registerCustomPipelineSource(pipeline, safeName,
			transformed.get(PatchShaderType.VERTEX), fragment, collapseOutputs);
		return new Pass(source.getName(), pipeline, drawBuffers);
	}

	private static boolean usesUnsupportedSamplerType(String fragment) {
		return fragment.contains("sampler3D")
			|| fragment.contains("sampler1D")
			|| fragment.contains("sampler2DRect")
			|| fragment.contains("sampler2DArray")
			|| fragment.contains("samplerCube")
			|| fragment.contains("samplerBuffer")
			|| fragment.contains("sampler2DShadow");
	}

	private static List<String> unsupportedSamplers(String fragment) {
		Matcher matcher = SAMPLER.matcher(fragment);
		List<String> unsupported = new ArrayList<>();

		while (matcher.find()) {
			String type = matcher.group(1);
			String name = matcher.group(2);

			if (!isSupportedSamplerType(type) || !isSupportedSamplerName(name)) {
				unsupported.add(name);
			}
		}

		return unsupported;
	}

	private static boolean isSupportedSamplerType(String type) {
		return type.equals("sampler2D") || type.equals("isampler2D") || type.equals("usampler2D");
	}

	private static boolean isSupportedSamplerName(String name) {
		if (name.startsWith("colortex")) {
			try {
				int index = Integer.parseInt(name.substring("colortex".length()));
				return index >= 0 && index < COLOR_TARGET_COUNT;
			} catch (NumberFormatException ignored) {
				return false;
			}
		}

		return switch (name) {
			case "InSampler", "Sampler0", "u_MainSampler", "texture", "tex", "composite",
				 "gcolor", "gdepth", "gnormal", "gaux1", "gaux2", "gaux3", "gaux4",
				 "depthtex0", "depthtex1", "depthtex2", "gdepthtex" -> true;
			default -> false;
		};
	}

	private void renderLogicalPassIfAvailable(CommandEncoder encoder, Pass screenPass, GpuTextureView depthView,
											  GpuBuffer indices, IndexType indexType) {
		if (failedPasses.contains(screenPass)) {
			return;
		}

		try {
			renderLogicalPass(encoder, screenPass, depthView, indices, indexType);
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Skipping native Vulkan screen pass {} after an error: {}", screenPass.name(), e.getMessage());
		}
	}

	private boolean renderFinalPassIfAvailable(CommandEncoder encoder, Pass screenPass, GpuTextureView outputView,
											   GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		if (failedPasses.contains(screenPass)) {
			return false;
		}

		try {
			renderFinalPass(encoder, screenPass, outputView, depthView, indices, indexType);
			return true;
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Skipping native Vulkan final pass {} after an error: {}", screenPass.name(), e.getMessage());
			return false;
		}
	}

	private void renderLogicalPass(CommandEncoder encoder, Pass screenPass, GpuTextureView depthView,
								   GpuBuffer indices, IndexType indexType) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris native Vulkan " + screenPass.name());
		GpuTextureView firstOutputView = null;

		for (int logicalTarget : screenPass.drawBuffers()) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(logicalTarget);
			if (firstOutputView == null) {
				firstOutputView = outputView;
			}

			descriptor.withColorAttachment(outputView);
		}

		if (firstOutputView != null) {
			descriptor.withRenderArea(new RenderArea(0, 0, firstOutputView.getWidth(0), firstOutputView.getHeight(0)));
		}

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(screenPass.pipeline());
			RenderSystem.bindDefaultUniforms(pass);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}

		for (int logicalTarget : screenPass.drawBuffers()) {
			IrisVulkanGbufferTargets.swap(logicalTarget);
		}
	}

	private void renderFinalPass(CommandEncoder encoder, Pass screenPass, GpuTextureView outputView,
								 GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		try (RenderPass pass = encoder.createRenderPass(() -> "Iris native Vulkan " + screenPass.name(), outputView, java.util.Optional.empty())) {
			pass.setPipeline(screenPass.pipeline());
			RenderSystem.bindDefaultUniforms(pass);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	private static int[] drawBuffers(String fragment) {
		Matcher matcher = DRAWBUFFERS.matcher(fragment);
		String value = null;

		while (matcher.find()) {
			value = matcher.group(1);
		}

		if (value == null || value.isBlank()) {
			return new int[0];
		}

		int[] targets = new int[value.length()];
		int count = 0;

		for (int i = 0; i < value.length(); i++) {
			int target = Character.digit(value.charAt(i), 10);

			if (target >= 0 && target < COLOR_TARGET_COUNT) {
				targets[count++] = target;
			}
		}

		if (count == targets.length) {
			return targets;
		}

		int[] trimmed = new int[count];
		System.arraycopy(targets, 0, trimmed, 0, count);
		return trimmed;
	}

	private static int[] inferDrawBuffers(String fragment) {
		List<String> outputs = outputNames(fragment);

		if (outputs.isEmpty()) {
			return new int[0];
		}

		if (outputs.contains("velocityData") && outputs.contains("temporalData")) {
			return new int[] { 2, 5 };
		}

		if (outputs.contains("fogData") && outputs.contains("sceneData")) {
			return new int[] { 1, 4 };
		}

		if (outputs.contains("specularData") && outputs.contains("sceneData")) {
			return new int[] { 0, 4 };
		}

		String first = outputs.getFirst();
		int target = switch (first) {
			case "sceneColor" -> 3;
			case "sceneData", "bloomTiles" -> 4;
			case "reflectionData", "blurColor", "velocityData" -> 2;
			case "fogData" -> 1;
			default -> 0;
		};

		return new int[] { target };
	}

	private static List<String> outputNames(String fragment) {
		Matcher matcher = OUTPUT.matcher(fragment);
		List<String> outputs = new ArrayList<>();

		while (matcher.find()) {
			outputs.add(matcher.group(1));
		}

		return outputs;
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
	}

	private record Pass(String name, RenderPipeline pipeline, int[] drawBuffers) {
	}
}
