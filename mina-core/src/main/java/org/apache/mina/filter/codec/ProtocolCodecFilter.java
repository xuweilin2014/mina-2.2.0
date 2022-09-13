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

import java.net.SocketAddress;
import java.util.Queue;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link IoFilter} which translates binary or protocol specific data into
 * message objects and vice versa using {@link ProtocolCodecFactory},
 * {@link ProtocolEncoder}, or {@link ProtocolDecoder}.
 *
 * ProtocolCodecFilter 是一个编解码过滤器，它在用户创建 filterChain 时添加到其中，并且接受
 * 实现了 ProtocolCodecFactory 接口的类当做对象。这个工厂类会返回 encoder 和 decoder，
 * encoder 是当发生 filterWrite 事件时，对要发送的信息进行编码；decoder 是当发生 messageReceived
 * 事件时，对发送过来的消息进行解码
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * @org.apache.xbean.XBean
 */
public class ProtocolCodecFilter extends IoFilterAdapter {
    /** A logger for this class */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolCodecFilter.class);

    private static final Class<?>[] EMPTY_PARAMS = new Class[0];

    private static final IoBuffer EMPTY_BUFFER = IoBuffer.wrap(new byte[0]);

    private static final AttributeKey ENCODER = new AttributeKey(ProtocolCodecFilter.class, "encoder");

    private static final AttributeKey DECODER = new AttributeKey(ProtocolCodecFilter.class, "decoder");

    private static final ProtocolDecoderOutputLocal DECODER_OUTPUT = new ProtocolDecoderOutputLocal();

    private static final ProtocolEncoderOutputLocal ENCODER_OUTPUT = new ProtocolEncoderOutputLocal();

    /** The factory responsible for creating the encoder and decoder */
    private final ProtocolCodecFactory factory;

    /**
     * Creates a new instance of ProtocolCodecFilter, associating a factory for the
     * creation of the encoder and decoder.
     *
     * @param factory The associated factory
     */
    public ProtocolCodecFilter(ProtocolCodecFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory");
        }

        this.factory = factory;
    }

    /**
     * Creates a new instance of ProtocolCodecFilter, without any factory. The
     * encoder/decoder factory will be created as an inner class, using the two
     * parameters (encoder and decoder).
     * 
     * @param encoder The class responsible for encoding the message
     * @param decoder The class responsible for decoding the message
     */
    public ProtocolCodecFilter(final ProtocolEncoder encoder, final ProtocolDecoder decoder) {
        if (encoder == null) {
            throw new IllegalArgumentException("encoder");
        }
        if (decoder == null) {
            throw new IllegalArgumentException("decoder");
        }

        // Create the inner Factory based on the two parameters
        this.factory = new ProtocolCodecFactory() {
            /**
             * {@inheritDoc}
             */
            @Override
            public ProtocolEncoder getEncoder(IoSession session) {
                return encoder;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public ProtocolDecoder getDecoder(IoSession session) {
                return decoder;
            }
        };
    }

    /**
     * Creates a new instance of ProtocolCodecFilter, without any factory. The
     * encoder/decoder factory will be created as an inner class, using the two
     * parameters (encoder and decoder), which are class names. Instances for those
     * classes will be created in this constructor.
     * 
     * @param encoderClass The class responsible for encoding the message
     * @param decoderClass The class responsible for decoding the message
     */
    public ProtocolCodecFilter(final Class<? extends ProtocolEncoder> encoderClass, final Class<? extends ProtocolDecoder> decoderClass) {
        if (encoderClass == null) {
            throw new IllegalArgumentException("encoderClass");
        }
        if (decoderClass == null) {
            throw new IllegalArgumentException("decoderClass");
        }
        if (!ProtocolEncoder.class.isAssignableFrom(encoderClass)) {
            throw new IllegalArgumentException("encoderClass: " + encoderClass.getName());
        }
        if (!ProtocolDecoder.class.isAssignableFrom(decoderClass)) {
            throw new IllegalArgumentException("decoderClass: " + decoderClass.getName());
        }
        try {
            encoderClass.getConstructor(EMPTY_PARAMS);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("encoderClass doesn't have a public default constructor.");
        }
        try {
            decoderClass.getConstructor(EMPTY_PARAMS);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("decoderClass doesn't have a public default constructor.");
        }

        final ProtocolEncoder encoder;

        try {
            encoder = encoderClass.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("encoderClass cannot be initialized");
        }

        final ProtocolDecoder decoder;

        try {
            decoder = decoderClass.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("decoderClass cannot be initialized");
        }

        // Create the inner factory based on the two parameters.
        this.factory = new ProtocolCodecFactory() {
            /**
             * {@inheritDoc}
             */
            @Override
            public ProtocolEncoder getEncoder(IoSession session) throws Exception {
                return encoder;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public ProtocolDecoder getDecoder(IoSession session) throws Exception {
                return decoder;
            }
        };
    }

    /**
     * Get the encoder instance from a given session.
     *
     * @param session The associated session we will get the encoder from
     * @return The encoder instance, if any
     */
    public ProtocolEncoder getEncoder(IoSession session) {
        return (ProtocolEncoder) session.getAttribute(ENCODER);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreAdd(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
        if (parent.contains(this)) {
            throw new IllegalArgumentException(
                    "You can't add the same filter instance more than once.  Create another instance and add it.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPostRemove(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
        // Clean everything
        disposeCodec(parent.getSession());
    }

    /**
     * Process the incoming message, calling the session decoder. As the incoming
     * buffer might contains more than one messages, we have to loop until the
     * decoder throws an exception.
     * 
     * while ( buffer not empty ) try decode ( buffer ) catch break;
     *
     * 
     */
    @Override
    public void messageReceived(final NextFilter nextFilter, final IoSession session, final Object message)
            throws Exception {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Processing a MESSAGE_RECEIVED for session {}", session.getId());
        }

        // 如果 message 不是 IoBuffer 的类型，直接继续传递给下一个 filter
        if (!(message instanceof IoBuffer)) {
            nextFilter.messageReceived(session, message);
            return;
        }

        final IoBuffer in = (IoBuffer) message;
        // 通过工厂类获取到解码器 decoder
        final ProtocolDecoder decoder = factory.getDecoder(session);
        // DECODER_OUTPUT 继承了 ThreadLocal 类，因此调用 get 方法每一个线程都会返回自己专属的
        // ProtocolDecoderOutputImpl 类对象
        final ProtocolDecoderOutputImpl decoderOut = DECODER_OUTPUT.get();

        // Loop until we don't have anymore byte in the buffer,
        // or until the decoder throws an unrecoverable exception or
        // can't decoder a message, because there are not enough
        // data in the buffer
        while (in.hasRemaining()) {
            // 记录 in 这个 buffer 之前的 position 位置
            int oldPos = in.position();
            try {
                // Call the decoder with the read bytes
                // 调用解码器对 in 这个 buffer 中的字节数据进行解码，然后将解码后的结果保存到 decoderOut 这个类的 messageQueue 中
                decoder.decode(session, in, decoderOut);
                // Finish decoding if no exception was thrown.
                // flush 方法遍历 messageQueue 中的消息对象，把每一个消息继续传递给下一个 filter 的 messageReceived 方法
                decoderOut.flush(nextFilter, session);
            } catch (Exception e) {
                ProtocolDecoderException pde;
                if (e instanceof ProtocolDecoderException) {
                    pde = (ProtocolDecoderException) e;
                } else {
                    pde = new ProtocolDecoderException(e);
                }

                // 将 in 这个 buffer 中从 oldPos 开始一直到 limit 这个区间的二进制数据转换成 16 进制的字符串，
                // 并设置到 pde 中的 hexdump 属性中
                if (pde.getHexdump() == null) {
                    // Generate a message hex dump
                    int curPos = in.position();
                    in.position(oldPos);
                    pde.setHexdump(in.getHexDump());
                    in.position(curPos);
                }

                // Fire the exceptionCaught event.
                // 把 messageQueue 中解码好的消息发送给下一个 filter 的 messageReceived 方法
                decoderOut.flush(nextFilter, session);
                // fire filterChain 的 exceptionCaught 事件
                nextFilter.exceptionCaught(session, pde);
                // Retry only if the type of the caught exception is recoverable and the buffer position has changed.
                // We check buffer position additionally to prevent an infinite loop.
                if (!(e instanceof RecoverableProtocolDecoderException) || (in.position() == oldPos)) {
                    break;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void messageSent(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        if (writeRequest instanceof EncodedWriteRequest) {
            return;
        }

        nextFilter.messageSent(session, writeRequest);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void filterWrite(final NextFilter nextFilter, final IoSession session, final WriteRequest writeRequest)  throws Exception {
        final Object message = writeRequest.getMessage();

        // Bypass the encoding if the message is contained in a IoBuffer, as it has already been encoded before
        // 如果 message 是 IoBuffer 类型的话，就不用去进行编码处理
        if ((message instanceof IoBuffer) || (message instanceof FileRegion)) {
            nextFilter.filterWrite(session, writeRequest);
            return;
        }

        // Get the encoder in the session
        final ProtocolEncoder encoder = factory.getEncoder(session);
        final ProtocolEncoderOutputImpl encoderOut = ENCODER_OUTPUT.get();

        if (encoder == null) {
            throw new ProtocolEncoderException("The encoder is null for the session " + session);
        }

        try {
            // Now we can try to encode the response
            // 使用编码器 encoder 对 message 进行编码，也就是把 message 转换成字节数据保存到一个 IoBuffer 中，
            // 并且最后写入到 encoderOut 中的 messageQueue 里面
            encoder.encode(session, message, encoderOut);

            final Queue<Object> queue = encoderOut.messageQueue;

            if (queue.isEmpty()) {
                // Write empty message to ensure that messageSent is fired later
                // 将 WriteRequest 中的消息体设置为空（空白消息），然后调用传递给下一个 filter 的 filterWrite 方法，
                // 这样做是因为：当 filterWrite 事件发生之后，之后一般会发生 messageSent 事件（buffer 被 processor
                // 真正写入到 channel 中发送，然后 fire messageSent 事件）
                writeRequest.setMessage(EMPTY_BUFFER);
                nextFilter.filterWrite(session, writeRequest);
            } else {
                // Write all the encoded messages now
                Object encodedMessage = null;

                /*
                 * 如果在编码的过程中，将 writeRequest 中的数据编码成了多个消息，那么就会把除最后一个消息外的其它 message
                 * 封装成 EncodedWriteRequest（最后一个还是 WriteRequest），这样最后一个 WriteRequest 才会被发送给
                 * 用户自定义 Handler 中的 messageSent 方法，而其余的 EncodedWriteRequest 被 ProtocolCodecFilter
                 * 中的 messageSent 方法拦截下来
                 */
                while ((encodedMessage = queue.poll()) != null) {
                    if (queue.isEmpty()) {
                        // Write last message using original WriteRequest to ensure that any Future and
                        // dependency on messageSent event is emitted correctly
                        writeRequest.setMessage(encodedMessage);
                        nextFilter.filterWrite(session, writeRequest);
                    } else {
                        SocketAddress destination = writeRequest.getDestination();
                        WriteRequest encodedWriteRequest = new EncodedWriteRequest(encodedMessage, null, destination);
                        nextFilter.filterWrite(session, encodedWriteRequest);
                    }
                }
            }
        } catch (final ProtocolEncoderException e) {
            throw e;
        } catch (final Exception e) {
            // Generate the correct exception
            throw new ProtocolEncoderException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
        // Call finishDecode() first when a connection is closed.
        ProtocolDecoder decoder = factory.getDecoder(session);
        ProtocolDecoderOutput decoderOut = DECODER_OUTPUT.get();

        try {
            // 调用 finishDecode 来对剩余的数据进行解码，并且同样把解码后的数据保存到 decoderOut 中
            decoder.finishDecode(session, decoderOut);
        } catch (Exception e) {
            ProtocolDecoderException pde;
            if (e instanceof ProtocolDecoderException) {
                pde = (ProtocolDecoderException) e;
            } else {
                pde = new ProtocolDecoderException(e);
            }
            throw pde;
        } finally {
            // Dispose everything
            // 销毁 encoder 和 decoder，具体由用户自己定义实现
            disposeCodec(session);
            decoderOut.flush(nextFilter, session);
        }

        // Call the next filter
        nextFilter.sessionClosed(session);
    }

    private static class EncodedWriteRequest extends DefaultWriteRequest {
        public EncodedWriteRequest(Object encodedMessage, WriteFuture future, SocketAddress destination) {
            super(encodedMessage, future, destination);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isEncoded() {
            return true;
        }
    }

    private static class ProtocolDecoderOutputImpl extends AbstractProtocolDecoderOutput {
        public ProtocolDecoderOutputImpl() {
            // Do nothing
        }
    }

    private static class ProtocolEncoderOutputImpl extends AbstractProtocolEncoderOutput {
        public ProtocolEncoderOutputImpl() {
            // Do nothing
        }
    }

    // ----------- Helper methods ---------------------------------------------
    /**
     * Dispose the encoder, decoder, and the callback for the decoded messages.
     */
    private void disposeCodec(IoSession session) {
        // We just remove the two instances of encoder/decoder to release resources from the session
        disposeEncoder(session);
        disposeDecoder(session);
    }

    /**
     * Dispose the encoder, removing its instance from the session's attributes, and
     * calling the associated dispose method.
     */
    private void disposeEncoder(IoSession session) {
        // session 中 AttributeMap 里面的 ENCODER 可以在自定义的 encoder 类中添加进去
        ProtocolEncoder encoder = (ProtocolEncoder) session.removeAttribute(ENCODER);
        if (encoder == null) {
            return;
        }

        try {
            encoder.dispose(session);
        } catch (Exception e) {
            LOGGER.warn("Failed to dispose: " + encoder.getClass().getName() + " (" + encoder + ')');
        }
    }

    /**
     * Dispose the decoder, removing its instance from the session's attributes, and
     * calling the associated dispose method.
     */
    private void disposeDecoder(IoSession session) {
        // session 中 AttributeMap 里面的 DECODER 可以在自定义的 decoder 类中添加进去
        ProtocolDecoder decoder = (ProtocolDecoder) session.removeAttribute(DECODER);
        if (decoder == null) {
            return;
        }

        try {
            decoder.dispose(session);
        } catch (Exception e) {
            LOGGER.warn("Failed to dispose: " + decoder.getClass().getName() + " (" + decoder + ')');
        }
    }

    /**
     * ProtocolDecoderOutputLocal 类型的对象 DECODER_OUTPUT 继承了 ThreadLocal，因此当调用 DECODER_OUTPUT.get 方法
     * 时，不同的线程都会获取到不同的 ProtocolDecoderOutputImpl 类实例
     */
    static private class ProtocolDecoderOutputLocal extends ThreadLocal<ProtocolDecoderOutputImpl> {
        @Override
        protected ProtocolDecoderOutputImpl initialValue() {
            return new ProtocolDecoderOutputImpl();
        }
    }

    static private class ProtocolEncoderOutputLocal extends ThreadLocal<ProtocolEncoderOutputImpl> {
        @Override
        protected ProtocolEncoderOutputImpl initialValue() {
            return new ProtocolEncoderOutputImpl();
        }
    }
}
