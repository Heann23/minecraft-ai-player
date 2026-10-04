package me.herry.minecraftAI.nms;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 실제 클라이언트가 없는 접속. 서버가 보내는 패킷은 받을 상대가 없으므로 전부 버린다.
 */
final class FakeConnection extends Connection {

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // 바닐라의 테스트용 mock 플레이어와 같은 방식으로 채널을 연결한다.
        new EmbeddedChannel(this);
        // 서버 코드 일부가 주소를 InetSocketAddress 로 가정하고 있어서 루프백 주소로 바꿔 둔다.
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        this.preparing = false;
    }

    // EmbeddedChannel 은 받은 메시지를 큐에 계속 쌓기 때문에, 그대로 두면 메모리가 새게 된다.
    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    void close() {
        if (this.channel instanceof EmbeddedChannel embedded) {
            embedded.releaseOutbound();
            embedded.releaseInbound();
        }
        if (this.channel != null && this.channel.isOpen()) this.channel.close();
    }
}
