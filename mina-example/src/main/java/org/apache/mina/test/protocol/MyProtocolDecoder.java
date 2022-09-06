package org.apache.mina.test.protocol;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

public class MyProtocolDecoder implements ProtocolDecoder {

    // 在 MyProtocolDecoder 类中，创建一个上下文信息类 CONTEXT，会保存在 session 中的 attributes 这个 map 中，
    // 这里定义的 AttributeKey 类型的 CONTEXT 就是 map 中的 key
    private final AttributeKey CONTEXT = new AttributeKey(this.getClass(), "context");
    // 传入 charset 字符集编码，把从网络上接收到的字节流解码成字符串
    private final Charset charset;
    private int maxPacketLength = 100;

    public MyProtocolDecoder() {
        this(Charset.defaultCharset());
    }

    public MyProtocolDecoder(Charset charset) {
        this.charset = charset;
    }

    public Context getContext(IoSession session) {
        Context ctx = (Context) session.getAttribute(CONTEXT);
        if (ctx == null) {
            ctx = new Context();
            session.setAttribute(CONTEXT, ctx);
        }
        return ctx;
    }

    @Override
    public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        final int packetHeaderLength = 5;
        Context ctx = this.getContext(session);
        // 把 in 这个 buffer 中的字节流信息保存到 context 中的 buffer 中
        ctx.append(in);
        IoBuffer buffer = ctx.getBuffer();
        // buffer 翻转，准备从其中读取字节流
        buffer.flip();

        while (buffer.remaining() >= packetHeaderLength) {
            // 保存读取之前 buffer 中 position 的位置
            buffer.mark();
            int length = buffer.getInt();
            byte flag = buffer.get();
            if (length < 0 || length > maxPacketLength) {
                throw new IllegalArgumentException("message length exceeds max length");
            // 在 buffer 中包含了一个或者多个完整的报文
            } else if (length >= packetHeaderLength && length - packetHeaderLength <= buffer.remaining()) {
                int oldLimit = buffer.limit();
                // length - packetHeaderLength 为消息体的长度
                buffer.limit(buffer.position() + (length - packetHeaderLength));
                // 使用 decoder 将 buffer 中的字节流转换成字符串
                String content = buffer.getString(ctx.getDecoder());
                buffer.limit(oldLimit);
                ProtocolPacket packet = new ProtocolPacket(flag, content);
                out.write(packet);
            // 半包，可以恢复到之前的 position
            } else {
                buffer.reset();
                break;
            }
        }

        // 如果 buffer 中还有一些字节数据，就继续累积到 buffer 中，等待后面的字节数据到达
        // 这里使用 temp 作为一个中转，好让 buffer clear
        if (buffer.hasRemaining()) {
            IoBuffer temp = IoBuffer.allocate(maxPacketLength).setAutoExpand(true);
            temp.put(buffer);
            temp.flip();
            buffer.clear();
            buffer.put(temp);
        } else {
            buffer.clear();
        }
    }

    @Override
    public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {

    }

    @Override
    public void dispose(IoSession session) throws Exception {
        Context context = (Context) session.getAttribute("CONTEXT");
        if (context == null) {
            session.removeAttribute(CONTEXT);
        }
    }

    public int getMaxPacketLength() {
        return maxPacketLength;
    }

    public void setMaxPacketLength(int maxPacketLength) {
        if (maxPacketLength <= 0) {
            throw new IllegalArgumentException("maxPacketLength: " + maxPacketLength);
        }
        this.maxPacketLength = maxPacketLength;
    }

    /**
     * Context 是解码器中的上下文信息，其中的 buffer 是作为一个缓冲区用来不断接收对方发送过来的字节流，并且 buffer 中的
     * 字节流是累积的，比如 buffer 中没有接收到一个完整的消息包（例如只接收到 0.7 个消息包），那么这些不完整的消息也会保留
     * 在 buffer 中等待接收后续的字节流
     */
    private class Context {
        private final CharsetDecoder decoder;
        private IoBuffer buffer;

        private Context() {
            // 根据字符集获取解码器，将字节流解码成字符串
            decoder = charset.newDecoder();
            buffer = IoBuffer.allocate(80).setAutoExpand(true);
        }

        public void append(IoBuffer in) {
            this.getBuffer().put(in);
        }

        public void reset() {
            this.decoder.reset();
        }

        public CharsetDecoder getDecoder() {
            return decoder;
        }

        public IoBuffer getBuffer() {
            return buffer;
        }

        public void setBuffer(IoBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
