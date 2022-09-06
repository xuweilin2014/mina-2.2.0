package org.apache.mina.test.protocol;

import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ProtocolServer {

    public static final int port = 7080;

    public static void main(String[] args) throws IOException {
        IoAcceptor acceptor = new NioSocketAcceptor();
        // ProtocolCodecFilter 接收一个 MyProtocolFactory 这个工厂类作为参数，来获取 encoder 和 decoder
        acceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(
                new MyProtocolFactory(StandardCharsets.UTF_8)
        ));
        acceptor.getSessionConfig().setReadBufferSize(1024);
        acceptor.getSessionConfig().setIdleTime(IdleStatus.BOTH_IDLE, 10);
        acceptor.setHandler(new MyHandler());
        acceptor.bind(new InetSocketAddress(port));
        System.out.println("server start");
    }

    static class MyHandler extends IoHandlerAdapter {
        @Override
        public void sessionIdle(IoSession session, IdleStatus status) throws Exception {
            System.out.println("server->sessionIdle");
        }

        @Override
        public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
            System.out.println("server->exceptionCaught");
        }

        @Override
        public void messageReceived(IoSession session, Object message) throws Exception {
            ProtocolPacket packet = (ProtocolPacket) message;
            System.out.println("server received -> " + packet);
        }
    }

}
