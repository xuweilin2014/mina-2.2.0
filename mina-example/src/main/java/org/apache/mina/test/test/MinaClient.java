package org.apache.mina.test.test;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class MinaClient {

    private static String host = "127.0.0.1";
    private static int port = 7080;

    public static void main(String[] args) throws IOException {
        IoSession session = null;
        IoConnector connector = new NioSocketConnector();
        connector.setConnectTimeoutMillis(10 * 1000);
        // 设置编解码器
        connector.getFilterChain().addLast("codec", new ProtocolCodecFilter(
                new TextLineCodecFactory(
                        StandardCharsets.UTF_8, LineDelimiter.WINDOWS, LineDelimiter.WINDOWS
                )
        ));
        // 添加自定义的 IoFilter
        connector.getFilterChain().addLast("client-filter", new MyClientFilter());
        connector.setHandler(new MyClientHandler());
        ConnectFuture future = connector.connect(new InetSocketAddress(host, port));
        // Wait until the connection attempt is finished.
        future.awaitUninterruptibly();
        session = future.getSession();

        session.write("watch\r\nass\r\nyour");
        // Wait until the connection is closed
        session.getCloseFuture().awaitUninterruptibly();
        connector.dispose();
    }

}
