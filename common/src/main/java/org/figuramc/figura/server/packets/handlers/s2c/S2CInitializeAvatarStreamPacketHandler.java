package org.figuramc.figura.server.packets.handlers.s2c;

import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.server.packets.s2c.S2CInitializeAvatarStreamPacket;
import org.figuramc.figura.server.utils.IFriendlyByteBuf;

/**
 * Accepts {@code figura:s2c/stream/init}, which the server sends ahead of the data chunks of every avatar stream.
 * <p>
 * Upstream (1.20.6 FSB) registered this payload type but no receiver for it, so every avatar download made vanilla log
 * {@code Unknown custom packet payload: figura:s2c/stream/init} and drop the packet.
 * Delivery was never affected: {@code FSB.getAvatar} creates the input stream under its stream id <b>before</b> sending
 * the request, and the data chunks attach to it by that id. The only extra information here is the receiver's owner
 * ehash as recorded by the server.
 * <p>
 * Do not use that ehash to revive the ownership check at the end of the stream. {@code FSB.applyUserData} already checks
 * ownership of one's own avatar at the userdata stage; checking again here only adds a way for one's own avatar to be
 * rejected when the server's owner record disagrees with its equip record (e.g. after a cleanup).
 */
public class S2CInitializeAvatarStreamPacketHandler extends ConnectedPacketHandler<S2CInitializeAvatarStreamPacket> {
    @Override
    protected void handlePacket(S2CInitializeAvatarStreamPacket packet) {
        FiguraMod.debug("FSB avatar stream {} started by server", packet.streamId());
    }

    @Override
    public S2CInitializeAvatarStreamPacket serialize(IFriendlyByteBuf byteBuf) {
        return new S2CInitializeAvatarStreamPacket(byteBuf);
    }
}
