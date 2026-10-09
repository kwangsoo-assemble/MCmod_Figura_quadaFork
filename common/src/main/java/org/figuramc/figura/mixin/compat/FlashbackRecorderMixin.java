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
 * Puts server_data into Flashback recording snapshots — on rewind playback starts from that snapshot and the store returns to that point
 * (so the server never needs to resend everything periodically · verified: payloads of 46 B and 29,000 B came back on every rewind).
 * {@code Recorder.writeCustomSnapshot(Consumer<Packet<? super ClientGamePacketListener>>)} is an empty extension point (Flashback 0.39.10).
 * Without Flashback the {@code @Pseudo} mixin is silently skipped.
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
