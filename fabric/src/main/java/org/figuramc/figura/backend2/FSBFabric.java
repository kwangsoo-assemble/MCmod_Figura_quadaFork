package org.figuramc.figura.backend2;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.figuramc.figura.server.PayloadWrapper;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.utils.FriendlyByteBufWrapper;

public class FSBFabric extends FSB {
    @Override
    public void sendPacket(Packet packet) {
        ClientPlayNetworking.send(new PayloadWrapper<>(packet));
    }
}
