package org.apache.mina.test.test1;

import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * mina 主要屏蔽了网络通信的一些细节，对 socket 进行了封装，并且是 NIO 的一个实现架构
 *
 * 长连接：通信双方长期的保持一个连接状态不断开，比如腾讯 QQ，当我们登录 QQ 的时候，我们就去连接
 * 腾讯服务器，一旦建立连接后，就不断开，除非发生异常，这样的方式就是长连接，对于长连接比较耗费 IO 资源
 *
 * 短连接：通信双方不是保持一个长期的连接状态，比如 HTTP 协议，当客户端发起 HTTP 请求，服务器处理 HTTP
 * 请求，当服务器处理完成之后，返回客户端数据之后就断开连接。对于下次的连接请求需要重新发起。
 */
public class MinaServer {

    private static final int port = 7080;

    private static IoAcceptor acceptor = null;

    public static void main(String[] args) {
        acceptor = new NioSocketAcceptor();
        // 设置编解码器
        acceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(
                new TextLineCodecFactory(
                        StandardCharsets.UTF_8, LineDelimiter.WINDOWS, LineDelimiter.WINDOWS
                )
        ));

        acceptor.getSessionConfig().setReadBufferSize(1024);
        // 当 server 在 10s 内既没有发送给客户端数据，也没有接收到对方发送过来的数据时，就进入到 idle 状态
        acceptor.getSessionConfig().setIdleTime(IdleStatus.BOTH_IDLE, 10);
        acceptor.setHandler(new MyServerHandler());
        try {
            // 绑定一个端口
            acceptor.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
