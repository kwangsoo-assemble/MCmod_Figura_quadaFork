package org.figuramc.figura.model.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.lua.api.ClientAPI;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.math.vector.FiguraVec4;
import org.figuramc.figura.model.*;
import org.figuramc.figura.model.rendering.nodeRenderer.ConstantColorVertexConsumer;
import org.figuramc.figura.model.rendering.nodeRenderer.FiguraSubmission;
import org.figuramc.figura.model.rendering.texture.FiguraRenderTypes;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.figuramc.figura.model.rendertasks.RenderTask;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.FiguraSubmitUtils;
import org.figuramc.figura.utils.ui.UIHelper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class ImmediateFiguraRenderer extends FiguraRenderer {

    protected final PartCustomization.PartCustomizationStack customizationStack = new PartCustomization.PartCustomizationStack();

    public static final FiguraMat4 CAMERA_POS_TO_WORLD_MATRIX = FiguraMat4.of();

    private static final PartCustomization pivotOffsetter = new PartCustomization();

    protected VertexBuffer vertexBuffer;
    private final List<FiguraSubmission.QueuedRenderTask> queuedRenderTasks = new ArrayList<>();
    private final List<FiguraSubmission.QueuedPivotBox> queuedPivotBoxes = new ArrayList<>();

    public ImmediateFiguraRenderer(Avatar avatar) {
        super(avatar);

        // Vertex data, read model parts
        root = FiguraModelPartReader.read(avatar, avatar.nbt.getCompoundOrEmpty("models"), textureSets, false);

        sortParts();
    }

    public void checkEmpty() {
        if (!customizationStack.isEmpty())
            throw new IllegalStateException("Customization stack not empty!");
    }

    @Override
    public int render() {
        return commonRender(1.5d);
    }

    @Override
    public int renderSpecialParts() {
        return commonRender(0);
    }

    @Override
    public void updateMatrices() {
        // flag rendering state
        this.isRendering = true;

        // setup root customizations
        PartCustomization customization = setupRootCustomization(1.5d);

        // Push transform
        customizationStack.push(customization);

        // world matrices
        CAMERA_POS_TO_WORLD_MATRIX.set(FiguraRenderer.worldToCameraPosMatrix().invert());

        // calculate each part matrices
        calculatePartMatrices(root);

        // finish rendering
        customizationStack.pop();
        checkEmpty();

        this.isRendering = false;
    }

    public int getComplexity() {
        // complexity
        int prev = avatar.complexity.remaining;
        int[] remainingComplexity = new int[] {prev};

        // explore all model parts
        if (root.customization.visible) {
            if (currentFilterScheme.parentType.isSeparate) {
                List<FiguraModelPart> parts = separatedParts.get(currentFilterScheme.parentType);
                if (parts != null) {
                    for (FiguraModelPart part : parts) {
                        if (currentFilterScheme.parentType == ParentType.Item && part != itemToRender)
                            continue;

                        getPartComplexity(part, remainingComplexity, currentFilterScheme.initialValue);
                    }
                }
            } else {
                getPartComplexity(root, remainingComplexity, currentFilterScheme.initialValue);
            }
        }
        return prev - Math.max(remainingComplexity[0], 0);
    }

    protected int commonRender(double vertOffset) {
        // flag rendering state
        this.isRendering = true;

        // clear last data
        this.vertexBuffer = new VertexBuffer();
        this.queuedRenderTasks.clear();
        this.queuedPivotBoxes.clear();

        // iris fix
        int irisConfig = UIHelper.paperdoll || !ClientAPI.hasShaderPackMod() ? 0 : Configs.IRIS_COMPATIBILITY_FIX.value;
        doIrisEmissiveFix = (irisConfig >= 2 && ClientAPI.hasShaderPack()) || (avatar.renderMode != EntityRenderMode.RENDER && avatar.renderMode != EntityRenderMode.WORLD);
        offsetRenderLayers = irisConfig >= 1;

        // custom textures
        for (FiguraTextureSet set : textureSets)
            set.uploadIfNeeded();
        for (FiguraTexture texture : customTextures.values())
            texture.uploadIfDirty(false, false);

        // Set shouldRenderPivots
        int config = Configs.RENDER_DEBUG_PARTS_PIVOT.value;
        if (!Minecraft.getInstance().debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES) || (!avatar.isHost && config < 2))
            shouldRenderPivots = 0;
        else
            shouldRenderPivots = config;

        // world matrices
        if (allowMatrixUpdate) {
            CAMERA_POS_TO_WORLD_MATRIX.set(FiguraRenderer.worldToCameraPosMatrix().invert());
        }

        // complexity
        int prev = avatar.complexity.remaining;
        int[] remainingComplexity = new int[] {prev};

        // render all model parts
        if (root.customization.visible) {
            if (currentFilterScheme.parentType.isSeparate) {
                List<FiguraModelPart> parts = separatedParts.get(currentFilterScheme.parentType);
                if (parts != null) {
                    boolean renderLayer = !currentFilterScheme.parentType.isRenderLayer;
                    if (renderLayer) {
                        PartCustomization customization = setupRootCustomization(vertOffset);
                        customizationStack.push(customization); // push root
                        customizationStack.push(root.customization); // push "models"
                    }

                    for (FiguraModelPart part : parts) {
                        if (currentFilterScheme.parentType == ParentType.Item && part != itemToRender)
                            continue;

                        boolean saved = part.savedCustomization != null;
                        if (saved) {
                            customizationStack.push(part.savedCustomization);
                            part.savedCustomization = null;
                        }

                        renderPart(part, remainingComplexity, currentFilterScheme.initialValue);

                        if (saved) customizationStack.pop();
                    }

                    if (renderLayer) {
                        customizationStack.pop(); // pop "models"
                        customizationStack.pop(); // pop root
                    }
                }
            } else {
                PartCustomization customization = setupRootCustomization(vertOffset);
                customizationStack.push(customization);
                renderPart(root, remainingComplexity, currentFilterScheme.initialValue);
                customizationStack.pop();
            }

            // push vertices to vertex consumer
            FiguraMod.pushProfiler("draw");
            if (currentSubmitNodeCollector != null) {
                FiguraSubmission submission = new FiguraSubmission(
                        Map.copyOf(vertexBuffer.primaryBuffers), Map.copyOf(vertexBuffer.secondaryBuffers),
                        List.copyOf(queuedRenderTasks), List.copyOf(queuedPivotBoxes), Map.copyOf(vertexBuffer.outlineBuffers)
                );
                this.lastSubmission = submission;
                FiguraSubmitUtils.submit(currentSubmitNodeCollector, submission);
            } else if (currentVertexConsumerProvider != null) {
                // немедленный режим — пишем вершины прямо сейчас
                FiguraSubmitUtils.drawImmediate(currentVertexConsumerProvider, vertexBuffer.primaryBuffers);
                FiguraSubmitUtils.drawImmediate(currentVertexConsumerProvider, vertexBuffer.secondaryBuffers);
                for (var qt : queuedRenderTasks)
                    qt.task().render(qt.poseStack(), currentVertexConsumerProvider, qt.light(), qt.overlay());
                // pivot-боксы в этом режиме, скорее всего, не нужны (debug-фича) — пропускаем
            }
            FiguraMod.popProfiler();

            // finish rendering
            checkEmpty();
        }

        this.isRendering = false;
        if (this.dirty)
            clean();

        return prev - Math.max(remainingComplexity[0], 0);
    }

    protected PartCustomization setupRootCustomization(double vertOffset) {
        PartCustomization customization = new PartCustomization();

        customization.setPrimaryRenderType(FiguraRenderTypes.TRANSLUCENT);
        customization.setSecondaryRenderType(FiguraRenderTypes.EMISSIVE);

        double s = 1.0 / 16;
        customization.positionMatrix.scale(s, s, s);
        customization.positionMatrix.rotateZ(180);
        customization.positionMatrix.translate(0, vertOffset, 0);
        customization.normalMatrix.rotateZ(180);

        customization.positionMatrix.multiply(posMat);
        customization.normalMatrix.multiply(normalMat);

        customization.light = light;
        customization.alpha = alpha;
        customization.overlay = overlay;

        customization.primaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.PRIMARY, null);
        customization.secondaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.SECONDARY, null);

        return customization;
    }

    protected boolean renderPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate) {
        FiguraMod.pushProfiler(part.name);

        PartCustomization custom = part.customization;

        // test the current filter scheme
        FiguraMod.pushProfiler("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, prevPredicate);
        if (thisPassedPredicate == null || (!custom.visible)) {
            if (part.parentType.isRenderLayer)
                part.savedCustomization = customizationStack.peek();
            FiguraMod.popProfiler(2);
            return true;
        }

        // calculate part transforms

        // calculate vanilla parent
        FiguraMod.popPushProfiler("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // visibility
        FiguraMod.popPushProfiler("checkVanillaVisible");
        if (!ignoreVanillaVisibility && custom.vanillaVisible != null && !custom.vanillaVisible) {
            FiguraMod.popPushProfiler("removeVanillaTransforms");
            part.resetVanillaTransforms();
            FiguraMod.popProfiler(2);
            return true;
        }

        // pre render function
        if (part.preRender != null) {
            FiguraMod.popPushProfiler("preRenderFunction");
            avatar.run(part.preRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // recalculate stuff
        FiguraMod.popPushProfiler("calculatePartMatrices");
        custom.recalculate();

        // void blocked matrices
        // that's right, check only for previous predicate
        FiguraMat4 positionCopy = null;
        FiguraMat3 normalCopy = null;
        boolean voidMatrices = !allowHiddenTransforms && !prevPredicate;
        if (voidMatrices) {
            FiguraMod.popPushProfiler("clearMatrices");
            positionCopy = custom.positionMatrix.copy();
            normalCopy = custom.normalMatrix.copy();
            custom.positionMatrix.reset();
            custom.normalMatrix.reset();
        }

        // push stack
        FiguraMod.popPushProfiler("pushCustomizationStack");
        customizationStack.push(custom);

        // restore variables
        if (voidMatrices) {
            FiguraMod.popPushProfiler("restoreMatrices");
            custom.positionMatrix.set(positionCopy);
            custom.normalMatrix.set(normalCopy);
        }

        if (thisPassedPredicate) {
            // recalculate world matrices
            FiguraMod.popPushProfiler("worldMatrices");
            if (allowMatrixUpdate) {
                FiguraMat4 mat = partToWorldMatrices(custom);
                part.savedPartToWorldMat.set(mat);
            }

            // recalculate light
            FiguraMod.popPushProfiler("calculateLight");
            Level l;
            if (custom.light != null) {
                updateLight = false;
                pivotOffsetter.light = custom.light;
            }
            else if (updateLight && (l = Minecraft.getInstance().level) != null) {
                FiguraVec3 pos = part.savedPartToWorldMat.apply(0d, 0d, 0d);
                int block = l.getBrightness(LightLayer.BLOCK, pos.asBlockPos());
                int sky = l.getBrightness(LightLayer.SKY, pos.asBlockPos());
                customizationStack.peek().light = LightCoordsUtil.pack(block, sky);
            }

            if (custom.alpha != null)
                pivotOffsetter.alpha = custom.alpha;
            if (custom.overlay != null)
                pivotOffsetter.overlay = custom.overlay;
        }

        // mid render function
        if (part.midRender != null) {
            FiguraMod.popPushProfiler("midRenderFunction");
            avatar.run(part.midRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // render this
        FiguraMod.popPushProfiler("pushVertices");
        boolean breakRender = thisPassedPredicate && !part.pushVerticesImmediate(this, remainingComplexity);

        // render extras
        FiguraMod.popPushProfiler("extras");
        if (!breakRender && thisPassedPredicate) {
            boolean renderPivot = shouldRenderPivots > 0;
            boolean renderTasks = !part.renderTasks.isEmpty();
            boolean renderPivotParts = part.parentType.isPivot && allowPivotParts;

            // 부위 파츠의 발광을 기록한다 — 이 뒤 도는 바닐라 갑옷 · 아이템 레이어가 읽는다(피벗 파츠가 없는 아바타에서도
            // «왼팔 발광 → 왼팔 갑옷 · 아이템 발광» 이 되게)
            if (VANILLA_GLOW_PARTS.contains(part.parentType)) {
                int partGlow = explicitGlowOf(customizationStack.peek());
                if (partGlow != 0)
                    vanillaPartGlow.putIfAbsent(part.parentType, partGlow);
            }

            if (renderPivot || renderTasks || renderPivotParts) {
                // fix light and overlay
                PartCustomization parent = customizationStack.peek();
                int light = parent.light != null ? parent.light : LightCoordsUtil.FULL_BRIGHT;
                int overlay = parent.overlay != null ? parent.overlay : OverlayTexture.NO_OVERLAY;

                // fix pivots
                FiguraMod.pushProfiler("fixMatricesPivot");

                FiguraVec3 pivot = custom.getPivot().copy().add(custom.getOffsetPivot());
                pivotOffsetter.setPos(pivot);
                pivotOffsetter.recalculate();
                customizationStack.push(pivotOffsetter);

                PartCustomization peek = customizationStack.peek();

                // render pivot indicators
                if (renderPivot) {
                    FiguraMod.popPushProfiler("renderPivotCube");
                    renderPivot(part, peek);
                }

                // render tasks
                if (renderTasks) {
                    FiguraMod.popPushProfiler("renderTasks");
                    interceptRendersIntoFigura = false;
                    for (RenderTask task : part.renderTasks.values()) {
                        if (!task.shouldRender())
                            continue;
                        int neededComplexity = task.getComplexity();
                        if (neededComplexity > remainingComplexity[0])
                            break;
                        FiguraMod.pushProfiler(task.getName());
                        var taskPose = task.prepare(customizationStack);

                        // ⚠ 즉시 그리기(제출기 없음 — 머리 블록 등)면 직접 제출 태스크도 대기열로 — renderDirect 에 null 을 넘기면 죽는다
                        if (task.requiresDirectSubmit() && currentSubmitNodeCollector != null) {
                            task.renderDirect(taskPose, currentSubmitNodeCollector, light, overlay);
                        } else {
                            queuedRenderTasks.add(new FiguraSubmission.QueuedRenderTask(task, taskPose, light, overlay));
                        }

                        remainingComplexity[0] -= neededComplexity;
                        FiguraMod.popProfiler();
                    }
                    interceptRendersIntoFigura = true;
                }

                // render pivot parts
                if (renderPivotParts && part.parentType.isPivot) {
                    FiguraMod.popPushProfiler("savePivotParts");
                    savePivotTransform(part.parentType, peek);
                }

                customizationStack.pop();
                FiguraMod.popProfiler();
            }
        }

        // render children
        FiguraMod.popPushProfiler("children");
        for (FiguraModelPart child : List.copyOf(part.children)) {
            if (!renderPart(child, remainingComplexity, thisPassedPredicate)) {
                breakRender = true;
                break;
            }
        }

        // reset the parent
        FiguraMod.popPushProfiler("removeVanillaTransforms");
        part.resetVanillaTransforms();

        // post render function
        if (part.postRender != null) {
            FiguraMod.popPushProfiler("postRenderFunction");
            avatar.run(part.postRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // pop
        customizationStack.pop();
        FiguraMod.popProfiler(2);

        return !breakRender;
    }


    protected boolean getPartComplexity(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate) {
        PartCustomization custom = part.customization;

        // test the current filter scheme
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, prevPredicate);
        if (thisPassedPredicate == null || (!custom.visible)) {
            return true;
        }

        // visibility
        if (!ignoreVanillaVisibility && custom.vanillaVisible != null && !custom.vanillaVisible) {
            return true;
        }

        // calculate this part's complexity
        FiguraMod.popPushProfiler("pushVertices");
        boolean breakRender = thisPassedPredicate && !part.calculateComplexity(remainingComplexity);

        // calculate extras
        if (!breakRender && thisPassedPredicate) {
            boolean renderTasks = !part.renderTasks.isEmpty();
            // add tasks
            if (renderTasks) {

                for (RenderTask task : part.renderTasks.values()) {
                    if (!task.shouldRender())
                        continue;
                    int neededComplexity = task.getComplexity();
                    if (neededComplexity > remainingComplexity[0])
                        break;
                    FiguraMod.pushProfiler(task.getName());
                    remainingComplexity[0] -= neededComplexity;
                    FiguraMod.popProfiler();
                }
            }
        }

        // calculate children
        for (FiguraModelPart child : List.copyOf(part.children)) {
            if (!getPartComplexity(child, remainingComplexity, thisPassedPredicate)) {
                breakRender = true;
                break;
            }
        }
        return !breakRender;
    }

    protected void renderPivot(FiguraModelPart part, PartCustomization customization) {
        boolean group = part.customization.partType == PartCustomization.PartType.GROUP;
        FiguraVec3 color = group ? ColorUtils.Colors.FIGURA_BLUE.vec : ColorUtils.Colors.AWESOME_BLUE.vec;
        double boxSize = group ? 1 / 16d : 1 / 32d;
        boxSize /= Math.max(Math.cbrt(part.savedPartToWorldMat.det()), 0.02);

        PoseStack poseStack = customization.copyIntoNewPoseStack();

        queuedPivotBoxes.add(new FiguraSubmission.QueuedPivotBox(poseStack.last(),
                -boxSize, -boxSize, -boxSize,
                boxSize, boxSize, boxSize,
                (float) color.x, (float) color.y, (float) color.z, 1f));
    }

    public static void renderLineBox(PoseStack.Pose pose, VertexConsumer vertices, double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a) {
        float f = (float)x1;
        float h = (float)y1;
        float i = (float)z1;
        float j = (float)x2;
        float k = (float)y2;
        float l = (float)z2;
        vertices.addVertex(pose, f, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, j, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, f, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, f, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, f, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vertices.addVertex(pose, f, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vertices.addVertex(pose, j, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, j, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, j, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, -1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, f, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, -1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, f, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vertices.addVertex(pose, f, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vertices.addVertex(pose, f, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, -1.0F, 0.0F);
        vertices.addVertex(pose, f, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, -1.0F, 0.0F);
        vertices.addVertex(pose, f, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, j, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, j, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, -1.0F);
        vertices.addVertex(pose, j, h, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, -1.0F);
        vertices.addVertex(pose, f, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, j, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 1.0F, 0.0F, 0.0F);
        vertices.addVertex(pose, j, h, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, j, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 1.0F, 0.0F);
        vertices.addVertex(pose, j, k, i).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vertices.addVertex(pose, j, k, l).setColor(r, g, b, a).setLineWidth(2f).setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    protected void savePivotTransform(ParentType parentType, PartCustomization customization) {
        FiguraMat4 currentPosMat = customization.getPositionMatrix();
        FiguraMat3 currentNormalMat = customization.getNormalMatrix();
        ConcurrentLinkedQueue<PivotTransform> queue = pivotCustomizations.computeIfAbsent(parentType, p -> new ConcurrentLinkedQueue<>());
        queue.add(new PivotTransform(currentPosMat, currentNormalMat, explicitGlowOf(customization))); // These are COPIES, so ok to add
    }

    private static final java.util.EnumSet<ParentType> VANILLA_GLOW_PARTS = java.util.EnumSet.of(
            ParentType.Head, ParentType.Body,
            ParentType.LeftArm, ParentType.RightArm,
            ParentType.LeftLeg, ParentType.RightLeg);

    // setGlow 가 켜진 파츠만의 발광색(ARGB · 0 = 없음) — 바닐라 갑옷 · 아이템은 이 색이 없으면 엔티티 자신의 바깥선 색을 쓴다
    private int explicitGlowOf(PartCustomization customization) {
        if (!Boolean.TRUE.equals(customization.glow))
            return 0;
        if (customization.glowColor != null)
            return ColorUtils.rgbToInt(customization.glowColor) | 0xFF000000;
        return outlineColor != 0 ? outlineColor | 0xFF000000 : 0xFFFFFFFF;
    }

    protected FiguraMat4 partToWorldMatrices(PartCustomization cust) {
        FiguraMat4 customizePeek = customizationStack.peek().positionMatrix.copy();
        // Translate by the inverse matrix of the camera position, as of 1.20.5 it is no longer dependent on camera rot.
        customizePeek.multiply(CAMERA_POS_TO_WORLD_MATRIX);
        FiguraVec3 piv = cust.getPivot();

        FiguraMat4 translation = FiguraMat4.of();
        translation.translate(piv);
        customizePeek.rightMultiply(translation);

        return customizePeek;
    }

    protected void calculatePartMatrices(FiguraModelPart part) {
        FiguraMod.pushProfiler(part.name);

        PartCustomization custom = part.customization;

        // Store old visibility, but overwrite it in case we only want to render certain parts
        FiguraMod.pushProfiler("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, true);
        if (thisPassedPredicate == null) {
            FiguraMod.popProfiler(2);
            return;
        }

        // 숨긴 하위 트리는 건너뛴다 — renderPart 와 같다(1인칭에서만 숨긴 파츠까지 돌던 유일한 자리)
        // ⚠ 숨긴 파츠의 partToWorldMatrix 는 1인칭에서도 마지막으로 그린 값에 멈춘다(다른 경로는 원래 그랬다)
        if (!custom.visible) {
            FiguraMod.popProfiler(2);
            return;
        }

        // calculate part transforms

        // calculate vanilla parent
        FiguraMod.popPushProfiler("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // push customization stack
        FiguraMod.popPushProfiler("calculatePartMatrices");
        custom.recalculate();
        FiguraMod.popPushProfiler("applyOnStack");
        customizationStack.push(custom);

        // render extras
        if (thisPassedPredicate) {
            // part to world matrices
            FiguraMod.popPushProfiler("worldMatrices");
            FiguraMat4 mat = partToWorldMatrices(custom);
            part.savedPartToWorldMat.set(mat);
        }

        // render children
        FiguraMod.popPushProfiler("children");
        for (FiguraModelPart child : part.children)
            calculatePartMatrices(child);

        // reset the parent
        part.resetVanillaTransforms();

        // pop
        customizationStack.pop();
        FiguraMod.popProfiler(2);
    }

    public void pushFaces(int faceCount, int[] remainingComplexity, FiguraTextureSet textureSet, List<Vertex> vertices) {
        // Handle cases that we can quickly
        if (faceCount == 0 || vertices.isEmpty())
            return;

        PartCustomization customization = customizationStack.peek();

        VertexData primary = getTexture(customization, textureSet, true);
        VertexData secondary = getTexture(customization, textureSet, false);

        if (primary.renderType == null && secondary.renderType == null) {
            remainingComplexity[0] += faceCount;
            return;
        }

        // 바깥선은 주 렌더 타입만 — 보조 레이어(반짝임 등 덧칠)는 바깥선 모양을 바꾸지 않는다(바닐라 submitModel 도 주 모델만)
        int glowColor = currentSubmitNodeCollector != null ? glowColorOf(customization) : 0;
        if (primary.renderType != null)
            pushToBuffer(faceCount, primary, customization, textureSet, vertices, glowColor);
        if (secondary.renderType != null)
            pushToBuffer(faceCount, secondary, customization, textureSet, vertices, 0);
    }

    // 파츠 발광색(ARGB · 0 = 없음) — setGlow 가 켜진 파츠는 그 색(색이 없으면 아바타 바깥선 색 · 그것도 없으면 흰색) ·
    // 아니면 아바타 바깥선 색(엔티티 발광의 팀 색 · renderer:setOutlineColor)
    private int glowColorOf(PartCustomization customization) {
        int explicit = explicitGlowOf(customization);
        if (explicit != 0)
            return explicit;
        return outlineColor == 0 ? 0 : outlineColor | 0xFF000000;
    }

    private VertexData getTexture(PartCustomization customization, FiguraTextureSet textureSet, boolean primary) {
        FiguraRenderTypes types = primary ? customization.getPrimaryRenderType() : customization.getSecondaryRenderType();
        TextureCustomization texture = primary ? customization.primaryTexture : customization.secondaryTexture;
        VertexData ret = new VertexData();

        if (types == FiguraRenderTypes.NONE)
            return ret;

        // get texture
        Identifier id = textureSet.getOverrideTexture(avatar.owner, texture);

        // color
        ret.color = primary ? customization.color : customization.color2;

        // primary
        ret.primary = primary;

        // get render type
        if (id != null) {
            if (translucent) {
                ret.renderType = RenderTypes.entityTranslucentCull(id);
                return ret;
            }
            if (glowing) {
                ret.renderType = RenderTypes.outline(id);
                return ret;
            }
        }

        if (types == null)
            return ret;

        if (offsetRenderLayers && !primary && types.isOffset())
            ret.vertexOffset = FiguraMod.VERTEX_OFFSET;

        // Switch to cutout with fullbright if the iris emissive fix is enabled
        if (doIrisEmissiveFix && types == FiguraRenderTypes.EMISSIVE) {
            ret.fullBright = true;
            ret.renderType = FiguraRenderTypes.TRANSLUCENT_CULL.get(id);
        } else {
            ret.renderType = types.get(id);
        }

        return ret;
    }

    private static final FiguraVec4 pos = FiguraVec4.of();
    private static final FiguraVec3 normal = FiguraVec3.of();
    private static final FiguraVec3 uv = FiguraVec3.of(0, 0, 1);
    private void pushToBuffer(int faceCount, VertexData vertexData, PartCustomization customization, FiguraTextureSet textureSet, List<Vertex> vertices, int glowColor) {
        int vertCount = faceCount * 4;

        FiguraVec3 uvFixer = FiguraVec3.of();
        uvFixer.set(textureSet.getWidth(), textureSet.getHeight(), 1); // Dividing by this makes uv 0 to 1

        int overlay = customization.overlay;
        int light = vertexData.fullBright ? LightCoordsUtil.FULL_BRIGHT : customization.light;

        Consumer<VertexConsumer> writer = vertexConsumer -> {
            // 26.3 선 형식(POSITION_COLOR_NORMAL_LINE_WIDTH)은 정점마다 굵기를 받는다 — 빠지면 «Missing elements in vertex» 크래시.
            // 1.21.8 lines() 의 기본 굵기(창 크기에 맞춘 값)와 같게 · 선 형식이 아니면 BufferBuilder 가 무시한다
            float lineWidth = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
            for (int i = 0; i < vertCount; i++) {
                Vertex vertex = vertices.get(i);

                pos.set(vertex.x, vertex.y, vertex.z, 1);
                pos.transform(customization.positionMatrix);
                pos.add(pos.normalized().scale(vertexData.vertexOffset));
                normal.set(vertex.nx, vertex.ny, vertex.nz);
                normal.transform(customization.normalMatrix);
                uv.set(vertex.u, vertex.v, 1);
                uv.divide(uvFixer);
                uv.transform(customization.uvMatrix);

                vertexConsumer
                        .addVertex((float) pos.x, (float) pos.y, (float) pos.z)
                        .setColor((float) vertexData.color.x, (float) vertexData.color.y, (float) vertexData.color.z, customization.alpha)
                        .setUv((float) uv.x, (float) uv.y)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setLineWidth(lineWidth)
                        .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
            }
        };

        RenderType renderType = vertexData.renderType;
        // 투명 + 발광은 본 렌더 타입이 바깥선 타입이다 — 주 화면(solid)에 그리지 말고 outline 단계로만
        if (renderType.isOutline() && currentSubmitNodeCollector != null) {
            if (glowColor != 0)
                vertexBuffer.addOutline(renderType, vc -> writer.accept(new ConstantColorVertexConsumer(vc, glowColor)));
            return;
        }

        vertexBuffer.getBufferFor(renderType, vertexData.primary, writer);
        // 발광 — 같은 정점을 바닐라 바깥선 렌더 타입에 파츠 발광색으로 한 번 더(색은 정점 색 · 바깥선 짝이 없는 타입은 바깥선이 없다)
        if (glowColor != 0)
            renderType.outline().ifPresent(outline ->
                    vertexBuffer.addOutline(outline, vc -> writer.accept(new ConstantColorVertexConsumer(vc, glowColor))));
    }

    private static class VertexData {
        public RenderType renderType;
        public boolean fullBright;
        public float vertexOffset;
        public FiguraVec3 color;
        public boolean primary;
    }

    private static class VertexBuffer {
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> primaryBuffers = new LinkedHashMap<>();
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> secondaryBuffers = new LinkedHashMap<>();
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> outlineBuffers = new LinkedHashMap<>();

        public void getBufferFor(RenderType renderType, boolean primary, Consumer<VertexConsumer> consumer) {
            HashMap<RenderType, List<Consumer<VertexConsumer>>> buffer = primary ? primaryBuffers : secondaryBuffers;
            List<Consumer<VertexConsumer>> list = buffer.computeIfAbsent(renderType, renderType1 -> new ArrayList<>());
            list.add(consumer);
        }

        public void addOutline(RenderType outlineType, Consumer<VertexConsumer> consumer) {
            outlineBuffers.computeIfAbsent(outlineType, t -> new ArrayList<>()).add(consumer);
        }
    }
}
