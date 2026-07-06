package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVulkanScreenPassPlanner {
	private static final int COLOR_TARGET_COUNT = IrisVulkanGbufferTargets.COLOR_TARGET_COUNT;
	private static final int FINAL_SOURCE_TARGET = IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET;
	private static final Pattern DRAWBUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)");
	private static final Pattern OUTPUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*out\\s+[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
	private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+([iu]?sampler\\w+)\\s+(\\w+)\\s*(?:\\[[^]]+])?\\s*;");

	private IrisVulkanScreenPassPlanner() {
	}

	public static IrisVulkanScreenPassGraph create(ProgramSet programSet) {
		List<IrisVulkanScreenPassGraph.Node> deferredPasses = createPasses(programSet, ProgramArrayId.Deferred,
			TextureStage.DEFERRED, "deferred", IrisVulkanScreenPassGraph.Kind.DEFERRED);
		List<IrisVulkanScreenPassGraph.Node> compositePasses = createPasses(programSet, ProgramArrayId.Composite,
			TextureStage.COMPOSITE_AND_FINAL, "composite", IrisVulkanScreenPassGraph.Kind.COMPOSITE);
		IrisVulkanScreenPassGraph.Node finalPass = createFinalPass(programSet);
		IrisVulkanScreenPassGraph graph = new IrisVulkanScreenPassGraph(deferredPasses, compositePasses, finalPass);

		log(graph);
		return graph;
	}

	private static List<IrisVulkanScreenPassGraph.Node> createPasses(ProgramSet programSet, ProgramArrayId programArrayId,
																	 TextureStage stage, String namespace,
																	 IrisVulkanScreenPassGraph.Kind kind) {
		List<IrisVulkanScreenPassGraph.Node> passes = new ArrayList<>();
		ProgramSource[] sources = programSet.getComposite(programArrayId);

		for (int i = 0; i < sources.length; i++) {
			ProgramSource source = sources[i];

			if (source == null || !source.isValid()) {
				continue;
			}

			passes.add(createPass(programSet, source, stage, namespace + "/" + i, kind, false));
		}

		return List.copyOf(passes);
	}

	private static IrisVulkanScreenPassGraph.Node createFinalPass(ProgramSet programSet) {
		return programSet.get(ProgramId.Final)
			.map(source -> createPass(programSet, source, TextureStage.COMPOSITE_AND_FINAL,
				"final", IrisVulkanScreenPassGraph.Kind.FINAL, true))
			.orElse(null);
	}

	private static IrisVulkanScreenPassGraph.Node createPass(ProgramSet programSet, ProgramSource source,
															 TextureStage stage, String namespace,
															 IrisVulkanScreenPassGraph.Kind kind,
															 boolean collapseOutputs) {
		String label = sanitize(namespace + "/" + source.getName());
		String sourceName = source.getName();

		if (source.getGeometrySource().isPresent()) {
			return skipped(kind, label, sourceName, List.of(), collapseOutputs,
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
			return skipped(kind, label, sourceName, List.of(), collapseOutputs,
				"transform failed: " + e.getMessage());
		}

		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		List<String> samplers = samplerNames(fragment);

		if (usesUnsupportedSamplerType(fragment)) {
			return skipped(kind, label, sourceName, samplers, collapseOutputs,
				"unsupported non-2D sampler type");
		}

		List<String> unsupportedSamplers = unsupportedSamplers(fragment);
		if (!unsupportedSamplers.isEmpty()) {
			return skipped(kind, label, sourceName, samplers, collapseOutputs,
				"unsupported sampler(s) " + unsupportedSamplers);
		}

		int[] drawBuffers = collapseOutputs ? new int[] { FINAL_SOURCE_TARGET } : drawBuffers(fragment);

		if (!collapseOutputs && drawBuffers.length == 0) {
			drawBuffers = inferDrawBuffers(fragment);
		}

		if (!collapseOutputs && drawBuffers.length == 0) {
			return skipped(kind, label, sourceName, samplers, false, "no color outputs");
		}

		RenderPipeline pipeline = createPipeline(label, drawBuffers.length, collapseOutputs);
		IrisNativeVulkan.registerCustomPipelineSource(pipeline, label,
			transformed.get(PatchShaderType.VERTEX), fragment, collapseOutputs);

		return new IrisVulkanScreenPassGraph.Node(kind, label, sourceName, drawBuffers, samplers,
			collapseOutputs, pipeline, IrisVulkanScreenPassGraph.Status.READY, "");
	}

	private static RenderPipeline createPipeline(String label, int drawBufferCount, boolean collapseOutputs) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/screen/" + label))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS);

		int attachmentCount = collapseOutputs ? 1 : drawBufferCount;
		for (int i = 0; i < attachmentCount; i++) {
			builder.withColorTargetState(i, ColorTargetState.DEFAULT);
		}

		return builder.build();
	}

	private static IrisVulkanScreenPassGraph.Node skipped(IrisVulkanScreenPassGraph.Kind kind, String label,
														 String sourceName, List<String> samplers,
														 boolean collapseOutputs, String reason) {
		return new IrisVulkanScreenPassGraph.Node(kind, label, sourceName, new int[0], samplers,
			collapseOutputs, null, IrisVulkanScreenPassGraph.Status.SKIPPED, reason);
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
				 "depthtex0", "depthtex1", "depthtex2", "gdepthtex", "noisetex" -> true;
			default -> false;
		};
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

		return count == targets.length ? targets : Arrays.copyOf(targets, count);
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

	private static void log(IrisVulkanScreenPassGraph graph) {
		Iris.logger.info("Built native Vulkan screen pass graph: {} deferred node(s), {} composite node(s), final={}.",
			graph.deferredPasses().size(), graph.compositePasses().size(), graph.finalPass() != null);

		for (IrisVulkanScreenPassGraph.Node node : graph.nodes()) {
			if (node.ready()) {
				Iris.logger.info("Native Vulkan screen pass node {}: status={}, drawBuffers={}, samplers={}, collapseOutputs={}.",
					node.label(), node.status(), Arrays.toString(node.drawBuffers()), node.samplers(), node.collapseOutputs());
			} else {
				Iris.logger.info("Native Vulkan screen pass node {}: status={}, samplers={}, reason={}.",
					node.label(), node.status(), node.samplers(), node.failureReason());
			}
		}
	}
}
