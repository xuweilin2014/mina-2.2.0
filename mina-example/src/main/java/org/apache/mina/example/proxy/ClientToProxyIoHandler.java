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

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.prefixedstring.PrefixedStringCodecFactory;

/**
 * Handles the client to proxy part of the proxied connection.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class ClientToProxyIoHandler extends AbstractProxyIoHandler {

    private final ServerToProxyIoHandler connectorHandler = new ServerToProxyIoHandler();

    private final IoConnector connector;

    private final SocketAddress remoteAddress;

    public ClientToProxyIoHandler(IoConnector connector, SocketAddress remoteAddress) {
        this.connector = connector;
        this.remoteAddress = remoteAddress;
        connector.setHandler(connectorHandler);
    }

    @Override
    public void sessionOpened(final IoSession session) throws Exception {
        // 当客户端与 proxy 之间的连接 sess1 建立完毕之后，就开始使用 connector 来主动和服务端建立连接 sess2。
        // 当连接 sess2 建立完毕之后，就把 sess1 保存到 sess2 的 AttributeMap 中；同样也把 sess2 保存到
        // sess1 的 AttributeMap 中
        connector.connect(remoteAddress).addListener(new IoFutureListener<ConnectFuture>() {
            public void operationComplete(ConnectFuture future) {
                try {
                    future.getSession().setAttribute(OTHER_IO_SESSION, session);
                    session.setAttribute(OTHER_IO_SESSION, future.getSession());
                    IoSession session2 = future.getSession();
                    session2.resumeRead();
                    session2.resumeWrite();
                } catch (RuntimeIoException e) {
                    // Connect failed
                    session.closeNow();
                } finally {
                    session.resumeRead();
                    session.resumeWrite();
                }
            }
        });
    }
}
