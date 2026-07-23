package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVulkanScreenPassPlanner {
	private static final int COLOR_TARGET_COUNT = IrisVulkanGbufferTargets.COLOR_TARGET_COUNT;
	private static final int FINAL_SOURCE_TARGET = IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET;
	private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+([iu]?sampler\\w+)\\s+(\\w+)\\s*((?:\\[[^\\]]*\\])+)?\\s*;");
	private static final Pattern IMAGE_UNIFORM = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:readonly|writeonly|coherent|volatile)\\s+)*([iu]?image\\w+)\\s+(\\w+)\\s*((?:\\[[^\\]]*\\])+)?\\s*;");
	private static final Pattern RESOURCE_BLOCK = Pattern.compile("(?ms)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*(uniform|buffer)\\s+([A-Za-z_]\\w*)\\s*\\{.*?}\\s*([A-Za-z_]\\w*)?\\s*((?:\\[[^\\]]*\\])+)?\\s*;");

	private IrisVulkanScreenPassPlanner() {
	}

	public static IrisVulkanScreenPassGraph create(ProgramSet programSet) {
		List<GpuFormat> targetFormats = IrisVulkanTargetFormat.resolveTargetFormats(
			programSet.getPackDirectives().getRenderTargetDirectives(), null, COLOR_TARGET_COUNT,
			IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET);
		List<IrisVulkanScreenPassGraph.Node> beginPasses = createPasses(programSet, ProgramArrayId.Begin,
			TextureStage.BEGIN, "begin", IrisVulkanScreenPassGraph.Kind.BEGIN, targetFormats);
		List<IrisVulkanScreenPassGraph.Node> preparePasses = createPasses(programSet, ProgramArrayId.Prepare,
			TextureStage.PREPARE, "prepare", IrisVulkanScreenPassGraph.Kind.PREPARE, targetFormats);
		List<IrisVulkanScreenPassGraph.Node> deferredPasses = createPasses(programSet, ProgramArrayId.Deferred,
			TextureStage.DEFERRED, "deferred", IrisVulkanScreenPassGraph.Kind.DEFERRED, targetFormats);
		List<IrisVulkanScreenPassGraph.Node> compositePasses = createPasses(programSet, ProgramArrayId.Composite,
			TextureStage.COMPOSITE_AND_FINAL, "composite", IrisVulkanScreenPassGraph.Kind.COMPOSITE, targetFormats);
		IrisVulkanScreenPassGraph.Node finalPass = createFinalPass(programSet, targetFormats);
		IrisVulkanScreenPassGraph graph = new IrisVulkanScreenPassGraph(beginPasses, preparePasses,
			deferredPasses, compositePasses, finalPass);

		log(graph);
		return graph;
	}

	private static List<IrisVulkanScreenPassGraph.Node> createPasses(ProgramSet programSet, ProgramArrayId programArrayId,
																						 TextureStage stage, String namespace,
																						 IrisVulkanScreenPassGraph.Kind kind, List<GpuFormat> targetFormats) {
		List<IrisVulkanScreenPassGraph.Node> passes = new ArrayList<>();
		ProgramSource[] sources = programSet.getComposite(programArrayId);

		for (int i = 0; i < sources.length; i++) {
			ProgramSource source = sources[i];

			if (source == null) {
				continue;
			}

			String passNamespace = namespace + "/" + i;
			if (!source.isValid()) {
				passes.add(skipped(kind, sanitize(passNamespace + "/" + source.getName()), source.getName(),
					List.of(), false, source.getDirectives(), "invalid program source or unsupported shader stage"));
			} else {
				passes.add(createPass(programSet, source, stage, passNamespace, kind, false, targetFormats));
			}
		}

		return List.copyOf(passes);
	}

	private static IrisVulkanScreenPassGraph.Node createFinalPass(ProgramSet programSet, List<GpuFormat> targetFormats) {
		return programSet.get(ProgramId.Final)
			.map(source -> createPass(programSet, source, TextureStage.COMPOSITE_AND_FINAL,
				"final", IrisVulkanScreenPassGraph.Kind.FINAL, true, targetFormats))
			.orElse(null);
	}

	private static IrisVulkanScreenPassGraph.Node createPass(ProgramSet programSet, ProgramSource source,
																						 TextureStage stage, String namespace,
																						 IrisVulkanScreenPassGraph.Kind kind,
																						 boolean collapseOutputs, List<GpuFormat> targetFormats) {
		String label = sanitize(namespace + "/" + source.getName());
		String sourceName = source.getName();
		ProgramDirectives directives = source.getDirectives();

		if (source.getGeometrySource().isPresent()) {
			return skipped(kind, label, sourceName, List.of(), collapseOutputs, directives,
				"geometry shaders are not supported yet");
		}

		Map<PatchShaderType, String> transformed;

		try {
			transformed = TransformPatcher.patchComposite(
				sourceName,
				source.getVertexSource().orElseThrow(NullPointerException::new),
				null,
				source.getFragmentSource().orElseThrow(NullPointerException::new),
				stage,
				programSet.getPackDirectives().getTextureMap());
		} catch (RuntimeException e) {
			return skipped(kind, label, sourceName, List.of(), collapseOutputs, directives,
				failureReason("transform failed", e));
		}

		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		List<String> samplers = samplerNames(fragment);

		List<String> unsupportedResources = unsupportedResources(fragment, programSet, stage);
		List<String> preflightReasons = new ArrayList<>();
		if (!unsupportedResources.isEmpty()) {
			preflightReasons.add("unsupported shader resource(s) " + unsupportedResources);
		}
		if (!directives.getMipmappedBuffers().isEmpty()) {
			preflightReasons.add("mipmap chain is not implemented for buffer(s) " + directives.getMipmappedBuffers());
		}
		if (!preflightReasons.isEmpty()) {
			return skipped(kind, label, sourceName, samplers, collapseOutputs, directives,
				String.join("; ", preflightReasons));
		}

		int[] configuredDrawBuffers = directives.getDrawBuffers();
		if (!collapseOutputs) {
			List<Integer> invalidDrawBuffers = invalidDrawBuffers(configuredDrawBuffers);
			if (!invalidDrawBuffers.isEmpty()) {
				return skipped(kind, label, sourceName, samplers, false, directives,
					"invalid draw buffer(s) " + invalidDrawBuffers + " in ProgramDirectives");
			}
		}

		int[] drawBuffers = collapseOutputs ? new int[] { FINAL_SOURCE_TARGET } : configuredDrawBuffers;

		if (!collapseOutputs && drawBuffers.length == 0) {
			return skipped(kind, label, sourceName, samplers, false, directives, "no color outputs in ProgramDirectives");
		}

		String vertex = transformed.get(PatchShaderType.VERTEX);
		IrisVulkanScreenPassGraph.PipelineHandle pipeline = new IrisVulkanScreenPassGraph.PipelineHandle(() ->
			createPipeline(label, drawBuffers, collapseOutputs, targetFormats, vertex, fragment));

		// Keep the directive metadata attached to the node. The executor owns when these
		// values take effect, including target flipping, viewport scaling, and mipmaps.
		return new IrisVulkanScreenPassGraph.Node(kind, label, sourceName, drawBuffers, samplers,
			directives.getExplicitFlips(), directives.getMipmappedBuffers(), directives.getViewportScale(),
			collapseOutputs, pipeline, vertex, fragment, IrisVulkanScreenPassGraph.Status.READY, "");
	}

	private static RenderPipeline createPipeline(String label, int[] drawBuffers, boolean collapseOutputs,
			List<GpuFormat> plannedTargetFormats, String vertex, String fragment) {
		// These passes never attach depth. Leaving the state unset makes Minecraft's
		// Vulkan compiler create the withoutDepthPipeline variant used by RenderPass.
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + label))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS);

		int attachmentCount = collapseOutputs ? 1 : drawBuffers.length;
		for (int attachment = 0; attachment < attachmentCount; attachment++) {
			int logicalTarget = drawBuffers[attachment];
			GpuFormat plannedFormat = plannedTargetFormats.get(logicalTarget);
			GpuFormat format = IrisVulkanGbufferTargets.effectiveFormat(logicalTarget, plannedFormat);
			builder.withColorTargetState(attachment,
				new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL));
		}

		RenderPipeline pipeline = builder.build();
		try {
			IrisNativeVulkan.registerCustomPipelineSource(pipeline, label, vertex, fragment, collapseOutputs);
			return pipeline;
		} catch (RuntimeException exception) {
			IrisNativeVulkan.unregisterCustomPipelineSource(pipeline);
			throw exception;
		}
	}

	private static IrisVulkanScreenPassGraph.Node skipped(IrisVulkanScreenPassGraph.Kind kind, String label,
															 String sourceName, List<String> samplers,
															 boolean collapseOutputs, ProgramDirectives directives, String reason) {
		int[] drawBuffers = directives == null ? new int[0] : directives.getDrawBuffers();
		return new IrisVulkanScreenPassGraph.Node(kind, label, sourceName, drawBuffers, samplers,
			directives == null ? Map.of() : directives.getExplicitFlips(),
			directives == null ? java.util.Set.of() : directives.getMipmappedBuffers(),
			directives == null ? null : directives.getViewportScale(),
			collapseOutputs, null, null, null, IrisVulkanScreenPassGraph.Status.SKIPPED, reason);
	}

	private static List<String> unsupportedResources(String fragment, ProgramSet programSet, TextureStage stage) {
		List<ResourceUse> resources = new ArrayList<>();
		Matcher matcher = SAMPLER.matcher(fragment);

		while (matcher.find()) {
			String type = matcher.group(1);
			String name = matcher.group(2);
			String array = matcher.group(3);

			if (array != null) {
				resources.add(new ResourceUse(matcher.start(), "sampler array " + name + " (" + type + ")"));
			} else if (!isSupportedSamplerType(type) || !isSupportedSamplerName(name, programSet, stage)) {
				resources.add(new ResourceUse(matcher.start(), "sampler " + name + " (" + type + ")"));
			}
		}

		Matcher imageMatcher = IMAGE_UNIFORM.matcher(fragment);
		while (imageMatcher.find()) {
			String type = imageMatcher.group(1);
			String name = imageMatcher.group(2);
			String array = imageMatcher.group(3);
			resources.add(new ResourceUse(imageMatcher.start(),
				"image uniform" + (array == null ? "" : " array") + " " + name + " (" + type + ")"));
		}

		Matcher blockMatcher = RESOURCE_BLOCK.matcher(fragment);
		while (blockMatcher.find()) {
			String kind = blockMatcher.group(1).equals("buffer") ? "storage block" : "uniform block";
			String blockType = blockMatcher.group(2);
			String name = blockMatcher.group(3) == null ? blockType : blockMatcher.group(3);
			String array = blockMatcher.group(4);
			resources.add(new ResourceUse(blockMatcher.start(),
				kind + (array == null ? "" : " array") + " " + name + " (" + blockType + ")"));
		}

		resources.sort((left, right) -> Integer.compare(left.position(), right.position()));
		return resources.stream().map(ResourceUse::description).toList();
	}

	private static List<String> samplerNames(String fragment) {
		Matcher matcher = SAMPLER.matcher(fragment);
		List<String> samplers = new ArrayList<>();

		while (matcher.find()) {
			samplers.add(matcher.group(2));
		}

		return List.copyOf(samplers);
	}

	private static boolean isSupportedSamplerType(String type) {
		return type.equals("sampler2D") || type.equals("isampler2D") || type.equals("usampler2D");
	}

	private static boolean isSupportedSamplerName(String name, ProgramSet programSet, TextureStage stage) {
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
				 "depthtex0", "depthtex1", "depthtex2", "gdepthtex", "noisetex" -> true;
			default -> IrisVulkanCustomTextures.supports(programSet.getPack(), stage, name);
		};
	}

	private static List<Integer> invalidDrawBuffers(int[] configured) {
		return Arrays.stream(configured)
			.filter(target -> target < 0 || target >= COLOR_TARGET_COUNT)
			.boxed()
			.toList();
	}

	private static String failureReason(String prefix, RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? prefix + " (" + exception.getClass().getSimpleName() + ")"
			: prefix + ": " + message;
	}

	private record ResourceUse(int position, String description) {
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
	}

	private static void log(IrisVulkanScreenPassGraph graph) {
		Iris.logger.info("Built native Vulkan screen pass graph: {} begin, {} prepare, {} deferred, {} composite node(s), final={}.",
			graph.beginPasses().size(), graph.preparePasses().size(), graph.deferredPasses().size(),
			graph.compositePasses().size(), graph.finalPass() != null);

		for (IrisVulkanScreenPassGraph.Node node : graph.nodes()) {
			if (node.ready()) {
				Iris.logger.info("Native Vulkan screen pass node {}: status={}, drawBuffers={}, samplers={}, explicitFlips={}, mipmappedBuffers={}, viewport={}, collapseOutputs={}.",
					node.label(), node.status(), java.util.Arrays.toString(node.drawBuffers()), node.samplers(),
					node.explicitFlips(), node.mipmappedBuffers(), node.viewport(), node.collapseOutputs());
			} else {
				Iris.logger.info("Native Vulkan screen pass node {}: status={}, samplers={}, reason={}.",
					node.label(), node.status(), node.samplers(), node.failureReason());
			}
		}
	}
}
