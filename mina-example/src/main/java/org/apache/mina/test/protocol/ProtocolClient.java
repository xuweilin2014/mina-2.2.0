package org.apache.mina.test.protocol;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ProtocolClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 7080;
    public static final int fil = 100;

    public static void main(String[] args) {
        IoConnector connector = new NioSocketConnector();
        connector.getFilterChain().addLast("codec", new ProtocolCodecFilter(
                new MyProtocolFactory(StandardCharsets.UTF_8)
        ));
        connector.getSessionConfig().setReadBufferSize(1024);
        connector.getSessionConfig().setIdleTime(IdleStatus.BOTH_IDLE, 10);
        connector.setHandler(new IoHandlerAdapter(){
            // 当客户端的 session 连接处于 BOTH_IDLE 时，即没有发送数据也没有写数据时，就关闭掉连接
            @Override
            public void sessionIdle(IoSession session, IdleStatus status) throws Exception {
                if (status == IdleStatus.BOTH_IDLE)
                    session.closeOnFlush();
            }

            @Override
            public void messageReceived(IoSession session, Object message) throws Exception {
                System.out.println("client -> " + message);
            }
        });
        ConnectFuture connectFuture = connector.connect(new InetSocketAddress(HOST, PORT));
        // 在 connectFuture 上注册一个 Listener，当 session 连接建立完毕时，再开始发送数据给服务端
        connectFuture.addListener(new IoFutureListener<ConnectFuture>() {
            @Override
            public void operationComplete(ConnectFuture future) {
                if (future.isConnected()){
                    IoSession session = future.getSession();
                    sendData(session);
                }
            }
        });
    }

    private static void sendData(IoSession session) {
        for (int i = 0; i < fil; i++) {
            String content = "watchmen: " + i;
            ProtocolPacket packet = new ProtocolPacket((byte) i, content);
            session.write(packet);
            System.out.println("客户端发送数据：" + packet);
        }
    }

}
