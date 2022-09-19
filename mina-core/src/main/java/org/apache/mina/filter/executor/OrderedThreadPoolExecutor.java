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
package org.apache.mina.filter.executor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IoEvent;
import org.apache.mina.core.session.IoSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ThreadPoolExecutor} that maintains the order of {@link IoEvent}s.
 * <p>
 * If you don't need to maintain the order of events per session, please use
 * {@link UnorderedThreadPoolExecutor}.
 *
 * OrderedThreadPoolExecutor 是一个维持 IoEvent 事件顺序的线程池，即线程池中对于 IoEvent 事件的执行顺序等于事件被保存到任务队列的顺序，
 * 或者说等于 IoEvent 事件发生的顺序（ExecutionFilter 中一个事件一旦发生就会被保存到任务队列中）。OrderedThreadPoolExecutor 为了保证
 * IoEvent 事件执行的顺序性，大致的思想就是一个 session 上发生的 IoEvent 事件被保存到 taskQueue 中，并且 taskQueue 和 session 一一
 * 绑定，taskQueue 保存到 session 的 AttributeMap 中，只有在获取到 session 之后才能进一步获取到任务队列处理其中的任务。而 session 保存在
 * 一个阻塞队列 waitingSessions 中（阻塞队列使用锁保证了线程安全），在某一时刻只能有一个线程从 waitingSessions 中获取到 session，其它获取
 * 不到 session 的线程只能阻塞。因而 session 中的任务队列 taskQueue 中的任务在某一时刻【全部由】某一个线程处理，只有全部处理完毕之后才有可能
 * 切换到下一个线程获取到 session 继续处理其中的所有任务。所以任务队列中的任务在某一段时间内全由线程 A 处理，在另外某一段时间内全由线程 B 处理，
 * 不存在 race contention 的情况。
 *
 * 具体的实现方式为通过一个变量 processingCompleted 来表示当前 session 中的任务队列是否全部处理完毕，如果是的话为 true，否则为 false。
 * 另外初始化的时候，processingCompleted 为 true，因为此时 taskQueue 为空。只有当 processingCompleted 为 true 时，线程才会把 session
 * 添加到 waitingSessions 中（之后把 processingCompleted 设置为 false），然后多个等待线程中的某一个才会从 waitingSessions 阻塞队列中获取
 * 到 session，然后处理此 session 对应的任务队列中所有的任务，其它线程只能阻塞等待。等到此线程处理完任务队列之后，会把 processingCompleted
 * 设置为 true，然后 session 就有可能被添加到 waitingSessions 中，继续被下一个线程获取到。
 *
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * @org.apache.xbean.XBean
 */
@SuppressWarnings("DuplicatedCode")
public class OrderedThreadPoolExecutor extends ThreadPoolExecutor {
    /** A logger for this class (commented as it breaks MDCFlter tests) */
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderedThreadPoolExecutor.class);

    /** A default value for the initial pool size */
    private static final int DEFAULT_INITIAL_THREAD_POOL_SIZE = 0;

    /** A default value for the maximum pool size */
    private static final int DEFAULT_MAX_THREAD_POOL = 16;

    /** A default value for the KeepAlive delay */
    private static final int DEFAULT_KEEP_ALIVE = 30;

    private static final IoSession EXIT_SIGNAL = new DummySession();

    /** A key stored into the session's attribute for the event tasks being queued */
    private static final AttributeKey TASKS_QUEUE = new AttributeKey(OrderedThreadPoolExecutor.class, "tasksQueue");

    /** A queue used to store the available sessions */
    private final BlockingQueue<IoSession> waitingSessions = new LinkedBlockingQueue<>();

    private final Set<Worker> workers = new HashSet<>();

    private volatile int largestPoolSize;

    private final AtomicInteger idleWorkers = new AtomicInteger();

    private long completedTaskCount;

    private volatile boolean shutdown;

    private final IoEventQueueHandler eventQueueHandler;

    /**
     * Creates a default ThreadPool, with default values :
     * - minimum pool size is 0
     * - maximum pool size is 16
     * - keepAlive set to 30 seconds
     * - A default ThreadFactory
     * - All events are accepted
     */
    public OrderedThreadPoolExecutor() {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, DEFAULT_MAX_THREAD_POOL, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS, Executors
                .defaultThreadFactory(), null);
    }

    /**
     * Creates a default ThreadPool, with default values :
     * - minimum pool size is 0
     * - keepAlive set to 30 seconds
     * - A default ThreadFactory
     * - All events are accepted
     * 
     * @param maximumPoolSize The maximum pool size
     */
    public OrderedThreadPoolExecutor(int maximumPoolSize) {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, maximumPoolSize, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS, Executors
                .defaultThreadFactory(), null);
    }

    /**
     * Creates a default ThreadPool, with default values :
     * - keepAlive set to 30 seconds
     * - A default ThreadFactory
     * - All events are accepted
     *
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     */
    public OrderedThreadPoolExecutor(int corePoolSize, int maximumPoolSize) {
        this(corePoolSize, maximumPoolSize, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS, Executors.defaultThreadFactory(),
                null);
    }

    /**
     * Creates a default ThreadPool, with default values :
     * - A default ThreadFactory
     * - All events are accepted
     * 
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     */
    public OrderedThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, Executors.defaultThreadFactory(), null);
    }

    /**
     * Creates a default ThreadPool, with default values :
     * - A default ThreadFactory
     * 
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     * @param eventQueueHandler The queue used to store events
     */
    public OrderedThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            IoEventQueueHandler eventQueueHandler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, Executors.defaultThreadFactory(), eventQueueHandler);
    }

    /**
     * Creates a default ThreadPool, with default values :
     * - A default ThreadFactory
     * 
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     * @param threadFactory The factory used to create threads
     */
    public OrderedThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, threadFactory, null);
    }

    /**
     * Creates a new instance of a OrderedThreadPoolExecutor.
     * 
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     * @param threadFactory The factory used to create threads
     * @param eventQueueHandler The queue used to store events
     */
    public OrderedThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            ThreadFactory threadFactory, IoEventQueueHandler eventQueueHandler) {
        // We have to initialize the pool with default values (0 and 1) in order to
        // handle the exception in a better way. We can't add a try {} catch() {}
        // around the super() call.
        super(DEFAULT_INITIAL_THREAD_POOL_SIZE, 1, keepAliveTime, unit, new SynchronousQueue<Runnable>(),
                threadFactory, new AbortPolicy());

        if (corePoolSize < DEFAULT_INITIAL_THREAD_POOL_SIZE) {
            throw new IllegalArgumentException("corePoolSize: " + corePoolSize);
        }

        if ((maximumPoolSize <= 0) || (maximumPoolSize < corePoolSize)) {
            throw new IllegalArgumentException("maximumPoolSize: " + maximumPoolSize);
        }

        // Now, we can setup the pool sizes
        super.setMaximumPoolSize(maximumPoolSize);
        super.setCorePoolSize(corePoolSize);

        // The queueHandler might be null.
        if (eventQueueHandler == null) {
            this.eventQueueHandler = IoEventQueueHandler.NOOP;
        } else {
            this.eventQueueHandler = eventQueueHandler;
        }
    }

    /**
     * Get the session's tasks queue.
     */
    private SessionTasksQueue getSessionTasksQueue(IoSession session) {
        SessionTasksQueue queue = (SessionTasksQueue) session.getAttribute(TASKS_QUEUE);

        if (queue == null) {
            queue = new SessionTasksQueue();
            // setAttributeIfAbsent 方法如果 map 中 TASKS_QUEUE 键对应的 queue 也已经存在了，那么就把已经存在的 queue 直接返回，
            // 如果 TASKS_QUEUE 键对应的 queue 不存在，就把新创建好的 queue 直接保存到 map 中，然后返回 null
            SessionTasksQueue oldQueue = (SessionTasksQueue) session.setAttributeIfAbsent(TASKS_QUEUE, queue);

            if (oldQueue != null) {
                queue = oldQueue;
            }
        }

        return queue;
    }

    /**
     * @return The associated queue handler. 
     */
    public IoEventQueueHandler getQueueHandler() {
        return eventQueueHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        // Ignore the request.  It must always be AbortPolicy.
    }

    /**
     * Add a new thread to execute a task, if needed and possible.
     * It depends on the current pool size. If it's full, we do nothing.
     */
    private void addWorker() {
        synchronized (workers) {
            // 如果线程池中线程数量超过 MaximumPoolSize，那么就直接退出
            if (workers.size() >= super.getMaximumPoolSize()) {
                return;
            }

            // Create a new worker, and add it to the thread pool
            // 创建一个新的 Worker，并且将新的 Worker 作为 Runnable 对象保存到 Thread 中，最后启动 Thread
            Worker worker = new Worker();
            Thread thread = getThreadFactory().newThread(worker);
            workers.add(worker);

            // As we have added a new thread, it's considered as idle.
            // 新添加的 worker 被视为 idleWorker，因为刚创建处于空闲状态
            idleWorkers.incrementAndGet();

            // Now, we can start it.
            // 开启线程
            thread.start();

            // largestPoolSize 纯粹进行记录，表示线程池最大的线程数量（也就是 worker 数量）
            if (workers.size() > largestPoolSize) {
                largestPoolSize = workers.size();
            }
        }
    }

    /**
     * Add a new Worker only if there are no idle worker.
     */
    private void addWorkerIfNecessary() {
        if (idleWorkers.get() == 0) {
            synchronized (workers) {
                if (workers.isEmpty() || (idleWorkers.get() == 0)) {
                    addWorker();
                }
            }
        }
    }

    private void removeWorker() {
        synchronized (workers) {
            if (workers.size() <= super.getCorePoolSize()) {
                return;
            }
            waitingSessions.offer(EXIT_SIGNAL);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaximumPoolSize(int maximumPoolSize) {
        if ((maximumPoolSize <= 0) || (maximumPoolSize < super.getCorePoolSize())) {
            throw new IllegalArgumentException("maximumPoolSize: " + maximumPoolSize);
        }

        synchronized (workers) {
            super.setMaximumPoolSize(maximumPoolSize);
            int difference = workers.size() - maximumPoolSize;
            while (difference > 0) {
                removeWorker();
                --difference;
            }
        }
    }

    /**
     * 当 workers.wait
     *
     * {@inheritDoc}
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {

        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);

        synchronized (workers) {
            while (!isTerminated()) {
                long waitTime = deadline - System.currentTimeMillis();
                if (waitTime <= 0) {
                    break;
                }

                workers.wait(waitTime);
            }
        }

        return isTerminated();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTerminated() {
        // 当线程池不处于 shutdown，直接返回 false
        if (!shutdown) {
            return false;
        }

        // 只有当线程池关闭之后，发出 EXIT_SIGNAL 信号，worker 线程的数量才会减少到 0。
        // 因此当 OrderedThreadPoolExecutor 线程池处于 TERMINATED 状态时，和 jdk 原生的线程池类似，
        // 都是所有的任务都销毁了，线程的数量变为 0。
        synchronized (workers) {
            return workers.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        // 如果已经 shutdown 了，直接返回
        if (shutdown) {
            return;
        }

        shutdown = true;

        // 如果线程池已经 shutdown 了，当前线程池中还有多少个线程，就添加多个 EXIT_SIGNAL 到其中，这样每一个线程都可能
        // 获取到 EXIT_SIGNAL，从而退出 loop，并且从线程池中移除掉（准确地说是从 workers 中移除掉）。
        // 从这里也可以看出和原来的 ThreadPoolExecutor 相同的特点，即线程池 shutdown 之后，首先会拒绝接收新的任务，
        // 但是正在处理任务的线程 worker 不会立刻停止（worker 只有在接受到 EXIT_SIGNAL 这个特殊的 session 之后才会退出），而是会继续运行。
        synchronized (workers) {
            for (int i = workers.size(); i > 0; i--) {
                waitingSessions.offer(EXIT_SIGNAL);
            }
        }
    }

    /**
     * Attempts to stop all actively executing tasks, halts the processing of waiting tasks, and returns
     * a list of the tasks that were awaiting execution. These tasks are drained (removed) from the task queue
     * upon return from this method.
     *
     * jdk 原生的 ThreadPoolExecutor 线程池的 shutdownNow 方法会尝试去暂停所有正在执行的任务（通过调用 Thread.interrupt），
     * 同时还没有执行的任务也不再执行，最后返回等待执行的任务集合。
     *
     * 在 OrderedThreadPoolExecutor 线程池中，shutdownNow 方法首先调用 shutdown 方法，给每个 worker 线程发送 EXIT_SIGNAL
     * 信号，空闲的 worker 在接收到此信号之后就会退出 loop，并且从线程池 workers 集合中移除掉。有 worker 正在执行 taskQueue 中的
     * 其它任务时（正在执行 runTask 方法），就会把 taskQueue 中剩下的任务保存到 answer 集合中，并且从 taskQueue 中移除，使得
     * 还没有被执行的任务不会再执行。最后返回 answer 集合。
     *
     * 和 jdk 原生的线程池的区别是，没有调用线程的 interrupt 方法来暂停正在执行的任务
     *
     * {@inheritDoc}
     */
    @Override
    public List<Runnable> shutdownNow() {
        shutdown();

        List<Runnable> answer = new ArrayList<>();
        IoSession session;

        // while 循环需要返回 session 中还没有执行的任务，并且清空
        while ((session = waitingSessions.poll()) != null) {
            // 如果获取到的是 EXIT_SIGNAL 这个结束标识的话，添加回去，然后继续循环
            if (session == EXIT_SIGNAL) {
                waitingSessions.offer(EXIT_SIGNAL);
                Thread.yield(); // Let others take the signal.
                continue;
            }

            SessionTasksQueue sessionTasksQueue = (SessionTasksQueue) session.getAttribute(TASKS_QUEUE);

            synchronized (sessionTasksQueue.tasksQueue) {

                // 将还没有执行的任务保存到 answer 集合中，最终返回，并且清空原有的集合
                for (Runnable task : sessionTasksQueue.tasksQueue) {
                    getQueueHandler().polled(this, (IoEvent) task);
                    answer.add(task);
                }

                sessionTasksQueue.tasksQueue.clear();
            }
        }

        return answer;
    }

    /**
     * A Helper class used to print the list of events being queued. 
     */
    private void print(Queue<Runnable> queue, IoEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Adding event ").append(event.getType()).append(" to session ").append(event.getSession().getId());
        boolean first = true;
        sb.append("\nQueue : [");
        
        for (Runnable elem : queue) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }

            sb.append(((IoEvent) elem).getType()).append(", ");
        }
        
        sb.append("]\n");
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(sb.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Runnable task) {
        // 如果线程池已经被关闭了，那么拒绝任务的提交
        if (shutdown) {
            rejectTask(task);
        }

        // Check that it's a IoEvent task
        // 检查 task 类型是否为 IoEvent 或者其子类（即 IoFilterEvent）的实例
        checkTaskType(task);
        // 将 task 转变为 IoEvent 对象，后面统一保存到 session 的 taskQueue 中
        IoEvent event = (IoEvent) task;

        // Get the associated session
        IoSession session = event.getSession();

        // Get the session's queue of events
        // 从 session 的 AttributeMap 中获取到任务队列
        SessionTasksQueue sessionTasksQueue = getSessionTasksQueue(session);
        Queue<Runnable> tasksQueue = sessionTasksQueue.tasksQueue;

        boolean offerSession;

        // propose the new event to the event queue handler. If we use a throttle queue handler,
        // the message may be rejected if the maximum size has been reached.
        // 使用 eventQueueHandler 来决定是否接受处理此 IoEvent，比如根据流量限制等因素
        boolean offerEvent = eventQueueHandler.accept(this, event);

        // 如果可以处理此 IoEvent 事件
        if (offerEvent) {
            // Ok, the message has been accepted
            // 在任务队列 tasksQueue 上使用 synchronized 关键字是为了保证对于 processingCompleted 变量的修改唯一，
            // 否则的话，A 线程和 B 线程有可能同时执行到 if(sessionTasksQueue.processingCompleted) 条件判断语句，
            // 然后都为真，接着 B 线程 CPU 时间片到期，A 线程继续执行，offerSession 为 true，将当前 session 添加到
            // waitingSessions 中。如果 B 线程被唤醒继续执行时，那么也会把 offerSession 设置为 true，然后将当前
            // session 添加到 waitingSessions 中，这样 waitingSessions 中就有两个相同的 session，可能同时有两个线程
            // 来获取到 session 中相同的任务队列进行处理，无法保证事件的执行顺序。
            synchronized (tasksQueue) {
                // Inject the event into the executor taskQueue
                tasksQueue.offer(event);

                // processingCompleted 表示任务队列中的任务是否全部处理完毕，为 true 表示处理完毕，为 false 表示还没有
                if (sessionTasksQueue.processingCompleted) {
                    sessionTasksQueue.processingCompleted = false;
                    offerSession = true;
                } else {
                    offerSession = false;
                }

                if (LOGGER.isDebugEnabled()) {
                    print(tasksQueue, event);
                }
            }
        } else {
            offerSession = false;
        }

        // 当 processingCompleted 为 true 时，offerSession 也为 true，可以把当前的 session 添加到 waitingSessions 中
        if (offerSession) {
            // As the tasksQueue was empty, the task has been executed immediately, so we can move the session to
            // the queue of sessions waiting for completion.
            waitingSessions.offer(session);
        }

        addWorkerIfNecessary();

        if (offerEvent) {
            eventQueueHandler.offered(this, event);
        }
    }

    private void rejectTask(Runnable task) {
        getRejectedExecutionHandler().rejectedExecution(task, this);
    }

    private void checkTaskType(Runnable task) {
        // instanceof 运算符前一个操作数通常是一个引用类型变量，后一个操作数通常是一个类，也可以是一个接口，
        // 用于判断前面的对象是否是后面的类，或者其子类、实现类的实例
        // 判断 task 是否是 IoEvent 类对象，或者是 IoEvent 子类的对象
        if (!(task instanceof IoEvent)) {
            throw new IllegalArgumentException("task must be an IoEvent or its subclass.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getActiveCount() {
        synchronized (workers) {
            return workers.size() - idleWorkers.get();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCompletedTaskCount() {
        synchronized (workers) {
            long answer = completedTaskCount;
            for (Worker w : workers) {
                answer += w.completedTaskCount.get();
            }

            return answer;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLargestPoolSize() {
        return largestPoolSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPoolSize() {
        synchronized (workers) {
            return workers.size();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTaskCount() {
        return getCompletedTaskCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTerminating() {
        synchronized (workers) {
            return isShutdown() && !isTerminated();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int prestartAllCoreThreads() {
        int answer = 0;
        synchronized (workers) {
            for (int i = super.getCorePoolSize() - workers.size(); i > 0; i--) {
                addWorker();
                answer++;
            }
        }
        return answer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean prestartCoreThread() {
        synchronized (workers) {
            if (workers.size() < super.getCorePoolSize()) {
                addWorker();
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BlockingQueue<Runnable> getQueue() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purge() {
        // Nothing to purge in this implementation.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(Runnable task) {
        checkTaskType(task);
        IoEvent event = (IoEvent) task;
        IoSession session = event.getSession();
        SessionTasksQueue sessionTasksQueue = (SessionTasksQueue) session.getAttribute(TASKS_QUEUE);

        if (sessionTasksQueue == null) {
            return false;
        }

        boolean removed;
        Queue<Runnable> tasksQueue = sessionTasksQueue.tasksQueue;

        synchronized (tasksQueue) {
            removed = tasksQueue.remove(task);
        }

        if (removed) {
            getQueueHandler().polled(this, event);
        }

        return removed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException("corePoolSize: " + corePoolSize);
        }
        if (corePoolSize > super.getMaximumPoolSize()) {
            throw new IllegalArgumentException("corePoolSize exceeds maximumPoolSize");
        }

        synchronized (workers) {
            if (super.getCorePoolSize() > corePoolSize) {
                for (int i = super.getCorePoolSize() - corePoolSize; i > 0; i--) {
                    removeWorker();
                }
            }
            super.setCorePoolSize(corePoolSize);
        }
    }

    private class Worker implements Runnable {

        private AtomicLong completedTaskCount = new AtomicLong(0);

        private Thread thread;

        /**
         * @inheritedDoc
         */
        @Override
        public void run() {
            thread = Thread.currentThread();

            try {
                /*
                 * 只有三种方式可以退出 for 循环：
                 * 1.线程池被 shutdown，接受到 EXIT_SIGNAL 信号，worker 直接退出，并且将自己从线程池 workers 集合中删除
                 * 2.当前线程处于空闲状态（获取到的 session 等于 null），如果线程数目大于核心线程数，那么也退出，并且从 workers 集合中删除
                 * 3.发生其他异常
                 */
                for (;;) {
                    IoSession session = fetchSession();

                    // worker 马上就要循环执行 session 中的任务队列，因此将 idleWorkers 的数量减一
                    idleWorkers.decrementAndGet();

                    // 如果 session 为 null，说明当前没有任务可以处理，而当前 workers 的 size 如果再大于 CorePoolSize 的话，
                    // 此 worker 没有继续保留的必要，那么直接退出 loop，并且将此 worker 从线程池中移除掉。即超过 CorePoolSize
                    // 的 worker，如果空闲时间超过 KeepAliveTime 的话，需要将其移除。相反，对于 CorePoolSize 中的线程，即使
                    // 没有任务处于空闲状态，session = null，也会不停循环等待，而不会被移除掉。
                    if (session == null) {
                        synchronized (workers) {
                            if (workers.size() > getCorePoolSize()) {
                                break;
                            }
                        }
                    }

                    // 当获取到的 session 为 EXIT_SIGNAL 时，说明线程池已经被 shutdown，那么 worker 直接退出，不再执行任务
                    if (session == EXIT_SIGNAL) {
                        break;
                    }

                    if (session != null) {
                        // 不断循环，从 session 的任务队列中获取到任务，然后进行处理。如果任务队列中的任务全部处理完毕，则直接退出 loop
                        // 以及 runTasks 方法
                        runTasks(getSessionTasksQueue(session));
                    }

                    // idleWorkers 的数量增加一
                    idleWorkers.incrementAndGet();
                }
            } finally {
                synchronized (workers) {
                    workers.remove(this);
                    OrderedThreadPoolExecutor.this.completedTaskCount += completedTaskCount.get();
                    workers.notifyAll();
                }
            }
        }

        private IoSession fetchSession() {
            IoSession session = null;
            long currentTime = System.currentTimeMillis();
            long deadline = currentTime + getKeepAliveTime(TimeUnit.MILLISECONDS);
            
            for (;;) {
                try {
                    long waitTime = deadline - currentTime;
                    
                    if (waitTime <= 0) {
                        break;
                    }

                    try {
                        // 当前线程从阻塞队列 waitingSessions 中获取到 session，注意当多个线程都阻塞在 waitingSessions 上时，
                        // 当调用了 waitingSessions.offer 后，只能有一个线程从阻塞队列中获取到 session，其它线程要么继续阻塞，要么超时退出
                        session = waitingSessions.poll(waitTime, TimeUnit.MILLISECONDS);
                        break;
                    } finally {
                        // 当线程在 waitingSessions.poll 上阻塞时，如果有其它线程调用了此线程的中断，那么当 session == null 时，会
                        // 更新 currentTime 的值
                        if (session == null) {
                            currentTime = System.currentTimeMillis();
                        }
                    }
                } catch (InterruptedException e) {
                    // Ignore.
                    continue;
                }
            }
            
            return session;
        }

        private void runTasks(SessionTasksQueue sessionTasksQueue) {
            for (;;) {
                Runnable task;
                Queue<Runnable> tasksQueue = sessionTasksQueue.tasksQueue;

                // 在 tasksQueue 上使用 synchronized 关键字是为了保证对 taskQueue 和 processingCompleted 变量操作的原子性
                synchronized (tasksQueue) {
                    task = tasksQueue.poll();
                    // 当 taskQueue 中的任务全部处理完毕之后，就可以把 processingCompleted 设置为 true，表示此次线程
                    // 处理 session 完毕。之后 session 会被再一次添加到 waitingSessions 中，其它阻塞线程可能获取到 session
                    // （当前线程也有可能再次获取到），然后继续处理 session 中的任务
                    if (task == null) {
                        sessionTasksQueue.processingCompleted = true;
                        break;
                    }
                }

                eventQueueHandler.polled(OrderedThreadPoolExecutor.this, (IoEvent) task);
                // 真正执行 task 任务
                runTask(task);
            }
        }

        private void runTask(Runnable task) {
            // ThreadPoolExecutor 的钩子函数
            beforeExecute(thread, task);
            boolean ran = false;
            try {
                task.run();
                ran = true;
                afterExecute(task, null);
                completedTaskCount.incrementAndGet();
            } catch (RuntimeException e) {
                if (!ran) {
                    afterExecute(task, e);
                }
                throw e;
            }
        }
    }

    /**
     * A class used to store the ordered list of events to be processed by the
     * session, and the current task state.
     */
    private class SessionTasksQueue {
        /**  A queue of ordered event waiting to be processed */
        private final Queue<Runnable> tasksQueue = new ConcurrentLinkedQueue<>();

        /** The current task state */
        private boolean processingCompleted = true;
    }
}
