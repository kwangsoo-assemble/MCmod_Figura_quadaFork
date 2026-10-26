package org.figuramc.figura.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.backend2.FSB;
import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.gui.ActionWheel;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.gui.PopupMenu;
import org.figuramc.figura.gui.screens.WardrobeScreen;
import org.figuramc.figura.lua.FiguraLuaPrinter;
import org.figuramc.figura.utils.EntityUtils;
import org.figuramc.figura.utils.FiguraText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow @Final public MouseHandler mouseHandler;
    @Shadow @Final public Options options;
    @Shadow public LocalPlayer player;

    // @Shadow public abstract void setScreen(@Nullable Screen screen);
    @Final
    @Shadow public Gui gui;
    @Shadow public abstract Entity getCameraEntity();
    @Shadow public net.minecraft.world.phys.HitResult hitResult;

    @Unique
    private boolean scriptMouseUnlock = false;

    // 디버그 막대기로 다른 플레이어를 우클릭하면 남 세팅용 옷장 — 리플레이(Replay Viewer) · 싱글 · 멀티 · FSB 멀티.
    // 상호작용 패킷은 보내지 않는다. 블록 · 공중 · 플레이어 아닌 엔티티 우클릭은 바닐라 그대로(디버그 막대기 원래 기능)
    @Inject(at = @At("HEAD"), method = "startUseItem", cancellable = true)
    private void figura$debugStickNonhostWardrobe(CallbackInfo ci) {
        if (this.player == null || !this.player.getMainHandItem().is(net.minecraft.world.item.Items.DEBUG_STICK))
            return;
        if (!(this.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) || !(hit.getEntity() instanceof Player target) || target == this.player)
            return;

        ci.cancel();
        this.gui.setScreen(new org.figuramc.figura.gui.screens.NonhostWardrobeScreen(null, target));
    }

    @Inject(at = @At("RETURN"), method = "handleKeybinds")
    private void handleKeybinds(CallbackInfo ci) {
        // don't handle keybinds on panic
        if (AvatarManager.panic)
            return;

        // reload avatar button
        if (Configs.RELOAD_BUTTON.keyBind.consumeClick()) {
            AvatarManager.reloadAvatar(FiguraMod.getLocalPlayerUUID());
            FiguraToast.sendToast(FiguraText.of("toast.reload"));
        }

        // reload avatar button
        if (Configs.WARDROBE_BUTTON.keyBind.consumeClick())
            this.gui.setScreen(new WardrobeScreen(null));

        // action wheel button
        Boolean wheel = null;
        if (Configs.ACTION_WHEEL_MODE.value % 2 == 1) {
            if (Configs.ACTION_WHEEL_BUTTON.keyBind.consumeClick())
                wheel = !ActionWheel.isEnabled();
        } else if (Configs.ACTION_WHEEL_BUTTON.keyBind.isDown()) {
            wheel = true;
        } else if (ActionWheel.isEnabled()) {
            wheel = false;
        }
        if (wheel != null) {
            if (wheel) {
                ActionWheel.setEnabled(true);
                this.mouseHandler.releaseMouse();
            } else {
                if (Configs.ACTION_WHEEL_MODE.value >= 2)
                    ActionWheel.execute(ActionWheel.getSelected(), true);
                ActionWheel.setEnabled(false);
                this.mouseHandler.grabMouse();
            }
        }

        // popup menu button
        if (Configs.POPUP_BUTTON.keyBind.isDown()) {
            PopupMenu.setEnabled(true);

            if (!PopupMenu.hasEntity()) {
                Entity target = FiguraMod.extendedPickEntity;
                if (this.player != null && target instanceof Player && !target.isInvisibleTo(this.player)) {
                    PopupMenu.setEntity(target);
                } else if (!this.options.getCameraType().isFirstPerson()) {
                    PopupMenu.setEntity(this.getCameraEntity());
                }
            }
        } else if (PopupMenu.isEnabled()) {
            PopupMenu.run();
        }

        // unlock cursor :p
        Avatar avatar = AvatarManager.getAvatarForPlayer(FiguraMod.getLocalPlayerUUID());
        if (avatar != null && avatar.luaRuntime != null && avatar.luaRuntime.host.unlockCursor) {
            this.mouseHandler.releaseMouse();
            scriptMouseUnlock = true;
        } else if (scriptMouseUnlock) {
            this.mouseHandler.grabMouse();
            scriptMouseUnlock = false;
        }
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), method = "handleKeybinds", locals = LocalCapture.CAPTURE_FAILSOFT)
    private void handleHotbarSlots(CallbackInfo ci, int i) {
        if (PopupMenu.isEnabled())
            PopupMenu.hotbarKeyPressed(i);
        if (ActionWheel.isEnabled())
            ActionWheel.hotbarKeyPressed(i);
    }

    @Inject(at = @At("RETURN"), method = "clearClientLevel")
    private void clearLevel(Screen screen, CallbackInfo ci) {
        AvatarManager.resetPinsAndLock(); // 월드(리플레이 포함)를 나가면 nonhost_load pin · netlock 이 풀린다
        AvatarManager.clearAllAvatars();
        FiguraLuaPrinter.clearPrintQueue();
        NetworkStuff.unsubscribeAll();
        org.figuramc.figura.serverdata.ServerDataStore.clear(); // server_data — 월드(리플레이 포함)를 나가면 비운다
    }

    @Inject(at = @At("HEAD"), method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V")
    private void onDisconnect(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        FSB.instance().onDisconnect();
    }

    @Inject(at = @At("RETURN"), method = "setLevel")
    private void setLevel(ClientLevel world, CallbackInfo ci) {
        NetworkStuff.auth();
    }

    @Inject(at = @At("HEAD"), method = "runTick")
    private void preTick(boolean tick, CallbackInfo ci) {
        AvatarManager.executeAll("applyBBAnimations", Avatar::applyAnimations);
    }

    @Inject(at = @At("RETURN"), method = "runTick")
    private void afterTick(boolean tick, CallbackInfo ci) {
        AvatarManager.executeAll("clearBBAnimations", Avatar::clearAnimations);
    }

    @Inject(at = @At("RETURN"), method = "tick")
    private void startTick(CallbackInfo ci) {
        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.tick();
        FiguraMod.popProfiler();
    }

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void pick(float tickDelta, CallbackInfo ci) {
        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler("extendedPick");
        FiguraMod.extendedPickEntity = EntityUtils.getViewedEntity(32);
        FiguraMod.popProfiler(2);
    }
}
