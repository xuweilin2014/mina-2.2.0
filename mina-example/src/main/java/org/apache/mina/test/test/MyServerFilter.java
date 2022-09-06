package org.apache.mina.test.test;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

public class MyServerFilter extends IoFilterAdapter {

    @Override
    public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
        System.out.println("MyServerFilter -> messageReceived");
        nextFilter.messageReceived(session, message);
    }

    @Override
    public void messageSent(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        System.out.println("MyServerFilter -> messageSent");
        nextFilter.messageSent(session, writeRequest);
    }

}
