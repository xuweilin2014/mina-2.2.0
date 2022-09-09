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
package org.apache.mina.core.future;

import org.apache.mina.core.session.IoSession;

/**
 * A default implementation of {@link WriteFuture}.
 * DefaultWriteFuture 表示写操作的异步
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class DefaultWriteFuture extends DefaultIoFuture implements WriteFuture {
    /**
     * Creates a new instance.
     * 
     * @param session The associated session
     */
    public DefaultWriteFuture(IoSession session) {
        super(session);
    }

    /**
     * Returns a new {@link DefaultWriteFuture} which is already marked as 'written'.
     * 
     * @param session The associated session
     * @return A new future for a written message
     */
    public static WriteFuture newWrittenFuture(IoSession session) {
        DefaultWriteFuture writtenFuture = new DefaultWriteFuture(session);
        writtenFuture.setWritten();
        
        return writtenFuture;
    }

    /**
     * Returns a new {@link DefaultWriteFuture} which is already marked as 'not written'.
     * 
     * @param session The associated session
     * @param cause The reason why the message has not be written
     * @return A new future for not written message
     */
    public static WriteFuture newNotWrittenFuture(IoSession session, Throwable cause) {
        DefaultWriteFuture unwrittenFuture = new DefaultWriteFuture(session);
        unwrittenFuture.setException(cause);
        
        return unwrittenFuture;
    }

    /**
     * {@inheritDoc}
     * DefaultWriteFuture 继承了 DefaultFuture，对于不同类型的 future，其 value 值可能不同。
     * 对于 writeFuture 而言，其 value 只可能是 Boolean 或者 Throwable 两种（只有 setWritten 和
     * setException 两个方法），但是这两种都表示异步操作的完成
     */
    @Override
    public boolean isWritten() {
        if (isDone()) {
            Object v = getValue();
            
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
        }
        
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable getException() {
        if (isDone()) {
            Object v = getValue();
            
            if (v instanceof Throwable) {
                return (Throwable) v;
            }
        }
        
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWritten() {
        setValue(Boolean.TRUE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setException(Throwable exception) {
        if (exception == null) {
            throw new IllegalArgumentException("exception");
        }

        setValue(exception);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WriteFuture await() throws InterruptedException {
        return (WriteFuture) super.await();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WriteFuture awaitUninterruptibly() {
        return (WriteFuture) super.awaitUninterruptibly();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WriteFuture addListener(IoFutureListener<?> listener) {
        return (WriteFuture) super.addListener(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WriteFuture removeListener(IoFutureListener<?> listener) {
        return (WriteFuture) super.removeListener(listener);
    }
}
