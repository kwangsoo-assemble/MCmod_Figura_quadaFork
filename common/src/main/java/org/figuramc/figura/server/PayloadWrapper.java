package org.figuramc.figura.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.utils.FriendlyByteBufWrapper;

import java.util.function.Function;

public class PayloadWrapper<P extends Packet> implements CustomPacketPayload {
    private final P source;
    public static final Function<Identifier,Type<PayloadWrapper>> TYPE = id -> CustomPacketPayload.createType(id.toString());

    public PayloadWrapper(P source) {
        this.source = source;
    }


    public void write(FriendlyByteBuf buf) {
        source.write(new FriendlyByteBufWrapper(buf));
    }

    public P source() {
        return source;
    }

    public Identifier id() {
        var id = source.getId();
        return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
    }

    @Override
    public Type<PayloadWrapper<P>> type() {
        return new Type<>(id());
    }
}
