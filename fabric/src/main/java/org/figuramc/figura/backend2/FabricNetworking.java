package org.figuramc.figura.backend2;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.figuramc.figura.server.FSBCodec;
import org.figuramc.figura.server.PayloadWrapper;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.packets.Packets;
import org.figuramc.figura.server.packets.Side;

import java.util.function.Consumer;

public class FabricNetworking {
    public static void init(final Consumer<CustomPacketPayload.Type<PayloadWrapper<Packet>>> registrar, Side currentSide) {
        Packets.forEachPacket((id, desc) -> {
            ResourceLocation resLoc = ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
            Side side = desc.side();
            final CustomPacketPayload.Type<PayloadWrapper<Packet>> type = new CustomPacketPayload.Type<>(resLoc);
            if (side.sentBy(Side.SERVER)) PayloadTypeRegistry.playS2C().register(type, new FSBCodec(desc.constructor()));
            if (side.sentBy(Side.CLIENT)) PayloadTypeRegistry.playC2S().register(type, new FSBCodec(desc.constructor()));
            if (side.receivedBy(currentSide)) registrar.accept(type);
        });
    }
}
