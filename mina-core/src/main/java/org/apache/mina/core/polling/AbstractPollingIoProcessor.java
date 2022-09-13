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
package org.apache.mina.core.polling;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.ClosedSelectorException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.future.DefaultIoFuture;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.IoServiceListenerSupport;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionConfig;
import org.apache.mina.core.session.SessionState;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.core.write.WriteToClosedSessionException;
import org.apache.mina.transport.socket.AbstractDatagramSessionConfig;
import org.apache.mina.util.ExceptionMonitor;
import org.apache.mina.util.NamePreservingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An abstract implementation of {@link IoProcessor} which helps transport
 * developers to write an {@link IoProcessor} easily. This class is in charge of
 * active polling a set of {@link IoSession} and trigger events when some I/O
 * operation is possible.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * 
 * @param <S>
 *            the type of the {@link IoSession} this processor can handle
 */
public abstract class AbstractPollingIoProcessor<S extends AbstractIoSession> implements IoProcessor<S> {
    /** A logger for this class */
    private static final Logger LOG = LoggerFactory.getLogger(IoProcessor.class);

    /**
     * A timeout used for the select, as we need to get out to deal with idle sessions
     */
    private static final long SELECT_TIMEOUT = 1000L;

    /** A map containing the last Thread ID for each class */
    private static final ConcurrentHashMap<Class<?>, AtomicInteger> threadIds = new ConcurrentHashMap<>();

    /** This IoProcessor instance name */
    private final String threadName;

    /** The executor to use when we need to start the inner Processor */
    private final Executor executor;

    /** A Session queue containing the newly created sessions */
    private final Queue<S> newSessions = new ConcurrentLinkedQueue<>();

    /** A queue used to store the sessions to be removed */
    private final Queue<S> removingSessions = new ConcurrentLinkedQueue<>();

    /**
     * A queue used to store the sessions to be flushed
     * session 中发生了 OP_WRITE 写事件准备把数据写出时，就会把 session 添加到 flushingSessions 集合中
     */
    private final Queue<S> flushingSessions = new ConcurrentLinkedQueue<>();

    /**
     * A queue used to store the sessions which have a trafficControl to be updated
     */
    private final Queue<S> trafficControllingSessions = new ConcurrentLinkedQueue<>();

    /** The processor thread : it handles the incoming messages */
    private final AtomicReference<Processor> processorRef = new AtomicReference<>();

    private long lastIdleCheckTime;

    private final Object disposalLock = new Object();

    private volatile boolean disposing;

    private volatile boolean disposed;

    private final DefaultIoFuture disposalFuture = new DefaultIoFuture(null);

    protected AtomicBoolean wakeupCalled = new AtomicBoolean(false);

    /**
     * Create an {@link AbstractPollingIoProcessor} with the given
     * {@link Executor} for handling I/Os events.
     * 
     * @param executor
     *            the {@link Executor} for handling I/O events
     */
    protected AbstractPollingIoProcessor(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor");
        }

        this.threadName = nextThreadName();
        this.executor = executor;
    }

    /**
     * Compute the thread ID for this class instance. As we may have different
     * classes, we store the last ID number into a Map associating the class
     * name to the last assigned ID.
     * 
     * @return a name for the current thread, based on the class name and an incremental value, starting at 1.
     */
    private String nextThreadName() {
        Class<?> cls = getClass();
        int newThreadId;

        AtomicInteger threadId = threadIds.putIfAbsent(cls, new AtomicInteger(1));

        if (threadId == null) {
            newThreadId = 1;
        } else {
            // Just increment the last ID, and get it.
            newThreadId = threadId.incrementAndGet();
        }

        // Now we can compute the name for this thread
        return cls.getSimpleName() + '-' + newThreadId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isDisposing() {
        return disposing;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isDisposed() {
        return disposed;
    }

    /**
     * dispose 方法是 AbstractPollingAcceptor 或者 AbstractPollingConnector 类关闭时，
     * 调用 SimpleIoProcessorPool 中 ioProcessor#dispose 方法，随后调用下面的 dispose
     * 方法关闭释放 processor。不过，此方法可能存在 race condition，比如多个 connector
     * 想要关闭释放，因此使用 disposalLock 锁，随后在 disposalFuture 上阻塞，等待
     * processor 的 run 方法真正执行 dispose 方法关闭 selector，并设置 disposalFuture.setValue(true)，
     * 唤醒阻塞线程。
     *
     * 另外还有一种情况，如果 Processor 的 run 方法因为没有 session 注册在 selector 上，同时没有新连接时，会
     * 退出事件循环，主动 doDispose，设置 disposalFuture.setValue(true)。后面如果 acceptor 要 dispose
     * 时，执行到 disposalFuture.awaitUninterruptibly() 时会直接返回，不会阻塞
     *
     * {@inheritDoc}
     */
    @Override
    public final void dispose() {
        if (disposed || disposing) {
            return;
        }

        synchronized (disposalLock) {
            disposing = true;
            startupProcessor();
        }

        disposalFuture.awaitUninterruptibly();
        disposed = true;
    }

    /**
     * Dispose the resources used by this {@link IoProcessor} for polling the
     * client connections. The implementing class doDispose method will be
     * called.
     * 
     * @throws Exception
     *             if some low level IO error occurs
     */
    protected abstract void doDispose() throws Exception;

    /**
     * poll those sessions for the given timeout
     * 
     * @param timeout
     *            milliseconds before the call timeout if no event appear
     * @return The number of session ready for read or for write
     * @throws Exception
     *             if some low level IO error occurs
     */
    protected abstract int select(long timeout) throws Exception;

    /**
     * poll those sessions forever
     * 
     * @return The number of session ready for read or for write
     * @throws Exception
     *             if some low level IO error occurs
     */
    protected abstract int select() throws Exception;

    /**
     * Say if the list of {@link IoSession} polled by this {@link IoProcessor}
     * is empty
     * 
     * @return <tt>true</tt> if at least a session is managed by this
     *         {@link IoProcessor}
     */
    protected abstract boolean isSelectorEmpty();

    /**
     * Interrupt the {@link #select(long)} call.
     */
    protected abstract void wakeup();

    /**
     * Get an {@link Iterator} for the list of {@link IoSession} polled by this
     * {@link IoProcessor}
     * 
     * @return {@link Iterator} of {@link IoSession}
     */
    protected abstract Iterator<S> allSessions();
    
    /**
     * Get the number of {@link IoSession} polled by this {@link IoProcessor}
     *
     * @return the number of sessions attached to this {@link IoProcessor}
     */
    protected abstract int allSessionsCount();

    /**
     * Get an {@link Iterator} for the list of {@link IoSession} found selected
     * by the last call of {@link #select(long)}
     * 
     * @return {@link Iterator} of {@link IoSession} read for I/Os operation
     */
    protected abstract Iterator<S> selectedSessions();

    /**
     * Get the state of a session (One of OPENING, OPEN, CLOSING)
     * 
     * @param session
     *            the {@link IoSession} to inspect
     * @return the state of the session
     */
    protected abstract SessionState getState(S session);

    /**
     * Tells if the session ready for writing
     * 
     * @param session
     *            the queried session
     * @return <tt>true</tt> is ready, <tt>false</tt> if not ready
     */
    protected abstract boolean isWritable(S session);

    /**
     * Tells if the session ready for reading
     * 
     * @param session
     *            the queried session
     * @return <tt>true</tt> is ready, <tt>false</tt> if not ready
     */
    protected abstract boolean isReadable(S session);

    /**
     * Set the session to be informed when a write event should be processed
     * 
     * @param session
     *            the session for which we want to be interested in write events
     * @param isInterested
     *            <tt>true</tt> for registering, <tt>false</tt> for removing
     * @throws Exception
     *             If there was a problem while registering the session
     */
    protected abstract void setInterestedInWrite(S session, boolean isInterested) throws Exception;

    /**
     * Set the session to be informed when a read event should be processed
     * 
     * @param session
     *            the session for which we want to be interested in read events
     * @param isInterested
     *            <tt>true</tt> for registering, <tt>false</tt> for removing
     * @throws Exception
     *             If there was a problem while registering the session
     */
    protected abstract void setInterestedInRead(S session, boolean isInterested) throws Exception;

    /**
     * Tells if this session is registered for reading
     * 
     * @param session
     *            the queried session
     * @return <tt>true</tt> is registered for reading
     */
    protected abstract boolean isInterestedInRead(S session);

    /**
     * Tells if this session is registered for writing
     * 
     * @param session
     *            the queried session
     * @return <tt>true</tt> is registered for writing
     */
    protected abstract boolean isInterestedInWrite(S session);

    /**
     * Initialize the polling of a session. Add it to the polling process.
     * 
     * @param session
     *            the {@link IoSession} to add to the polling
     * @throws Exception
     *             any exception thrown by the underlying system calls
     */
    protected abstract void init(S session) throws Exception;

    /**
     * Destroy the underlying client socket handle
     * 
     * @param session
     *            the {@link IoSession}
     * @throws Exception
     *             any exception thrown by the underlying system calls
     */
    protected abstract void destroy(S session) throws Exception;

    /**
     * Reads a sequence of bytes from a {@link IoSession} into the given
     * {@link IoBuffer}. Is called when the session was found ready for reading.
     *
     * 从 buffer 中读取字节数据到 session 中
     *
     * @param session
     *            the session to read
     * @param buf
     *            the buffer to fill
     * @return the number of bytes read
     * @throws Exception
     *             any exception thrown by the underlying system calls
     */
    protected abstract int read(S session, IoBuffer buf) throws Exception;

    /**
     * Write a sequence of bytes to a {@link IoSession}, means to be called when
     * a session was found ready for writing.
     *
     * 将 buffer 中长度为 length 的字节数据写入到 session 中，具体的实现由子类 NioProcessor 来实现
     * 
     * @param session
     *            the session to write
     * @param buf
     *            the buffer to write
     * @param length
     *            the number of bytes to write can be superior to the number of
     *            bytes remaining in the buffer
     * @return the number of byte written
     * @throws IOException
     *             any exception thrown by the underlying system calls
     */
    protected abstract int write(S session, IoBuffer buf, int length) throws IOException;

    /**
     * Write a part of a file to a {@link IoSession}, if the underlying API
     * isn't supporting system calls like sendfile(), you can throw a
     * {@link UnsupportedOperationException} so the file will be send using
     * usual {@link #write(AbstractIoSession, IoBuffer, int)} call.
     * 
     * @param session
     *            the session to write
     * @param region
     *            the file region to write
     * @param length
     *            the length of the portion to send
     * @return the number of written bytes
     * @throws Exception
     *             any exception thrown by the underlying system calls
     */
    protected abstract int transferFile(S session, FileRegion region, int length) throws Exception;

    /**
     * {@inheritDoc}
     */
    @Override
    public final void add(S session) {
        if (disposed || disposing) {
            throw new IllegalStateException("Already disposed.");
        }

        // Adds the session to the newSession queue and starts the worker
        newSessions.add(session);
        startupProcessor();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void remove(S session) {
        // 将此 session 添加到 removingSessions 中，然后启动 processor 的事件循环处理
        scheduleRemove(session);
        startupProcessor();
    }

    private void scheduleRemove(S session) {
        if (!removingSessions.contains(session)) {
            removingSessions.add(session);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(S session, WriteRequest writeRequest) {
        WriteRequestQueue writeRequestQueue = session.getWriteRequestQueue();
        writeRequestQueue.offer(session, writeRequest);

        if (!session.isWriteSuspended()) {
            this.flush(session);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void flush(S session) {
        // add the session to the queue if it's not already in the queue, then wake up the select()
        if (session.setScheduledForFlush(true)) {
            flushingSessions.add(session);
            wakeup();
        }
    }

    /**
     * Updates the traffic mask for a given session
     * 
     * @param session
     *            the session to update
     */
    public final void updateTrafficMask(S session) {
        trafficControllingSessions.add(session);
        wakeup();
    }

    /**
     * Starts the inner Processor, asking the executor to pick a thread in its pool. The Runnable will be renamed
     */
    private void startupProcessor() {
        Processor processor = processorRef.get();

        // 如果第一次启动 startupProcessor，就创建 processor，并且使用 executor 线程池中的一个线程来
        // 执行 processor 中的 run 方法处理 session 中的 I/O 事件；如果之前启动过，则直接执行 wakeup，
        // 效果就是要么在 select 上阻塞的方法立即返回，要么就是下次调用 select 方法不阻塞，直接返回
        if (processor == null) {
            processor = new Processor();

            if (processorRef.compareAndSet(null, processor)) {
                executor.execute(new NamePreservingRunnable(processor, threadName));
            }
        }

        // Just stop the select() and start it again, so that the processor can be activated immediately.
        wakeup();
    }

    /**
     * In the case we are using the java select() method, this method is used to
     * trash the buggy selector and create a new one, registring all the sockets
     * on it.
     * 
     * @throws IOException
     *             If we got an exception
     */
    protected abstract void registerNewSelector() throws IOException;

    /**
     * Check that the select() has not exited immediately just because of a
     * broken connection. In this case, this is a standard case, and we just
     * have to loop.
     * 
     * @return <tt>true</tt> if a connection has been brutally closed.
     * @throws IOException
     *             If we got an exception
     */
    protected abstract boolean isBrokenConnection() throws IOException;

    private void read(S session) {
        IoSessionConfig config = session.getConfig();
        int bufferSize = config.getReadBufferSize();
        IoBuffer buf = IoBuffer.allocate(bufferSize);

        // hasFragmentation 表明数据的传输是否存在碎片化，也就是半包或者粘包的情况
        final boolean hasFragmentation = session.getTransportMetadata().hasFragmentation();

        try {
            int readBytes = 0;
            int ret;

            try {
                if (hasFragmentation) {
                    // read 方法使用 nio 原生的 api 从 channel 中读取字节数据到 buf 中，返回读取的字节数
                    // 如果发送的消息存在碎片话，也就是不能一次读完的话，需要使用 while 循环不断从 channel 中
                    // 读取，直到没有读取到任何字节为止
                    while ((ret = read(session, buf)) > 0) {
                        readBytes += ret;
                        // buf 在从 channel 中读取数据时，position 会不断增加，这时需要判断 position <= limit，
                        // 如果不满足，则需要直接返回
                        if (!buf.hasRemaining()) {
                            break;
                        }
                    }
                } else {
                    // 没有碎片化的话，直接一次读完
                    ret = read(session, buf);

                    if (ret > 0) {
                        readBytes = ret;
                    }
                }
            } finally {
                buf.flip();
            }

            if (readBytes > 0) {
                // 读取到数据之后，调用 filterChain 上每一个 filter 中的 messageReceived 事件
                // 最后 filterChain 中的 TailFilter 会把此事件连带 message 传送给用户自定义的业务 handler
                IoFilterChain filterChain = session.getFilterChain();
                filterChain.fireMessageReceived(buf);
                buf = null;

                if (hasFragmentation) {
                    // 如果设置的读缓冲区大小比读取到的字节数的两倍还多，那么减小读缓冲区（除以2）
                    if (readBytes << 1 < config.getReadBufferSize()) {
                        session.decreaseReadBufferSize();
                    // 如果设置的读缓冲区大小和读取到的字节数相同，那么增加读缓冲区（乘以2）
                    } else if (readBytes == config.getReadBufferSize()) {
                        session.increaseReadBufferSize();
                    }
                }
            } else {
                // release temporary buffer when read nothing
                buf.free(); 
            }

            // 这里要特别注意，当连接的某一端 A 突然关闭掉连接时，会触发 OP_READ 事件，另一端 B 读取到的字节数为 -1
            // 所以，如果 ret 如果 小于 0，说明这个连接 session 已经关闭了不会再发送数据
            if (ret < 0) {
                IoFilterChain filterChain = session.getFilterChain();
                // fire filterChain 中所有 filter 的 inputClosed 事件
                filterChain.fireInputClosed();
            }
        } catch (Exception e) {
            if ((e instanceof IOException) &&
                (!(e instanceof PortUnreachableException)
                        || !AbstractDatagramSessionConfig.class.isAssignableFrom(config.getClass())
                        || ((AbstractDatagramSessionConfig) config).isCloseOnPortUnreachable())) {
                scheduleRemove(session);
            }

            IoFilterChain filterChain = session.getFilterChain();
            filterChain.fireExceptionCaught(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateTrafficControl(S session) {

        try {
            // 如果 readSuspended 为 true，表明暂缓读，那么设置此 session 取消监听读 OP_READ 事件
            // 否则的话，设置此 session 监听 OP_READ 事件
            setInterestedInRead(session, !session.isReadSuspended());
        } catch (Exception e) {
            IoFilterChain filterChain = session.getFilterChain();
            filterChain.fireExceptionCaught(e);
        }

        try {
            // 如果 writeSuspended 为 true，表明暂缓写，那么设置此 session 取消监听 OP_WRITE 事件
            // 否则的话，设置此 session 监听 OP_WRITE 事件
            setInterestedInWrite(session, !session.getWriteRequestQueue().isEmpty(session) && !session.isWriteSuspended());
        } catch (Exception e) {
            IoFilterChain filterChain = session.getFilterChain();
            filterChain.fireExceptionCaught(e);
        }
    }

    /**
     * The main loop. This is the place in charge to poll the Selector, and to
     * process the active sessions. It's done in - handle the newly created
     * sessions -
     */
    private class Processor implements Runnable {
        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            assert processorRef.get() == this;

            lastIdleCheckTime = System.currentTimeMillis();
            int nbTries = 10;

            for (;;) {
                try {
                    // This select has a timeout so that we can manage
                    // idle session when we get out of the select every
                    // second. (note : this is a hack to avoid creating
                    // a dedicated thread).
                    long t0 = System.currentTimeMillis();
                    int selected = select(SELECT_TIMEOUT);
                    long t1 = System.currentTimeMillis();
                    long delta = t1 - t0;

                    if (!wakeupCalled.getAndSet(false) && (selected == 0) && (delta < 100)) {
                        // Last chance : the select() may have been
                        // interrupted because we have had an closed channel.
                        if (isBrokenConnection()) {
                            LOG.warn("Broken connection");
                        } else {
                            // Ok, we are hit by the nasty epoll
                            // spinning.
                            // Basically, there is a race condition
                            // which causes a closing file descriptor not to be
                            // considered as available as a selected channel,
                            // but
                            // it stopped the select. The next time we will
                            // call select(), it will exit immediately for the
                            // same
                            // reason, and do so forever, consuming 100%
                            // CPU.
                            // We have to destroy the selector, and
                            // register all the socket on a new one.
                            if (nbTries == 0) {
                                LOG.warn("Create a new selector. Selected is 0, delta = " + delta);
                                registerNewSelector();
                                nbTries = 10;
                            } else {
                                nbTries--;
                            }
                        }
                    } else {
                        nbTries = 10;
                    }
                    
                    // Manage newly created session first
                    // handleNewSession 方法真正初始化新建立的连接，比如注册 selector，fire session 中 filter 和 listener 的相关事件
                    if(handleNewSessions() == 0) {
                        // Get a chance to exit the infinite loop if there are no
                        // more sessions on this Processor
                        // allSessionsCount 返回注册在 selector 上的连接的个数
                        if (allSessionsCount() == 0) {
                            processorRef.set(null);

                            // 如果当前没有任何 session 注册在 selector 上，并且 newSessions 也为空，
                            // 因此没有任何 OP_READ 和 OP_WRITE 事件需要处理，退出事件循环
                            if (newSessions.isEmpty() && isSelectorEmpty()) {
                                // newSessions.add() precedes startupProcessor
                                assert processorRef.get() != this;
                                break;
                            }

                            assert processorRef.get() != this;

                            if (!processorRef.compareAndSet(null, this)) {
                                // startupProcessor won race, so must exit processor
                                assert processorRef.get() != this;
                                break;
                            }

                            assert processorRef.get() == this;
                        }
                    }

                    // 更新 trafficControllingSessions 中所有 session 的监听的事件，即根据 session 中的 suspendRead 以及 suspendWrite
                    // 属性来决定是否监听 OP_READ 或者 OP_WRITE 事件
                    // PS: 不过似乎只有在 ClientToProxyIoHandler 中才会用到 suspendRead 以及 suspendWrite 属性
                    updateTrafficMask();

                    // Now, if we have had some incoming or outgoing events, deal with them
                    if (selected > 0) {
                        // LOG.debug("Processing ..."); // This log hurts one of
                        // the MDCFilter test...
                        // 处理 session 中的 OP_READ 和 OP_WRITE 事件
                        // 1.如果是 OP_READ 事件，那么就直接读取数据，并且 fire messageReceived 方法
                        // 2.如果是 OP_WRITE 事件，那么就将此 session 添加到 flushingSessions 队列中
                        process();
                    }
                    
                    // Write the pending requests
                    long currentTime = System.currentTimeMillis();

                    // 遍历 flushingSessions 队列，然后处理每一个 session 中 WriteRequestQueue 队列里面的写请求，
                    // 把写请求中的信息写入到 channel 中，然后在 filterChain 上 fire messageSent 事件
                    flush(currentTime);
                    
                    // Last, not least, send Idle events to the idle sessions
                    // 检查各个 session 在 idleTime 内是否没有发生 I/O 事件，处于 idleStatus 状态
                    notifyIdleSessions(currentTime);
                    
                    // And manage removed sessions
                    // 处理 removingSessions 队列中的各个 session，将 session 从 selector 上取消注册，并且触发 session 中
                    // 所有注册的 listener 的 sessionDestroyed 事件，同时 fire filterChain 上的 sessionClosed 事件。
                    // 如果 session 中还存在没有写完的写请求的话，就为各个写请求生成一个异常 WriteToClosedSessionException，
                    // 然后把此异常设置到写请求对应的 WriteFuture 中，唤醒阻塞在此 future 上的线程
                    removeSessions();
                    
                    // Disconnect all sessions immediately if disposal has been
                    // requested so that we exit this loop eventually.
                    if (isDisposing()) {
                        boolean hasKeys = false;

                        for (Iterator<S> i = allSessions(); i.hasNext();) {
                            IoSession session = i.next();
                            // 将此 Processor 所管理的每个 session 都添加到 removingSessions 中
                            scheduleRemove((S) session);

                            if (session.isActive()) {
                                hasKeys = true;
                            }
                        }
                        // 唤醒其他调用了 select() 或者 select(timeout) 方法而阻塞的线程
                        // 如果此时没有线程阻塞在 select 方法上，那么当有线程下次调用 select 方法时，会立刻返回
                        // 因此本线程下次不会阻塞在 select 方法上，而是继续处理事件
                        wakeup();
                    }
                } catch (ClosedSelectorException cse) {
                    // If the selector has been closed, we can exit the loop
                    // But first, dump a stack trace
                    ExceptionMonitor.getInstance().exceptionCaught(cse);
                    break;
                } catch (Exception e) {
                    ExceptionMonitor.getInstance().exceptionCaught(e);

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        ExceptionMonitor.getInstance().exceptionCaught(e1);
                    }
                }
            }

            // 如果 selector 上没有注册任何 session，或者没有新的 session 连接到 acceptor，那么会退出事件循环
            // 另外，如果 selector 关闭后继续处理 OP_READ 和 OP_WRITE 事件时，会抛出 ClosedSelectorException
            // 异常，也会退出事件循环
            try {
                synchronized (disposalLock) {
                    if (disposing) {
                        // 关闭 selector
                        doDispose();
                    }
                }
            } catch (Exception e) {
                ExceptionMonitor.getInstance().exceptionCaught(e);
            } finally {
                disposalFuture.setValue(true);
            }
        }

        /**
         * Loops over the new sessions blocking queue and returns the number of
         * sessions which are effectively created
         * 
         * @return The number of new sessions
         */
        private int handleNewSessions() {
            int addedSessions = 0;

            for (S session = newSessions.poll(); session != null; session = newSessions.poll()) {
                if (addNow(session)) {
                    // A new session has been created
                    addedSessions++;
                }
            }

            return addedSessions;
        }

        private void notifyIdleSessions(long currentTime) throws Exception {
            // process idle sessions
            // 每隔 SELECT_TIMEOUT 时间检查一次各个 session 的是否处于 idleStatus
            if (currentTime - lastIdleCheckTime >= SELECT_TIMEOUT) {
                lastIdleCheckTime = currentTime;
                AbstractIoSession.notifyIdleness(allSessions(), currentTime);
            }
        }

        /**
         * Update the trafficControl for all the session.
         */
        private void updateTrafficMask() {
            int queueSize = trafficControllingSessions.size();

            while (queueSize > 0) {
                S session = trafficControllingSessions.poll();

                if (session == null) {
                    // We are done with this queue.
                    return;
                }

                SessionState state = getState(session);

                switch (state) {
                case OPENED:
                    updateTrafficControl(session);

                    break;

                case CLOSING:
                    break;

                case OPENING:
                    // Retry later if session is not yet fully initialized.
                    // (In case that Session.suspend??() or session.resume??() is
                    // called before addSession() is processed)
                    // We just put back the session at the end of the queue.
                    trafficControllingSessions.add(session);
                    break;

                default:
                    throw new IllegalStateException(String.valueOf(state));
                }

                // As we have handled one session, decrement the number of
                // remaining sessions. The OPENING session will be processed
                // with the next select(), as the queue size has been decreased,
                // even
                // if the session has been pushed at the end of the queue
                queueSize--;
            }
        }

        /**
         * Process a new session : - initialize it - create its chain - fire the
         * CREATED listeners if any
         * 对新获得的 session 连接进行处理：将 session 注册到 selector 上，同时初始化这个 session 连接上的 filter chain，
         * 另外 fire 所有 filter 上的 sessionCreated 和 sessionOpened 事件，同时 fire 监听器 listener 上的 sessionCreated 事件
         * 
         * @param session
         *            The session to create
         * @return <tt>true</tt> if the session has been registered
         */
        private boolean addNow(S session) {
            boolean registered = false;

            try {
                // 真正将 session 中的 channel 注册到 selector 上，同时保存 SelectionKey 到 session 中
                init(session);
                registered = true;

                // Build the filter chain of this session.
                // 从 session 中获取到 chainBuilder，然后把 chainBuilder#entries 集合中的 filter 集合保存到此 session 的 filterChain 里面
                // 同时调用 filter 中的 onPredAdd 和 onPostAdd 方法
                IoFilterChainBuilder chainBuilder = session.getService().getFilterChainBuilder();
                chainBuilder.buildFilterChain(session.getFilterChain());

                // DefaultIoFilterChain.CONNECT_FUTURE is cleared inside here
                // in AbstractIoFilterChain.fireSessionOpened().
                // Propagate the SESSION_CREATED event up to the chain
                // fire 此 session 的 filterChain 中所有 filter 的 sessionCreated 和 sessionOpened 事件，
                // 以及注册的所有 listener 的 sessionCreated 事件
                IoServiceListenerSupport listeners = ((AbstractIoService) session.getService()).getListeners();
                listeners.fireSessionCreated(session);
            } catch (Exception e) {
                ExceptionMonitor.getInstance().exceptionCaught(e);

                try {
                    destroy(session);
                } catch (Exception e1) {
                    ExceptionMonitor.getInstance().exceptionCaught(e1);
                } finally {
                    registered = false;
                }
            }

            return registered;
        }

        private int removeSessions() {
            int removedSessions = 0;

            // removingSessions 表明将要关闭移除的 session 集合，这里循环读取每一个 session，根据 session 的不同状态分别处理
            for (S session = removingSessions.poll(); session != null; session = removingSessions.poll()) {
                SessionState state = getState(session);

                // Now deal with the removal accordingly to the session's state
                switch (state) {
                // 如果 session 处于 OPENED 状态，则关闭移除此 session
                case OPENED:
                    // Try to remove this session
                    if (removeNow(session)) {
                        removedSessions++;
                    }

                    break;

                // 如果 session 处于 CLOSING 状态，那么说明 session 已经关闭了，跳过
                case CLOSING:
                    // Skip if channel is already closed
                    // In any case, remove the session from the queue
                    removedSessions++;
                    break;

                // 如果 session 处于 OPENING 状态时，那么说明 session 刚刚创建完成，还没有注册到 selector 上，
                // 因此需要关闭
                case OPENING:
                    // Remove session from the newSessions queue and remove it
                    newSessions.remove(session);

                    if (removeNow(session)) {
                        removedSessions++;
                    }

                    break;

                default:
                    throw new IllegalStateException(String.valueOf(state));
                }
            }

            return removedSessions;
        }

        /**
         * Write all the pending messages
         */
        private void flush(long currentTime) {
            // Processor 的 I/O 线程在处理写事件时，只会把有 OP_WRITE 事件的 session 保存到 flushingSessions 队列中
            // flushingSessions 表示有写事件的 session 队列
            if (flushingSessions.isEmpty()) {
                return;
            }

            do {
                S session = flushingSessions.poll(); // the same one with firstSession

                if (session == null) {
                    // Just in case ... It should not happen.
                    break;
                }

                // Reset the Schedule for flush flag for this session,
                // as we are flushing it now.  This allows another thread
                // to enqueue data to be written without corrupting the
                // selector interest state.
                session.unscheduledForFlush();
                SessionState state = getState(session);

                switch (state) {
                    case OPENED:
                        try {
                            boolean flushedAll = flushNow(session, currentTime);
                            // 如果消息被全部正确地写出去，并且 session 中的 WriteRequestQueue 不为空，还有写请求，以及 session 没有被添加到
                            // flushingSessions 中去，在随后的 while 循环中被接着获取，处理写请求
                            if (flushedAll && !session.getWriteRequestQueue().isEmpty(session) && !session.isScheduledForFlush()) {
                                scheduleFlush(session);
                            }
                        } catch (Exception e) {
                            scheduleRemove(session);
                            session.closeNow();
                            IoFilterChain filterChain = session.getFilterChain();
                            filterChain.fireExceptionCaught(e);
                        }

                        break;

                    // 当 NioSession 处于关闭状态时，直接跳过
                    case CLOSING:
                        // Skip if the channel is already closed.
                        break;

                    case OPENING:
                        // Retry later if session is not yet fully initialized.
                        // (In case that Session.write() is called before addSession() is processed)
                        scheduleFlush(session);
                        return;

                    default:
                        throw new IllegalStateException(String.valueOf(state));
                }

            } while (!flushingSessions.isEmpty());
        }

        private boolean flushNow(S session, long currentTime) {
            // 如果 session 没有连接，那么就把此 session 添加到 removingSessions 中，等待被移除
            if (!session.isConnected()) {
                scheduleRemove(session);
                return false;
            }

            // 发送的数据包是否存在半包和粘包现象
            final boolean hasFragmentation = session.getTransportMetadata().hasFragmentation();
            final WriteRequestQueue writeRequestQueue = session.getWriteRequestQueue();

            // Set limitation for the number of written bytes for read-write
            // fairness. I used maxReadBufferSize * 3 / 2, which yields best
            // performance in my experience while not breaking fairness much.
            // 读写事件都由 Processor I/O 线程来进行，因此花费在读写上的时间应该大致相同。这里设置最多写的字节数为 maxReadBufferSize * 1.5
            final int maxWrittenBytes = session.getConfig().getMaxReadBufferSize() + (session.getConfig().getMaxReadBufferSize() >>> 1);
            // 已经发送的字节数
            int writtenBytes = 0;
            WriteRequest req = null;

            try {
                // Clear OP_WRITE
                // 使得当前 session 不再监听 OP_WRITE 事件
                setInterestedInWrite(session, false);

                do {
                    // Check for pending writes.
                    // 检查 session 中当前正在处理的写请求，如果为 null，则再去检查 session 中的写请求，如果都为空则退出
                    req = session.getCurrentWriteRequest();

                    if (req == null) {
                        req = writeRequestQueue.poll(session);

                        if (req == null) {
                            break;
                        }

                        session.setCurrentWriteRequest(req);
                    }

                    int localWrittenBytes;
                    Object message = req.getMessage();

                    if (message instanceof IoBuffer) {
                        // 将 buffer 中的数据真正发送出去，并返回实际发送的字节数
                        // 如果字节数据全部发送完毕，就把 currentWriteRequest 设置为 null
                        localWrittenBytes = writeBuffer(session, req, hasFragmentation, maxWrittenBytes - writtenBytes, currentTime);
                        // 如果说，buffer 中仍然有部分数据没有发送，那么就设置此 session 再次监听 OP_WRITE 事件，然后在 Processor 中再次
                        // 处理 OP_WRITE 事件时，就会将当前 session 添加到 flushingSession 中，然后 flush 方法会再次获取到 session 中
                        // currentWriteRequest 相同的 buffer，再次进行写入 channel 中
                        if ((localWrittenBytes > 0) && ((IoBuffer) message).hasRemaining()) {
                            // the buffer isn't empty, we re-interest it in writing
                            setInterestedInWrite(session, true);
                            return false;
                        }
                    } else if (message instanceof FileRegion) {
                        localWrittenBytes = writeFile(session, req, hasFragmentation, maxWrittenBytes - writtenBytes, currentTime);
                        // Fix for Java bug on Linux
                        // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5103988
                        // If there's still data to be written in the FileRegion,
                        // return 0 indicating that we need
                        // to pause until writing may resume.
                        if ((localWrittenBytes > 0) && (((FileRegion) message).getRemainingBytes() > 0)) {
                            setInterestedInWrite(session, true);
                            return false;
                        }
                    } else {
                        throw new IllegalStateException("Don't know how to handle message of type '"
                                + message.getClass().getName() + "'.  Are you missing a protocol encoder?");
                    }

                    if (localWrittenBytes == 0) {
                        // Kernel buffer is full.
                        // 当 buffer 写入到 channel 中的数据为 0 时，说明底层的 socket 缓冲区已经被写满，等待 Processor I/O 线程下次调度
                        // 继续执行 flushNow 方法往里面写入
                        if (!req.equals(AbstractIoSession.MESSAGE_SENT_REQUEST)) {
                            setInterestedInWrite(session, true);
                            return false;
                        }
                    } else {
                        writtenBytes += localWrittenBytes;
                        // 如果写入 channel 的数据太多（不过没有在上面几个 if 中返回，说明每一个 buffer 中全部发送出去），
                        // 因此将 session 再次添加到 flushingSessions 中，立刻就可以对 session 中的数据再次处理
                        if (writtenBytes >= maxWrittenBytes) {
                            // Wrote too much
                            scheduleFlush(session);
                            return false;
                        }
                    }

                    if (message instanceof IoBuffer) {
                        ((IoBuffer) message).free();
                    }
                } while (writtenBytes < maxWrittenBytes);
            } catch (Exception e) {
                if (req != null) {
                    req.getFuture().setException(e);
                }

                IoFilterChain filterChain = session.getFilterChain();
                filterChain.fireExceptionCaught(e);
                return false;
            }

            return true;
        }

        private void scheduleFlush(S session) {
            // add the session to the queue if it's not already in the queue
            if (session.setScheduledForFlush(true)) {
                flushingSessions.add(session);
            }
        }

        private int writeFile(S session, WriteRequest req, boolean hasFragmentation, int maxLength, long currentTime)
                throws Exception {
            int localWrittenBytes;
            FileRegion region = (FileRegion) req.getMessage();

            if (region.getRemainingBytes() > 0) {
                int length;

                if (hasFragmentation) {
                    length = (int) Math.min(region.getRemainingBytes(), maxLength);
                } else {
                    length = (int) Math.min(Integer.MAX_VALUE, region.getRemainingBytes());
                }

                localWrittenBytes = transferFile(session, region, length);
                region.update(localWrittenBytes);
            } else {
                localWrittenBytes = 0;
            }

            session.increaseWrittenBytes(localWrittenBytes, currentTime);

            if ((region.getRemainingBytes() <= 0) || (!hasFragmentation && (localWrittenBytes != 0))) {
                fireMessageSent(session, req);
            }

            return localWrittenBytes;
        }

        private int writeBuffer(S session, WriteRequest req, boolean hasFragmentation, int maxLength, long currentTime) throws Exception {
            IoBuffer buf = (IoBuffer) req.getMessage();
            int localWrittenBytes = 0;

            if (buf.hasRemaining()) {
                int length;

                // hasFragmentation 为 true 的话，表明发送数据的过程中，不是一次就发送一整个报文，而是一次可能会发送一部分报文信息
                if (hasFragmentation) {
                    // 写入到 channel 中的字节数不能超过 maxLength
                    length = Math.min(buf.remaining(), maxLength);
                } else {
                    length = buf.remaining();
                }

                try {
                    // 使用 java nio 原生 API 将 buf 中的字节数据写入到 session 中
                    localWrittenBytes = write(session, buf, length);
                } catch (IOException ioe) {
                    // We have had an issue while trying to send data to the peer : let's close the session.
                    buf.free();
                    session.closeNow();
                    this.removeNow(session);

                    return 0;
                }

                session.increaseWrittenBytes(localWrittenBytes, currentTime);

                // Now, forward the original message if it has been fully sent
                // 如果将 buffer 中的数据字节全部发送完毕，就 fire messageSent 事件，并且将 session 的 currentWriteRequest 设置为 null，
                // 表示当前写请求处理完毕
                if (!buf.hasRemaining() || (!hasFragmentation && (localWrittenBytes != 0))) {
                    this.fireMessageSent(session, req);
                }
            } else {
                this.fireMessageSent(session, req);
            }

            // 返回实际发送的字节数
            return localWrittenBytes;
        }

        private boolean removeNow(S session) {
            // 清理 NioSession 中的 WriterRequest，也就是为每一个写请求创建一个异常 WriteToClosedSessionException，
            // 然后将此异常设置到 future 中，唤醒可能阻塞在 future#await 方法上的线程，最后在此 session 上的 filterChain 中
            // fire exceptionCaught 事件
            clearWriteRequestQueue(session);

            try {
                // 从 NioSession 中获得 java nio 原生的 channel 和 key，
                // 然后分别调用 channel#close 和 key#cancel 方法，关闭连接以及取消注册
                // 另外，关闭连接的本质就是取消 channel 在 selector 上的注册
                destroy(session);
                return true;
            } catch (Exception e) {
                // 如果在销毁 session 的过程中出现了异常，就 fire ExceptionCaught 事件
                IoFilterChain filterChain = session.getFilterChain();
                filterChain.fireExceptionCaught(e);
            } finally {
                try {
                    // 在销毁完 session 之后，就往 session 中所有的 listeners 发送 SessionDestroyed 事件
                    // 同时往 session 中的 filterChain fire sessionClosed 事件
                    ((AbstractIoService) session.getService()).getListeners().fireSessionDestroyed(session);
                } catch (Exception e) {
                    // The session was either destroyed or not at this point.
                    // We do not want any exception thrown from this "cleanup" code
                    // to change
                    // the return value by bubbling up.
                    IoFilterChain filterChain = session.getFilterChain();
                    filterChain.fireExceptionCaught(e);
                } finally {
                    clearWriteRequestQueue(session);
                }
            }

            return false;
        }

        /**
         * 清理 session 连接中的所有 WriteRequest 写请求，也就是给每个写请求创建一个 WriteToClosedSessionException 异常，
         * 然后将此异常设置到 WriteFuture 对象中。因为每调用一次 session#write 方法，都会返回一个 WriteFuture 对象，然后
         * 有可能有线程调用 future#await 阻塞在此线程上，因此在将异常设置到 WriteFuture 对象之后，await 方法就能返回。
         * 最后在此 session 的 filterChain 中 fire exceptionCaught 事件。
         * @param session
         */
        private void clearWriteRequestQueue(S session) {
            WriteRequestQueue writeRequestQueue = session.getWriteRequestQueue();
            WriteRequest req;

            List<WriteRequest> failedRequests = new ArrayList<>();

            if ((req = writeRequestQueue.poll(session)) != null) {
                Object message = req.getMessage();

                if (message instanceof IoBuffer) {
                    IoBuffer buf = (IoBuffer) message;

                    // The first unwritten empty buffer must be forwarded to the filter chain.
                    if (buf.hasRemaining()) {
                        failedRequests.add(req);
                    } else {
                        IoFilterChain filterChain = session.getFilterChain();
                        filterChain.fireMessageSent(req);
                    }
                } else {
                    failedRequests.add(req);
                }

                // Discard others.
                while ((req = writeRequestQueue.poll(session)) != null) {
                    failedRequests.add(req);
                }
            }

            // Create an exception and notify.
            // 为每一个 WriteRequest 写请求创建一个异常 WriteToClosedSessionException，然后将异常设置到
            // WriteFuture 中，notify 阻塞在此 future 上的线程
            if (!failedRequests.isEmpty()) {
                WriteToClosedSessionException cause = new WriteToClosedSessionException(failedRequests);

                for (WriteRequest r : failedRequests) {
                    session.decreaseScheduledBytesAndMessages(r);
                    r.getFuture().setException(cause);
                }

                IoFilterChain filterChain = session.getFilterChain();
                filterChain.fireExceptionCaught(cause);
            }
        }

        private void fireMessageSent(S session, WriteRequest req) {
            // 将 session 中的当前写请求设置为 null，表明当前写请求处理完毕
            session.setCurrentWriteRequest(null);
            IoFilterChain filterChain = session.getFilterChain();
            filterChain.fireMessageSent(req);
        }
        
        private void process() throws Exception {
            for (Iterator<S> i = selectedSessions(); i.hasNext();) {
                S session = i.next();
                process(session);
                i.remove();
            }
        }

        /**
         * Deal with session ready for the read or write operations, or both.
         */
        private void process(S session) {
            // Process Reads
            // 如果 SelectionKey 上的 session 没有被取消注册，并且监听了 OP_READ 事件
            if (isReadable(session) && !session.isReadSuspended()) {
                // 从 session 中读取数据，
                read(session);
            }

            // Process writes
            // 如果 SelectionKey 上的 session 没有被取消注册，并且监听了 OP_WRITE 事件
            // 如果调用 IoSession 中的 writeSuspended 方法，就会最终调用 updateTrafficControl 方法取消监听 OP_WRITE 事件
            if (isWritable(session) && !session.isWriteSuspended() && session.setScheduledForFlush(true)) {
                // add the session to the queue, if it's not already there
                // 将需要写入数据的 session 添加到 flushingSessions 中
                flushingSessions.add(session);
            }
        }
    }
}
