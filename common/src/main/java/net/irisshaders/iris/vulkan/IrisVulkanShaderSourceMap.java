package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.programs.IrisShaderSource;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.util.Optional;

public final class IrisVulkanShaderSourceMap {
	private final ProgramFallbackResolver resolver;
	private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap;
	private final IrisShaderSource[] sources;

	public IrisVulkanShaderSourceMap(ProgramSet programSet) {
		this.resolver = new ProgramFallbackResolver(programSet);
		this.textureMap = programSet.getPackDirectives().getTextureMap();
		this.sources = new IrisShaderSource[ShaderKey.values().length];

		WorldRenderingSettings.INSTANCE.setVertexFormat(ChunkMeshFormats.COMPACT);
	}

	public IrisShaderSource getSource(ShaderKey key) {
		IrisShaderSource source = sources[key.ordinal()];

		if (source == null) {
			source = createSource(key);
			sources[key.ordinal()] = source;
		}

		return source;
	}

	private IrisShaderSource createSource(ShaderKey key) {
		Optional<ProgramSource> source = resolver.resolve(key.getProgram());

		if (key.isShadow()) {
			return createShadowSource(key.getName(), source, key, key.patch);
		}

		return createMainSource(key.getName(), source, key, key.patch);
	}

	private IrisShaderSource createMainSource(String name, Optional<ProgramSource> source, ShaderKey key, Patch patch) {
		if (source.isEmpty()) {
			return createFallbackSource(name, key, false);
		}

		ProgramId programId = key.getProgram();
		AlphaTest alpha = key.getAlphaTest();
		VertexFormat vertexFormat = key.getVertexFormat();
		boolean isLines = programId == ProgramId.Line && resolver.has(ProgramId.Line);
		ShaderAttributeInputs inputs = new ShaderAttributeInputs(vertexFormat, key.shouldIgnoreLightmap(), isLines,
			key.isGlint(), key.isText(), false);

		return ShaderCreator.createIrisSource(textureMap, name, key, source.get(), alpha, vertexFormat, inputs,
			false, isLines, patch);
	}

	private IrisShaderSource createShadowSource(String name, Optional<ProgramSource> source, ShaderKey key, Patch patch) {
		if (source.isEmpty()) {
			return createFallbackSource(name, key, true);
		}

		ProgramId programId = key.getProgram();
		AlphaTest alpha = key.getAlphaTest();
		VertexFormat vertexFormat = key.getVertexFormat();
		boolean isLines = programId == ProgramId.Line && resolver.has(ProgramId.Line);
		ShaderAttributeInputs inputs = new ShaderAttributeInputs(vertexFormat, key.shouldIgnoreLightmap(), isLines,
			false, key.isText(), false);

		return ShaderCreator.createIrisSource(textureMap, name, key, source.get(), alpha, vertexFormat, inputs,
			true, isLines, patch);
	}

	private IrisShaderSource createFallbackSource(String name, ShaderKey key, boolean shadowPass) {
		return ShaderCreator.createFallbackIrisSource(name, key.getAlphaTest(), key.getVertexFormat(), key.getFogMode(),
			key.hasDiffuseLighting(), key.isGlint(), key.isText(), key.isIntensity(), key.shouldIgnoreLightmap(),
			shadowPass);
	}
}
