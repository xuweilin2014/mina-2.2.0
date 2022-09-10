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

import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.service.AbstractIoAcceptor;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionConfig;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A base class for implementing transport using a polling strategy. The
 * underlying sockets will be checked in an active loop and woke up when an
 * socket needed to be processed. This class handle the logic behind binding,
 * accepting and disposing the server sockets. An {@link Executor} will be used
 * for running client accepting and an {@link AbstractPollingIoProcessor} will
 * be used for processing client I/O operations like reading, writing and
 * closing.
 * 
 * All the low level methods for binding, accepting, closing need to be provided
 * by the subclassing implementation.
 * 
 * @see NioSocketAcceptor for a example of implementation
 * @param <H> The type of IoHandler
 * @param <S> The type of IoSession
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
@SuppressWarnings("ALL")
public abstract class AbstractPollingIoAcceptor<S extends AbstractIoSession, H> extends AbstractIoAcceptor {
    /** A lock used to protect the selector to be waked up before it's created */
    private final Semaphore lock = new Semaphore(1);

    private final IoProcessor<S> processor;

    private final boolean createdProcessor;

    private final Queue<AcceptorOperationFuture> registerQueue = new ConcurrentLinkedQueue<>();

    private final Queue<AcceptorOperationFuture> cancelQueue = new ConcurrentLinkedQueue<>();

    private final Map<SocketAddress, H> boundHandles = Collections.synchronizedMap(new HashMap<SocketAddress, H>());

    private final ServiceOperationFuture disposalFuture = new ServiceOperationFuture();

    /** A flag set when the acceptor has been created and initialized */
    private volatile boolean selectable;

    /** The thread responsible of accepting incoming requests */
    private AtomicReference<Acceptor> acceptorRef = new AtomicReference<>();

    protected boolean reuseAddress = false;

    /**
     * Define the number of socket that can wait to be accepted. Default
     * to 50 (as in the SocketServer default).
     */
    protected int backlog = 50;

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
     * session configuration, a class of {@link IoProcessor} which will be instantiated in a
     * {@link SimpleIoProcessorPool} for better scaling in multiprocessor systems. The default
     * pool size will be used.
     * 
     * @see SimpleIoProcessorPool
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processorClass a {@link Class} of {@link IoProcessor} for the associated {@link IoSession}
     *            type.
     */
    protected AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, Class<? extends IoProcessor<S>> processorClass) {
        this(sessionConfig, null, new SimpleIoProcessorPool<S>(processorClass), true, null);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
     * session configuration, a class of {@link IoProcessor} which will be instantiated in a
     * {@link SimpleIoProcessorPool} for using multiple thread for better scaling in multiprocessor
     * systems.
     * 
     * @see SimpleIoProcessorPool
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processorClass a {@link Class} of {@link IoProcessor} for the associated {@link IoSession}
     *            type.
     * @param processorCount the amount of processor to instantiate for the pool
     */
    protected AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, Class<? extends IoProcessor<S>> processorClass,
            int processorCount) {
        this(sessionConfig, null, new SimpleIoProcessorPool<S>(processorClass, processorCount), true, null);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
     * session configuration, a class of {@link IoProcessor} which will be instantiated in a
     * {@link SimpleIoProcessorPool} for using multiple thread for better scaling in multiprocessor
     * systems.
     *
     * @see SimpleIoProcessorPool
     *
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processorClass a {@link Class} of {@link IoProcessor} for the associated {@link IoSession}
     *            type.
     * @param processorCount the amount of processor to instantiate for the pool
     * @param selectorProvider The SelectorProvider to use
     */
    protected AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, Class<? extends IoProcessor<S>> processorClass,
            int processorCount, SelectorProvider selectorProvider ) {
        this(sessionConfig, null, new SimpleIoProcessorPool<S>(processorClass, processorCount, selectorProvider), true, selectorProvider);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
     * session configuration, a default {@link Executor} will be created using
     * {@link Executors#newCachedThreadPool()}.
     * 
     * @see AbstractIoService
     * 
     * @param sessionConfig
     *            the default configuration for the managed {@link IoSession}
     * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport, triggering
     *            events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
     */
    protected AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, IoProcessor<S> processor) {
        this(sessionConfig, null, processor, false, null);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a
     * default session configuration and an {@link Executor} for handling I/O
     * events. If a null {@link Executor} is provided, a default one will be
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
    protected AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, Executor executor, IoProcessor<S> processor) {
        this(sessionConfig, executor, processor, false, null);
    }

    /**
     * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a
     * default session configuration and an {@link Executor} for handling I/O
     * events. If a null {@link Executor} is provided, a default one will be
     * created using {@link Executors#newCachedThreadPool()}.
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
    private AbstractPollingIoAcceptor(IoSessionConfig sessionConfig, Executor executor, IoProcessor<S> processor,
            boolean createdProcessor, SelectorProvider selectorProvider) {
        super(sessionConfig, executor);

        if (processor == null) {
            throw new IllegalArgumentException("processor");
        }

        this.processor = processor;
        this.createdProcessor = createdProcessor;

        try {
            // Initialize the selector
            // 在 NioSocketAcceptor 中，创建一个 selector
            init(selectorProvider);

            // The selector is now ready, we can switch the flag to true so that incoming connection can be accepted
            selectable = true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeIoException("Failed to initialize.", e);
        } finally {
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
     * @throws Exception any exception thrown by the underlying system calls
     */
    protected abstract void init() throws Exception;

    /**
     * Initialize the polling system, will be called at construction time.
     * 
     * @param selectorProvider The Selector Provider that will be used by this polling acceptor
     * @throws Exception any exception thrown by the underlying system calls
     */
    protected abstract void init(SelectorProvider selectorProvider) throws Exception;

    /**
     * Destroy the polling system, will be called when this {@link IoAcceptor}
     * implementation will be disposed.
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract void destroy() throws Exception;

    /**
     * Check for acceptable connections, interrupt when at least a server is ready for accepting.
     * All the ready server socket descriptors need to be returned by {@link #selectedHandles()}
     * @return The number of sockets having got incoming client
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract int select() throws Exception;

    /**
     * Interrupt the {@link #select()} method. Used when the poll set need to be modified.
     */
    protected abstract void wakeup();

    /**
     * {@link Iterator} for the set of server sockets found with acceptable incoming connections
     *  during the last {@link #select()} call.
     * @return the list of server handles ready
     */
    protected abstract Iterator<H> selectedHandles();

    /**
     * Open a server socket for a given local address.
     * @param localAddress the associated local address
     * @return the opened server socket
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract H open(SocketAddress localAddress) throws Exception;

    /**
     * Get the local address associated with a given server socket
     * @param handle the server socket
     * @return the local {@link SocketAddress} associated with this handle
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract SocketAddress localAddress(H handle) throws Exception;

    /**
     * Accept a client connection for a server socket and return a new {@link IoSession}
     * associated with the given {@link IoProcessor}
     * @param processor the {@link IoProcessor} to associate with the {@link IoSession}
     * @param handle the server handle
     * @return the created {@link IoSession}
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract S accept(IoProcessor<S> processor, H handle) throws Exception;

    /**
     * Close a server socket.
     * @param handle the server socket
     * @throws Exception any exception thrown by the underlying systems calls
     */
    protected abstract void close(H handle) throws Exception;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispose0() throws Exception {
        unbind();

        startupAcceptor();
        wakeup();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final Set<SocketAddress> bindInternal(List<? extends SocketAddress> localAddresses) throws Exception {
        // Create a bind request as a Future operation. When the selector
        // have handled the registration, it will signal this future.
        // 创建一个 AcceptorOperationFuture 对象，并且将其保存到 registerQueue 中，随后启动 acceptor，
        // 由于 acceptor 实现了 runnable 接口，因此所谓启动，就是将其提交到线程池 executor 中，由线程池中
        // 的某一个线程来具体执行 acceptor 中的事件循环
        AcceptorOperationFuture request = new AcceptorOperationFuture(localAddresses);

        // adds the Registration request to the queue for the Workers to handle
        registerQueue.add(request);

        // creates the Acceptor instance and has the local executor kick it off.
        // 将 acceptor 提交到线程池中执行
        startupAcceptor();

        // As we just started the acceptor, we have to unblock the select()
        // in order to process the bind request we just have added to the
        // registerQueue.
        try {
            lock.acquire();
            // 唤醒可能阻塞在 selector 上的线程，或者下次调用 select 方法立即返回
            wakeup();
        } finally {
            lock.release();
        }

        // Now, we wait until this request is completed.
        // 阻塞等待 acceptor 线程处理前面创建的 request 请求，acceptor 线程会为其中 localAddresses 中的
        // 每一个地址 addr 创建 ServerSocketChannel，并且 bind 到 addr 上，然后注册到 selector 上监听 OP_ACCEPT 事件
        // 如果 acceptor 处理完 request 请求，就会唤醒阻塞在 request 上的线程
        request.awaitUninterruptibly();

        if (request.getException() != null) {
            throw request.getException();
        }

        // Update the local addresses.
        // setLocalAddresses() shouldn't be called from the worker thread because of deadlock.
        Set<SocketAddress> newLocalAddresses = new HashSet<>();

        for (H handle : boundHandles.values()) {
            newLocalAddresses.add(localAddress(handle));
        }

        return newLocalAddresses;
    }

    /**
     * This method is called by the doBind() and doUnbind()
     * methods.  If the acceptor is null, the acceptor object will
     * be created and kicked off by the executor.  If the acceptor
     * object is null, probably already created and this class
     * is now working, then nothing will happen and the method
     * will just return.
     */
    private void startupAcceptor() throws InterruptedException {
        // If the acceptor is not ready, clear the queues
        if (!selectable) {
            registerQueue.clear();
            cancelQueue.clear();
        }

        // start the acceptor if not already started
        Acceptor acceptor = acceptorRef.get();

        if (acceptor == null) {
            lock.acquire();
            acceptor = new Acceptor();

            if (acceptorRef.compareAndSet(null, acceptor)) {
                executeWorker(acceptor);
            } else {
                lock.release();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final void unbind0(List<? extends SocketAddress> localAddresses) throws Exception {
        AcceptorOperationFuture future = new AcceptorOperationFuture(localAddresses);

        cancelQueue.add(future);
        startupAcceptor();
        wakeup();

        future.awaitUninterruptibly();
        if (future.getException() != null) {
            throw future.getException();
        }
    }

    /**
     * This class is called by the startupAcceptor() method and is
     * placed into a NamePreservingRunnable class.
     * It's a thread accepting incoming connections from clients.
     * The loop is stopped when all the bound handlers are unbound.
     */
    private class Acceptor implements Runnable {
        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            assert acceptorRef.get() == this;

            int nHandles = 0;

            // Release the lock
            lock.release();

            // selector 在创建完毕之后，就会设置 selectable 为 true，可以接受新的连接
            while (selectable) {
                try {
                    // Process the bound sockets to this acceptor.
                    // this actually sets the selector to OP_ACCEPT,
                    // and binds to the port on which this class will
                    // listen on. We do that before the select because 
                    // the registerQueue containing the new handler is
                    // already updated at this point.
                    // 从 registerQueue 中获取到注册请求 request，并且从 request 中获取需要监听的地址列表，
                    // 然后为每一个地址 addr 创建一个 ServerSocketChannel，然后将其 bind 到 addr 上，监听 OP_ACCEPT 事件，
                    // 即等待此 addr 上是否有客户端想建立连接。最后返回注册在 selector 上的连接数。
                    nHandles += registerHandles();

                    // Detect if we have some keys ready to be processed. The select() will be woke up
                    // if some new connection have occurred, or if the selector has been explicitly
                    // woke up
                    int selected = select();

                    // Now, if the number of registered handles is 0, we can
                    // quit the loop: we don't have any socket listening
                    // for incoming connection.
                    // nHandles 表示的是一共注册在 selector 上的连接数
                    if (nHandles == 0) {
                        acceptorRef.set(null);
                        // 如果没有新的监听端口的请求，以及取消注册的请求，直接退出 loop
                        if (registerQueue.isEmpty() && cancelQueue.isEmpty()) {
                            assert acceptorRef.get() != this;
                            break;
                        }

                        if (!acceptorRef.compareAndSet(null, this)) {
                            assert acceptorRef.get() != this;
                            break;
                        }

                        assert acceptorRef.get() == this;
                    }

                    // 如果有些连接上有 OP_ACCEPT 事件发生
                    if (selected > 0) {
                        // We have some connection request, let's process them here.
                        // 监听获取到 SocketChannel，并且将 SocketChannel 封装成 NioSocketSession，并且将此 session
                        // 分配到一个 Processor 上，让 Processor 真正将 channel 注册到 selector 上
                        processHandles(selectedHandles());
                    }

                    // check to see if any cancellation request has been made.
                    nHandles -= unregisterHandles();
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

            // Cleanup all the processors, and shutdown the acceptor.
            if (selectable && isDisposing()) {
                selectable = false;
                try {
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

        /**
         * This method will process new sessions for the Worker class.  All
         * keys that have had their status updates as per the Selector.selectedKeys()
         * method will be processed here.  Only keys that are ready to accept
         * connections are handled here.
         * <p/>
         * Session objects are created by making new instances of SocketSessionImpl
         * and passing the session object to the SocketIoProcessor class.
         */
        @SuppressWarnings("unchecked")
        private void processHandles(Iterator<H> handles) throws Exception {

            while (handles.hasNext()) {
                // handles 是调用 selector.selectedKeys 返回的集合，但是调用 next 方法会调用 key.channel()
                // 最终返回 ServerSocketChannel 对象
                H handle = handles.next();
                handles.remove();

                // Associates a new created connection to a processor, and get back a session
                // 监听获取到 SocketChannel，并且将 SocketChannel 封装成 NioSocketChannel。在封装时会给这个 channel 初始化一个
                // filterChain 过滤器链（但此时还不包含任何 filter，除了 headFilter 和 tailFilter）和一个 processor（实际上是一个
                // SimpleIoProcessorPool 对象）
                S session = accept(processor, handle);

                if (session == null) {
                    continue;
                }

                // 为 NioSocketChannel 初始化一个 AttributeMap 以及一个写请求队列 WriteRequestQueue
                initSession(session, null, null);
                // add the session to the SocketIoProcessor
                // 用 SimpleIoProcessorPool 为 session 分配一个 processor，并且将此 session 添加到
                // AbstractPollingIoProcessor 中的 newSessions 中，并启动 processor 来处理这个 session，
                // 真正的将其注册到 selector 上，并且将 filterChainBuilder 中的 filter 保存到此 session 的 filterChain 中
                session.getProcessor().add(session);
            }
        }

        /**
         * Sets up the socket communications.  Sets items such as:
         * <p/>
         * Blocking
         * Reuse address
         * Receive buffer size
         * Bind to listen port
         * Registers OP_ACCEPT for selector
         */
        private int registerHandles() {
            for (;;) {
                // The register queue contains the list of services to manage in this acceptor.
                // 从 registerQueue 中获取到 future 对象，future 中包含需要监听的地址列表
                AcceptorOperationFuture future = registerQueue.poll();

                if (future == null) {
                    return 0;
                }

                // We create a temporary map to store the bound handles, as we may have to remove them all if there is an exception
                // during the sockets opening.
                // 从 future 中获得需要监听的地址 address 列表
                Map<SocketAddress, H> newHandles = new ConcurrentHashMap<>();
                List<SocketAddress> localAddresses = future.getLocalAddresses();

                try {
                    // Process all the addresses
                    // 为每一个 address 创建一个 ServerSocketChannel 对象，将其 bind 到 address 上，随后注册到 selector 上，
                    // 并且监听 OP_ACCEPT 事件，随后返回 ServerSocketChannel 对象，也就是这里的 handle
                    for (SocketAddress a : localAddresses) {
                        H handle = open(a);
                        newHandles.put(localAddress(handle), handle);
                    }

                    // Everything went ok, we can now update the map storing all the bound sockets.
                    // boundHandles 表示当前注册在 selector 上的所有 ServerSocketChannel 和 地址之间的映射关系
                    boundHandles.putAll(newHandles);

                    // and notify.
                    // notify 阻塞在 future 上的线程（future 就是 registerQueue 上的 request）
                    future.setDone();
                    
                    return newHandles.size();
                } catch (Exception e) {
                    // We store the exception in the future
                    // 如果在 open 的过程中发生异常，则设置异常，同样会唤醒阻塞在 future 上的线程
                    future.setException(e);
                } finally {
                    // Roll back if failed to bind all addresses.
                    if (future.getException() != null) {
                        for (H handle : newHandles.values()) {
                            try {
                                close(handle);
                            } catch (Exception e) {
                                ExceptionMonitor.getInstance().exceptionCaught(e);
                            }
                        }

                        // Wake up the selector to be sure we will process the newly bound handle
                        // and not block forever in the select()
                        wakeup();
                    }
                }
            }
        }

        /**
         * This method just checks to see if anything has been placed into the
         * cancellation queue.  The only thing that should be in the cancelQueue
         * is CancellationRequest objects and the only place this happens is in
         * the doUnbind() method.
         *
         * 从 cancelQueue 中获取到 future 对象，future 对象中保存了要监听的地址列表，遍历列表，
         * 将每一个地址对应的 ServeSocketChannel 从 selector 上取消注册，并且关闭
         */
        private int unregisterHandles() {
            int cancelledHandles = 0;
            for (;;) {
                AcceptorOperationFuture future = cancelQueue.poll();
                if (future == null) {
                    break;
                }

                // close the channels
                for (SocketAddress a : future.getLocalAddresses()) {
                    H handle = boundHandles.remove(a);

                    if (handle == null) {
                        continue;
                    }

                    try {
                        // ServerSocketChannel 从 selector 上取消注册，并且关闭连接
                        close(handle);
                        wakeup(); // wake up again to trigger thread death
                    } catch (Exception e) {
                        ExceptionMonitor.getInstance().exceptionCaught(e);
                    } finally {
                        cancelledHandles++;
                    }
                }

                // 唤醒等待 dispose 的线程
                future.setDone();
            }

            return cancelledHandles;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final IoSession newSession(SocketAddress remoteAddress, SocketAddress localAddress) {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the backLog
     */
    public int getBacklog() {
        return backlog;
    }

    /**
     * Sets the Backlog parameter
     * 
     * @param backlog
     *            the backlog variable
     */
    public void setBacklog(int backlog) {
        synchronized (bindLock) {
            if (isActive()) {
                throw new IllegalStateException("backlog can't be set while the acceptor is bound.");
            }

            this.backlog = backlog;
        }
    }

    /**
     * @return the flag that sets the reuseAddress information
     */
    public boolean isReuseAddress() {
        return reuseAddress;
    }

    /**
     * Set the Reuse Address flag
     * 
     * @param reuseAddress
     *            The flag to set
     */
    public void setReuseAddress(boolean reuseAddress) {
        synchronized (bindLock) {
            if (isActive()) {
                throw new IllegalStateException("backlog can't be set while the acceptor is bound.");
            }

            this.reuseAddress = reuseAddress;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SocketSessionConfig getSessionConfig() {
        return (SocketSessionConfig)sessionConfig;
    }
}
