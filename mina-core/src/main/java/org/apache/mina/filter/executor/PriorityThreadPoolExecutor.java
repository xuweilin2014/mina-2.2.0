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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
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
 * A {@link ThreadPoolExecutor} that maintains the order of {@link IoEvent}s
 * within a session (similar to {@link OrderedThreadPoolExecutor}) and allows
 * some sessions to be prioritized over other sessions.
 * <p>
 * If you don't need to maintain the order of events per session, please use
 * {@link UnorderedThreadPoolExecutor}.
 * <p>
 * If you don't need to prioritize sessions, please use
 * {@link OrderedThreadPoolExecutor}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 * @org.apache.xbean.XBean
 */
// TODO this class currently copies OrderedThreadPoolExecutor, and changes the
// BlockingQueue used for the waitingSessions field. This code duplication
// should be avoided.
public class PriorityThreadPoolExecutor extends ThreadPoolExecutor {
    /** A logger for this class (commented as it breaks MDCFlter tests) */
    private static final Logger LOGGER = LoggerFactory.getLogger(PriorityThreadPoolExecutor.class);

    /** Generates sequential identifiers that ensure FIFO behavior. */
    private static final AtomicLong seq = new AtomicLong(0);

    /** A default value for the initial pool size */
    private static final int DEFAULT_INITIAL_THREAD_POOL_SIZE = 0;

    /** A default value for the maximum pool size */
    private static final int DEFAULT_MAX_THREAD_POOL = 16;

    /** A default value for the KeepAlive delay */
    private static final int DEFAULT_KEEP_ALIVE = 30;

    private static final SessionEntry EXIT_SIGNAL = new SessionEntry(new DummySession(), null);

    /**
     * A key stored into the session's attribute for the event tasks being queued
     */
    private static final AttributeKey TASKS_QUEUE = new AttributeKey(PriorityThreadPoolExecutor.class, "tasksQueue");

    /** A queue used to store the available sessions */
    private final BlockingQueue<SessionEntry> waitingSessions;

    private final Set<Worker> workers = new HashSet<>();

    private volatile int largestPoolSize;

    private final AtomicInteger idleWorkers = new AtomicInteger();

    private long completedTaskCount;

    private volatile boolean shutdown;

    private final IoEventQueueHandler eventQueueHandler;

    private final Comparator<IoSession> comparator;

    /**
     * Creates a default ThreadPool, with default values : - minimum pool size is 0
     * - maximum pool size is 16 - keepAlive set to 30 seconds - A default
     * ThreadFactory - All events are accepted
     */
    public PriorityThreadPoolExecutor() {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, DEFAULT_MAX_THREAD_POOL, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS,
                Executors.defaultThreadFactory(), null, null);
    }

    /**
     * Creates a default ThreadPool, with default values : - minimum pool size is 0
     * - maximum pool size is 16 - keepAlive set to 30 seconds - A default
     * ThreadFactory - All events are accepted
     * 
     * @param comparator The comparator used to prioritize the queue
     */
    public PriorityThreadPoolExecutor(Comparator<IoSession> comparator) {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, DEFAULT_MAX_THREAD_POOL, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS,
                Executors.defaultThreadFactory(), null, comparator);
    }

    /**
     * Creates a default ThreadPool, with default values : 
     * <ul>
     *   <li>minimum pool size is 0</li>
     *   <li>keepAlive set to 30 seconds</li>
     *   <li>A default ThreadFactory - All events are accepted</li>
     * </ul>
     *
     * @param maximumPoolSize The maximum pool size
     */
    public PriorityThreadPoolExecutor(int maximumPoolSize) {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, maximumPoolSize, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS,
                Executors.defaultThreadFactory(), null, null);
    }

    /**
     * Creates a default ThreadPool, with default values : - minimum pool size is 0
     * - keepAlive set to 30 seconds - A default ThreadFactory - All events are
     * accepted
     *
     * @param maximumPoolSize
     *            The maximum pool size
     */
    public PriorityThreadPoolExecutor(int maximumPoolSize, Comparator<IoSession> comparator) {
        this(DEFAULT_INITIAL_THREAD_POOL_SIZE, maximumPoolSize, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS,
                Executors.defaultThreadFactory(), null, comparator);
    }

    /**
     * Creates a default ThreadPool, with default values : - keepAlive set to 30
     * seconds - A default ThreadFactory - All events are accepted
     *
     * @param corePoolSize
     *            The initial pool sizePoolSize
     * @param maximumPoolSize
     *            The maximum pool size
     */
    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize) {
        this(corePoolSize, maximumPoolSize, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS, Executors.defaultThreadFactory(),
                null, null);
    }

    /**
     * Creates a default ThreadPool, with default values : - A default ThreadFactory
     * - All events are accepted
     *
     * @param corePoolSize
     *            The initial pool sizePoolSize
     * @param maximumPoolSize
     *            The maximum pool size
     * @param keepAliveTime
     *            Default duration for a thread
     * @param unit
     *            Time unit used for the keepAlive value
     */
    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, Executors.defaultThreadFactory(), null, null);
    }

    /**
     * Creates a default ThreadPool, with default values : - A default ThreadFactory
     *
     * @param corePoolSize
     *            The initial pool sizePoolSize
     * @param maximumPoolSize
     *            The maximum pool size
     * @param keepAliveTime
     *            Default duration for a thread
     * @param unit
     *            Time unit used for the keepAlive value
     * @param eventQueueHandler
     *            The queue used to store events
     */
    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            IoEventQueueHandler eventQueueHandler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, Executors.defaultThreadFactory(), eventQueueHandler,
                null);
    }

    /**
     * Creates a default ThreadPool, with default values : - A default ThreadFactory
     *
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     * @param threadFactory The factory used to create threads
     */
    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, threadFactory, null, null);
    }

    /**
     * Creates a new instance of a PrioritisedOrderedThreadPoolExecutor.
     *
     * @param corePoolSize The initial pool sizePoolSize
     * @param maximumPoolSize The maximum pool size
     * @param keepAliveTime Default duration for a thread
     * @param unit Time unit used for the keepAlive value
     * @param threadFactory The factory used to create threads
     * @param eventQueueHandler The queue used to store events
     * @param comparator The comparator used to prioritize the queue
     */
    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            ThreadFactory threadFactory, IoEventQueueHandler eventQueueHandler, Comparator<IoSession> comparator) {
        // We have to initialize the pool with default values (0 and 1) in order
        // to
        // handle the exception in a better way. We can't add a try {} catch()
        // {}
        // around the super() call.
        super(DEFAULT_INITIAL_THREAD_POOL_SIZE, 1, keepAliveTime, unit, new SynchronousQueue<Runnable>(), threadFactory,
                new AbortPolicy());

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

        // The comparator can be null.
        this.comparator = comparator;

        if (this.comparator == null) {
            this.waitingSessions = new LinkedBlockingQueue<>();
        } else {
            this.waitingSessions = new PriorityBlockingQueue<>();
        }
    }

    /**
     * Get the session's tasks queue.
     */
    private SessionQueue getSessionTasksQueue(IoSession session) {
        SessionQueue queue = (SessionQueue) session.getAttribute(TASKS_QUEUE);

        if (queue == null) {
            queue = new SessionQueue();
            SessionQueue oldQueue = (SessionQueue) session.setAttributeIfAbsent(TASKS_QUEUE, queue);

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
        // Ignore the request. It must always be AbortPolicy.
    }

    /**
     * Add a new thread to execute a task, if needed and possible. It depends on the
     * current pool size. If it's full, we do nothing.
     */
    private void addWorker() {
        synchronized (workers) {
            if (workers.size() >= super.getMaximumPoolSize()) {
                return;
            }

            // Create a new worker, and add it to the thread pool
            Worker worker = new Worker();
            Thread thread = getThreadFactory().newThread(worker);

            workers.add(worker);

            // As we have added a new thread, it's considered as idle.
            idleWorkers.incrementAndGet();

            // Now, we can start it.
            thread.start();

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
        if (!shutdown) {
            return false;
        }

        synchronized (workers) {
            return workers.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }

        shutdown = true;

        synchronized (workers) {
            for (int i = workers.size(); i > 0; i--) {
                waitingSessions.offer(EXIT_SIGNAL);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Runnable> shutdownNow() {
        shutdown();

        List<Runnable> answer = new ArrayList<>();
        SessionEntry entry;

        while ((entry = waitingSessions.poll()) != null) {
            if (entry == EXIT_SIGNAL) {
                waitingSessions.offer(EXIT_SIGNAL);
                Thread.yield(); // Let others take the signal.
                continue;
            }

            SessionQueue sessionTasksQueue = (SessionQueue) entry.getSession().getAttribute(TASKS_QUEUE);

            synchronized (sessionTasksQueue.tasksQueue) {

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
        if (shutdown) {
            rejectTask(task);
        }

        // Check that it's a IoEvent task
        checkTaskType(task);

        IoEvent event = (IoEvent) task;

        // Get the associated session
        IoSession session = event.getSession();

        // Get the session's queue of events
        SessionQueue sessionTasksQueue = getSessionTasksQueue(session);
        Queue<Runnable> tasksQueue = sessionTasksQueue.tasksQueue;

        boolean offerSession;

        // propose the new event to the event queue handler. If we
        // use a throttle queue handler, the message may be rejected
        // if the maximum size has been reached.
        boolean offerEvent = eventQueueHandler.accept(this, event);

        if (offerEvent) {
            // Ok, the message has been accepted
            synchronized (tasksQueue) {
                // Inject the event into the executor taskQueue
                tasksQueue.offer(event);

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

        if (offerSession) {
            // As the tasksQueue was empty, the task has been executed
            // immediately, so we can move the session to the queue
            // of sessions waiting for completion.
            waitingSessions.offer(new SessionEntry(session, comparator));
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
        SessionQueue sessionTasksQueue = (SessionQueue) session.getAttribute(TASKS_QUEUE);

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
                for (;;) {
                    IoSession session = fetchSession();

                    idleWorkers.decrementAndGet();

                    if (session == null) {
                        synchronized (workers) {
                            if (workers.size() > getCorePoolSize()) {
                                break;
                            }
                        }
                    }

                    if (session == EXIT_SIGNAL) {
                        break;
                    }

                    if (session != null) {
                        runTasks(getSessionTasksQueue(session));
                    }

                    idleWorkers.incrementAndGet();
                }
            } finally {
                synchronized (workers) {
                    workers.remove(this);
                    PriorityThreadPoolExecutor.this.completedTaskCount += completedTaskCount.get();
                    workers.notifyAll();
                }
            }
        }

        private IoSession fetchSession() {
            SessionEntry entry = null;
            long currentTime = System.currentTimeMillis();
            long deadline = currentTime + getKeepAliveTime(TimeUnit.MILLISECONDS);

            for (;;) {
                try {
                    long waitTime = deadline - currentTime;

                    if (waitTime <= 0) {
                        break;
                    }

                    try {
                        entry = waitingSessions.poll(waitTime, TimeUnit.MILLISECONDS);
                        break;
                    } finally {
                        if (entry != null) {
                            currentTime = System.currentTimeMillis();
                        }
                    }
                } catch (InterruptedException e) {
                    // Ignore.
                    continue;
                }
            }

            if (entry != null) {
                return entry.getSession();
            }
            return null;
        }

        private void runTasks(SessionQueue sessionTasksQueue) {
            for (;;) {
                Runnable task;
                Queue<Runnable> tasksQueue = sessionTasksQueue.tasksQueue;

                synchronized (tasksQueue) {
                    task = tasksQueue.poll();

                    if (task == null) {
                        sessionTasksQueue.processingCompleted = true;
                        break;
                    }
                }

                eventQueueHandler.polled(PriorityThreadPoolExecutor.this, (IoEvent) task);

                runTask(task);
            }
        }

        private void runTask(Runnable task) {
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
    private class SessionQueue {
        /** A queue of ordered event waiting to be processed */
        private final Queue<Runnable> tasksQueue = new ConcurrentLinkedQueue<>();

        /** The current task state */
        private boolean processingCompleted = true;
    }

    /**
     * A class used to preserve first-in-first-out order of sessions that have equal
     * priority.
     */
    static class SessionEntry implements Comparable<SessionEntry> {
        private final long seqNum;
        private final IoSession session;
        private final Comparator<IoSession> comparator;

        public SessionEntry(IoSession session, Comparator<IoSession> comparator) {
            if (session == null) {
                throw new IllegalArgumentException("session");
            }
            seqNum = seq.getAndIncrement();
            this.session = session;
            this.comparator = comparator;
        }

        public IoSession getSession() {
            return session;
        }

        public int compareTo(SessionEntry other) {
            if (other == this) {
                return 0;
            }

            if (other.session == this.session) {
                return 0;
            }

            // An exit signal should always be preferred.
            if (this == EXIT_SIGNAL) {
                return -1;
            }
            if (other == EXIT_SIGNAL) {
                return 1;
            }

            int res = 0;

            // If there's a comparator, use it to prioritise events.
            if (comparator != null) {
                res = comparator.compare(session, other.session);
            }

            // FIFO tiebreaker.
            if (res == 0) {
                res = (seqNum < other.seqNum ? -1 : 1);
            }

            return res;
        }
    }
}
