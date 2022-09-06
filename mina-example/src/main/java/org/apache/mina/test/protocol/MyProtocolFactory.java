package org.apache.mina.test.protocol;

import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolEncoder;

import java.nio.charset.Charset;

/**
 * MyProtocolFactory 是一个工厂类，传入到 ProtocolCodecFilter 的构造函数中，用来获取 encoder 和 decoder
 */
public class MyProtocolFactory implements ProtocolCodecFactory {

    private final MyProtocolEncoder encoder;
    private final ProtocolDecoder decoder;

    public MyProtocolFactory(Charset charset) {
        this.encoder = new MyProtocolEncoder(charset);
        this.decoder = new MyProtocolDecoder(charset);
    }

    @Override
    public ProtocolEncoder getEncoder(IoSession session) throws Exception {
        return encoder;
    }

    @Override
    public ProtocolDecoder getDecoder(IoSession session) throws Exception {
        return decoder;
    }

}
