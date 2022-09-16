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
package org.apache.mina.filter.codec.demux;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

/**
 * A composite {@link ProtocolDecoder} that demultiplexes incoming {@link IoBuffer}
 * decoding requests into an appropriate {@link MessageDecoder}.
 * 
 * <h2>Internal mechanism of {@link MessageDecoder} selection</h2>
 * <ol>
 *   <li>
 *     {@link DemuxingProtocolDecoder} iterates the list of candidate
 *     {@link MessageDecoder}s and calls {@link MessageDecoder#decodable(IoSession, IoBuffer)}.
 *     Initially, all registered {@link MessageDecoder}s are candidates.
 *   </li>
 *   <li>
 *     If {@link MessageDecoderResult#NOT_OK} is returned, it is removed from the candidate
 *     list.
 *   </li>
 *   <li>
 *     If {@link MessageDecoderResult#NEED_DATA} is returned, it is retained in the candidate
 *     list, and its {@link MessageDecoder#decodable(IoSession, IoBuffer)} will be invoked
 *     again when more data is received.
 *   </li>
 *   <li>
 *     If {@link MessageDecoderResult#OK} is returned, {@link DemuxingProtocolDecoder}
 *     found the right {@link MessageDecoder}.
 *   </li>
 *   <li>
 *     If there's no candidate left, an exception is raised.  Otherwise, 
 *     {@link DemuxingProtocolDecoder} will keep iterating the candidate list.
 *   </li>
 * </ol>
 * 
 * Please note that any change of position and limit of the specified {@link IoBuffer}
 * in {@link MessageDecoder#decodable(IoSession, IoBuffer)} will be reverted back to its
 * original value.
 * <p>
 * Once a {@link MessageDecoder} is selected, {@link DemuxingProtocolDecoder} calls
 * {@link MessageDecoder#decode(IoSession, IoBuffer, ProtocolDecoderOutput)} continuously
 * reading its return value:
 * <ul>
 *   <li>
 *     {@link MessageDecoderResult#NOT_OK} - protocol violation; {@link ProtocolDecoderException}
 *     is raised automatically.
 *   </li>
 *   <li>
 *     {@link MessageDecoderResult#NEED_DATA} - needs more data to read the whole message;
 *     {@link MessageDecoder#decode(IoSession, IoBuffer, ProtocolDecoderOutput)}
 *     will be invoked again when more data is received.
 *   </li>
 *   <li>
 *     {@link MessageDecoderResult#OK} - successfully decoded a message; the candidate list will
 *     be reset and the selection process will start over.
 *   </li>
 * </ul>
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 *
 * @see MessageDecoderFactory
 * @see MessageDecoder
 */
public class DemuxingProtocolDecoder extends CumulativeProtocolDecoder {

    private static final AttributeKey STATE = new AttributeKey(DemuxingProtocolDecoder.class, "state");

    private MessageDecoderFactory[] decoderFactories = new MessageDecoderFactory[0];

    private static final Class<?>[] EMPTY_PARAMS = new Class[0];

    /**
     * Adds a new message decoder class
     * 
     * @param decoderClass The decoder class
     */
    public void addMessageDecoder(Class<? extends MessageDecoder> decoderClass) {
        if (decoderClass == null) {
            throw new IllegalArgumentException("decoderClass");
        }

        try {
            // 检查 decoderClass 中是否有默认的构造函数，如果没有的话，直接抛出异常
            decoderClass.getConstructor(EMPTY_PARAMS);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("The specified class doesn't have a public default constructor.");
        }

        boolean registered = false;
        // decoderClass 是否实现了 MessageDecoder 接口
        if (MessageDecoder.class.isAssignableFrom(decoderClass)) {
            // 将 decoderClass 使用一个工厂类封装起来，在调用工厂类的 getDecoder 方法时，需要通过反射创建实例对象
            addMessageDecoder(new DefaultConstructorMessageDecoderFactory(decoderClass));
            registered = true;
        }

        if (!registered) {
            throw new IllegalArgumentException("Unregisterable type: " + decoderClass);
        }
    }

    /**
     * Adds a new message decoder instance
     * 
     * @param decoder The decoder instance
     */
    public void addMessageDecoder(MessageDecoder decoder) {
        addMessageDecoder(new SingletonMessageDecoderFactory(decoder));
    }

    /**
     * Adds a new message decoder factory
     * 将此 factory 保存到 decoderFactories 数组中
     * @param factory The decoder factory
     */
    public void addMessageDecoder(MessageDecoderFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory");
        }

        MessageDecoderFactory[] newDecoderFactories = new MessageDecoderFactory[decoderFactories.length + 1];
        System.arraycopy(decoderFactories, 0, newDecoderFactories, 0, decoderFactories.length);
        newDecoderFactories[decoderFactories.length] = factory;
        this.decoderFactories = newDecoderFactories;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        State state = getState(session);

        if (state.currentDecoder == null) {
            MessageDecoder[] decoders = state.decoders;
            int undecodables = 0;

            for (int i = decoders.length - 1; i >= 0; i--) {
                MessageDecoder decoder = decoders[i];
                int limit = in.limit();
                int pos = in.position();

                MessageDecoderResult result;

                try {
                    // decodable 方法会返回以下三个值：
                    // 1.NEED_DATA：接收到的消息的消息体还不是很完整
                    // 2.OK：接收到的消息的类型和解码器的类型 decoder 相符（具体的做法由用户自己定义，举例来说，可以将使用消息的前两个
                    //   字节保存消息的类型）
                    // 3.NOT_OK：如果消息类型和 decoder 不匹配
                    result = decoder.decodable(session, in);
                } finally {
                    // 恢复 in 这个 buffer 的状态
                    in.position(pos);
                    in.limit(limit);
                }

                if (result == MessageDecoder.OK) {
                    state.currentDecoder = decoder;
                    break;
                } else if (result == MessageDecoder.NOT_OK) {
                    undecodables++;
                // decodable 方法返回的 MessageDecoder 结果不在三种 OK、NOT_OK 以及 NEED_DATA 之中
                } else if (result != MessageDecoder.NEED_DATA) {
                    throw new IllegalStateException("Unexpected decode result (see your decodable()): " + result);
                }
            }

            // 如果 undecodables 和 decoders.length 相等的话，说明没有找到合适的解码器，抛出异常
            if (undecodables == decoders.length) {
                // Throw an exception if all decoders cannot decode data.
                String dump = in.getHexDump();
                in.position(in.limit()); // Skip data
                ProtocolDecoderException e = new ProtocolDecoderException("No appropriate message decoder: " + dump);
                e.setHexdump(dump);
                throw e;
            }

            // 如果 undecodables 和 decoders.length 不相等，且没有找到 decoder，说明接收到的数据还太少
            if (state.currentDecoder == null) {
                // Decoder is not determined yet (i.e. we need more data)
                return false;
            }
        }

        try {
            MessageDecoderResult result = state.currentDecoder.decode(session, in, out);
            if (result == MessageDecoder.OK) {
                state.currentDecoder = null;
                return true;
            } else if (result == MessageDecoder.NEED_DATA) {
                return false;
            } else if (result == MessageDecoder.NOT_OK) {
                state.currentDecoder = null;
                throw new ProtocolDecoderException("Message decoder returned NOT_OK.");
            } else {
                state.currentDecoder = null;
                throw new IllegalStateException("Unexpected decode result (see your decode()): " + result);
            }
        } catch (Exception e) {
            state.currentDecoder = null;
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
        super.finishDecode(session, out);
        State state = getState(session);
        MessageDecoder currentDecoder = state.currentDecoder;
        if (currentDecoder == null) {
            return;
        }

        currentDecoder.finishDecode(session, out);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose(IoSession session) throws Exception {
        super.dispose(session);
        session.removeAttribute(STATE);
    }

    private State getState(IoSession session) throws Exception {
        State state = (State) session.getAttribute(STATE);

        if (state == null) {
            state = new State();
            State oldState = (State) session.setAttributeIfAbsent(STATE, state);

            if (oldState != null) {
                state = oldState;
            }
        }

        return state;
    }

    /**
     * 在 State 类中保存了所有的解码器，以及当前消息所使用的解码器
     */
    private class State {
        private final MessageDecoder[] decoders;

        private MessageDecoder currentDecoder;

        private State() throws Exception {
            MessageDecoderFactory[] factories = DemuxingProtocolDecoder.this.decoderFactories;
            decoders = new MessageDecoder[factories.length];
            
            for (int i = factories.length - 1; i >= 0; i--) {
                decoders[i] = factories[i].getDecoder();
            }
        }
    }

    // SingletonMessageDecoderFactory 是一个静态内部类，它不会使用到外部类的属性和方法，把它设为内部类
    // 只是 SingletonMessageDecoderFactory 类只在当前类中使用，为了更好地耦合。
    private static class SingletonMessageDecoderFactory implements MessageDecoderFactory {
        private final MessageDecoder decoder;

        private SingletonMessageDecoderFactory(MessageDecoder decoder) {
            if (decoder == null) {
                throw new IllegalArgumentException("decoder");
            }
            this.decoder = decoder;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public MessageDecoder getDecoder() {
            return decoder;
        }
    }

    // DefaultConstructorMessageDecoderFactory 是一个静态内部类，它不会使用到外部类的属性和方法，把它设为内部类
    // 只是 DefaultConstructorMessageDecoderFactory 类只在当前类中使用，为了更好地耦合。
    private static class DefaultConstructorMessageDecoderFactory implements MessageDecoderFactory {
        private final Class<?> decoderClass;

        private DefaultConstructorMessageDecoderFactory(Class<?> decoderClass) {
            if (decoderClass == null) {
                throw new IllegalArgumentException("decoderClass");
            }

            if (!MessageDecoder.class.isAssignableFrom(decoderClass)) {
                throw new IllegalArgumentException("decoderClass is not assignable to MessageDecoder");
            }
            this.decoderClass = decoderClass;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public MessageDecoder getDecoder() throws Exception {
            return (MessageDecoder) decoderClass.newInstance();
        }
    }
}
