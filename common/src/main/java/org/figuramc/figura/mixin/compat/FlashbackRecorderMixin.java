package org.figuramc.figura.mixin.compat;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.server.PayloadWrapper;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.serverdata.ServerDataStore;
import kr.asmbl.figuracontroller.protocol.Protocol;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * server_data 를 Flashback 녹화 스냅샷에 넣는다 — 되감기 때 그 스냅샷부터 재생되며 저장소가 그 시점으로 돌아간다
 * (서버가 주기적으로 전체를 다시 보낼 까닭이 없다 · 하네스 figura_controller 설계 §3.5 · T1 스파이크 실측: 46 B · 29,000 B 둘 다 되감기마다 돌아왔다).
 * {@code Recorder.writeCustomSnapshot(Consumer<Packet<? super ClientGamePacketListener>>)} 는 몸이 빈 확장 자리다(Flashback 0.39.10).
 * Flashback 이 없으면 {@code @Pseudo} 라 조용히 빠진다.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.record.Recorder")
public class FlashbackRecorderMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "writeCustomSnapshot", at = @At("HEAD"), remap = false, require = 0)
    private void figura$serverDataSnapshot(Consumer consumer, CallbackInfo ci) {
        try {
            UUID owner = ServerDataStore.snapshotOwner();
            for (byte[] body : ServerDataStore.snapshotBodies()) {
                consumer.accept(new ClientboundCustomPayloadPacket(new PayloadWrapper<>(new CustomFSBPacket(owner, Protocol.CHANNEL_ID, body))));
            }
        } catch (Throwable e) {
            FiguraMod.LOGGER.warn("[server_data] Failed to write the Flashback snapshot", e);
        }
    }
}
