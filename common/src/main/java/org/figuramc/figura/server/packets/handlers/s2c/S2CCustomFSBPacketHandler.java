package org.figuramc.figura.server.packets.handlers.s2c;

import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.lua.api.ServerPacketsAPI;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.server.utils.IFriendlyByteBuf;

public class S2CCustomFSBPacketHandler extends ConnectedPacketHandler<CustomFSBPacket> {
    @Override
    protected void handlePacket(CustomFSBPacket packet) {
        // server_data (figuracontroller:v1) goes to the core store before avatars — nothing is lost while an avatar is not loaded yet
        if (org.figuramc.figura.serverdata.ServerDataStore.intercept(packet)) return;
        ServerPacketsAPI.handlePacket(packet.avatarOwner(), packet.id(), packet.data());
    }

    @Override
    public CustomFSBPacket serialize(IFriendlyByteBuf byteBuf) {
        return new CustomFSBPacket(byteBuf);
    }
}
