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
package org.apache.mina.filter.codec;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.TransportMetadata;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;

/**
 * A {@link ProtocolDecoder} that cumulates the content of received buffers to a
 * <em>cumulative buffer</em> to help users implement decoders.
 * <p>
 * If the received {@link IoBuffer} is only a part of a message. decoders should
 * cumulate received buffers to make a message complete or to postpone decoding
 * until more buffers arrive.
 * <p>
 * Here is an example decoder that decodes CRLF terminated lines into
 * <code>Command</code> objects:
 * 
 * <pre>
 * public class CrLfTerminatedCommandLineDecoder extends CumulativeProtocolDecoder {
 * 
 *     private Command parseCommand(IoBuffer in) {
 *         // Convert the bytes in the specified buffer to a
 *         // Command object.
 *         ...
 *     }
 * 
 *     protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
 * 
 *         // Remember the initial position.
 *         int start = in.position();
 * 
 *         // Now find the first CRLF in the buffer.
 *         byte previous = 0;
 *         while (in.hasRemaining()) {
 *             byte current = in.get();
 * 
 *             if (previous == '\r' &amp;&amp; current == '\n') {
 *                 // Remember the current position and limit.
 *                 int position = in.position();
 *                 int limit = in.limit();
 *                 try {
 *                     in.position(start);
 *                     in.limit(position);
 *                     // The bytes between in.position() and in.limit()
 *                     // now contain a full CRLF terminated line.
 *                     out.write(parseCommand(in.slice()));
 *                 } finally {
 *                     // Set the position to point right after the
 *                     // detected line and set the limit to the old
 *                     // one.
 *                     in.position(position);
 *                     in.limit(limit);
 *                 }
 *                 // Decoded one line; CumulativeProtocolDecoder will
 *                 // call me again until I return false. So just
 *                 // return true until there are no more lines in the
 *                 // buffer.
 *                 return true;
 *             }
 * 
 *             previous = current;
 *         }
 * 
 *         // Could not find CRLF in the buffer. Reset the initial
 *         // position to the one we recorded above.
 *         in.position(start);
 * 
 *         return false;
 *     }
 * }
 * </pre>
 * <p>
 * Please note that this decoder simply forward the call to
 * doDecode(IoSession, IoBuffer, ProtocolDecoderOutput) if the
 * underlying transport doesn't have a packet fragmentation. Whether the
 * transport has fragmentation or not is determined by querying
 * {@link TransportMetadata}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class CumulativeProtocolDecoder extends ProtocolDecoderAdapter {
    /**
     * The buffer used to store the data in the session
     * session 中的这个 BUFFER 就是用来存储从 channel 中读取的数据，如果只接收到半包的数据，那么就把半包的
     * 数据继续保存在 BUFFER 中，等待下次继续读取数据到 BUFFER 中再次进行解析。另外要注意的是， BUFFER 中的
     * limit = capacity，而 position 要么等于 0，要么等于某一个特定的数字，这样可以直接调用 BUFFER.put 往
     * 其中添加字节数据。
     */
    private static final AttributeKey BUFFER = new AttributeKey(CumulativeProtocolDecoder.class, "buffer");
    
    /**
     * A flag set to true if we handle fragmentation accordingly to the TransportMetadata setting.
     * It can be set to false if needed (UDP with fragments, for instance). the default value is 'true'
     */
    private boolean transportMetadataFragmentation = true;

    /**
     * Creates a new instance.
     */
    protected CumulativeProtocolDecoder() {
        // Do nothing
    }

    /**
     * Cumulates content of <tt>in</tt> into internal buffer and forwards
     * decoding request to
     * doDecode(IoSession, IoBuffer, ProtocolDecoderOutput).
     * <tt>doDecode()</tt> is invoked repeatedly until it returns <tt>false</tt>
     * and the cumulative buffer is compacted after decoding ends.
     *
     * @throws IllegalStateException
     *             if your <tt>doDecode()</tt> returned <tt>true</tt> not
     *             consuming the cumulative buffer.
     */
    @Override
    public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        if (transportMetadataFragmentation && !session.getTransportMetadata().hasFragmentation()) {
            while (in.hasRemaining()) {
                if (!doDecode(session, in, out)) {
                    break;
                }
            }

            return;
        }

        // 表示是否使用的是 session 中的 BUFFER 来保存数据
        boolean usingSessionBuffer = true;
        IoBuffer buf = (IoBuffer) session.getAttribute(BUFFER);
        // If we have a session buffer, append data to that; otherwise use the buffer read from the network directly.
        // 如果在 session 中有 BUFFER 属性，那么就使用对应的 BUFFER 来存储数据，否则直接使用传过来的 IoBuffer 对象 in
        if (buf != null) {
            boolean appended = false;
            // Make sure that the buffer is auto-expanded.
            if (buf.isAutoExpand()) {
                try {
                    // 在 session 中的 buf 的 position 为 0（初始情况）或者其它值，而 limit = capacity，
                    // 因此可以直接往其中添加 ByteBuffer 数据，buf 的 position 会增加 in.remaining() 值
                    buf.put(in);
                    appended = true;
                } catch (IllegalStateException | IndexOutOfBoundsException e) {
                    // A user called derivation method (e.g. slice()),
                    // which disables auto-expansion of the parent buffer.
                }
            }

            // 如果成功把 in 添加到 buf 中，那么接下来还要对 buf 进行翻转，limit = position，position = 0，
            // 这样在 decode 方法中可以直接读取 buf 中的数据
            if (appended) {
                buf.flip();

            // 如果添加失败，那么就重新分配一个 newBuf，将 buf 和 in 中的数据拷贝进去（buf 中可能有上次读取残留下来的数据），
            // 同样再把 newBuf 进行 flip，使得 decode 可以进行读取操作，最后设置到 session 中
            } else {
                // Reallocate the buffer if append operation failed due to derivation or disabled auto-expansion.
                // 由于 buf 中的 position 为 0（初始情况）或者其它值，而 limit = capacity，因此需要 flip。而 in 这个 buffer
                // 是由 Processor 读取之后会自动进行 flip 操作，所以这里没必要再重复操作
                buf.flip();
                IoBuffer newBuf = IoBuffer.allocate(buf.remaining() + in.remaining()).setAutoExpand(true);
                newBuf.order(buf.order());
                newBuf.put(buf);
                newBuf.put(in);
                newBuf.flip();
                buf.free();
                buf = newBuf;

                // Update the session attribute.
                session.setAttribute(BUFFER, buf);
            }
        } else {
            buf = in;
            usingSessionBuffer = false;
        }

        for (;;) {
            // 记录下 buf 当前 position 的位置
            int oldPos = buf.position();
            // 调用 doDecode 进行真正的解码操作，在解码的过程中，limit 和 capacity 都不会发生改变，返回的结果有两种情况：
            // 1.返回 true：表明当前这次消息解码成功完成，但是 buf 中可能还有额外的几条数据，doDecode 方法希望被多调用几次
            // 2.返回 false：表明当前消息解码失败，可能是因为 buf 中只有半包数据，需要等待剩下的数据到来
            boolean decoded = doDecode(session, buf, out);
            if (decoded) {
                if (buf.position() == oldPos) {
                    throw new IllegalStateException("doDecode() can't return true when buffer is not consumed.");
                }

                // 如果解码成功，同时 buf 中没有额外的数据时，直接退出
                if (!buf.hasRemaining()) {
                    break;
                }
            // 如果解码失败，直接退出
            } else {
                break;
            }
        }

        // if there is any data left that cannot be decoded, we store it in a buffer in the session and next time this decoder is
        // invoked the session buffer gets appended to
        // 如果 buf 中还有一部分数据没有解码完成
        if (buf.hasRemaining()) {
            // 如果 buf 被保存在 session 中，那么就直接将 buf 进行 compact，假设 buf 中 pos = 12，limit = 35，capacity = 50，
            // 那么 compact 之后的 buf 就为 pos = 23，limit = capacity = 50，也就是把从 buf 中 pos 到 limit 之间的数据拷贝
            // 到 0 到 limit - pos - 1 之间，最后将 limit 设置为 capacity，pos 设置为 limit - pos，这样接下来就可以直接通过 buf.put
            // 往 buf 中拷贝数据
            if (usingSessionBuffer && buf.isAutoExpand()) {
                buf.compact();
            } else {
                // 如果 buf 不是从 session 中获取到的，将 buf 保存到 session 中
                storeRemainingInSession(buf, session);
            }

        // 如果 buffer 中没有剩余数据，那么直接从 session 中将其移除掉
        } else {
            if (usingSessionBuffer) {
                removeSessionBuffer(session);
            }
        }
    }

    /**
     * Implement this method to consume the specified cumulative buffer and
     * decode its content into message(s).
     *
     * @param session The current Session
     * @param in the cumulative buffer
     * @param out The {@link ProtocolDecoderOutput} that will receive the decoded message
     * @return <tt>true</tt> if and only if there's more to decode in the buffer
     *         and you want to have <tt>doDecode</tt> method invoked again.
     *         Return <tt>false</tt> if remaining data is not enough to decode,
     *         then this method will be invoked again when more data is
     *         cumulated.
     * @throws Exception if cannot decode <tt>in</tt>.
     */
    protected abstract boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception;

    /**
     * Releases the cumulative buffer used by the specified <tt>session</tt>.
     * Please don't forget to call <tt>super.dispose( session )</tt> when you
     * override this method.
     */
    @Override
    public void dispose(IoSession session) throws Exception {
        removeSessionBuffer(session);
    }

    private void removeSessionBuffer(IoSession session) {
        IoBuffer buf = (IoBuffer) session.removeAttribute(BUFFER);
        if (buf != null) {
            buf.free();
        }
    }

    private void storeRemainingInSession(IoBuffer buf, IoSession session) {
        final IoBuffer remainingBuf = IoBuffer.allocate(buf.capacity()).setAutoExpand(true);

        remainingBuf.order(buf.order());
        remainingBuf.put(buf);

        session.setAttribute(BUFFER, remainingBuf);
    }
    
    /**
     * Let the user change the way we handle fragmentation. If set to <tt>false</tt>, the 
     * decode() method will not check the TransportMetadata fragmentation capability
     *  
     * @param transportMetadataFragmentation The flag to set.
     */
    public void setTransportMetadataFragmentation(boolean transportMetadataFragmentation) {
        this.transportMetadataFragmentation = transportMetadataFragmentation;
    }
}
