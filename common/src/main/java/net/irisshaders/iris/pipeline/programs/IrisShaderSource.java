package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;

import java.util.EnumMap;
import java.util.Map;

public record IrisShaderSource(String name, String vertex, String geometry, String tessControl, String tessEval,
							   String fragment, VertexFormat vertexFormat, Patch patch, boolean shadowPass,
							   boolean fallback) {
	public static IrisShaderSource of(String name, Map<PatchShaderType, String> sources, VertexFormat vertexFormat,
									  Patch patch, boolean shadowPass, boolean fallback) {
		return new IrisShaderSource(
			name,
			sources.get(PatchShaderType.VERTEX),
			sources.get(PatchShaderType.GEOMETRY),
			sources.get(PatchShaderType.TESS_CONTROL),
			sources.get(PatchShaderType.TESS_EVAL),
			sources.get(PatchShaderType.FRAGMENT),
			vertexFormat,
			patch,
			shadowPass,
			fallback
		);
	}

	public Map<PatchShaderType, String> asMap() {
		EnumMap<PatchShaderType, String> sources = new EnumMap<>(PatchShaderType.class);
		sources.put(PatchShaderType.VERTEX, vertex);
		sources.put(PatchShaderType.GEOMETRY, geometry);
		sources.put(PatchShaderType.TESS_CONTROL, tessControl);
		sources.put(PatchShaderType.TESS_EVAL, tessEval);
		sources.put(PatchShaderType.FRAGMENT, fragment);
		return sources;
	}
}
