/*
 * (C) 2007-2012 Alibaba Group Holding Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Authors:
 *   wuhua <wq163@163.com> , boyan <killme2008@gmail.com>
 */
package com.taobao.metamorphosis.client.consumer;

import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.core.util.ConcurrentHashSet;
import com.taobao.gecko.service.exception.NotifyRemotingException;
import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.MessageAccessor;
import com.taobao.metamorphosis.cluster.Partition;
import com.taobao.metamorphosis.exception.InvalidMessageException;
import com.taobao.metamorphosis.exception.MetaClientException;
import com.taobao.metamorphosis.utils.MetaStatLog;
import com.taobao.metamorphosis.utils.StatConstants;


/**
 * ��Ϣץȡ��������ʵ��
 * 
 * @author boyan(boyan@taobao.com)
 * @date 2011-9-13
 * 
 */
public class SimpleFetchManager implements FetchManager {

    private volatile boolean shutdown = false;

    private Thread[] fetchThreads;

    private FetchRequestRunner[] requestRunners;

    private volatile int fetchRequestCount;

    private FetchRequestQueue requestQueue;

    private final ConsumerConfig consumerConfig;

    private final InnerConsumer consumer;


    public SimpleFetchManager(final ConsumerConfig consumerConfig, final InnerConsumer consumer) {
        super();
        this.consumerConfig = consumerConfig;
        this.consumer = consumer;
    }


    @Override
    public int getFetchRequestCount() {
        return this.fetchRequestCount;
    }


    @Override
    public boolean isShutdown() {
        return this.shutdown;
    }


    @Override
    public void stopFetchRunner() throws InterruptedException {
        this.shutdown = true;
        // �ж���������
        if (this.fetchThreads != null) {
            for (int i = 0; i < this.fetchThreads.length; i++) {
                Thread thread = this.fetchThreads[i];
                FetchRequestRunner runner = this.requestRunners[i];
                if (thread != null) {
                    runner.shutdown();
                    thread.interrupt();
                    runner.interruptExecutor();
                    try {
                        thread.join(100);
                    }
                    catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

            }
        }
        // // �ȴ������������
        // if (this.requestQueue != null) {
        // while (this.requestQueue.size() != this.fetchRequestCount) {
        // Thread.sleep(50);
        // }
        // }
        this.fetchRequestCount = 0;
    }


    @Override
    public void resetFetchState() {
        this.requestQueue = new FetchRequestQueue();
        this.fetchThreads = new Thread[this.consumerConfig.getFetchRunnerCount()];
        this.requestRunners = new FetchRequestRunner[this.consumerConfig.getFetchRunnerCount()];
        for (int i = 0; i < this.fetchThreads.length; i++) {
            FetchRequestRunner runner = new FetchRequestRunner();
            this.requestRunners[i] = runner;
            this.fetchThreads[i] = new Thread(runner);
            this.fetchThreads[i].setName(this.consumerConfig.getGroup() + "Fetch-Runner-" + i);
        }

    }


    @Override
    public void startFetchRunner() {
        // ����������Ŀ����ֹͣ��ʱ��Ҫ���
        this.fetchRequestCount = this.requestQueue.size();
        this.shutdown = false;
        for (final Thread thread : this.fetchThreads) {
            thread.start();
        }

    }


    @Override
    public void addFetchRequest(final FetchRequest request) {
        this.requestQueue.offer(request);

    }


    FetchRequest takeFetchRequest() throws InterruptedException {
        return this.requestQueue.take();
    }

    static final Log log = LogFactory.getLog(SimpleFetchManager.class);

    class FetchRequestRunner implements Runnable {

        private static final int DELAY_NPARTS = 10;

        private volatile boolean stopped = false;


        void shutdown() {
            this.stopped = true;
        }


        @Override
        public void run() {
            while (!this.stopped) {
                try {
                    final FetchRequest request = SimpleFetchManager.this.requestQueue.take();
                    this.processRequest(request);
                }
                catch (final InterruptedException e) {
                    // take��Ӧ�жϣ�����
                }

            }
        }


        void processRequest(final FetchRequest request) {
            try {
                final MessageIterator iterator = SimpleFetchManager.this.consumer.fetch(request, -1, null);
                final MessageListener listener =
                        SimpleFetchManager.this.consumer.getMessageListener(request.getTopic());
                this.notifyListener(request, iterator, listener);
            }
            catch (final MetaClientException e) {
                this.updateDelay(request);
                this.LogAddRequest(request, e);
            }
            catch (final InterruptedException e) {
                this.reAddFetchRequest2Queue(request);
            }
            catch (final Throwable e) {
                this.updateDelay(request);
                this.LogAddRequest(request, e);
            }
        }

        private long lastLogNoConnectionTime;


        private void LogAddRequest(final FetchRequest request, final Throwable e) {
            if (e instanceof MetaClientException && e.getCause() instanceof NotifyRemotingException
                    && e.getMessage().contains("�޿�������")) {
                // ���30���ӡһ��
                final long now = System.currentTimeMillis();
                if (this.lastLogNoConnectionTime <= 0 || now - this.lastLogNoConnectionTime > 30000) {
                    log.error("��ȡ��Ϣʧ��,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
                    this.lastLogNoConnectionTime = now;
                }
            }
            else {
                log.error("��ȡ��Ϣʧ��,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
            }
            this.reAddFetchRequest2Queue(request);
        }


        private void getOffsetAddRequest(final FetchRequest request, final InvalidMessageException e) {
            try {
                final long newOffset = SimpleFetchManager.this.consumer.offset(request);
                request.resetRetries();
                if (!this.stopped) {
                    request.setOffset(newOffset, request.getLastMessageId(), request.getPartitionObject().isAutoAck());
                }
            }
            catch (final MetaClientException ex) {
                log.error("��ѯoffsetʧ��,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
            }
            finally {
                this.reAddFetchRequest2Queue(request);
            }
        }


        public void interruptExecutor() {
            for (Thread thread : this.executorThreads) {
                if (!thread.isInterrupted()) {
                    thread.interrupt();
                }
            }
        }

        private final ConcurrentHashSet<Thread> executorThreads = new ConcurrentHashSet<Thread>();


        private void notifyListener(final FetchRequest request, final MessageIterator it, final MessageListener listener) {
            if (listener != null) {
                if (listener.getExecutor() != null) {
                    try {
                        listener.getExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                Thread currentThread = Thread.currentThread();
                                FetchRequestRunner.this.executorThreads.add(currentThread);
                                try {
                                    FetchRequestRunner.this.receiveMessages(request, it, listener);
                                }
                                finally {
                                    FetchRequestRunner.this.executorThreads.remove(currentThread);
                                }
                            }
                        });
                    }
                    catch (final RejectedExecutionException e) {
                        log.error(
                            "MessageListener�̳߳ط�æ���޷�������Ϣ,topic=" + request.getTopic() + ",partition="
                                    + request.getPartition(), e);
                        this.reAddFetchRequest2Queue(request);
                    }

                }
                else {
                    this.receiveMessages(request, it, listener);
                }
            }
        }


        private void reAddFetchRequest2Queue(final FetchRequest request) {
            if (!this.stopped) {
                SimpleFetchManager.this.addFetchRequest(request);
            }
        }


        /**
         * ������Ϣ���������̣�<br>
         * <ul>
         * <li>1.�ж��Ƿ�����Ϣ���Դ������û����Ϣ���������ݵ������Դ��������ж��Ƿ���Ҫ����maxSize</li>
         * <li>2.�ж���Ϣ�Ƿ����Զ�Σ���������趨����������������Ϣ���������ߡ���������Ϣ�����ڱ������Ի��߽���notify��Ͷ</li>
         * <li>3.������Ϣ�������̣������Ƿ��Զ�ack��������д���:
         * <ul>
         * <li>(1)�����Ϣ���Զ�ack��������ѷ����쳣�����޸�offset���ӳ����ѵȴ�����</li>
         * <li>(2)�����Ϣ���Զ�ack�������������������offset</li>
         * <li>(3)�����Ϣ���Զ�ack���������������ack����offset�޸�Ϊtmp offset��������tmp offset</li>
         * <li>(4)�����Ϣ���Զ�ack���������������rollback��������offset������tmp offset</li>
         * <li>(5)�����Ϣ���Զ�ack���������������ackҲ��rollback��������offset������tmp offset</li>
         * </ul>
         * </li>
         * </ul>
         * 
         * @param request
         * @param it
         * @param listener
         */
        private void receiveMessages(final FetchRequest request, final MessageIterator it,
                final MessageListener listener) {
            if (it != null && it.hasNext()) {
                if (this.processWhenRetryTooMany(request, it)) {
                    return;
                }
                final Partition partition = request.getPartitionObject();
                if (this.processReceiveMessage(request, it, listener, partition)) {
                    return;
                }
                this.postReceiveMessage(request, it, partition);
            }
            else {

                // ���Զ���޷���������ȡ�����ݣ�������Ҫ����maxSize
                if (SimpleFetchManager.this.isRetryTooManyForIncrease(request) && it != null && it.getDataLength() > 0) {
                    request.increaseMaxSize();
                    log.warn("���棬��" + request.getRetries() + "���޷���ȡtopic=" + request.getTopic() + ",partition="
                            + request.getPartitionObject() + "����Ϣ������maxSize=" + request.getMaxSize() + " Bytes");
                }

                // һ��Ҫ�ж�it�Ƿ�Ϊnull,����������������βʱ(����null)Ҳ������Retries����,�ᵼ���Ժ���������Ϣʱ����recover
                if (it != null) {
                    request.incrementRetriesAndGet();
                }

                this.updateDelay(request);
                this.reAddFetchRequest2Queue(request);
            }
        }


        /**
         * �����Ƿ���Ҫ���������Ĵ���
         * 
         * @param request
         * @param it
         * @param listener
         * @param partition
         * @return
         */
        private boolean processReceiveMessage(final FetchRequest request, final MessageIterator it,
                final MessageListener listener, final Partition partition) {
            int count = 0;
            while (it.hasNext()) {
                final int prevOffset = it.getOffset();
                try {
                    final Message msg = it.next();
                    MessageAccessor.setPartition(msg, partition);
                    listener.recieveMessages(msg);
                    // rollback message if it is in rollback only state.
                    if (MessageAccessor.isRollbackOnly(msg)) {
                        it.setOffset(prevOffset);
                        break;
                    }
                    if (partition.isAutoAck()) {
                        count++;
                    }
                    else {
                        // �ύ���߻ع�����������ѭ��
                        if (partition.isAcked()) {
                            count++;
                            break;
                        }
                        else if (partition.isRollback()) {
                            break;
                        }
                        else {
                            // �����ύҲ���ǻع�������������
                            count++;
                        }
                    }
                }
                catch (InterruptedException e) {
                    // Receive messages thread is interrupted
                    it.setOffset(prevOffset);
                    log.error("Process messages thread was interrupted,topic=" + request.getTopic() + ",partition="
                            + request.getPartition(), e);
                    break;
                }
                catch (final InvalidMessageException e) {
                    MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT, request.getTopic());
                    // ��Ϣ��Ƿ�����ȡ��Чoffset�����·����ѯ
                    this.getOffsetAddRequest(request, e);
                    return true;
                }
                catch (final Throwable e) {
                    // ��ָ���Ƶ���һ����Ϣ
                    it.setOffset(prevOffset);
                    log.error(
                        "Process messages failed,topic=" + request.getTopic() + ",partition=" + request.getPartition(),
                        e);
                    // ����ѭ����������Ϣ�쳣������Ϊֹ
                    break;
                }
            }
            MetaStatLog.addStatValue2(null, StatConstants.GET_MSG_COUNT_STAT, request.getTopic(), count);
            return false;
        }


        private boolean processWhenRetryTooMany(final FetchRequest request, final MessageIterator it) {
            if (SimpleFetchManager.this.isRetryTooMany(request)) {
                try {
                    final Message couldNotProecssMsg = it.next();
                    MessageAccessor.setPartition(couldNotProecssMsg, request.getPartitionObject());
                    MetaStatLog.addStat(null, StatConstants.SKIP_MSG_COUNT, couldNotProecssMsg.getTopic());
                    SimpleFetchManager.this.consumer.appendCouldNotProcessMessage(couldNotProecssMsg);
                }
                catch (final InvalidMessageException e) {
                    MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT, request.getTopic());
                    // ��Ϣ��Ƿ�����ȡ��Чoffset�����·����ѯ
                    this.getOffsetAddRequest(request, e);
                    return true;
                }
                catch (final Throwable t) {
                    this.LogAddRequest(request, t);
                    return true;
                }

                request.resetRetries();
                // �����������ܴ������Ϣ
                if (!this.stopped) {
                    request.setOffset(request.getOffset() + it.getOffset(), it.getPrevMessage().getId(), true);
                }
                // ǿ�������ӳ�Ϊ0
                request.setDelay(0);
                this.reAddFetchRequest2Queue(request);
                return true;
            }
            else {
                return false;
            }
        }


        private void postReceiveMessage(final FetchRequest request, final MessageIterator it, final Partition partition) {
            // ���offset��Ȼû��ǰ�����������Դ���
            if (it.getOffset() == 0) {
                request.incrementRetriesAndGet();
            }
            else {
                request.resetRetries();
            }

            // ���Զ�ackģʽ
            if (!partition.isAutoAck()) {
                // ����ǻع�,��ع�offset���ٴη�������
                if (partition.isRollback()) {
                    request.rollbackOffset();
                    partition.reset();
                    this.addRequst(request);
                }
                // ����ύ���������ʱoffset���洢
                else if (partition.isAcked()) {
                    partition.reset();
                    this.ackRequest(request, it, true);
                }
                else {
                    // �����ǣ�������ʱoffset
                    this.ackRequest(request, it, false);
                }
            }
            else {
                // �Զ�ackģʽ
                this.ackRequest(request, it, true);
            }
        }


        private void ackRequest(final FetchRequest request, final MessageIterator it, final boolean ack) {
            if (!this.stopped) {
                request.setOffset(request.getOffset() + it.getOffset(), it.getPrevMessage() != null ? it.getPrevMessage()
                        .getId() : -1, ack);
            }
            this.addRequst(request);
        }


        private void addRequst(final FetchRequest request) {
            final long delay = this.getRetryDelay(request);
            request.setDelay(delay);
            this.reAddFetchRequest2Queue(request);
        }


        private long getRetryDelay(final FetchRequest request) {
            final long maxDelayFetchTimeInMills = SimpleFetchManager.this.getMaxDelayFetchTimeInMills();
            final long nPartsDelayTime = maxDelayFetchTimeInMills / DELAY_NPARTS;
            // �ӳ�ʱ��Ϊ������ӳ�ʱ��/10*���Դ���
            long delay = nPartsDelayTime * request.getRetries();
            if (delay > maxDelayFetchTimeInMills) {
                delay = maxDelayFetchTimeInMills;
            }
            return delay;
        }


        // ��ʱ��ѯ
        private void updateDelay(final FetchRequest request) {
            final long delay = this.getNextDelay(request);
            request.setDelay(delay);
        }


        private long getNextDelay(final FetchRequest request) {
            final long maxDelayFetchTimeInMills = SimpleFetchManager.this.getMaxDelayFetchTimeInMills();
            // ÿ��1/10����,���MaxDelayFetchTimeInMills
            final long nPartsDelayTime = maxDelayFetchTimeInMills / DELAY_NPARTS;
            long delay = request.getDelay() + nPartsDelayTime;
            if (delay > maxDelayFetchTimeInMills) {
                delay = maxDelayFetchTimeInMills;
            }
            return delay;
        }

    }


    boolean isRetryTooMany(final FetchRequest request) {
        return request.getRetries() > this.consumerConfig.getMaxFetchRetries();
    }


    boolean isRetryTooManyForIncrease(final FetchRequest request) {
        return request.getRetries() > this.consumerConfig.getMaxIncreaseFetchDataRetries();
    }


    long getMaxDelayFetchTimeInMills() {
        return this.consumerConfig.getMaxDelayFetchTimeInMills();
    }

}