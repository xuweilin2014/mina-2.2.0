package org.apache.mina.test.protocol;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

import java.nio.charset.Charset;

public class MyProtocolEncoder extends ProtocolEncoderAdapter {

    private final Charset charset;

    public MyProtocolEncoder(Charset charset) {
        this.charset = charset;
    }

    @Override
    public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception {
        // 将传送过来的 message 转换为 ProtocolPacket
        ProtocolPacket packet = (ProtocolPacket) message;
        IoBuffer buffer = IoBuffer.allocate(packet.getLength()).setAutoExpand(true);
        // 这个数据包构造为：length | flag | body
        // 所以在构造数据包时，应该先保存 length，接着再保存 flag，然后再保存消息体数据
        buffer.putInt(packet.getLength());
        buffer.put(packet.getFlag());
        if (packet.getContent() != null) {
            buffer.put(packet.getContent().getBytes());
        }
        buffer.flip();
        out.write(buffer);
    }
}
