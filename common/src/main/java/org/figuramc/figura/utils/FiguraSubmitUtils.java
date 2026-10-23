package org.figuramc.figura.utils;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.figuramc.figura.model.FiguraVertexConsumerProvider;
import org.figuramc.figura.model.rendering.nodeRenderer.FiguraSubmission;

import net.minecraft.client.renderer.SubmitNodeCollection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class FiguraSubmitUtils {
    // 바닐라 submitCustomGeometry 와 같은 규칙으로 단계를 나눈다 — 섞기 있는 렌더 타입 → translucentCustomGeometry · 없는 것 → solid · 바깥선 → outline.
    // ⚠ 한 단계에 몰아 넣지 마라 — OIT 를 켜면 바닐라가 반투명 단계를 모두 oitTranslucent 로 묶어 OIT 파이프라인으로 그린다.
    //   OIT 짝이 없는 타입(CUTOUT · SOLID · Figura 가 만든 BLURRY 등)이 거기 들어가면 «does not have OIT pipelines set up» 으로 게임이 멈춘다
    public static void submit(SubmitNodeCollector collector, FiguraSubmission submission) {
        if (submission == null) return;
        if (!(collector instanceof SubmitNodeStorage storage)) return;
        SubmitNodeCollection c = storage.order(0);
        boolean oit = c.translucentCustomGeometry == c.oitTranslucent;  // OIT 가 켜지면 바닐라가 같은 단계로 묶는다

        Map<RenderType, List<Consumer<VertexConsumer>>> solidPrimary = new HashMap<>(), solidSecondary = new HashMap<>();
        Map<RenderType, List<Consumer<VertexConsumer>>> blendPrimary = new HashMap<>(), blendSecondary = new HashMap<>();
        split(submission.primaryBuffers(), solidPrimary, blendPrimary, oit);
        split(submission.secondaryBuffers(), solidSecondary, blendSecondary, oit);

        List<FiguraSubmission.QueuedRenderTask> solidTasks = new ArrayList<>(), blendTasks = new ArrayList<>();
        for (FiguraSubmission.QueuedRenderTask qt : submission.renderTasks()) {
            RenderType type = qt.task().queuedRenderType();
            (type == null || translucentPhase(type, oit) ? blendTasks : solidTasks).add(qt);
        }

        // 고정 상자(디버그 선)는 solid — 선 파이프라인의 OIT 짝을 믿지 않는다
        if (!solidPrimary.isEmpty() || !solidSecondary.isEmpty() || !solidTasks.isEmpty() || !submission.pivotBoxes().isEmpty())
            c.solid.submit(new FiguraSubmission(solidPrimary, solidSecondary, solidTasks, submission.pivotBoxes(), 0));
        if (!blendPrimary.isEmpty() || !blendSecondary.isEmpty() || !blendTasks.isEmpty())
            c.translucentCustomGeometry.submit(new FiguraSubmission(blendPrimary, blendSecondary, blendTasks, List.of(), 0));
        if (submission.outlineColor() != 0)
            c.outline.submit(new FiguraSubmission(submission.primaryBuffers(), submission.secondaryBuffers(), List.of(), List.of(), submission.outlineColor(), true));
    }

    private static void split(Map<RenderType, List<Consumer<VertexConsumer>>> from,
                              Map<RenderType, List<Consumer<VertexConsumer>>> solid,
                              Map<RenderType, List<Consumer<VertexConsumer>>> blend, boolean oit) {
        for (var entry : from.entrySet())
            (translucentPhase(entry.getKey(), oit) ? blend : solid).put(entry.getKey(), entry.getValue());
    }

    // 섞기 있고, OIT 가 켜졌으면 OIT 짝까지 있어야 반투명 단계 — 짝 없는 섞기 타입은 solid 로(정렬은 잃지만 멈추지 않는다)
    private static boolean translucentPhase(RenderType type, boolean oit) {
        return type.hasBlending() && (!oit || type.state.oitPipelineSet != null);
    }

    public static void drawImmediate(FiguraVertexConsumerProvider provider, Map<RenderType, List<Consumer<VertexConsumer>>> buffers) {
        for (var entry : buffers.entrySet()) {
            VertexConsumer vc = provider.getBuffer(entry.getKey());

            for (var action : entry.getValue())
                action.accept(vc);
        }
    }
}
