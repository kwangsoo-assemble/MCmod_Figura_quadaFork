package org.figuramc.figura.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.utils.FriendlyByteBufWrapper;

public class FSBCodec<P extends Packet> implements StreamCodec<FriendlyByteBuf, PayloadWrapper<P>> {
    private final Packet.Deserializer<P> deserializer;

    public FSBCodec(Packet.Deserializer<P> deserializer) {
        this.deserializer = deserializer;
    }

    @Override
    public PayloadWrapper<P> decode(FriendlyByteBuf buf) {
        P packet = deserializer.read(new FriendlyByteBufWrapper(buf));
        return new PayloadWrapper<>(packet);
    }

    @Override
    public void encode(FriendlyByteBuf buf, PayloadWrapper wrapper) {
        wrapper.write(buf);
    }
}
