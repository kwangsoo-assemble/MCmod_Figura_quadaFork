package org.figuramc.figura.server.packets.handlers.s2c;

import org.figuramc.figura.backend2.FSB;
import org.figuramc.figura.compat.FlashbackCompat;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.utils.IFriendlyByteBuf;

public abstract class ConnectedPacketHandler<T extends Packet> implements S2CPacketHandler<T> {
    @Override
    public void handle(T packet) {
        // During Flashback replay the client is not connected to an FSB server, but the recorded
        // packets are legitimately being replayed and must still be processed.
        if (FSB.instance().connected() || FlashbackCompat.isInReplay()) handlePacket(packet);
    }

    protected abstract void handlePacket(T packet);
}
