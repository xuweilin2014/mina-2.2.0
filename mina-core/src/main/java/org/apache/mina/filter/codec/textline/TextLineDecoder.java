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
package org.apache.mina.filter.codec.textline;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import org.apache.mina.core.buffer.BufferDataException;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.RecoverableProtocolDecoderException;

/**
 * A {@link ProtocolDecoder} which decodes a text line into a string.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class TextLineDecoder implements ProtocolDecoder {

    private static final AttributeKey CONTEXT = new AttributeKey(TextLineDecoder.class, "context");

    private final Charset charset;

    /** The delimiter used to determinate when a line has been fully decoded */
    private final LineDelimiter delimiter;

    /** An IoBuffer containing the delimiter */
    private IoBuffer delimBuf;

    /** The default maximum Line length. Default to 1024. */
    private int maxLineLength = 1024;

    /** The default maximum buffer length. Default to 128 chars. */
    private int bufferLength = 128;

    /**
     * Creates a new instance with the current default {@link Charset}
     * and {@link LineDelimiter#AUTO} delimiter.
     */
    public TextLineDecoder() {
        this(LineDelimiter.AUTO);
    }

    /**
     * Creates a new instance with the current default {@link Charset}
     * and the specified <tt>delimiter</tt>.
     * 
     * @param delimiter The line delimiter to use
     */
    public TextLineDecoder(String delimiter) {
        this(new LineDelimiter(delimiter));
    }

    /**
     * Creates a new instance with the current default {@link Charset}
     * and the specified <tt>delimiter</tt>.
     * 
     * @param delimiter The line delimiter to use
     */
    public TextLineDecoder(LineDelimiter delimiter) {
        this(Charset.defaultCharset(), delimiter);
    }

    /**
     * Creates a new instance with the spcified <tt>charset</tt>
     * and {@link LineDelimiter#AUTO} delimiter.
     * 
     * @param charset The {@link Charset} to use
     */
    public TextLineDecoder(Charset charset) {
        this(charset, LineDelimiter.AUTO);
    }

    /**
     * Creates a new instance with the spcified <tt>charset</tt>
     * and the specified <tt>delimiter</tt>.
     * 
     * @param charset The {@link Charset} to use
     * @param delimiter The line delimiter to use
     */
    public TextLineDecoder(Charset charset, String delimiter) {
        this(charset, new LineDelimiter(delimiter));
    }

    /**
     * Creates a new instance with the specified <tt>charset</tt>
     * and the specified <tt>delimiter</tt>.
     * 
     * @param charset The {@link Charset} to use
     * @param delimiter The line delimiter to use
     */
    public TextLineDecoder(Charset charset, LineDelimiter delimiter) {
        if (charset == null) {
            throw new IllegalArgumentException("charset parameter shuld not be null");
        }

        if (delimiter == null) {
            throw new IllegalArgumentException("delimiter parameter should not be null");
        }

        this.charset = charset;
        this.delimiter = delimiter;

        // Convert delimiter to ByteBuffer if not done yet.
        if (delimBuf == null) {
            IoBuffer tmp = IoBuffer.allocate(2).setAutoExpand(true);

            try {
                tmp.putString(delimiter.getValue(), charset.newEncoder());
            } catch (CharacterCodingException cce) {

            }

            tmp.flip();
            delimBuf = tmp;
        }
    }

    /**
     * @return the allowed maximum size of the line to be decoded.
     * If the size of the line to be decoded exceeds this value, the
     * decoder will throw a {@link BufferDataException}.  The default
     * value is <tt>1024</tt> (1KB).
     */
    public int getMaxLineLength() {
        return maxLineLength;
    }

    /**
     * Sets the allowed maximum size of the line to be decoded.
     * If the size of the line to be decoded exceeds this value, the
     * decoder will throw a {@link BufferDataException}.  The default
     * value is <tt>1024</tt> (1KB).
     * 
     * @param maxLineLength The maximum line length
     */
    public void setMaxLineLength(int maxLineLength) {
        if (maxLineLength <= 0) {
            throw new IllegalArgumentException("maxLineLength (" + maxLineLength + ") should be a positive value");
        }

        this.maxLineLength = maxLineLength;
    }

    /**
     * Sets the default buffer size. This buffer is used in the Context
     * to store the decoded line.
     *
     * @param bufferLength The default bufer size
     */
    public void setBufferLength(int bufferLength) {
        if (bufferLength <= 0) {
            throw new IllegalArgumentException("bufferLength (" + maxLineLength + ") should be a positive value");

        }

        this.bufferLength = bufferLength;
    }

    /**
     * @return the allowed buffer size used to store the decoded line
     * in the Context instance.
     */
    public int getBufferLength() {
        return bufferLength;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        Context ctx = getContext(session);

        if (LineDelimiter.AUTO.equals(delimiter)) {
            decodeAuto(ctx, session, in, out);
        // 在 WINDOWS 下 delimiter 的值为 \r\n，一般调用 decodeNormal
        } else {
            decodeNormal(ctx, session, in, out);
        }
    }

    /**
     * @return the context for this session
     *
     * 如果 session 中没有 decoder 的上下文环境，那么就创建 context 并且保存到 session 中
     * 
     * @param session The session for which we want the context
     */
    private Context getContext(IoSession session) {
        Context ctx;
        ctx = (Context) session.getAttribute(CONTEXT);

        if (ctx == null) {
            ctx = new Context(bufferLength);
            session.setAttribute(CONTEXT, ctx);
        }

        return ctx;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
        // Do nothing
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose(IoSession session) throws Exception {
        Context ctx = (Context) session.getAttribute(CONTEXT);

        if (ctx != null) {
            session.removeAttribute(CONTEXT);
        }
    }

    /**
     * Decode a line using the default delimiter on the current system
     */
    private void decodeAuto(Context ctx, IoSession session, IoBuffer in, ProtocolDecoderOutput out)
            throws CharacterCodingException, ProtocolDecoderException {
        int matchCount = ctx.getMatchCount();

        // Try to find a match
        int oldPos = in.position();
        int oldLimit = in.limit();

        while (in.hasRemaining()) {
            byte b = in.get();
            boolean matched = false;

            switch (b) {
            case '\r':
                // Might be Mac, but we don't auto-detect Mac EOL
                // to avoid confusion.
                matchCount++;
                break;

            case '\n':
                // UNIX
                matchCount++;
                matched = true;
                break;

            default:
                matchCount = 0;
            }

            if (matched) {
                // Found a match.
                int pos = in.position();
                in.limit(pos);
                in.position(oldPos);

                ctx.append(in);

                in.limit(oldLimit);
                in.position(pos);

                if (ctx.getOverflowPosition() == 0) {
                    IoBuffer buf = ctx.getBuffer();
                    buf.flip();
                    buf.limit(buf.limit() - matchCount);

                    try {
                        byte[] data = new byte[buf.limit()];
                        buf.get(data);
                        CharsetDecoder decoder = ctx.getDecoder();

                        CharBuffer buffer = decoder.decode(ByteBuffer.wrap(data));
                        String str = buffer.toString();
                        writeText(session, str, out);
                    } finally {
                        buf.clear();
                    }
                } else {
                    int overflowPosition = ctx.getOverflowPosition();
                    ctx.reset();
                    throw new RecoverableProtocolDecoderException("Line is too long: " + overflowPosition);
                }

                oldPos = pos;
                matchCount = 0;
            }
        }

        // Put remainder to buf.
        in.position(oldPos);
        ctx.append(in);

        ctx.setMatchCount(matchCount);
    }

    /**
     * Decode a line using the delimiter defined by the caller
     */
    private void decodeNormal(Context ctx, IoSession session, IoBuffer in, ProtocolDecoderOutput out)
            throws CharacterCodingException, ProtocolDecoderException {
        // 从 ctx 中获取到 matchCount 的值，matchCount 可以表示上次解析到了哪儿
        int matchCount = ctx.getMatchCount();

        // Try to find a match
        // oldPos 表示前一次成功解码的信息的结尾
        int oldPos = in.position();
        int oldLimit = in.limit();

        /*
         * 在 WINDOWS 系统中，一行的分隔符为 \r\n，因此先不断循环直到 in[pos] = \r（此时 matchCount = 0），
         * 随后 matchCount 自增变为 1，delimBuf.get(matchCount) == in[pos] == \r，但是还需要确认后面的
         * 元素为 \n。当确认找到了 \r 和 \n 字符并且它们连续时，就会把 oldPos 到 pos （oldPos 表示上一次
         * 成功解码的信息结尾，pos 表示当前这一次成功解码的信息结尾）这之间的字节数据解码成 String 字符串，然后
         * 保存到 ProtocolDecoderOutput 的 messageQueue 中。以下注释举例都以 WINDOWS 系统为准。
         */
        while (in.hasRemaining()) {
            byte b = in.get();

            if (delimBuf.get(matchCount) == b) {
                matchCount++;

                // 如果分隔符为 \r\n，那么数据中必须出现 \r\n，并且必须连续，matchCount == delimBuf.limit() == 2
                if (matchCount == delimBuf.limit()) {
                    // Found a match.
                    int pos = in.position();

                    // 把 oldPos 到 pos 区间的数据添加到 ctx 的 buf 中，在 append 方法中会做一个溢出判断，
                    // 如果 buf.position() + in.remaining() > maxLength 的话，就说明溢出，此时会丢弃掉
                    // in 里面的数据，即使 in 这个 ByteBuffer 中还有数据，也不会再接受（将其 position 设置为
                    // limit），同时清空 ctx 中的 buf。
                    in.limit(pos);
                    in.position(oldPos);
                    ctx.append(in);

                    // 恢复 in 的状态
                    in.limit(oldLimit);
                    in.position(pos);

                    if (ctx.getOverflowPosition() == 0) {
                        IoBuffer buf = ctx.getBuffer();
                        buf.flip();
                        // 最后解码得到的消息需要排除掉分隔符
                        buf.limit(buf.limit() - matchCount);

                        try {
                            // 将解码得到的消息保存到 out 中的 messageQueue 中，并且清空 buf，为下一次解码做准备
                            writeText(session, buf.getString(ctx.getDecoder()), out);
                        } finally {
                            buf.clear();
                        }

                    // 如果发生了溢出（读取到的行字符数太多），直接重置解码器的上下文环境，in 里面的数据丢弃不管
                    } else {
                        int overflowPosition = ctx.getOverflowPosition();
                        ctx.reset();
                        throw new RecoverableProtocolDecoderException("Line is too long: " + overflowPosition);
                    }

                    oldPos = pos;
                    matchCount = 0;
                }
            } else {
                // fix for DIRMINA-506 & DIRMINA-536
                in.position(Math.max(0, in.position() - matchCount));
                matchCount = 0;
            }
        }

        // Put remainder to buf.
        in.position(oldPos);
        // in 里面可能还有半包数据，继续存储到 ctx 的 buf 中，同时也要进行溢出检测
        ctx.append(in);
        // matchCount 也要保存到 ctx 中
        ctx.setMatchCount(matchCount);
    }

    /**
     * By default, this method propagates the decoded line of text to
     * {@code ProtocolDecoderOutput#write(Object)}.  You may override this method to modify
     * the default behavior.
     *
     * @param session  the {@code IoSession} the received data.
     * @param text  the decoded text
     * @param out  the upstream {@code ProtocolDecoderOutput}.
     */
    protected void writeText(IoSession session, String text, ProtocolDecoderOutput out) {
        out.write(text);
    }

    /**
     * A Context used during the decoding of a lin. It stores the decoder,
     * the temporary buffer containing the decoded line, and other status flags.
     *
     * Context 对象用来保存 TextLineDecoder 解码过程中的上下文信息，比如解码器 decoder，还有用来暂存接收到的数据的 buf，
     * 因为 TextLineDecoder 在解码的过程中也会出现半包、粘包现象，所以如果当前信息解码不成功，可能因为剩下的数据
     * 还没有接收到，因此需要使用 buf 来暂时存储无法解码的数据。matchCount 表示找到第几个分隔符了，举例来说，windows 下
     * 的分隔符为 \r\n 两个，如果在数据中找到了 \r，那么 matchCount = 0；如果找到了 \r，那么 matchCount = 1；如果
     * 找到了 \n，那么 matchCount = 2
     *
     * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
     * @version $Rev$, $Date$
     */
    private class Context {
        /** The decoder */
        private final CharsetDecoder decoder;

        /** The temporary buffer containing the decoded line */
        // 用来暂时存储半包数据
        private final IoBuffer buf;

        /** The number of lines found so far */
        // matchCount 表示匹配到第几个分隔符
        private int matchCount = 0;

        /** A counter to signal that the line is too long */
        // 是否溢出的标志
        private int overflowPosition = 0;

        /** Create a new Context object with a default buffer */
        private Context(int bufferLength) {
            decoder = charset.newDecoder();
            buf = IoBuffer.allocate(bufferLength).setAutoExpand(true);
        }

        public CharsetDecoder getDecoder() {
            return decoder;
        }

        public IoBuffer getBuffer() {
            return buf;
        }

        public int getOverflowPosition() {
            return overflowPosition;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public void setMatchCount(int matchCount) {
            this.matchCount = matchCount;
        }

        public void reset() {
            overflowPosition = 0;
            matchCount = 0;
            // Resets this decoder, clearing any internal state.
            decoder.reset();
        }

        public void append(IoBuffer in) {
            if (overflowPosition != 0) {
                discard(in);
            // 如果 buf.position + in.remaining 大于 maxLineLength 溢出，就把 in 这个 Buffer 中的数据丢弃掉，
            // 并且清空掉 buf
            } else if (buf.position() > maxLineLength - in.remaining()) {
                overflowPosition = buf.position();
                buf.clear();
                // 丢弃掉 in，设置 position = limit，不会再从 in 中读取任何数据
                discard(in);
            } else {
                getBuffer().put(in);
            }
        }

        private void discard(IoBuffer in) {
            if (Integer.MAX_VALUE - in.remaining() < overflowPosition) {
                overflowPosition = Integer.MAX_VALUE;
            } else {
                overflowPosition += in.remaining();
            }

            in.position(in.limit());
        }
    }
}