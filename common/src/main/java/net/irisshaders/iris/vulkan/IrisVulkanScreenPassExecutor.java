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
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVulkanScreenPassExecutor {
	private static final int FINAL_SOURCE_TARGET = IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET;
	private static final String DIAGNOSTIC_COPY_LABEL = "diagnostic/copy";
	private static final String PACK_VERTEX_COPY_FRAGMENT_LABEL = "diagnostic/pack_vertex_copy_fragment";
	private static final String COPY_VERTEX_PACK_FRAGMENT_LABEL = "diagnostic/copy_vertex_pack_fragment";
	private static final String COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION_LABEL = "diagnostic/copy_vertex_copy_fragment_final_version";
	private static final String COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION_LABEL = "diagnostic/copy_vertex_copy_fragment_core_version";
	private static final String COPY_VERTEX_FINAL_CONSTANT_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_constant_fragment";
	private static final String COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_texture_150_fragment";
	private static final String COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_texture_core_fragment";
	private static final String COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_texelfetch_fragment";
	private static final String COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_texelfetch_raw_fragment";
	private static final String COPY_VERTEX_FINAL_TEXTURE_FRAGMENT_LABEL = "diagnostic/copy_vertex_final_texture_fragment";
	private static final Pattern FRAGMENT_INPUT = Pattern.compile("(?m)^\\s*((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*)in\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^]]+])?\\s*;");
	private static final String DIAGNOSTIC_COPY_VERTEX = """
		#version 150

		in vec3 Position;
		in vec2 UV0;

		out vec2 iris_texCoord;

		void main() {
			iris_texCoord = UV0;
			gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	private static final String DIAGNOSTIC_COPY_FRAGMENT_BODY = """
		
		uniform sampler2D colortex3;
		
		out vec4 iris_fragColor;
		
		void main() {
			vec2 iris_texCoord = gl_FragCoord.xy / vec2(textureSize(colortex3, 0));
			iris_fragColor = texture(colortex3, iris_texCoord);
		}
		""";

	private final IrisVulkanScreenPassGraph graph;
	private final IrisNativeVulkan.ScreenPassMode mode;
	private final IrisNativeVulkan.ScreenPassDrawMode drawMode;
	private final String selectedPass;
	private final Set<IrisVulkanScreenPassGraph.Node> failedPasses = new HashSet<>();
	private final Set<IrisVulkanScreenPassGraph.Node> preflightedPasses = new HashSet<>();
	private final Set<String> loggedDrawAttempts = new HashSet<>();
	private RenderPipeline diagnosticCopyPipeline;
	private RenderPipeline packVertexCopyFragmentPipeline;
	private RenderPipeline copyVertexPackFragmentPipeline;
	private RenderPipeline copyVertexCopyFragmentFinalVersionPipeline;
	private RenderPipeline copyVertexCopyFragmentCoreVersionPipeline;
	private RenderPipeline copyVertexFinalConstantFragmentPipeline;
	private RenderPipeline copyVertexFinalTexture150FragmentPipeline;
	private RenderPipeline copyVertexFinalTextureCoreFragmentPipeline;
	private RenderPipeline copyVertexFinalTexelFetchFragmentPipeline;
	private RenderPipeline copyVertexFinalTexelFetchRawFragmentPipeline;
	private RenderPipeline copyVertexFinalTextureFragmentPipeline;
	private boolean loggedBuildOnlyFrame;
	private boolean loggedDrawDisabledFrame;
	private boolean loggedDiagnosticCopyFrame;
	private boolean loggedPackVertexCopyFragmentFrame;
	private boolean loggedCopyVertexPackFragmentFrame;
	private boolean loggedCopyVertexCopyFragmentFinalVersionFrame;
	private boolean loggedCopyVertexCopyFragmentCoreVersionFrame;
	private boolean loggedCopyVertexFinalConstantFragmentFrame;
	private boolean loggedCopyVertexFinalTexture150FragmentFrame;
	private boolean loggedCopyVertexFinalTextureCoreFragmentFrame;
	private boolean loggedCopyVertexFinalTexelFetchFragmentFrame;
	private boolean loggedCopyVertexFinalTexelFetchRawFragmentFrame;
	private boolean loggedCopyVertexFinalTextureFragmentFrame;
	private boolean loggedDirectFinalSourceFrame;
	private boolean loggedFallbackFinalSourceFrame;
	private boolean preflightComplete;

	public IrisVulkanScreenPassExecutor(IrisVulkanScreenPassGraph graph, IrisNativeVulkan.ScreenPassMode mode,
										String selectedPass) {
		this.graph = graph;
		this.mode = mode;
		this.drawMode = IrisNativeVulkan.screenPassDrawMode();
		this.selectedPass = selectedPass == null || selectedPass.isBlank()
			? null
			: selectedPass.trim().toLowerCase(Locale.ROOT);
	}

	public void destroy() {
		if (diagnosticCopyPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(diagnosticCopyPipeline);
			diagnosticCopyPipeline = null;
		}

		if (packVertexCopyFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(packVertexCopyFragmentPipeline);
			packVertexCopyFragmentPipeline = null;
		}

		if (copyVertexPackFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexPackFragmentPipeline);
			copyVertexPackFragmentPipeline = null;
		}

		if (copyVertexCopyFragmentFinalVersionPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexCopyFragmentFinalVersionPipeline);
			copyVertexCopyFragmentFinalVersionPipeline = null;
		}

		if (copyVertexCopyFragmentCoreVersionPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexCopyFragmentCoreVersionPipeline);
			copyVertexCopyFragmentCoreVersionPipeline = null;
		}

		if (copyVertexFinalConstantFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalConstantFragmentPipeline);
			copyVertexFinalConstantFragmentPipeline = null;
		}

		if (copyVertexFinalTexture150FragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalTexture150FragmentPipeline);
			copyVertexFinalTexture150FragmentPipeline = null;
		}

		if (copyVertexFinalTextureCoreFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalTextureCoreFragmentPipeline);
			copyVertexFinalTextureCoreFragmentPipeline = null;
		}

		if (copyVertexFinalTexelFetchFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalTexelFetchFragmentPipeline);
			copyVertexFinalTexelFetchFragmentPipeline = null;
		}

		if (copyVertexFinalTexelFetchRawFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalTexelFetchRawFragmentPipeline);
			copyVertexFinalTexelFetchRawFragmentPipeline = null;
		}

		if (copyVertexFinalTextureFragmentPipeline != null) {
			IrisNativeVulkan.unregisterCustomPipelineSource(copyVertexFinalTextureFragmentPipeline);
			copyVertexFinalTextureFragmentPipeline = null;
		}
	}

	public void render() {
		if (!graph.hasRunnablePasses()) {
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
		GpuTextureView depthView = main.getDepthTextureView();
		GpuTextureView finalSourceView = finalSourceViewForMode(main, colorTexture);
		logFinalSourceViewOnce(finalSourceView);

		if (!mode.runsFinalPass()) {
			preflightScreenPasses(encoder, depthView);

			if (!loggedBuildOnlyFrame) {
				loggedBuildOnlyFrame = true;
				Iris.logger.info("Native Vulkan screen pass graph is in {} mode; pipelines were preflighted without drawing.",
					mode.name().toLowerCase(Locale.ROOT));
			}

			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		if (!drawMode.draws()) {
			preflightScreenPasses(encoder, depthView);

			if (!loggedDrawDisabledFrame) {
				loggedDrawDisabledFrame = true;
				Iris.logger.info("Native Vulkan screen pass graph is in {} mode, but shaderpack screen pass drawing is disabled. Set -Diris.vulkan.drawShaderpackScreenPasses=true to execute selected screen pass draws.",
					mode.name().toLowerCase(Locale.ROOT));
			}

			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
		IndexType indexType = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY) {
			preflightScreenPasses(encoder, depthView);

			boolean renderedCopy = matchesDiagnosticCopySelection() && finalSourceView != null;
			if (renderedCopy) {
				try {
					renderDiagnosticCopyPass(encoder, depthView, finalSourceView, indices, indexType);
					IrisVulkanGbufferTargets.swap(FINAL_SOURCE_TARGET);
				} catch (RuntimeException e) {
					renderedCopy = false;
					Iris.logger.warn("Skipping native Vulkan diagnostic copy after an error: {}", e.getMessage());
				}
			}

			if (renderedCopy) {
				copyFinalSourceToMain(encoder, colorTexture);
			}
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.PACK_VERTEX_COPY_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_PACK_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_CONSTANT_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_FRAGMENT) {
			preflightScreenPasses(encoder, depthView);

			boolean finalPreFlipped = hasExecutableFinalPass(finalSourceView);
			if (finalPreFlipped) {
				IrisVulkanGbufferTargets.applyExplicitFlips("final_pre");
			}
			boolean renderedFinal = renderDiagnosticFinalVariantIfAvailable(encoder, depthView, finalSourceView, indices, indexType);

			if (renderedFinal) {
				IrisVulkanGbufferTargets.swap(FINAL_SOURCE_TARGET);
			} else if (finalPreFlipped) {
				IrisVulkanGbufferTargets.applyExplicitFlips("final_pre");
			}

			if (renderedFinal) {
				copyFinalSourceToMain(encoder, colorTexture);
			}
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		boolean renderedLogicalColor = false;
		if (mode.runsLogicalPasses()) {
			LogicalStageResult deferred = renderLogicalStage(encoder, graph.deferredPasses(), "deferred_pre",
				depthView, indices, indexType);
			LogicalStageResult composite = renderLogicalStage(encoder, graph.compositePasses(), "composite_pre",
				depthView, indices, indexType);
			renderedLogicalColor = deferred.renderedColor() || composite.renderedColor();
		}

		boolean finalPreFlipped = hasExecutableFinalPass(finalSourceView);
		if (finalPreFlipped) {
			IrisVulkanGbufferTargets.applyExplicitFlips("final_pre");
		}
		boolean renderedFinal = renderSelectedFinalPassIfAvailable(encoder, depthView, finalSourceView, indices, indexType);

		if (renderedFinal) {
			IrisVulkanGbufferTargets.swap(FINAL_SOURCE_TARGET);
		} else if (finalPreFlipped) {
			IrisVulkanGbufferTargets.applyExplicitFlips("final_pre");
		}

		if (renderedFinal) {
			copyTargetToMain(encoder, colorTexture, FINAL_SOURCE_TARGET);
		} else if (graph.finalPass() == null && renderedLogicalColor) {
			copyTargetToMain(encoder, colorTexture, 0);
		}

		IrisVulkanGbufferTargets.finishFrame();
	}

	public boolean requiresGbufferCapture() {
		if (!IrisNativeVulkan.drawShaderpackScreenPasses()) {
			return false;
		}

		if (!mode.runsLogicalPasses()) {
			return false;
		}

		for (IrisVulkanScreenPassGraph.Node node : graph.logicalPasses()) {
			if (node.ready() && matchesSelection(node)) {
				return true;
			}
		}

		return false;
	}

	private void preflightScreenPasses(CommandEncoder encoder, GpuTextureView depthView) {
		if (preflightComplete) {
			return;
		}

		for (IrisVulkanScreenPassGraph.Node screenPass : graph.nodes()) {
			preflightPass(encoder, screenPass, depthView);
		}

		preflightComplete = true;
	}

	private void preflightPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
							   GpuTextureView depthView) {
		if (!screenPass.ready() || failedPasses.contains(screenPass) || preflightedPasses.contains(screenPass)) {
			return;
		}

		try (RenderPass pass = createPreflightRenderPass(encoder, screenPass)) {
			pass.setPipeline(screenPass.pipeline());
			preflightedPasses.add(screenPass);
			Iris.logger.info("Preflighted native Vulkan screen pass {}.", screenPass.label());
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Failed to preflight native Vulkan screen pass {}: {}", screenPass.label(), e.getMessage());
		}
	}

	private RenderPass createPreflightRenderPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass) {
		if (screenPass.kind() == IrisVulkanScreenPassGraph.Kind.FINAL) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(FINAL_SOURCE_TARGET);
			return encoder.createRenderPass(() -> "Iris native Vulkan preflight " + screenPass.label(),
				outputView, java.util.Optional.empty());
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris native Vulkan preflight " + screenPass.label());
		GpuTextureView firstOutputView = null;

		for (int logicalTarget : screenPass.drawBuffers()) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(logicalTarget);

			if (firstOutputView == null) {
				firstOutputView = outputView;
			}

			descriptor.withColorAttachment(outputView);
		}

		if (firstOutputView != null) {
			descriptor.withRenderArea(renderArea(screenPass, firstOutputView));
		}

		return encoder.createRenderPass(descriptor);
	}

	private boolean renderLogicalPassIfAvailable(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
															  GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		if (!screenPass.ready() || failedPasses.contains(screenPass) || !matchesSelection(screenPass)) {
			return false;
		}

		try {
			renderLogicalPass(encoder, screenPass, depthView, indices, indexType);
			return true;
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Skipping native Vulkan screen pass {} after an error: {}", screenPass.label(), e.getMessage());
			return false;
		}
	}

	private LogicalStageResult renderLogicalStage(CommandEncoder encoder,
														 List<IrisVulkanScreenPassGraph.Node> passes, String preFlipPass,
														 GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		if (!hasExecutablePass(passes)) {
			return new LogicalStageResult(false, false);
		}

		IrisVulkanGbufferTargets.applyExplicitFlips(preFlipPass);
		boolean rendered = false;
		boolean renderedColor = false;
		for (IrisVulkanScreenPassGraph.Node screenPass : passes) {
			if (renderLogicalPassIfAvailable(encoder, screenPass, depthView, indices, indexType)) {
				rendered = true;
				if (writesTarget(screenPass, 0)) {
					renderedColor = true;
				}
			}
		}

		if (!rendered) {
			// A stage that failed completely must not leave its pre-flips applied.
			IrisVulkanGbufferTargets.applyExplicitFlips(preFlipPass);
		}

		return new LogicalStageResult(rendered, renderedColor);
	}

	private boolean hasExecutablePass(List<IrisVulkanScreenPassGraph.Node> passes) {
		for (IrisVulkanScreenPassGraph.Node screenPass : passes) {
			if (screenPass.ready() && !failedPasses.contains(screenPass) && matchesSelection(screenPass)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasExecutableFinalPass(GpuTextureView finalSourceView) {
		IrisVulkanScreenPassGraph.Node finalPass = graph.finalPass();
		return finalSourceView != null && finalPass != null && finalPass.ready()
			&& !failedPasses.contains(finalPass) && matchesSelection(finalPass);
	}

	private boolean renderSelectedFinalPassIfAvailable(CommandEncoder encoder, GpuTextureView depthView,
													   GpuTextureView finalSourceView, GpuBuffer indices,
													   IndexType indexType) {
		IrisVulkanScreenPassGraph.Node finalPass = graph.finalPass();

		if (finalPass == null || !finalPass.ready() || failedPasses.contains(finalPass) || !matchesSelection(finalPass)) {
			return false;
		}
		if (finalSourceView == null) {
			Iris.logger.warn("Skipping native Vulkan final pass {} because no current-frame main-color source is available.", finalPass.label());
			return false;
		}

		try {
			renderFinalPass(encoder, finalPass, depthView, finalSourceView, indices, indexType);
			return true;
		} catch (RuntimeException e) {
			failedPasses.add(finalPass);
			Iris.logger.warn("Skipping native Vulkan final pass {} after an error: {}", finalPass.label(), e.getMessage());
			return false;
		}
	}

	private boolean renderDiagnosticFinalVariantIfAvailable(CommandEncoder encoder, GpuTextureView depthView,
															GpuTextureView finalSourceView, GpuBuffer indices,
															IndexType indexType) {
		IrisVulkanScreenPassGraph.Node finalPass = graph.finalPass();

		if (finalPass == null || !finalPass.ready() || failedPasses.contains(finalPass) || !matchesSelection(finalPass)) {
			return false;
		}

		if (finalSourceView == null) {
			Iris.logger.warn("Skipping native Vulkan diagnostic final pass {} because no current-frame main-color source is available.", diagnosticLabel());
			return false;
		}

		try {
			RenderPipeline pipeline = switch (drawMode) {
				case PACK_VERTEX_COPY_FRAGMENT -> packVertexCopyFragmentPipeline(finalPass);
				case COPY_VERTEX_PACK_FRAGMENT -> copyVertexPackFragmentPipeline(finalPass);
				case COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION -> copyVertexCopyFragmentFinalVersionPipeline(finalPass);
				case COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION -> copyVertexCopyFragmentCoreVersionPipeline(finalPass);
				case COPY_VERTEX_FINAL_CONSTANT_FRAGMENT -> copyVertexFinalConstantFragmentPipeline(finalPass);
				case COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT -> copyVertexFinalTexture150FragmentPipeline(finalPass);
				case COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT -> copyVertexFinalTextureCoreFragmentPipeline(finalPass);
				case COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT -> copyVertexFinalTexelFetchFragmentPipeline(finalPass);
				case COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT -> copyVertexFinalTexelFetchRawFragmentPipeline(finalPass);
				case COPY_VERTEX_FINAL_TEXTURE_FRAGMENT -> copyVertexFinalTextureFragmentPipeline(finalPass);
				default -> null;
			};

			if (pipeline == null) {
				return false;
			}

			renderFinalPassWithPipeline(encoder, finalPass, pipeline, diagnosticLabel(), depthView, finalSourceView,
				indices, indexType);
			logDiagnosticVariantOnce();
			return true;
		} catch (RuntimeException e) {
			failedPasses.add(finalPass);
			Iris.logger.warn("Skipping native Vulkan diagnostic final pass {} after an error: {}",
				diagnosticLabel(), e.getMessage());
			return false;
		}
	}

	private void renderLogicalPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
								   GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris native Vulkan " + screenPass.label());
		GpuTextureView firstOutputView = null;

		for (int logicalTarget : screenPass.drawBuffers()) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(logicalTarget);

			if (firstOutputView == null) {
				firstOutputView = outputView;
			}

			descriptor.withColorAttachment(outputView);
		}

		if (firstOutputView != null) {
			descriptor.withRenderArea(renderArea(screenPass, firstOutputView));
		}

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			bindPass(screenPass, pass, depthView);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}

		applyPassFlips(screenPass);
	}

	private void renderFinalPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
								 GpuTextureView depthView, GpuTextureView finalSourceView, GpuBuffer indices,
								 IndexType indexType) {
		renderFinalPassWithPipeline(encoder, screenPass, screenPass.pipeline(), screenPass.label(),
			depthView, finalSourceView, indices, indexType);
	}

	private void renderFinalPassWithPipeline(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
											 RenderPipeline pipeline, String passLabel, GpuTextureView depthView,
											 GpuTextureView finalSourceView, GpuBuffer indices, IndexType indexType) {
		GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(FINAL_SOURCE_TARGET);

		try (RenderPass pass = encoder.createRenderPass(() -> "Iris native Vulkan " + passLabel,
			outputView, java.util.Optional.empty())) {
			pass.setPipeline(pipeline);
			IrisVulkanRenderPassBindings.prepareScreenPassResources(pass, passLabel, stageFor(screenPass.kind()),
				depthView, finalSourceView);
			logDrawAttempt(passLabel, pipeline);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	private void renderDiagnosticCopyPass(CommandEncoder encoder, GpuTextureView depthView,
										  GpuTextureView finalSourceView, GpuBuffer indices, IndexType indexType) {
		RenderPipeline pipeline = diagnosticCopyPipeline();
		GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(FINAL_SOURCE_TARGET);

		try (RenderPass pass = encoder.createRenderPass(() -> "Iris native Vulkan " + DIAGNOSTIC_COPY_LABEL,
			outputView, java.util.Optional.empty())) {
			pass.setPipeline(pipeline);
			IrisVulkanRenderPassBindings.prepareScreenPassResources(pass, DIAGNOSTIC_COPY_LABEL,
				TextureStage.COMPOSITE_AND_FINAL, depthView, finalSourceView);
			logDrawAttempt(DIAGNOSTIC_COPY_LABEL, pipeline);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}

		if (!loggedDiagnosticCopyFrame) {
			loggedDiagnosticCopyFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic copy screen pass.");
		}
	}

	private RenderPipeline packVertexCopyFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (packVertexCopyFragmentPipeline == null) {
			packVertexCopyFragmentPipeline = createDiagnosticPipeline(PACK_VERTEX_COPY_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(packVertexCopyFragmentPipeline, PACK_VERTEX_COPY_FRAGMENT_LABEL,
				finalPass.vertexSource(), diagnosticCopyFragmentFor(finalPass.fragmentSource()), true);
		}

		return packVertexCopyFragmentPipeline;
	}

	private RenderPipeline copyVertexPackFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexPackFragmentPipeline == null) {
			copyVertexPackFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_PACK_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexPackFragmentPipeline, COPY_VERTEX_PACK_FRAGMENT_LABEL,
				copyVertexForFragment(finalPass.fragmentSource()), finalPass.fragmentSource(), true);
		}

		return copyVertexPackFragmentPipeline;
	}

	private RenderPipeline copyVertexCopyFragmentFinalVersionPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexCopyFragmentFinalVersionPipeline == null) {
			copyVertexCopyFragmentFinalVersionPipeline = createDiagnosticPipeline(COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexCopyFragmentFinalVersionPipeline,
				COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION_LABEL, copyVertexForFragment(finalPass.fragmentSource()),
				diagnosticCopyFragmentFor(finalPass.fragmentSource()), true);
		}

		return copyVertexCopyFragmentFinalVersionPipeline;
	}

	private RenderPipeline copyVertexCopyFragmentCoreVersionPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexCopyFragmentCoreVersionPipeline == null) {
			copyVertexCopyFragmentCoreVersionPipeline = createDiagnosticPipeline(COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexCopyFragmentCoreVersionPipeline,
				COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION_LABEL, copyVertexForCoreFragment(finalPass.fragmentSource()),
				diagnosticCopyCoreFragment(), true);
		}

		return copyVertexCopyFragmentCoreVersionPipeline;
	}

	private RenderPipeline copyVertexFinalConstantFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalConstantFragmentPipeline == null) {
			copyVertexFinalConstantFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_CONSTANT_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalConstantFragmentPipeline,
				COPY_VERTEX_FINAL_CONSTANT_FRAGMENT_LABEL, copyVertexForFragment(finalPass.fragmentSource()),
				finalConstantFragmentFor(finalPass.fragmentSource()), true);
		}

		return copyVertexFinalConstantFragmentPipeline;
	}

	private RenderPipeline copyVertexFinalTexture150FragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalTexture150FragmentPipeline == null) {
			copyVertexFinalTexture150FragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalTexture150FragmentPipeline,
				COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT_LABEL, DIAGNOSTIC_COPY_VERTEX, finalTexture150Fragment(), true);
		}

		return copyVertexFinalTexture150FragmentPipeline;
	}

	private RenderPipeline copyVertexFinalTextureCoreFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalTextureCoreFragmentPipeline == null) {
			copyVertexFinalTextureCoreFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalTextureCoreFragmentPipeline,
				COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT_LABEL, copyVertexForCoreFragment(finalPass.fragmentSource()),
				finalTextureCoreFragment(), true);
		}

		return copyVertexFinalTextureCoreFragmentPipeline;
	}

	private RenderPipeline copyVertexFinalTexelFetchFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalTexelFetchFragmentPipeline == null) {
			copyVertexFinalTexelFetchFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalTexelFetchFragmentPipeline,
				COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT_LABEL, copyVertexForFragment(finalPass.fragmentSource()),
				finalTexelFetchFragmentFor(finalPass.fragmentSource()), true);
		}

		return copyVertexFinalTexelFetchFragmentPipeline;
	}

	private RenderPipeline copyVertexFinalTexelFetchRawFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalTexelFetchRawFragmentPipeline == null) {
			copyVertexFinalTexelFetchRawFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalTexelFetchRawFragmentPipeline,
				COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT_LABEL, copyVertexForFragment(finalPass.fragmentSource()),
				finalTexelFetchRawFragmentFor(finalPass.fragmentSource()), true);
		}

		return copyVertexFinalTexelFetchRawFragmentPipeline;
	}

	private RenderPipeline copyVertexFinalTextureFragmentPipeline(IrisVulkanScreenPassGraph.Node finalPass) {
		if (copyVertexFinalTextureFragmentPipeline == null) {
			copyVertexFinalTextureFragmentPipeline = createDiagnosticPipeline(COPY_VERTEX_FINAL_TEXTURE_FRAGMENT_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(copyVertexFinalTextureFragmentPipeline,
				COPY_VERTEX_FINAL_TEXTURE_FRAGMENT_LABEL, copyVertexForFragment(finalPass.fragmentSource()),
				finalTextureFragmentFor(finalPass.fragmentSource()), true);
		}

		return copyVertexFinalTextureFragmentPipeline;
	}

	private void bindPass(IrisVulkanScreenPassGraph.Node screenPass, RenderPass pass, GpuTextureView depthView) {
		pass.setPipeline(screenPass.pipeline());
		IrisVulkanRenderPassBindings.prepareScreenPassResources(pass, screenPass.label(),
			stageFor(screenPass.kind()), depthView);
	}

	private RenderPipeline diagnosticCopyPipeline() {
		if (diagnosticCopyPipeline == null) {
			diagnosticCopyPipeline = createDiagnosticPipeline(DIAGNOSTIC_COPY_LABEL);
			IrisNativeVulkan.registerCustomPipelineSource(diagnosticCopyPipeline, DIAGNOSTIC_COPY_LABEL,
				DIAGNOSTIC_COPY_VERTEX, "#version 150\n" + DIAGNOSTIC_COPY_FRAGMENT_BODY, true);
		}

		return diagnosticCopyPipeline;
	}

	private RenderPipeline createDiagnosticPipeline(String label) {
		return RenderPipeline.builder()
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + label))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(0, ColorTargetState.DEFAULT)
			.build();
	}

	private static String copyVertexForFragment(String fragmentSource) {
		return copyVertexForVersion(fragmentSource, versionDirective(fragmentSource), false);
	}

	private static String copyVertexForCoreFragment(String fragmentSource) {
		return copyVertexForVersion(fragmentSource, "#version 450 core\n", true);
	}

	private static String copyVertexForVersion(String fragmentSource, String versionDirective, boolean explicitLayout) {
		StringBuilder declarations = new StringBuilder();
		StringBuilder assignments = new StringBuilder();
		Matcher matcher = FRAGMENT_INPUT.matcher(fragmentSource == null ? "" : fragmentSource);
		Set<String> declared = new HashSet<>();
		int varyingLocation = 0;

		while (matcher.find()) {
			String qualifiers = matcher.group(1) == null ? "" : matcher.group(1);
			String type = matcher.group(2);
			String name = matcher.group(3);

			if (!declared.add(name)) {
				continue;
			}

			if (explicitLayout) {
				declarations.append("layout(location = ").append(varyingLocation++).append(") ");
			}

			declarations.append(qualifiers).append("out ").append(type).append(' ').append(name).append(";\n");
			assignments.append(name).append(" = ").append(defaultVaryingExpression(type)).append(";\n");
		}

		String positionDeclaration = explicitLayout
			? "layout(location = 0) in vec3 Position;"
			: "in vec3 Position;";
		String uvDeclaration = explicitLayout
			? "layout(location = 1) in vec2 UV0;"
			: "in vec2 UV0;";

		return (versionDirective + """
			
			%s
			%s

			%s
			void main() {
				vec2 iris_texCoord = UV0;
				%s
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""".formatted(positionDeclaration, uvDeclaration, declarations, assignments));
	}

	private static String diagnosticCopyFragmentFor(String fragmentSource) {
		return versionDirective(fragmentSource) + DIAGNOSTIC_COPY_FRAGMENT_BODY;
	}

	private static String diagnosticCopyCoreFragment() {
		return """
			#version 450 core

			uniform sampler2D colortex3;

			layout(location = 0) out vec4 iris_fragColor;

			void main() {
				vec2 iris_texCoord = gl_FragCoord.xy / vec2(textureSize(colortex3, 0));
				iris_fragColor = texture(colortex3, iris_texCoord);
			}
			""";
	}

	private static String finalConstantFragmentFor(String fragmentSource) {
		return versionDirective(fragmentSource) + """

			out vec3 finalData;

			void main() {
				finalData = vec3(1.0, 0.0, 1.0);
			}
			""";
	}

	private static String finalTexture150Fragment() {
		return """
			#version 150

			uniform sampler2D colortex3;

			out vec3 finalData;

			void main() {
				ivec2 size = max(textureSize(colortex3, 0), ivec2(1));
				vec2 texel = clamp(gl_FragCoord.xy, vec2(0.0), vec2(size - ivec2(1)));
				finalData = texture(colortex3, (texel + vec2(0.5)) / vec2(size)).rgb;
			}
			""";
	}

	private static String finalTextureCoreFragment() {
		return """
			#version 450 core

			uniform sampler2D colortex3;

			layout(location = 0) out vec3 finalData;

			void main() {
				ivec2 size = max(textureSize(colortex3, 0), ivec2(1));
				vec2 texel = clamp(gl_FragCoord.xy, vec2(0.0), vec2(size - ivec2(1)));
				finalData = texture(colortex3, (texel + vec2(0.5)) / vec2(size)).rgb;
			}
			""";
	}

	private static String finalTexelFetchFragmentFor(String fragmentSource) {
		return versionDirective(fragmentSource) + """

			uniform sampler2D colortex3;

			out vec3 finalData;

			void main() {
				ivec2 size = max(textureSize(colortex3, 0), ivec2(1));
				ivec2 texel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - ivec2(1));
				finalData = texelFetch(colortex3, texel, 0).rgb;
			}
			""";
	}

	private static String finalTexelFetchRawFragmentFor(String fragmentSource) {
		return versionDirective(fragmentSource) + """

			uniform sampler2D colortex3;

			out vec3 finalData;

			void main() {
				finalData = texelFetch(colortex3, ivec2(gl_FragCoord.xy), 0).rgb;
			}
			""";
	}

	private static String finalTextureFragmentFor(String fragmentSource) {
		return versionDirective(fragmentSource) + """

			uniform sampler2D colortex3;

			out vec3 finalData;

			void main() {
				ivec2 size = max(textureSize(colortex3, 0), ivec2(1));
				vec2 texel = clamp(gl_FragCoord.xy, vec2(0.0), vec2(size - ivec2(1)));
				finalData = texture(colortex3, (texel + vec2(0.5)) / vec2(size)).rgb;
			}
			""";
	}

	private static String versionDirective(String source) {
		if (source != null) {
			Matcher matcher = Pattern.compile("(?m)^\\s*#version\\s+[^\\r\\n]+").matcher(source);

			if (matcher.find()) {
				return matcher.group().trim() + "\n";
			}
		}

		return "#version 150\n";
	}

	private static String defaultVaryingExpression(String type) {
		return switch (type) {
			case "float" -> "0.0";
			case "vec2" -> "iris_texCoord";
			case "vec3" -> "vec3(iris_texCoord, 0.0)";
			case "vec4" -> "vec4(iris_texCoord, 0.0, 1.0)";
			case "int" -> "0";
			case "ivec2" -> "ivec2(0)";
			case "ivec3" -> "ivec3(0)";
			case "ivec4" -> "ivec4(0)";
			case "uint" -> "0u";
			case "uvec2" -> "uvec2(0u)";
			case "uvec3" -> "uvec3(0u)";
			case "uvec4" -> "uvec4(0u)";
			default -> type + "(0.0)";
		};
	}

	private void copyFinalSourceToMain(CommandEncoder encoder, GpuTexture colorTexture) {
		copyTargetToMain(encoder, colorTexture, FINAL_SOURCE_TARGET);
	}

	private void copyTargetToMain(CommandEncoder encoder, GpuTexture colorTexture, int target) {
		if (!IrisVulkanGbufferTargets.canCopyToMain(target, colorTexture)) {
			return;
		}

		GpuTexture source = IrisVulkanGbufferTargets.currentTexture(target);
		encoder.copyTextureToTexture(source, colorTexture, 0, 0, 0, 0, 0,
			colorTexture.getWidth(0), colorTexture.getHeight(0));
	}

	private static RenderArea renderArea(IrisVulkanScreenPassGraph.Node screenPass, GpuTextureView outputView) {
		int width = outputView.getWidth(0);
		int height = outputView.getHeight(0);
		float scale = Math.clamp(screenPass.viewport().scale(), 0.0f, 1.0f);
		int x = Math.clamp((int) (width * screenPass.viewport().viewportX()), 0, Math.max(0, width - 1));
		int y = Math.clamp((int) (height * screenPass.viewport().viewportY()), 0, Math.max(0, height - 1));
		int scaledWidth = Math.clamp((int) (width * scale), 1, width - x);
		int scaledHeight = Math.clamp((int) (height * scale), 1, height - y);
		return new RenderArea(x, y, scaledWidth, scaledHeight);
	}

	private static void applyPassFlips(IrisVulkanScreenPassGraph.Node screenPass) {
		// Match CompositeRenderer: a draw buffer flips by default, explicit false
		// suppresses that flip, and explicit true adds an independent flip.
		for (int logicalTarget : screenPass.drawBuffers()) {
			if (logicalTarget >= 0 && logicalTarget < IrisVulkanGbufferTargets.COLOR_TARGET_COUNT
				&& !Boolean.FALSE.equals(screenPass.explicitFlips().get(logicalTarget))) {
				IrisVulkanGbufferTargets.swap(logicalTarget);
			}
		}

		screenPass.explicitFlips().forEach((logicalTarget, shouldFlip) -> {
			if (Boolean.TRUE.equals(shouldFlip)
				&& logicalTarget >= 0 && logicalTarget < IrisVulkanGbufferTargets.COLOR_TARGET_COUNT) {
				IrisVulkanGbufferTargets.swap(logicalTarget);
			}
		});
	}

	private static GpuTextureView finalSourceView(com.mojang.blaze3d.pipeline.RenderTarget main, GpuTexture colorTexture) {
		if ((colorTexture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
			return null;
		}

		GpuTextureView view = main.getColorTextureView();
		return view != null && !view.isClosed() ? view : null;
	}

	private GpuTextureView finalSourceViewForMode(com.mojang.blaze3d.pipeline.RenderTarget main, GpuTexture colorTexture) {
		GpuTextureView liveMainView = usesIrisFinalSourceTarget() ? null : finalSourceView(main, colorTexture);
		if (liveMainView != null) {
			return liveMainView;
		}

		if (IrisVulkanGbufferTargets.hasCurrentFrameMainColorCopy()) {
			if (!loggedFallbackFinalSourceFrame) {
				loggedFallbackFinalSourceFrame = true;
				Iris.logger.info("Using the current-frame copied main color as native Vulkan final source for {}.",
					diagnosticLabel());
			}
			return IrisVulkanGbufferTargets.currentView(IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET);
		}

		if (!loggedFallbackFinalSourceFrame) {
			loggedFallbackFinalSourceFrame = true;
			Iris.logger.warn("No current-frame main-color copy is available for native Vulkan {}.",
				diagnosticLabel());
		}

		return null;
	}

	private boolean usesIrisFinalSourceTarget() {
		return drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION
			|| drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT;
	}

	private void logFinalSourceViewOnce(GpuTextureView finalSourceView) {
		if (!mode.runsFinalPass() || !drawMode.draws()) {
			return;
		}

		if (usesIrisFinalSourceTarget()) {
			return;
		}

		if (finalSourceView != null) {
			if (!loggedDirectFinalSourceFrame) {
				loggedDirectFinalSourceFrame = true;
				Iris.logger.info("Using main framebuffer color view as native Vulkan final source.");
			}
		} else if (!loggedFallbackFinalSourceFrame) {
			loggedFallbackFinalSourceFrame = true;
			Iris.logger.warn("Main framebuffer color view is not sampleable; using Iris final source target for native Vulkan final samplers.");
		}
	}

	private boolean matchesDiagnosticCopySelection() {
		return selectedPass == null
			|| selectedPass.equals("copy")
			|| selectedPass.equals(DIAGNOSTIC_COPY_LABEL)
			|| selectedPass.equals("diagnostic");
	}

	private static boolean writesTarget(IrisVulkanScreenPassGraph.Node screenPass, int target) {
		for (int drawBuffer : screenPass.drawBuffers()) {
			if (drawBuffer == target) {
				return true;
			}
		}
		return false;
	}

	private record LogicalStageResult(boolean rendered, boolean renderedColor) {
	}

	private String diagnosticLabel() {
		return switch (drawMode) {
			case PACK_VERTEX_COPY_FRAGMENT -> PACK_VERTEX_COPY_FRAGMENT_LABEL;
			case COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION -> COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION_LABEL;
			case COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION -> COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION_LABEL;
			case COPY_VERTEX_FINAL_CONSTANT_FRAGMENT -> COPY_VERTEX_FINAL_CONSTANT_FRAGMENT_LABEL;
			case COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT -> COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT_LABEL;
			case COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT -> COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT_LABEL;
			case COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT -> COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT_LABEL;
			case COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT -> COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT_LABEL;
			case COPY_VERTEX_FINAL_TEXTURE_FRAGMENT -> COPY_VERTEX_FINAL_TEXTURE_FRAGMENT_LABEL;
			default -> COPY_VERTEX_PACK_FRAGMENT_LABEL;
		};
	}

	private void logDiagnosticVariantOnce() {
		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.PACK_VERTEX_COPY_FRAGMENT && !loggedPackVertexCopyFragmentFrame) {
			loggedPackVertexCopyFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using shaderpack vertex and copy fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_PACK_FRAGMENT && !loggedCopyVertexPackFragmentFrame) {
			loggedCopyVertexPackFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and shaderpack fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION
			&& !loggedCopyVertexCopyFragmentFinalVersionFrame) {
			loggedCopyVertexCopyFragmentFinalVersionFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and final-version copy fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION
			&& !loggedCopyVertexCopyFragmentCoreVersionFrame) {
			loggedCopyVertexCopyFragmentCoreVersionFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and core 450 copy fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_CONSTANT_FRAGMENT
			&& !loggedCopyVertexFinalConstantFragmentFrame) {
			loggedCopyVertexFinalConstantFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and constant final fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT
			&& !loggedCopyVertexFinalTexture150FragmentFrame) {
			loggedCopyVertexFinalTexture150FragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and final-style texture 150 fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT
			&& !loggedCopyVertexFinalTextureCoreFragmentFrame) {
			loggedCopyVertexFinalTextureCoreFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and final-style core 450 texture fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT
			&& !loggedCopyVertexFinalTexelFetchFragmentFrame) {
			loggedCopyVertexFinalTexelFetchFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and clamped final texelFetch fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT
			&& !loggedCopyVertexFinalTexelFetchRawFragmentFrame) {
			loggedCopyVertexFinalTexelFetchRawFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and raw final texelFetch fragment.");
		}

		if (drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_FINAL_TEXTURE_FRAGMENT
			&& !loggedCopyVertexFinalTextureFragmentFrame) {
			loggedCopyVertexFinalTextureFragmentFrame = true;
			Iris.logger.info("Rendered native Vulkan diagnostic pass using copy vertex and final texture fragment.");
		}
	}

	private void logDrawAttempt(String passLabel, RenderPipeline pipeline) {
		if (!loggedDrawAttempts.add(passLabel)) {
			return;
		}

		Iris.logger.info("Drawing native Vulkan screen pass {} with pipeline {}, drawMode={}, samplers={}, dummySamplers={}.",
			passLabel, pipeline.getLocation(), drawMode.name().toLowerCase(Locale.ROOT),
			diagnosticUsesFinalFragmentSamplers(passLabel) ? graph.finalPass().samplers() : "<compiled>",
			IrisNativeVulkan.screenPassDummySamplers());
	}

	private boolean diagnosticUsesFinalFragmentSamplers(String passLabel) {
		return graph.finalPass() != null
			&& passLabel.equals(diagnosticLabel())
			&& drawMode == IrisNativeVulkan.ScreenPassDrawMode.COPY_VERTEX_PACK_FRAGMENT;
	}

	private static TextureStage stageFor(IrisVulkanScreenPassGraph.Kind kind) {
		return switch (kind) {
			case BEGIN -> TextureStage.BEGIN;
			case PREPARE -> TextureStage.PREPARE;
			case DEFERRED -> TextureStage.DEFERRED;
			case COMPOSITE, FINAL -> TextureStage.COMPOSITE_AND_FINAL;
		};
	}

	private boolean matchesSelection(IrisVulkanScreenPassGraph.Node node) {
		if (selectedPass == null) {
			return true;
		}

		return node.label().equalsIgnoreCase(selectedPass)
			|| node.sourceName().equalsIgnoreCase(selectedPass)
			|| node.kind().name().toLowerCase(Locale.ROOT).equals(selectedPass);
	}
}
