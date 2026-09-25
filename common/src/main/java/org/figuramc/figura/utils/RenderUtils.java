package org.figuramc.figura.utils;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.lua.api.vanilla_model.VanillaPart;
import org.figuramc.figura.model.ParentType;
import org.figuramc.figura.permissions.Permissions;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class RenderUtils {

    /**
     * Whether the current render pass of this avatar may draw to the outline target.
     *
     * <p>Warning: removing this guard <b>causes a crash.</b> The output target of {@code RenderType.outline(...)}
     * is {@code levelRenderer.entityOutlineTarget()}, and that handle becomes null once the level frame graph
     * finishes. Drawing from the HUD, GUI, paper doll or wardrobe then throws an NPE.
     *
     * <p>{@code FIRST_PERSON_WORLD} is safe: that pass runs inside the main pass lambda, before
     * {@code endOutlineBatch} (also confirmed by in-game measurement).
     */
    /**
     * Whether rendering is currently inside the main level pass. {@code LevelRendererMixinFabric} sets it on
     * entry and exit.
     *
     * <p>This used to be decided from {@code avatar.renderMode}, which was <b>wrong.</b> Measurements showed
     * that when Iris replaces {@code renderEntities}, the {@code @ModifyArg} that sets
     * {@code renderMode = RENDER} never fires, and {@code worldRender} restores the previous value (OTHER) at
     * the end, so <b>renderMode was OTHER when the layers rendered</b>.
     * What the guard actually needs to know is "is the outline target valid", so it tracks exactly that.
     */
    public static boolean figura$inLevelPass = false;

    /**
     * Whether drawing to the outline target is allowed.
     *
     * <p>Warning: removing this guard <b>causes a crash.</b> The output target of {@code RenderType.outline(...)}
     * is {@code levelRenderer.entityOutlineTarget()}, and that handle becomes null once the level frame graph
     * finishes. Drawing from the HUD, GUI, paper doll or first-person hand render then throws an NPE.
     */
    public static boolean outlineTargetAvailable(Avatar avatar) {
        return avatar != null && figura$inLevelPass;
    }

    /**
     * If glow is set on the pivot part, wraps the buffer source so that the <b>vanilla</b> elements drawn at
     * that spot (armor, items, etc.) glow along with it. Otherwise returns the original source unchanged.
     *
     * <p>For non-outline render types, {@code OutlineBufferSource.getBuffer} returns a multi-consumer that
     * writes to <b>both the normal buffer and the outline buffer</b>. So simply wrapping the source draws
     * everything exactly as before while also leaving a silhouette in the outline target.
     *
     * <p>Warning: the color is captured at {@code getBuffer} time, so setColor <b>must be called before
     * wrapping</b>.
     * Warning: a source that is already an {@code OutlineBufferSource} is not wrapped twice (the case where
     * the entity itself is really glowing).
     */
    public static MultiBufferSource pivotGlowBuffer(Avatar avatar, MultiBufferSource source) {
        return figura$glowWrap(avatar, source, avatar == null ? null : avatar.currentPivotGlowColor);
    }

    /**
     * Carries the glow of <b>body parts</b> (head, body, arms, legs) over to vanilla armor and items.
     * If any of the given body parts is glowing, wraps the source with that color.
     *
     * <p>This is the path for avatars without pivots. Such avatars draw each armor piece as a whole, so the
     * <b>glow also covers whole pieces</b>. To split it per part, add pivot groups to the model; it then
     * automatically takes the {@link #pivotGlowBuffer} path and becomes precise.
     */
    public static MultiBufferSource vanillaPartGlowBuffer(Avatar avatar, MultiBufferSource source, ParentType... types) {
        if (avatar == null || avatar.renderer == null)
            return source;
        return figura$glowWrap(avatar, source, avatar.renderer.findVanillaPartGlow(types));
    }

    /**
     * If there is a glow color, wraps the source in a buffer that also feeds the outline target.
     *
     * <p>For non-outline render types, {@code OutlineBufferSource.getBuffer} returns a multi-consumer that
     * writes to <b>both the normal buffer and the outline buffer</b>.
     * Warning: the color is captured at {@code getBuffer} time, so setColor must be called <b>before</b> wrapping.
     * Warning: a source that is already an {@code OutlineBufferSource} is not wrapped twice (the case where the
     * entity really has the glowing effect).
     */
    private static MultiBufferSource figura$glowWrap(Avatar avatar, MultiBufferSource source, FiguraVec3 color) {
        if (color == null || avatar == null || source instanceof OutlineBufferSource || !outlineTargetAvailable(avatar))
            return source;

        OutlineBufferSource outline = Minecraft.getInstance().renderBuffers().outlineBufferSource();
        outline.setColor(
                (int) (Math.min(Math.max(color.x, 0), 1) * 255),
                (int) (Math.min(Math.max(color.y, 0), 1) * 255),
                (int) (Math.min(Math.max(color.z, 0), 1) * 255),
                0xFF // the outline shader forces alpha to 1
        );
        return outline;
    }

    public static boolean vanillaModel(Avatar avatar) {
        return avatar != null && avatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) >= 1;
    }

    public static boolean vanillaModelAndScript(Avatar avatar) {
        return avatar != null && avatar.luaRuntime != null && avatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) >= 1;
    }

    public static TextureAtlasSprite firstFireLayer(Avatar avatar) {
        if (!vanillaModelAndScript(avatar))
            return null;

        ResourceLocation layer = avatar.luaRuntime.renderer.fireLayer1;
        return layer != null ? Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer) : null;
    }

    public static TextureAtlasSprite secondFireLayer(Avatar avatar) {
        if (!vanillaModelAndScript(avatar))
            return null;

        ResourceLocation layer1 = avatar.luaRuntime.renderer.fireLayer1;
        ResourceLocation layer2 = avatar.luaRuntime.renderer.fireLayer2;

        if (layer2 != null)
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer2);
        if (layer1 != null)
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer1);

        return null;
    }

    public static VanillaPart partFromSlot(Avatar avatar, EquipmentSlot equipmentSlot) {
        if (!RenderUtils.vanillaModelAndScript(avatar))
            return null;

        return switch (equipmentSlot) {
            case HEAD -> avatar.luaRuntime.vanilla_model.HELMET;
            case CHEST -> avatar.luaRuntime.vanilla_model.CHESTPLATE;
            case LEGS -> avatar.luaRuntime.vanilla_model.LEGGINGS;
            case FEET -> avatar.luaRuntime.vanilla_model.BOOTS;
            default -> null;
        };
    }

    public static VanillaPart pivotToPart(Avatar avatar, ParentType type) {
        if (!RenderUtils.vanillaModelAndScript(avatar))
            return null;

        return switch (type) {
            case HelmetPivot -> avatar.luaRuntime.vanilla_model.HELMET;
            case ChestplatePivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE;
            case LeftShoulderPivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE_LEFT_ARM;
            case RightShoulderPivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE_RIGHT_ARM;
            case LeggingsPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS;
            case LeftLeggingPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS_LEFT_LEG;
            case RightLeggingPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS_RIGHT_LEG;
            case LeftBootPivot -> avatar.luaRuntime.vanilla_model.BOOTS_LEFT_LEG;
            case RightBootPivot -> avatar.luaRuntime.vanilla_model.BOOTS_RIGHT_LEG;
            case LeftElytraPivot -> avatar.luaRuntime.vanilla_model.LEFT_ELYTRA;
            case RightElytraPivot -> avatar.luaRuntime.vanilla_model.RIGHT_ELYTRA;
            default -> null;
        };
    }

    public static EquipmentSlot slotFromPart(ParentType type) {
        switch (type){
            case Head, HelmetItemPivot, HelmetPivot, Skull -> {
                return EquipmentSlot.HEAD;
            }
            case Body, ChestplatePivot, LeftShoulderPivot, RightShoulderPivot, LeftElytra, RightElytra, RightElytraPivot, LeftElytraPivot -> {
                return EquipmentSlot.CHEST;
            }
            case LeftArm, LeftItemPivot, LeftSpyglassPivot -> {
                return EquipmentSlot.OFFHAND;
            }
            case RightArm, RightItemPivot, RightSpyglassPivot -> {
                return EquipmentSlot.MAINHAND;
            }
            case LeftLeggingPivot, RightLeggingPivot, LeftLeg, RightLeg, LeggingsPivot -> {
                return EquipmentSlot.LEGS;
            }
            case LeftBootPivot, RightBootPivot -> {
                return EquipmentSlot.FEET;
            }
            default -> {
                return null;
            }
        }

    }

    public static boolean renderArmItem(Avatar avatar, boolean lefty, CallbackInfo ci) {
        if (!vanillaModel(avatar))
            return false;

        if (avatar.luaRuntime != null && (
                lefty && !avatar.luaRuntime.vanilla_model.LEFT_ITEM.checkVisible() ||
                !lefty && !avatar.luaRuntime.vanilla_model.RIGHT_ITEM.checkVisible()
        )) {
            ci.cancel();
            return false;
        }

        return true;
    }

    @ExpectPlatform
    public static ResourceLocation getPlayerSkinTexture(WingsLayer<?, ?> wingsLayer, HumanoidRenderState renderState) {
        throw new AssertionError();
    }
}
