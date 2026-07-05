package net.irisshaders.iris.compat.sodium.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ShaderChunkRenderer.class)
public class MixinShaderChunkRenderer {
    @Redirect(method = "createShader", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;vertexFormat:Lcom/mojang/blaze3d/vertex/VertexFormat;"))
    private VertexFormat iris$forceSoWeCanLookUpLater(ShaderChunkRenderer instance) {
        return WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
    }
}
