package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVulkanShaderResources {
	private static final Pattern COMMENT_BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern COMMENT_LINE = Pattern.compile("(?m)//.*$");
	private static final Pattern UNIFORM_BLOCK = Pattern.compile("(?m)^\\s*layout\\s*\\([^)]*\\)\\s*uniform\\s+(\\w+)\\s*\\{");
	private static final Pattern STORAGE_BLOCK = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:coherent|volatile|restrict|readonly|writeonly)\\s+)*buffer\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
	private static final Pattern BLOCK_ARRAY = Pattern.compile("(?s)\\b(?:uniform|buffer)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{.*?\\}\\s*[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\[[^]]+])+");
	private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:lowp|mediump|highp)\\s+)*([iu]?sampler\\w+)\\s+(\\w+)\\s*((?:\\s*\\[[^]]+])+)?\\s*;");
	private static final Pattern LOOSE_NON_OPAQUE_UNIFORM = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:lowp|mediump|highp|coherent|volatile|restrict|readonly|writeonly)\\s+)*([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)(\\s*\\[[^;=]+])?\\s*(?:=\\s*[^;]+)?;\\s*\\R?");
	private static final Pattern VERTEX_INPUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*(?:in|attribute)\\s+(?:(?:lowp|mediump|highp)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)(\\s*\\[[^;=]+])?\\s*;\\s*\\R?");
	private static final Pattern FRAGMENT_OUTPUT = Pattern.compile("(?m)^\\s*layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*\\R?");
	private static final Pattern PLAIN_FRAGMENT_OUTPUT = Pattern.compile("(?m)^(\\s*)((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*)out\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*\\R?");
	private static final Pattern DRAWBUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)");
	private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\([^)]*\\)\\s*\\{");
	private static final Pattern TBN_VERTEX_OUTPUT = Pattern.compile("(?m)^\\s*flat\\s+out\\s+mat3\\s+tbnMatrix\\s*;\\s*\\R?");
	private static final Pattern TBN_FRAGMENT_INPUT = Pattern.compile("(?m)^\\s*flat\\s+in\\s+mat3\\s+tbnMatrix\\s*;\\s*\\R?");
	private static final Pattern SODIUM_REGION_OFFSET_UNIFORM = Pattern.compile("(?m)^\\s*uniform\\s+vec3\\s+u_RegionOffset\\s*;\\s*\\R?");
	private static final Pattern SODIUM_CURRENT_TIME_UNIFORM = Pattern.compile("(?m)^\\s*uniform\\s+int\\s+u_CurrentTime\\s*;\\s*\\R?");
	private static final Pattern SODIUM_REGION_ID_UNIFORM = Pattern.compile("(?m)^\\s*uniform\\s+uint\\s+u_RegionID\\s*;\\s*\\R?");
	private static final Map<RenderPipeline, ResourceSet> RESOURCE_SETS =
		java.util.Collections.synchronizedMap(new WeakHashMap<>());

	private IrisVulkanShaderResources() {
	}

	public static Prepared prepare(RenderPipeline original, ShaderKey shaderKey, String vertex, String fragment) {
		return prepareInternal(original, shaderKey, chooseVertexFormats(original, shaderKey), vertex, fragment,
			shaderKey.patch == Patch.SODIUM, -1);
	}

	public static Prepared prepareGbufferPass(RenderPipeline original, ShaderKey shaderKey, String vertex, String fragment,
											  int colorTargetCount) {
		return prepareInternal(original, shaderKey, chooseVertexFormats(original, shaderKey), vertex, fragment,
			false, colorTargetCount);
	}

	public static Prepared prepareScreenPass(RenderPipeline original, String vertex, String fragment) {
		return prepareScreenPass(original, vertex, fragment, true);
	}

	public static Prepared prepareScreenPass(RenderPipeline original, String vertex, String fragment, boolean collapseFragmentOutputs) {
		VertexFormat[] vertexFormats = replacePrimaryVertexFormat(original.getVertexFormatBindings(), DefaultVertexFormat.POSITION_TEX);
		return prepareInternal(original, null, vertexFormats, vertex, fragment, collapseFragmentOutputs, -1);
	}

	private static Prepared prepareInternal(RenderPipeline original, ShaderKey shaderKey, VertexFormat[] vertexFormats,
											String vertex, String fragment, boolean collapseFragmentOutputs,
											int colorTargetCount) {
		String patchedVertex = patchSodiumNativeVulkanUniforms(vertex, shaderKey);
		String patchedFragment = patchSodiumNativeVulkanUniforms(fragment, shaderKey);
		UniformPatch vertexUniforms = patchLooseUniforms(patchedVertex);
		UniformPatch fragmentUniforms = patchLooseUniforms(patchedFragment);
		LinkedHashMap<String, IrisVulkanUniformSnapshot.Field> fieldsByName = new LinkedHashMap<>();
		LinkedHashSet<UnsupportedResource> unsupported = new LinkedHashSet<>(vertexUniforms.unsupported());
		unsupported.addAll(fragmentUniforms.unsupported());
		for (IrisVulkanUniformSnapshot.Field field : vertexUniforms.fields()) {
			fieldsByName.put(field.name(), field);
		}
		for (IrisVulkanUniformSnapshot.Field field : fragmentUniforms.fields()) {
			IrisVulkanUniformSnapshot.Field previous = fieldsByName.putIfAbsent(field.name(), field);
			if (previous != null && !previous.type().equals(field.type())) {
				unsupported.add(new UnsupportedResource("uniform", field.name(), field.type(),
					"conflicts with " + previous.type() + " in the other shader stage"));
			}
		}
		List<IrisVulkanUniformSnapshot.Field> uniformFields = List.copyOf(fieldsByName.values());
		patchedVertex = injectUniformBlock(vertexUniforms.source(), uniformFields);
		patchedFragment = injectUniformBlock(fragmentUniforms.source(), uniformFields);
		patchedVertex = patchMissingVertexInputs(patchedVertex, vertexFormats);
		patchedVertex = patchTbnMatrixOutput(patchedVertex, shaderKey);
		patchedFragment = patchTbnMatrixInput(patchedFragment, shaderKey);
		patchedFragment = patchFragmentOutputs(patchedFragment, collapseFragmentOutputs);
		ResourceSet resources = ResourceSet.collect(patchedVertex, patchedFragment, uniformFields, unsupported);
		if (!resources.unsupported().isEmpty()) {
			throw new UnsupportedOperationException("Unsupported Vulkan shader resources: " + resources.unsupported());
		}
		RenderPipeline pipeline = extendPipeline(original, resources, vertexFormats, colorTargetCount);
		RESOURCE_SETS.put(pipeline, resources);

		return new Prepared(patchedVertex, patchedFragment, pipeline, resources);
	}

	public static ResourceSet resourcesFor(RenderPipeline pipeline) {
		return RESOURCE_SETS.get(pipeline);
	}

	private static String patchSodiumNativeVulkanUniforms(String source, ShaderKey shaderKey) {
		if (shaderKey == null || shaderKey.patch != Patch.SODIUM) {
			return source;
		}

		if (source.contains("layout(push_constant) uniform PC")) {
			return source;
		}

		boolean hasRegionOffset = SODIUM_REGION_OFFSET_UNIFORM.matcher(source).find();
		boolean hasCurrentTime = SODIUM_CURRENT_TIME_UNIFORM.matcher(source).find();
		boolean hasRegionId = SODIUM_REGION_ID_UNIFORM.matcher(source).find();

		if (hasRegionOffset || hasCurrentTime || hasRegionId) {
			source = SODIUM_REGION_OFFSET_UNIFORM.matcher(source).replaceAll("");
			source = SODIUM_CURRENT_TIME_UNIFORM.matcher(source).replaceAll("");
			source = SODIUM_REGION_ID_UNIFORM.matcher(source).replaceAll("");
			source = insertAfterVersion(source, """
				layout(push_constant) uniform PC {
				vec3 u_RegionOffset;
				int u_CurrentTime;
				uint u_RegionID;
				};

				""");
		}

		return source;
	}

	private static RenderPipeline extendPipeline(RenderPipeline original, ResourceSet resources, VertexFormat[] vertexFormats,
												 int colorTargetCount) {
		List<String> existingSamplers = BindGroupLayout.flattenSamplers(original.getBindGroupLayouts());
		List<BindGroupLayout.UniformDescription> existingUniforms = BindGroupLayout.flattenUniforms(original.getBindGroupLayouts());
		BindGroupLayout.Builder extras = BindGroupLayout.builder();
		boolean hasExtras = false;

		for (String sampler : resources.samplers()) {
			if (!existingSamplers.contains(sampler)) {
				extras.withSampler(sampler);
				hasExtras = true;
			}
		}

		for (String uniform : resources.uniformBuffers()) {
			if (existingUniforms.stream().noneMatch(description -> description.name().equals(uniform))) {
				extras.withUniform(uniform, UniformType.UNIFORM_BUFFER);
				hasExtras = true;
			}
		}

		for (TexelBuffer texelBuffer : resources.texelBuffers()) {
			if (existingUniforms.stream().noneMatch(description -> description.name().equals(texelBuffer.name()))) {
				extras.withUniform(texelBuffer.name(), UniformType.TEXEL_BUFFER, texelBuffer.format());
				hasExtras = true;
			}
		}

		boolean vertexFormatsChanged = !sameVertexFormats(original.getVertexFormatBindings(), vertexFormats);
		boolean colorTargetsChanged = colorTargetCount > 0 && original.getColorTargetStates().length != colorTargetCount;
		if (!hasExtras && !vertexFormatsChanged && !colorTargetsChanged) {
			return original;
		}

		List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
		if (hasExtras) {
			layouts.add(extras.build());
		}

		RenderPipeline extended = copyPipeline(original, layouts, vertexFormats, colorTargetCount);

		if (Iris.getIrisConfig() != null) {
			Iris.logger.info("Extended Vulkan pipeline {} with Iris resources: samplers={}, uniformBuffers={}, texelBuffers={}, vertexFormats={}",
				original.getLocation(), resources.samplers(), resources.uniformBuffers(), resources.texelBuffers(),
				formatVertexFormats(vertexFormats));
		}

		return extended;
	}

	private static RenderPipeline copyPipeline(RenderPipeline original, List<BindGroupLayout> layouts, VertexFormat[] vertexFormats,
											   int colorTargetCount) {
		Identifier location = original.getLocation();
		Identifier vertexShader = original.getVertexShader();
		Identifier fragmentShader = original.getFragmentShader();
		ShaderDefines shaderDefines = original.getShaderDefines();
		ColorTargetState[] colorTargetStates = colorTargetCount > 0
			? adaptColorTargetStates(original.getColorTargetStates(), colorTargetCount)
			: original.getColorTargetStates().clone();
		DepthStencilState depthStencilState = original.getDepthStencilState();
		PolygonMode polygonMode = original.getPolygonMode();
		boolean cull = original.isCull();
		PrimitiveTopology primitiveTopology = original.getPrimitiveTopology();

		return VKOnly_RenderPipelineAccessor.iris$create(location, vertexShader, fragmentShader, shaderDefines, layouts,
			colorTargetStates, depthStencilState, polygonMode, cull, vertexFormats, primitiveTopology, original.getSortKey());
	}

	public static ColorTargetState[] createColorTargetStates(int colorTargetCount) {
		ColorTargetState[] colorTargetStates = new ColorTargetState[colorTargetCount];

		Arrays.fill(colorTargetStates, ColorTargetState.DEFAULT);
		return colorTargetStates;
	}

	private static ColorTargetState[] adaptColorTargetStates(ColorTargetState[] originalStates, int colorTargetCount) {
		ColorTargetState[] colorTargetStates = Arrays.copyOf(originalStates, colorTargetCount);
		if (colorTargetCount > originalStates.length) {
			Arrays.fill(colorTargetStates, originalStates.length, colorTargetCount, ColorTargetState.DEFAULT);
		}

		return colorTargetStates;
	}

	public static int[] drawBuffersFromSource(String source) {
		if (source == null) {
			return new int[0];
		}

		Matcher matcher = DRAWBUFFERS.matcher(source);
		String value = null;

		while (matcher.find()) {
			value = matcher.group(1);
		}

		if (value == null || value.isBlank()) {
			return new int[0];
		}

		int[] drawBuffers = new int[value.length()];
		int count = 0;

		for (int i = 0; i < value.length(); i++) {
			int target = Character.digit(value.charAt(i), 10);

			if (target >= 0 && target < 8) {
				drawBuffers[count++] = target;
			}
		}

		if (count == drawBuffers.length) {
			return drawBuffers;
		}

		return Arrays.copyOf(drawBuffers, count);
	}

	private static VertexFormat[] chooseVertexFormats(RenderPipeline original, ShaderKey shaderKey) {
		VertexFormat[] originalFormats = original.getVertexFormatBindings().clone();

		if (shaderKey.patch == Patch.SODIUM) {
			return replacePrimaryVertexFormat(originalFormats, ChunkMeshFormats.COMPACT.getVertexFormat());
		}

		VertexFormat shaderFormat = shaderKey.getVertexFormat();
		if (shaderFormat == null) {
			return originalFormats;
		}

		return replacePrimaryVertexFormat(originalFormats, aliasVanillaVertexFormat(shaderFormat));
	}

	private static VertexFormat[] replacePrimaryVertexFormat(VertexFormat[] originalFormats, VertexFormat primary) {
		VertexFormat[] formats = originalFormats.length >= 16 ? originalFormats.clone() : new VertexFormat[16];

		if (originalFormats.length < 16) {
			System.arraycopy(originalFormats, 0, formats, 0, originalFormats.length);
		}

		formats[0] = primary;
		return formats;
	}

	private static VertexFormat aliasVanillaVertexFormat(VertexFormat source) {
		VertexFormat.Builder builder = VertexFormat.builder(0);

		for (VertexFormatElement element : source.getElements()) {
			builder.addAttribute(aliasVanillaAttribute(element.name()), element.format());
		}

		return builder.build();
	}

	private static String aliasVanillaAttribute(String name) {
		return switch (name) {
			case "Position" -> "iris_Position";
			case "Color" -> "iris_Color";
			case "UV0" -> "iris_UV0";
			case "UV1" -> "iris_UV1";
			case "UV2" -> "iris_UV2";
			case "Normal" -> "iris_Normal";
			case "LineWidth" -> "iris_LineWidth";
			default -> name;
		};
	}

	private static boolean sameVertexFormats(VertexFormat[] first, VertexFormat[] second) {
		if (first.length != second.length) {
			return false;
		}

		for (int i = 0; i < first.length; i++) {
			if (!Objects.equals(first[i], second[i])) {
				return false;
			}
		}

		return true;
	}

	private static String formatVertexFormats(VertexFormat[] vertexFormats) {
		String[] formatted = new String[vertexFormats.length];

		for (int i = 0; i < vertexFormats.length; i++) {
			formatted[i] = vertexFormats[i] == null ? "null" : vertexFormats[i].toString();
		}

		return Arrays.toString(formatted);
	}

	private static UniformPatch patchLooseUniforms(String source) {
		Matcher matcher = LOOSE_NON_OPAQUE_UNIFORM.matcher(source);
		StringBuffer result = new StringBuffer(source.length());
		LinkedHashSet<IrisVulkanUniformSnapshot.Field> fields = new LinkedHashSet<>();
		LinkedHashSet<UnsupportedResource> unsupported = new LinkedHashSet<>();

		while (matcher.find()) {
			String type = matcher.group(1);
			String name = matcher.group(2);
			String arraySuffix = matcher.group(3);

			if (isOpaqueUniformType(type)) {
				if (isUnsupportedOpaqueType(type)) {
					unsupported.add(new UnsupportedResource("uniform", name, type,
						"image and subpass resources have no Iris Vulkan binding"));
					matcher.appendReplacement(result, "");
				}

				continue;
			}

			Optional<IrisVulkanUniformSnapshot.Field> field = IrisVulkanUniformSnapshot.field(name, type, arraySuffix);
			if (field.isPresent()) {
				fields.add(field.get());
			} else {
				unsupported.add(new UnsupportedResource("uniform", name, type,
					IrisVulkanUniformSnapshot.unsupportedReason(name, type, arraySuffix)));
			}

			matcher.appendReplacement(result, "");
		}

		matcher.appendTail(result);
		return new UniformPatch(result.toString(), List.copyOf(fields), unsupported);
	}

	private static String injectUniformBlock(String source, List<IrisVulkanUniformSnapshot.Field> fields) {
		if (fields.isEmpty()) {
			return source;
		}

		return insertAfterVersion(source, IrisVulkanUniformSnapshot.declaration(fields) + "\n");
	}

	private static String patchMissingVertexInputs(String source, VertexFormat[] vertexFormats) {
		DefineSet defines = new DefineSet();
		Set<String> availableInputs = collectVertexInputs(vertexFormats);
		Matcher matcher = VERTEX_INPUT.matcher(source);
		StringBuffer result = new StringBuffer(source.length());

		while (matcher.find()) {
			String type = matcher.group(1);
			String name = matcher.group(2);
			String arraySuffix = matcher.group(3);

			if (availableInputs.contains(name)) {
				continue;
			}

			String expression = defaultVertexInputExpression(name, type, arraySuffix);
			if (expression == null) {
				continue;
			}

			defines.add(name, expression);
			matcher.appendReplacement(result, "");
		}

		matcher.appendTail(result);
		return defines.apply(result.toString());
	}

	private static String patchFragmentOutputs(String source, boolean collapseFragmentOutputs) {
		if (!collapseFragmentOutputs) {
			return addImplicitFragmentOutputLocations(source);
		}

		Matcher matcher = FRAGMENT_OUTPUT.matcher(source);
		StringBuffer result = new StringBuffer(source.length());
		String primaryName = null;
		String primaryType = null;
		boolean foundOutput = false;

		while (matcher.find()) {
			int location = Integer.parseInt(matcher.group(1));
			String type = matcher.group(2);
			String name = matcher.group(3);
			String replacement = type + " " + name + ";\n";

			if (location == 0 && primaryName == null) {
				primaryName = name;
				primaryType = type;
				replacement = "layout(location = 0) out vec4 iris_VulkanCompatColor;\n" + replacement;
			}

			foundOutput = true;
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		if (!foundOutput || primaryName == null) {
			return collapsePlainFragmentOutput(source);
		}

		matcher.appendTail(result);
		String assignment = "\niris_VulkanCompatColor = " + toVec4(primaryName, primaryType) + ";\n";
		return appendToMain(result.toString(), assignment);
	}

	private static String addImplicitFragmentOutputLocations(String source) {
		Matcher explicitMatcher = FRAGMENT_OUTPUT.matcher(source);
		int nextLocation = 0;

		while (explicitMatcher.find()) {
			nextLocation = Math.max(nextLocation, Integer.parseInt(explicitMatcher.group(1)) + 1);
		}

		Matcher matcher = PLAIN_FRAGMENT_OUTPUT.matcher(source);
		StringBuffer result = new StringBuffer(source.length());

		while (matcher.find()) {
			String indent = matcher.group(1);
			String qualifiers = matcher.group(2);
			String type = matcher.group(3);
			String name = matcher.group(4);
			String replacement = indent + "layout(location = " + nextLocation++ + ") " + qualifiers + "out " + type + " " + name + ";\n";
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);
		return result.toString();
	}

	private static String collapsePlainFragmentOutput(String source) {
		Matcher matcher = PLAIN_FRAGMENT_OUTPUT.matcher(source);

		if (!matcher.find()) {
			return source;
		}

		String indent = matcher.group(1);
		String type = matcher.group(3);
		String name = matcher.group(4);
		String replacement = indent + "layout(location = 0) out vec4 iris_VulkanCompatColor;\n"
			+ indent + type + " " + name + ";\n";
		StringBuffer result = new StringBuffer(source.length());
		matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		matcher.appendTail(result);
		String assignment = "\niris_VulkanCompatColor = " + toVec4(name, type) + ";\n";
		return appendToMain(result.toString(), assignment);
	}

	private static String patchTbnMatrixOutput(String source, ShaderKey shaderKey) {
		if (shaderKey == null || shaderKey.patch != Patch.SODIUM || !TBN_VERTEX_OUTPUT.matcher(source).find()) {
			return source;
		}

		String replacement = "mat3 iris_vulkan_tbnMatrix;\n"
			+ "flat out vec3 iris_vulkan_tbnMatrix0;\n"
			+ "flat out vec3 iris_vulkan_tbnMatrix1;\n"
			+ "flat out vec3 iris_vulkan_tbnMatrix2;\n"
			+ "#define tbnMatrix iris_vulkan_tbnMatrix\n";
		String patched = TBN_VERTEX_OUTPUT.matcher(source).replaceFirst(Matcher.quoteReplacement(replacement));
		return appendToMain(patched, "\niris_vulkan_tbnMatrix0 = iris_vulkan_tbnMatrix[0];\n"
			+ "iris_vulkan_tbnMatrix1 = iris_vulkan_tbnMatrix[1];\n"
			+ "iris_vulkan_tbnMatrix2 = iris_vulkan_tbnMatrix[2];\n");
	}

	private static String patchTbnMatrixInput(String source, ShaderKey shaderKey) {
		if (shaderKey == null || shaderKey.patch != Patch.SODIUM || !TBN_FRAGMENT_INPUT.matcher(source).find()) {
			return source;
		}

		String replacement = "flat in vec3 iris_vulkan_tbnMatrix0;\n"
			+ "flat in vec3 iris_vulkan_tbnMatrix1;\n"
			+ "flat in vec3 iris_vulkan_tbnMatrix2;\n"
			+ "#define tbnMatrix mat3(iris_vulkan_tbnMatrix0, iris_vulkan_tbnMatrix1, iris_vulkan_tbnMatrix2)\n";
		return TBN_FRAGMENT_INPUT.matcher(source).replaceFirst(Matcher.quoteReplacement(replacement));
	}

	private static String toVec4(String name, String type) {
		return switch (type) {
			case "vec4" -> name;
			case "vec3" -> "vec4(" + name + ", 1.0)";
			case "vec2" -> "vec4(" + name + ", 0.0, 1.0)";
			case "float" -> "vec4(vec3(" + name + "), 1.0)";
			default -> "vec4(1.0, 0.0, 1.0, 1.0)";
		};
	}

	private static String appendToMain(String source, String statement) {
		Matcher matcher = MAIN_FUNCTION.matcher(source);
		if (!matcher.find()) {
			return source;
		}

		int depth = 1;
		for (int index = matcher.end(); index < source.length(); index++) {
			char current = source.charAt(index);

			if (current == '{') {
				depth++;
			} else if (current == '}') {
				depth--;

				if (depth == 0) {
					return source.substring(0, index) + statement + source.substring(index);
				}
			}
		}

		return source;
	}

	private static String insertAfterVersion(String source, String block) {
		Matcher matcher = Pattern.compile("(?m)^(#version\\s+\\d+.*\\R)").matcher(source);

		if (matcher.find()) {
			return source.substring(0, matcher.end()) + block + source.substring(matcher.end());
		}

		return block + source;
	}

	private static Set<String> collectVertexInputs(VertexFormat[] vertexFormats) {
		LinkedHashSet<String> inputs = new LinkedHashSet<>();

		for (VertexFormat format : vertexFormats) {
			if (format == null) {
				continue;
			}

			for (VertexFormatElement element : format.getElements()) {
				inputs.add(element.name());
			}
		}

		return inputs;
	}

	private static String defaultVertexInputExpression(String name, String type, String arraySuffix) {
		if (name.equals("iris_LineWidth")) {
			return "1.0";
		}

		if (name.equals("at_tangent")) {
			return "vec4(0.0, 0.0, 1.0, 1.0)";
		}

		if (name.equals("mc_midTexCoord")) {
			return switch (type) {
				case "vec2" -> "vec2(0.5)";
				case "vec3" -> "vec3(0.5, 0.5, 0.0)";
				case "vec4" -> "vec4(0.5, 0.5, 0.0, 1.0)";
				default -> defaultExpression(type, arraySuffix);
			};
		}

		if (name.equals("iris_Entity") || name.equals("mc_Entity") || name.equals("iris_Normal") || name.equals("at_midBlock")) {
			return defaultExpression(type, arraySuffix);
		}

		return null;
	}

	private static boolean isOpaqueUniformType(String type) {
		String normalized = type.toLowerCase();
		return normalized.contains("sampler") || normalized.contains("image") || normalized.startsWith("subpassinput");
	}

	private static boolean isUnsupportedOpaqueType(String type) {
		String normalized = type.toLowerCase();
		return normalized.contains("image") || normalized.startsWith("subpassinput");
	}

	private static String defaultExpression(String type, String arraySuffix) {
		String elementExpression = defaultElementExpression(type);

		if (elementExpression == null) {
			return null;
		}

		if (arraySuffix == null || arraySuffix.isBlank()) {
			return elementExpression;
		}

		Integer length = parseArrayLength(arraySuffix);
		if (length == null || length < 1 || length > 32) {
			return null;
		}

		StringBuilder builder = new StringBuilder(type).append("[").append(length).append("](");
		for (int i = 0; i < length; i++) {
			if (i > 0) {
				builder.append(", ");
			}

			builder.append(elementExpression);
		}

		return builder.append(")").toString();
	}

	private static Integer parseArrayLength(String arraySuffix) {
		String trimmed = arraySuffix.trim();

		if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
			return null;
		}

		try {
			return Integer.parseInt(trimmed.substring(1, trimmed.length() - 1).trim());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String defaultElementExpression(String type) {
		return switch (type) {
			case "float" -> "0.0";
			case "double" -> "0.0";
			case "int" -> "0";
			case "uint" -> "0u";
			case "bool" -> "false";
			case "vec2", "vec3", "vec4" -> type + "(0.0)";
			case "dvec2", "dvec3", "dvec4" -> type + "(0.0)";
			case "ivec2", "ivec3", "ivec4" -> type + "(0)";
			case "uvec2", "uvec3", "uvec4" -> type + "(0u)";
			case "bvec2", "bvec3", "bvec4" -> type + "(false)";
			case "mat2", "mat3", "mat4" -> type + "(1.0)";
			case "mat2x2", "mat3x3", "mat4x4" -> type + "(1.0)";
			case "mat2x3", "mat2x4", "mat3x2", "mat3x4", "mat4x2", "mat4x3" -> type + "(0.0)";
			default -> null;
		};
	}

	private static String stripComments(String source) {
		return COMMENT_LINE.matcher(COMMENT_BLOCK.matcher(source).replaceAll("")).replaceAll("");
	}

	private static boolean isSamplerBuffer(String type) {
		return type.equals("samplerBuffer") || type.equals("isamplerBuffer") || type.equals("usamplerBuffer");
	}

	private static GpuFormat texelFormat(String type) {
		if (type.equals("usamplerBuffer")) {
			return GpuFormat.R32_UINT;
		}

		if (type.equals("samplerBuffer")) {
			return GpuFormat.R32_FLOAT;
		}

		return GpuFormat.R32_SINT;
	}

	public record Prepared(String vertex, String fragment, RenderPipeline pipeline, ResourceSet resources) {
	}

	private record UniformPatch(String source, List<IrisVulkanUniformSnapshot.Field> fields,
								Set<UnsupportedResource> unsupported) {
	}

	public record TexelBuffer(String name, GpuFormat format) {
	}

	public record SamplerRequirement(String name, String type) {
	}

	public record UnsupportedResource(String kind, String name, String type, String reason) {
	}

	public record ResourceSet(Set<String> samplers, Set<String> uniformBuffers, Set<TexelBuffer> texelBuffers,
							 List<IrisVulkanUniformSnapshot.Field> uniformFields, Set<SamplerRequirement> samplerRequirements,
							 Set<UnsupportedResource> unsupported) {
		static ResourceSet collect(String vertex, String fragment, List<IrisVulkanUniformSnapshot.Field> uniformFields,
									Set<UnsupportedResource> unsupported) {
			LinkedHashSet<String> samplers = new LinkedHashSet<>();
			LinkedHashSet<String> uniformBuffers = new LinkedHashSet<>();
			LinkedHashSet<TexelBuffer> texelBuffers = new LinkedHashSet<>();
			LinkedHashSet<SamplerRequirement> samplerRequirements = new LinkedHashSet<>();

			collect(stripComments(vertex), samplers, uniformBuffers, texelBuffers, samplerRequirements, unsupported);
			collect(stripComments(fragment), samplers, uniformBuffers, texelBuffers, samplerRequirements, unsupported);

			return new ResourceSet(orderedSet(samplers), orderedSet(uniformBuffers), orderedSet(texelBuffers),
				List.copyOf(uniformFields), orderedSet(samplerRequirements), orderedSet(unsupported));
		}

		private static <T> Set<T> orderedSet(Set<T> values) {
			return Collections.unmodifiableSet(new LinkedHashSet<>(values));
		}

		private static void collect(String source, Set<String> samplers, Set<String> uniformBuffers,
								Set<TexelBuffer> texelBuffers, Set<SamplerRequirement> samplerRequirements,
								Set<UnsupportedResource> unsupported) {
			Matcher storageMatcher = STORAGE_BLOCK.matcher(source);
			while (storageMatcher.find()) {
				unsupported.add(new UnsupportedResource("ssbo", storageMatcher.group(1), "buffer",
					"storage-buffer resources have no Iris Vulkan binding"));
			}

			Matcher blockArrayMatcher = BLOCK_ARRAY.matcher(source);
			while (blockArrayMatcher.find()) {
				unsupported.add(new UnsupportedResource("uniform", blockArrayMatcher.group(1), "block array",
					"uniform and storage-buffer arrays are not represented"));
			}

			Matcher uniformMatcher = UNIFORM_BLOCK.matcher(source);
			while (uniformMatcher.find()) {
				if (uniformMatcher.group(0).contains("push_constant")) {
					continue;
				}

				uniformBuffers.add(uniformMatcher.group(1));
			}

			Matcher samplerMatcher = SAMPLER.matcher(source);
			while (samplerMatcher.find()) {
				String type = samplerMatcher.group(1);
				String name = samplerMatcher.group(2);
				String arraySuffix = samplerMatcher.group(3);
				samplerRequirements.add(new SamplerRequirement(name, type));

				if (arraySuffix != null && !arraySuffix.isBlank()) {
					unsupported.add(new UnsupportedResource("sampler", name, type,
						"sampler arrays are not represented"));
					continue;
				}

				if (isSamplerBuffer(type)) {
					texelBuffers.add(new TexelBuffer(name, texelFormat(type)));
					unsupported.add(new UnsupportedResource("sampler", name, type, "texel-buffer resource has no Iris Vulkan binding"));
				} else if (!isSupportedSamplerType(type)) {
					unsupported.add(new UnsupportedResource("sampler", name, type, "opaque sampler type is not representable by the current Vulkan texture path"));
				} else {
					samplers.add(name);
				}
			}
		}

		private static boolean isSupportedSamplerType(String type) {
			return type.equals("sampler2D") || type.equals("isampler2D") || type.equals("usampler2D");
		}
	}

	private static final class DefineSet {
		private final LinkedHashSet<String> lines = new LinkedHashSet<>();

		void add(String name, String expression) {
			lines.add("#define " + name + " (" + expression + ")");
		}

		String apply(String source) {
			if (lines.isEmpty()) {
				return source;
			}

			String block = String.join("\n", lines) + "\n";
			Matcher matcher = Pattern.compile("(?m)^(#version\\s+\\d+.*\\R)").matcher(source);

			if (matcher.find()) {
				return source.substring(0, matcher.end()) + block + source.substring(matcher.end());
			}

			return block + source;
		}
	}
}
