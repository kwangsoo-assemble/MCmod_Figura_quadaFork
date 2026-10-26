package org.figuramc.figura.model.rendering.nodeRenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.NonNull;

// 바깥선 — 모든 정점 색을 한 색(ARGB)으로 덮는다.
// ⚠ 이어 쓰는 메서드는 delegate 가 아니라 this 를 돌려준다 — delegate 를 돌려주면 다음 setColor 가 감싸기를 건너뛰어 원래 색이 박힌다
public class ConstantColorVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final int color;

    public ConstantColorVertexConsumer(VertexConsumer delegate, int color) {
        this.delegate = delegate;
        this.color = color;
    }

    private VertexConsumer writeColor() {
        delegate.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >> 24) & 0xFF);
        return this;
    }

    @Override
    public @NonNull VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setColor(int r, int g, int b, int a) {
        return writeColor();
    }

    @Override
    public @NonNull VertexConsumer setColor(int color) {
        return writeColor();
    }

    @Override
    public @NonNull VertexConsumer setColor(float r, float g, float b, float a) {
        return writeColor();
    }

    @Override
    public @NonNull VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setUv3(float u, float v) {
        delegate.setUv3(u, v);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setLineWidth(float width) {
        delegate.setLineWidth(width);
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
        delegate.addVertex(x, y, z, this.color, u, v, overlayCoords, lightCoords, nx, ny, nz);
    }

    @Override
    public @NonNull VertexConsumer setLight(int packedLightCoords) {
        delegate.setLight(packedLightCoords);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setOverlay(int packedOverlayCoords) {
        delegate.setOverlay(packedOverlayCoords);
        return this;
    }

    @Override
    public void putBlockBakedQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
        delegate.putBlockBakedQuad(x, y, z, quad, instance);
    }

    @Override
    public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
        delegate.putBakedQuad(pose, quad, instance);
    }

    @Override
    public void putBakedQuadWithGlint(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, PoseStack.Pose sheetedDecalPose) {
        delegate.putBakedQuadWithGlint(pose, quad, instance, sheetedDecalPose);
    }

    @Override
    public @NonNull VertexConsumer addVertex(Vector3fc position) {
        delegate.addVertex(position);
        return this;
    }

    @Override
    public @NonNull VertexConsumer addVertex(PoseStack.Pose pose, Vector3fc position) {
        delegate.addVertex(pose, position);
        return this;
    }

    @Override
    public @NonNull VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
        delegate.addVertex(pose, x, y, z);
        return this;
    }

    @Override
    public @NonNull VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
        delegate.addVertex(pose, x, y, z);
        return this;
    }

    @Override
    public @NonNull VertexConsumer addVertexWith2DPose(Matrix3x2fc pose, float x, float y) {
        delegate.addVertexWith2DPose(pose, x, y);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
        delegate.setNormal(pose, x, y, z);
        return this;
    }

    @Override
    public @NonNull VertexConsumer setNormal(PoseStack.Pose pose, Vector3fc normal) {
        delegate.setNormal(pose, normal);
        return this;
    }
}
