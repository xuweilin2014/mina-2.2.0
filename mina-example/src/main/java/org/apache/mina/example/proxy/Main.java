/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.example.proxy;


import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.apache.mina.core.service.IoConnector;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.prefixedstring.PrefixedStringCodecFactory;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;


/**
 * 下面这个例子使用 Apache Mina 实现了一个简单的代理服务器，创建了一个 acceptor 和一个 connector，
 * 其中 acceptor 用来监听客户端 client 对代理服务器 proxy 的连接，而 connector 用来连接到真正的
 * 服务器 server 的地址。
 *
 * 此 proxy 代理服务器实现比较简单，只是单纯的获取到客户端发送来的请求，用日志打印出来，然后再把消息
 * 转发给服务器端。
 *
 *
 * (<b>Entry point</b>) Demonstrates how to write a very simple tunneling proxy
 * using MINA. The proxy only logs all data passing through it. This is only
 * suitable for text based protocols since received data will be converted into
 * strings before being logged.
 * <p>
 * Start a proxy like this:<br>
 * <code>org.apache.mina.example.proxy.Main 12345 www.google.com 80</code><br>
 * and open <a href="http://localhost:12345">http://localhost:12345</a> in a
 * browser window.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class Main {

    public static void main( String[] args ) throws Exception {

        // Create TCP/IP acceptor.
        NioSocketAcceptor acceptor = new NioSocketAcceptor();

        // Create TCP/IP connector.
        IoConnector connector = new NioSocketConnector();

        // Set connect timeout.
        connector.setConnectTimeoutMillis( 30 * 1000L );

        // ClientToProxyIoHandler：当客户端和 proxy 建立好连接之后， proxy 就会使用 connector 来建立和服务器端的连接。
        //                         并且此 handler 继承 AbstractProxyIoHandler 来处理了客户端请求（messageReceived 事件），
        // ServerToProxyIoHandler：同样继承了 AbstractProxyIoHandler 类用于处理服务器端发送过来的请求（messageReceived 事件）
        ClientToProxyIoHandler handler = new ClientToProxyIoHandler(connector, new InetSocketAddress(
                "127.0.0.1", 9990
        ));

        // Start proxy.
        acceptor.setHandler(handler);
        acceptor.bind(new InetSocketAddress(9991));
        System.out.println( "Listening on port " + 9991);
    }

}
