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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.mina.core.polling.AbstractPollingIoProcessor;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A default implementation of {@link IoFuture} associated with
 * an {@link IoSession}.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class DefaultIoFuture implements IoFuture {

    /** A number of milliseconds to wait between two deadlock controls ( 5 seconds ) */
    // 每隔多少时间就去检查一下是否有死锁
    private static final long DEAD_LOCK_CHECK_INTERVAL = 5000L;

    /** The associated session */
    private final IoSession session;

    /** A lock used by the wait() method */
    private final Object lock;

    /** The first listener. This is easier to have this variable
     * when we most of the time have one single listener */
    private IoFutureListener<?> firstListener;

    /** All the other listeners, in case we have more than one */
    private List<IoFutureListener<?>> otherListeners;

    private Object result;

    /** The flag used to determinate if the Future is completed or not */
    private boolean ready;

    /**
     * A counter for the number of threads waiting on this future
     */
    private int waiters;

    /**
     * Creates a new instance associated with an {@link IoSession}.
     *
     * @param session an {@link IoSession} which is associated with this future
     */
    public DefaultIoFuture(IoSession session) {
        this.session = session;
        this.lock = this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoSession getSession() {
        return session;
    }

    /**
     * @deprecated Replaced with {@link #awaitUninterruptibly()}.
     */
    @Override
    @Deprecated
    public void join() {
        awaitUninterruptibly();
    }

    /**
     * @deprecated Replaced with {@link #awaitUninterruptibly(long)}.
     */
    @Override
    @Deprecated
    public boolean join(long timeoutMillis) {
        return awaitUninterruptibly(timeoutMillis);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoFuture await() throws InterruptedException {
        synchronized (lock) {
            // ready 表明 future 的异步操作是否完成，在操作完成后调用 future 的 setValue 方法来设置 ready 为 true
            while (!ready) {
                waiters++;
                
                try {
                    // Wait for a notify, or if no notify is called,
                    // assume that we have a deadlock and exit the
                    // loop to check for a potential deadlock.
                    // 在 lock 上阻塞 5s 之后就会去检查是否发生了死锁
                    lock.wait(DEAD_LOCK_CHECK_INTERVAL);
                } finally {
                    waiters--;
                    
                    if (!ready) {
                        // 检查是否有可能发生死锁，即检查当前运行的线程是否为 Processor 中的 I/O 线程。接下来举例说明发生死锁的条件：
                        // WriteFuture future = session.write(message);
                        // future.await();
                        // 当 Processor 中的 I/O 线程调用了 session#write 方法之后，并不会实际进行发送，而是把写信息包装成一个 WriteRequest
                        // 请求，保存到一个 WriteRequestQueue 队列中。而对 WriteRequestQueue 队列的处理在 Processor 的 run 方法中，
                        // 具体来说是调用 flush，从队列中获取写请求，真正把信息发送到连接另一端，然后 fire messageSent 事件，最后唤醒阻塞
                        // 在 lock 上的线程。因此，当 Processor I/O 线程调用了 future#await 方法阻塞之后，它不可能再进行真正写操作，去唤醒自己
                        // 所以这种情况下就会产生死锁
                        checkDeadLock();
                    }
                }
            }
        }
        
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toMillis(timeout), true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(timeoutMillis, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoFuture awaitUninterruptibly() {
        try {
            await0(Long.MAX_VALUE, false);
        } catch (InterruptedException ie) {
            // Do nothing : this catch is just mandatory by contract
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toMillis(timeout), false);
        } catch (InterruptedException e) {
            throw new InternalError();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(timeoutMillis, false);
        } catch (InterruptedException e) {
            throw new InternalError();
        }
    }

    /**
     * Wait for the Future to be ready. If the requested delay is 0 or
     * negative, this method immediately returns the value of the
     * 'ready' flag.
     * Every 5 second, the wait will be suspended to be able to check if
     * there is a deadlock or not.
     * 
     * @param timeoutMillis The delay we will wait for the Future to be ready
     * @param interruptable Tells if the wait can be interrupted or not
     * @return <tt>true</tt> if the Future is ready
     * @throws InterruptedException If the thread has been interrupted
     * when it's not allowed.
     */
    private boolean await0(long timeoutMillis, boolean interruptable) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeoutMillis;

        if (endTime < 0) {
            endTime = Long.MAX_VALUE;
        }

        synchronized (lock) {
            // We can quit if the ready flag is set to true, or if
            // the timeout is set to 0 or below : we don't wait in this case.
            // 如果 ready 为 true，即异步操作完成了，或者传入的 timeoutMills 为负值的话，立即返回
            if (ready||(timeoutMillis <= 0)) {
                return ready;
            }

            // The operation is not completed : we have to wait
            // waiters 表示在这个 DefaultIoFuture 上阻塞等待的线程数
            waiters++;

            try {
                for (;;) {
                    try {
                        long timeOut = Math.min(timeoutMillis, DEAD_LOCK_CHECK_INTERVAL);
                        
                        // Wait for the requested period of time,
                        // but every DEAD_LOCK_CHECK_INTERVAL seconds, we will
                        // check that we aren't blocked.
                        // 阻塞 timeout 时间段，之后检查是否发生死锁
                        lock.wait(timeOut);

                    // if any thread interrupted the current thread before or while the current thread
                    // was waiting for a notification. The interrupted status of the current thread is cleared when
                    // this exception is thrown.
                    // 当前线程如果阻塞在 wait 方法时，另外一个线程调用了当前线程的 Thread.interrupt 方法，那么 wait 方法就会抛出
                    // InterruptedException 异常
                    } catch (InterruptedException e) {
                        // 如果可以中断的话，就抛出中断异常
                        if (interruptable) {
                            throw e;
                        }
                    }

                    // 如果 ready 为 true 的话，说明异步操作已经完成，就直接返回 ready
                    // 或者阻塞的时间已经到了，不管异步操作是否完成，都直接返回 ready
                    if (ready || (endTime < System.currentTimeMillis())) {
                        return ready;
                    } else {
                        // Take a chance, detect a potential deadlock
                        checkDeadLock();
                    }
                }
            } finally {
                // We get here for 3 possible reasons :
                // 1) We have been notified (the operation has completed a way or another)
                // 2) We have reached the timeout
                // 3) The thread has been interrupted
                // In any case, we decrement the number of waiters, and we get out.
                waiters--;

                // 执行到这一步，可能有三个原因：
                // 1.当前线程已经被其它线程 notified 唤醒，并且异步操作已经执行完毕
                // 2.阻塞的 timeout 时间已经到了
                // 3.此线程阻塞在 wait 方法时被中断，抛出中断异常
                // 由于以上三种情况都可能没有检查过 dead lock，所以这里重新检查一次
                if (!ready) {
                    checkDeadLock();
                }
            }
        }
    }

    /**
     * Check for a deadlock, ie look into the stack trace that we don't have already an instance of the caller.
     * 检查是否发生死锁，即检查调用 await 的线程是否就是 Processor I/O 线程自己
     */
    private void checkDeadLock() {
        // Only read / write / connect / close future can cause dead lock.
        if (!(this instanceof CloseFuture || this instanceof WriteFuture || this instanceof ReadFuture || this instanceof ConnectFuture)) {
            return;
        }

        // Get the current thread stackTrace.
        // Using Thread.currentThread().getStackTrace() is the best solution,
        // even if slightly less efficient than doing a new Exception().getStackTrace(),
        // as internally, it does exactly the same thing. The advantage of using
        // this solution is that we may benefit some improvement with some
        // future versions of Java.
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Simple and quick check.
        // 如果在调用栈中，出现了 AbstractPollingIoProcessor 这个类的名称，那么就说明出现了死锁
        for (StackTraceElement stackElement : stackTrace) {
            if (AbstractPollingIoProcessor.class.getName().equals(stackElement.getClassName())) {
                IllegalStateException e = new IllegalStateException("t");
                e.getStackTrace();
                throw new IllegalStateException("DEAD LOCK: " + IoFuture.class.getSimpleName()
                        + ".await() was invoked from an I/O processor thread.  " + "Please use "
                        + IoFutureListener.class.getSimpleName() + " or configure a proper thread model alternatively.");
            }
        }

        // And then more precisely.
        for (StackTraceElement s : stackTrace) {
            try {
                // 通过 DefaultIoFuture 来获取到【应用程序类加载器】，即 AppClassLoader，使用此类加载器来获取到类 cls
                Class<?> cls = DefaultIoFuture.class.getClassLoader().loadClass(s.getClassName());
                // 判断类 cls 是否实现了 IoProcessor 这个接口
                if (IoProcessor.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException("DEAD LOCK: " + IoFuture.class.getSimpleName()
                            + ".await() was invoked from an I/O processor thread.  " + "Please use "
                            + IoFutureListener.class.getSimpleName()
                            + " or configure a proper thread model alternatively.");
                }
            } catch (ClassNotFoundException cnfe) {
                // Ignore
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        synchronized (lock) {
            return ready;
        }
    }

    /**
     * Sets the result of the asynchronous operation, and mark it as finished.
     * 设置异步操作的结果，并且标识 ready 为 true，表示已经完成
     * 
     * @param newValue The result to store into the Future
     * @return {@code true} if the value has been set, {@code false} if
     * the future already has a value (thus is in ready state)
     */
    public boolean setValue(Object newValue) {
        synchronized (lock) {
            // Allowed only once.
            if (ready) {
                return false;
            }

            result = newValue;
            ready = true;
            
            // Now, if we have waiters, notify them that the operation has completed
            if (waiters > 0) {
                lock.notifyAll();
            }
        }

        // Last, not least, inform the listeners
        // 回调注册在这个 future 上的所有 listener 上的 operationComplete 方法
        notifyListeners();
        
        return true;
    }

    /**
     * @return the result of the asynchronous operation.
     */
    protected Object getValue() {
        synchronized (lock) {
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoFuture addListener(IoFutureListener<?> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }

        synchronized (lock) {
            if (ready) {
                // Shortcut : if the operation has completed, no need to 
                // add a new listener, we just have to notify it. The existing
                // listeners have already been notified anyway, when the 
                // 'ready' flag has been set.
                notifyListener(listener);
            } else {
                if (firstListener == null) {
                    firstListener = listener;
                } else {
                    if (otherListeners == null) {
                        otherListeners = new ArrayList<>(1);
                    }
                    
                    otherListeners.add(listener);
                }
            }
        }
        
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoFuture removeListener(IoFutureListener<?> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }

        synchronized (lock) {
            if (!ready) {
                if (listener == firstListener) {
                    if ((otherListeners != null) && !otherListeners.isEmpty()) {
                        firstListener = otherListeners.remove(0);
                    } else {
                        firstListener = null;
                    }
                } else if (otherListeners != null) {
                    otherListeners.remove(listener);
                }
            }
        }

        return this;
    }

    /**
     * Notify the listeners, if we have some.
     */
    private void notifyListeners() {
        // There won't be any visibility problem or concurrent modification
        // because 'ready' flag will be checked against both addListener and
        // removeListener calls.
        if (firstListener != null) {
            notifyListener(firstListener);
            firstListener = null;

            if (otherListeners != null) {
                for (IoFutureListener<?> listener : otherListeners) {
                    notifyListener(listener);
                }
                
                otherListeners = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyListener(IoFutureListener listener) {
        try {
            listener.operationComplete(this);
        } catch (Exception e) {
            ExceptionMonitor.getInstance().exceptionCaught(e);
        }
    }
}
