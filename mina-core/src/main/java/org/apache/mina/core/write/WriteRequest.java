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
package org.apache.mina.core.write;

import java.net.SocketAddress;

import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;

/**
 * Represents write request fired by {@link IoSession#write(Object)}.
 *
 * 当用户调用 session#write 方法时，会把要写的数据、对方的地址等信息封装成一个 WriteRequest，随后在
 * session 中 filterChain#headFilter 添加到 WriteRequestQueue 中，所以 session#write 方法并不真正
 * 写出数据。而在 Processor 这个 I/O 线程的 run 方法中，会不断处理 session 上的读写事件，在处理写事件时，
 * 会从 WriteRequestQueue 中获取到写请求，并且真正把数据通过 channel 发送到对方，并 fire messageSent
 * 事件，最终在 filterChain#headFilter 中调用 future 的 setWritten 方法。
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public interface WriteRequest {
    /**
     * @return the {@link WriteRequest} which was requested originally,
     * which is not transformed by any {@link IoFilter}.
     */
    WriteRequest getOriginalRequest();

    /**
     * @return {@link WriteFuture} that is associated with this write request.
     */
    WriteFuture getFuture();

    /**
     * @return a message object to be written.
     */
    Object getMessage();

    /**
     * Set the modified message after it has been processed by a filter.
     * @param modifiedMessage The modified message
     */
    void setMessage(Object modifiedMessage);

    /**
     * Returns the destination of this write request.
     *
     * @return <tt>null</tt> for the default destination
     */
    SocketAddress getDestination();

    /**
     * Tells if the current message has been encoded
     *
     * @return true if the message has already been encoded
     */
    boolean isEncoded();
    
    /**
     * @return the original message which was sent to the session, before
     * any filter transformation.
     */
    Object getOriginalMessage();
}