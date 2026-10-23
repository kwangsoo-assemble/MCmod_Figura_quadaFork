package org.figuramc.figura.server.packets.handlers.s2c;

import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.server.packets.s2c.S2CInitializeAvatarStreamPacket;
import org.figuramc.figura.server.utils.IFriendlyByteBuf;

/**
 * 서버가 아바타 스트림마다 데이터 조각보다 먼저 보내는 {@code figura:s2c/stream/init} 을 받기만 한다.
 * <p>
 * 원본(1.20.6 FSB)은 이 패킷의 형식만 등록하고 수신기를 두지 않았다 — 그래서 아바타를 받을 때마다 바닐라가
 * {@code Unknown custom packet payload: figura:s2c/stream/init} 경고를 찍고 버렸다.
 * 전달에는 영향이 없었다: 받을 자리는 {@code FSB.getAvatar} 가 요청 <b>전에</b> streamId 로 만들어 두고,
 * 데이터 조각은 그 streamId 로 붙는다. 이 패킷이 더 알려 주는 것은 서버가 기록한 «받는 사람의 소유 ehash» 뿐이다.
 * <p>
 * ⚠ 그 ehash 로 스트림 끝의 소유권 검사를 되살리지 마라. 자기 아바타 소유권은 {@code FSB.applyUserData} 가
 * userdata 단계에서 이미 본다 — 여기서 다시 보면 서버의 소유 기록이 장착 기록과 어긋날 때(정리 기능 등)
 * 자기 아바타가 거부되는 새 실패 경로만 생긴다.
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
