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

import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultConnectFuture;
import org.apache.mina.core.service.AbstractIoConnector;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionConfig;
import org.apache.mina.core.session.IoSessionInitializer;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A base class for implementing client transport using a polling strategy. The
 * underlying sockets will be checked in an active loop and woke up when an
 * socket needed to be processed. This class handle the logic behind binding,
 * connecting and disposing the client sockets. A {@link Executor} will be used
 * for running client connection, and an {@link AbstractPollingIoProcessor} will
 * be used for processing connected client I/O operations like reading, writing
 * and closing.
 * 
 * All the low level methods for binding, connecting, closing need to be
 * provided by the subclassing implementation.
 * 
 * @see NioSocketConnector for a example of implementation
 * @param <H> The type of IoHandler
 * @param <S> The type of IoSession
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractPollingIoConnector<S extends AbstractIoSession, H> extends AbstractIoConnector {

    private final Queue<ConnectionRequest> connectQueue = new ConcurrentLinkedQueue<>();

    private final Queue<ConnectionRequest> cancelQueue = new ConcurrentLinkedQueue<>();

    private final IoProcessor<S> processor;

    private final boolean createdProcessor;

    private final ServiceOperationFuture disposalFuture = new ServiceOperationFuture();

    private volatile boolean selectable;

    /** The connector thread */
    private final AtomicReference<Connector> connectorRef = new AtomicReference<>();

    /**
     * Constructor for {@link AbstractPollingIoConnector}. You need to provide a
     * default session configuration, a class of {@link IoProcessor} which will
     * be instantiated in a {@link SimpleIoProcessorPool} for better scaling in
     * multiprocessor systems. The default pool size will be used.
     * 
     * @see SimpleIoProcessorPool
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processorClass
     *            a {@link Class} of {@link IoProcessor} for the associated
     *            {@link IoSession} type.
     */
    protected AbstractPollingIoConnector(IoSessionConfig sessionConfig, Class<? extends IoProcessor<S>> processorClass) {
        this(sessionConfig, null, new SimpleIoProcessorPool<S>(processorClass), true);
    }

    /**
     * Constructor for {@link AbstractPollingIoConnector}. You need to provide a
     * default session configuration, a class of {@link IoProcessor} which will
     * be instantiated in a {@link SimpleIoProcessorPool} for using multiple
     * thread for better scaling in multiprocessor systems.
     * 
     * @see SimpleIoProcessorPool
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processorClass
     *            a {@link Class} of {@link IoProcessor} for the associated
     *            {@link IoSession} type.
     * @param processorCount
     *            the amount of processor to instantiate for the pool
     */
    protected AbstractPollingIoConnector(IoSessionConfig sessionConfig, Class<? extends IoProcessor<S>> processorClass,
            int processorCount) {
        this(sessionConfig, null, new SimpleIoProcessorPool<S>(processorClass, processorCount), true);
    }

    /**
     * Constructor for {@link AbstractPollingIoConnector}. You need to provide a
     * default session configuration, a default {@link Executor} will be created
     * using {@link Executors#newCachedThreadPool()}.
     * 
     * @see AbstractIoService#AbstractIoService(IoSessionConfig, Executor)
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processor
     *            the {@link IoProcessor} for processing the {@link IoSession}
     *            of this transport, triggering events to the bound
     *            {@link IoHandler} and processing the chains of
     *            {@link IoFilter}
     */
    protected AbstractPollingIoConnector(IoSessionConfig sessionConfig, IoProcessor<S> processor) {
        this(sessionConfig, null, processor, false);
    }

    /**
     * Constructor for {@link AbstractPollingIoConnector}. You need to provide a
     * default session configuration and an {@link Executor} for handling I/O
     * events. If null {@link Executor} is provided, a default one will be
     * created using {@link Executors#newCachedThreadPool()}.
     * 
     * @see AbstractIoService#AbstractIoService(IoSessionConfig, Executor)
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param executor
     *            the {@link Executor} used for handling asynchronous execution
     *            of I/O events. Can be <code>null</code>.
     * @param processor
     *            the {@link IoProcessor} for processing the {@link IoSession}
     *            of this transport, triggering events to the bound
     *            {@link IoHandler} and processing the chains of
     *            {@link IoFilter}
     */
    protected AbstractPollingIoConnector(IoSessionConfig sessionConfig, Executor executor, IoProcessor<S> processor) {
        this(sessionConfig, executor, processor, false);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a
     * default session configuration and an {@link Executor} for handling I/O
     * events. If null {@link Executor} is provided, a default one will be
     * created using {@link Executors#newCachedThreadPool()}.
     * 
     * @see AbstractIoService#AbstractIoService(IoSessionConfig, Executor)
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param executor
     *            the {@link Executor} used for handling asynchronous execution
     *            of I/O events. Can be <code>null</code>.
     * @param processor
     *            the {@link IoProcessor} for processing the {@link IoSession}
     *            of this transport, triggering events to the bound
     *            {@link IoHandler} and processing the chains of
     *            {@link IoFilter}
     * @param createdProcessor
     *            tagging the processor as automatically created, so it will be
     *            automatically disposed
     */
    private AbstractPollingIoConnector(IoSessionConfig sessionConfig, Executor executor, IoProcessor<S> processor,
            boolean createdProcessor) {
        super(sessionConfig, executor);

        if (processor == null) {
            throw new IllegalArgumentException("processor");
        }

        // processor 为 SimpleIoProcessorPool 类对象，包含了 processor 的集合
        this.processor = processor;
        this.createdProcessor = createdProcessor;

        try {
            // 开启 NioSocketConnector 上的 selector，并设置 selectable 为 true，表示可以接受 OP_CONNECT 事件
            init();
            selectable = true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeIoException("Failed to initialize.", e);
        } finally {
            // 如果无法正常开启 selector，那么就直接关闭掉 selector
            if (!selectable) {
                try {
                    destroy();
                } catch (Exception e) {
                    ExceptionMonitor.getInstance().exceptionCaught(e);
                }
            }
        }
    }

    /**
     * Initialize the polling system, will be called at construction time.
     * 
     * @throws Exception
     *             any exception thrown by the underlying system calls
     */
    protected abstract void init() throws Exception;

    /**
     * Destroy the polling system, will be called when this {@link IoConnector}
     * implementation will be disposed.
     * 
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract void destroy() throws Exception;

    /**
     * Create a new client socket handle from a local {@link SocketAddress}
     * 
     * @param localAddress
     *            the socket address for binding the new client socket
     * @return a new client socket handle
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract H newHandle(SocketAddress localAddress) throws Exception;

    /**
     * Connect a newly created client socket handle to a remote
     * {@link SocketAddress}. This operation is non-blocking, so at end of the
     * call the socket can be still in connection process.
     * 
     * @param handle the client socket handle
     * @param remoteAddress the remote address where to connect
     * @return <tt>true</tt> if a connection was established, <tt>false</tt> if
     *         this client socket is in non-blocking mode and the connection
     *         operation is in progress
     * @throws Exception If the connect failed
     */
    protected abstract boolean connect(H handle, SocketAddress remoteAddress) throws Exception;

    /**
     * Finish the connection process of a client socket after it was marked as
     * ready to process by the {@link #select(int)} call. The socket will be
     * connected or reported as connection failed.
     * 
     * @param handle
     *            the client socket handle to finish to connect
     * @return true if the socket is connected
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract boolean finishConnect(H handle) throws Exception;

    /**
     * Create a new {@link IoSession} from a connected socket client handle.
     * Will assign the created {@link IoSession} to the given
     * {@link IoProcessor} for managing future I/O events.
     * 
     * @param processor
     *            the processor in charge of this session
     * @param handle
     *            the newly connected client socket handle
     * @return a new {@link IoSession}
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract S newSession(IoProcessor<S> processor, H handle) throws Exception;

    /**
     * Close a client socket.
     * 
     * @param handle
     *            the client socket
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract void close(H handle) throws Exception;

    /**
     * Interrupt the {@link #select(int)} method. Used when the poll set need to
     * be modified.
     */
    protected abstract void wakeup();

    /**
     * Check for connected sockets, interrupt when at least a connection is
     * processed (connected or failed to connect). All the client socket
     * descriptors processed need to be returned by {@link #selectedHandles()}
     * 
     * @param timeout The timeout for the select() method
     * @return The number of socket having received some data
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract int select(int timeout) throws Exception;

    /**
     * {@link Iterator} for the set of client sockets found connected or failed
     * to connect during the last {@link #select(int)} call.
     * 
     * @return the list of client socket handles to process
     */
    protected abstract Iterator<H> selectedHandles();

    /**
     * {@link Iterator} for all the client sockets polled for connection.
     * 
     * @return the list of client sockets currently polled for connection
     */
    protected abstract Iterator<H> allHandles();

    /**
     * Register a new client socket for connection, add it to connection polling
     * 
     * @param handle
     *            client socket handle
     * @param request
     *            the associated {@link ConnectionRequest}
     * @throws Exception
     *             any exception thrown by the underlying systems calls
     */
    protected abstract void register(H handle, ConnectionRequest request) throws Exception;

    /**
     * get the {@link ConnectionRequest} for a given client socket handle
     * 
     * @param handle
     *            the socket client handle
     * @return the connection request if the socket is connecting otherwise
     *         <code>null</code>
     */
    protected abstract ConnectionRequest getConnectionRequest(H handle);

    /**
     * {@inheritDoc}
     */
    @Override
    protected final void dispose0() throws Exception {
        startupWorker();
        wakeup();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    protected final ConnectFuture connect0(SocketAddress remoteAddress, SocketAddress localAddress,
            IoSessionInitializer<? extends ConnectFuture> sessionInitializer) {
        H handle = null;
        boolean success = false;
        try {
            // open SocketChannel 并且设定 READ BUFFER 参数，同时如果 localAddress 不为 null，那么
            // SocketChannel 也会同时绑定监听 localAddress 地址
            handle = newHandle(localAddress);
            // 尝试将 SocketChannel 连接到远程主机地址，如果返回 true，连接成功的话，直接创建一个 NioSocketSession，
            // 同时为这 session 设置 AttributeMap 和 WriteRequestQueue。最后给 session 分配 Processor，并且添加到
            // newSessions 上，然后开启 Processor 来处理 newSessions 新 session 的读写事件
            if (connect(handle, remoteAddress)) {
                ConnectFuture future = new DefaultConnectFuture();
                S session = newSession(processor, handle);
                // 初始化 session 上的 AttributeMap 和 WriteRequestQueue，并且在 future 上添加一个 listener，因为用户
                // 有可能在 session 被 Processor 处理前提前 cancel 掉 ConnectFuture，因此注册了 listener 之后可以及时关闭掉 session
                initSession(session, future, sessionInitializer);
                // Forward the remaining process to the IoProcessor.
                // 将 session 添加到 newSessions 中等待 Processor 处理
                session.getProcessor().add(session);
                success = true;
                return future;
            }

            success = true;
        } catch (Exception e) {
            return DefaultConnectFuture.newFailedFuture(e);
        } finally {
            if (!success && handle != null) {
                try {
                    close(handle);
                } catch (Exception e) {
                    ExceptionMonitor.getInstance().exceptionCaught(e);
                }
            }
        }

        // 如果上面没有连接成功，那么创建连接建立请求，并且将其保存到 connectQueue，开启并唤醒 connector 来进行处理
        ConnectionRequest request = new ConnectionRequest(handle, sessionInitializer);
        connectQueue.add(request);
        // 开启 connector
        startupWorker();
        wakeup();

        return request;
    }

    private void startupWorker() {
        if (!selectable) {
            connectQueue.clear();
            cancelQueue.clear();
        }

        Connector connector = connectorRef.get();

        if (connector == null) {
            connector = new Connector();

            if (connectorRef.compareAndSet(null, connector)) {
                executeWorker(connector);
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private class Connector implements Runnable {
        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            assert connectorRef.get() == this;

            int nHandles = 0;

            while (selectable) {
                try {
                    // the timeout for select shall be smaller of the connect timeout or 1 second...
                    int timeout = (int) Math.min(getConnectTimeoutMillis(), 1000L);
                    // 在 selector 上阻塞 timeout 时间段
                    int selected = select(timeout);

                    // 从 connectQueue 中获取到要和服务端建立连接的请求，然后将请求中的 SocketChannel，并且注册到
                    // selector 上监听 OP_CONNECT 事件。registerNew 返回方法中注册了多少个 channel
                    // nHandles 表示一共在 selector 上注册了多少个 channel
                    nHandles += registerNew();

                    // get a chance to get out of the connector loop, if we don't have any more handles
                    // nHandles 为 0，表示当前 selector 上没有注册任何 channel
                    if (nHandles == 0) {
                        connectorRef.set(null);
                        // 如果 connectQueue 也为 0 的话，说明客户端没有和远程主机建立新连接的请求，退出 loop
                        if (connectQueue.isEmpty()) {
                            assert connectorRef.get() != this;
                            break;
                        }

                        if (!connectorRef.compareAndSet(null, this)) {
                            assert connectorRef.get() != this;
                            break;
                        }

                        assert connectorRef.get() == this;
                    }

                    // selected > 0 说明 selector 上有连接建立成功，进行处理
                    if (selected > 0) {
                        // 处理 channel 上的 OP_CONNECT 事件，创建并初始化 session，并且为它分配一个 Processor，将其加入到
                        // Processor 的 newSessions 队列中。由于 OP_CONNECT 只需要处理一次，因此将 channel 从 selector 取消注册
                        // 另外，selector.selectedKeys 直接返回的是其内部 publicSelectedKeys 类集合对象，可以从中移除元素，但是不能添加，
                        // selector.keys 返回的是注册在当前选择器上的通道的 SelectionKey 对象，此集合不可改变（unmodifiableSet）
                        // 并且将 key 从 selectedKeys 中移除后，keys 集合不受影响
                        nHandles -= processConnections(selectedHandles());
                    }

                    // 将超时 channel 对应的 future 添加到 cancelQueue 中
                    processTimedOutSessions(allHandles());

                    // 遍历 cancelQueue，将对应的 key cancel 掉，同时关闭 channel
                    nHandles -= cancelKeys();
                } catch (ClosedSelectorException cse) {
                    // If the selector has been closed, we can exit the loop
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

            if (selectable && isDisposing()) {
                selectable = false;
                try {
                    // 如果是 mina 自己创建 processor，那么进行关闭释放
                    // 这里 processor 是 SimpleIoProcessorPool
                    if (createdProcessor) {
                        processor.dispose();
                    }
                } finally {
                    try {
                        synchronized (disposalLock) {
                            if (isDisposing()) {
                                destroy();
                            }
                        }
                    } catch (Exception e) {
                        ExceptionMonitor.getInstance().exceptionCaught(e);
                    } finally {
                        disposalFuture.setDone();
                    }
                }
            }
        }

        private int registerNew() {
            int nHandles = 0;
            for (;;) {
                ConnectionRequest req = connectQueue.poll();
                if (req == null) {
                    break;
                }

                // handle 是 SocketChannel 类型的对象
                H handle = req.handle;
                try {
                    // 将 handle 注册到 selector 上，监听 OP_CONNECT 事件，OP_CONNECT 表示客户端与服务器端建立连接完毕，
                    // 由于 OP_CONNECT 连接事件是只需要处理一次的事件，一旦连接建立完成，就可以进行读、写操作了。所以之后在 run
                    // 方法中会调用 cancelKeys 方法取消注册
                    register(handle, req);
                    nHandles++;
                } catch (Exception e) {
                    req.setException(e);
                    try {
                        close(handle);
                    } catch (Exception e2) {
                        ExceptionMonitor.getInstance().exceptionCaught(e2);
                    }
                }
            }
            return nHandles;
        }

        private int cancelKeys() {
            int nHandles = 0;

            for (;;) {
                ConnectionRequest req = cancelQueue.poll();

                if (req == null) {
                    break;
                }

                H handle = req.handle;

                try {
                    // key.cancel 取消注册，同时将 channel 关闭
                    close(handle);
                } catch (Exception e) {
                    ExceptionMonitor.getInstance().exceptionCaught(e);
                } finally {
                    nHandles++;
                }
            }

            if (nHandles > 0) {
                wakeup();
            }

            return nHandles;
        }

        /**
         * Process the incoming connections, creating a new session for each valid
         * connection.
         */
        private int processConnections(Iterator<H> handlers) {
            int nHandles = 0;

            // Loop on each connection request
            while (handlers.hasNext()) {
                // handlers 是在 selector 上产生了 OP_CONNECT 事件的 SocketChannel 的 SelectionKey 集合
                // 不过 next() 方法直接返回 SocketChannel
                H handle = handlers.next();
                // selector.selectedKeys 直接返回的是其内部 publicSelectedKeys 类对象，然后把当前 channel 的 key
                // 从集合中移除，防止重复添加。但是移除了 selectedKeys 中的 SelectionKey 不代表移除了 selector 中的
                // channel 信息(这点很重要)
                handlers.remove();

                // 返回这个 SocketChannel 对应的 ConnectionRequest
                ConnectionRequest connectionRequest = getConnectionRequest(handle);

                if (connectionRequest == null) {
                    continue;
                }

                boolean success = false;
                try {
                    // connect 的 api 文档是这么解释的：If this channel is in non-blocking mode then an invocation of this
                    // method initiates a non-blocking connection operation. If the connection is established immediately,
                    // as can happen with a local connection, then this method returns true. Otherwise this method returns
                    // false and the connection operation must later be completed by invoking the finishConnect method.
                    //
                    // 一个 channel 在非阻塞模式下执行 connect 后，如果连接能马上建立好则返回 true，否则完成 false。如果返回 false，那么
                    // 只能通过之后调用 finishConnect 来判断连接是否完成。在 select 循环里，先检测 SelectionKey 是否 isConnectable 为 true，
                    // 如果是则进入分支，再执行 SocketChannel.finishConnect。若连接成功，finishConnect 返回真；若连接失败，则抛出异常。
                    //
                    // 在之前，客户端如果能直接 connect 到远程主机地址，那么就直接返回；否则就会创建 ConnectRequest，保存到 connectQueue
                    // 中由 connector 线程来进行处理。注意当 selector 检测到 channel 建立好连接，或者连接出现异常时，都会出现 OP_CONNECT
                    // 事件，所以这里要通过 finishConnect 判断是否完成连接，具体的文档如下：
                    //
                    // Suppose that a selection key's interest set contains OP_CONNECT at the start of a selection operation.
                    // If the selector detects that the corresponding socket channel is ready to complete its connection sequence,
                    // or has an error pending, then it will add OP_CONNECT to the key's ready set and add the key to its selected-key set.
                    //
                    // 由于 OP_CONNECT 是只需要处理一次的事件，在 channel 建立好连接之后，就可以进行读写，因此 finishConnect 会把 channel 从
                    // selector 上取消注册
                    if (finishConnect(handle)) {
                        S session = newSession(processor, handle);
                        // 为 session 初始化 AttributeMap 和 WriteRequestQueue，并且注册 listener
                        initSession(session, connectionRequest, connectionRequest.getSessionInitializer());
                        // Forward the remaining process to the IoProcessor.
                        session.getProcessor().add(session);
                        nHandles++;
                    }
                    success = true;
                } catch (Exception e) {
                    connectionRequest.setException(e);
                } finally {
                    if (!success) {
                        // The connection failed, we have to cancel it.
                        cancelQueue.offer(connectionRequest);
                    }
                }
            }
            return nHandles;
        }

        private void processTimedOutSessions(Iterator<H> handles) {
            long currentTime = System.currentTimeMillis();

            while (handles.hasNext()) {
                H handle = handles.next();
                ConnectionRequest connectionRequest = getConnectionRequest(handle);

                // currentTime >= connectionRequest.deadline，说明在指定的 timeout 时间内 channel 还没有连接完毕，
                // 则需要将其取消注册
                if ((connectionRequest != null) && (currentTime >= connectionRequest.deadline)) {
                    connectionRequest.setException(new ConnectException("Connection timed out."));
                    cancelQueue.offer(connectionRequest);
                }
            }
        }
    }

    /**
     * A ConnectionRequest's Iouture 
     */
    public final class ConnectionRequest extends DefaultConnectFuture {
        /** The handle associated with this connection request */
        private final H handle;

        /** The time up to this connection request will be valid */
        private final long deadline;

        /** The callback to call when the session is initialized */
        private final IoSessionInitializer<? extends ConnectFuture> sessionInitializer;

        /**
         * Creates a new ConnectionRequest instance
         * 
         * @param handle The IoHander
         * @param callback The IoFuture callback
         */
        public ConnectionRequest(H handle, IoSessionInitializer<? extends ConnectFuture> callback) {
            this.handle = handle;
            long timeout = getConnectTimeoutMillis();

            if (timeout <= 0L) {
                this.deadline = Long.MAX_VALUE;
            } else {
                this.deadline = System.currentTimeMillis() + timeout;
            }

            this.sessionInitializer = callback;
        }

        /**
         * @return The IoHandler instance
         */
        public H getHandle() {
            return handle;
        }

        /**
         * @return The connection deadline 
         */
        public long getDeadline() {
            return deadline;
        }

        /**
         * @return The session initializer callback
         */
        public IoSessionInitializer<? extends ConnectFuture> getSessionInitializer() {
            return sessionInitializer;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean cancel() {
            if (!isDone()) {
                boolean justCancelled = super.cancel();

                // We haven't cancelled the request before, so add the future
                // in the cancel queue.
                if (justCancelled) {
                    cancelQueue.add(this);
                    startupWorker();
                    wakeup();
                }
            }

            return true;
        }
    }
}
