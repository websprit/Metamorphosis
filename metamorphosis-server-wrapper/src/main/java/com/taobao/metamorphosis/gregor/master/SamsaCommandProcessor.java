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
package com.taobao.metamorphosis.gregor.master;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.core.command.ResponseCommand;
import com.taobao.gecko.core.command.ResponseStatus;
import com.taobao.gecko.core.util.OpaqueGenerator;
import com.taobao.gecko.service.Connection;
import com.taobao.gecko.service.RemotingClient;
import com.taobao.gecko.service.RemotingServer;
import com.taobao.gecko.service.SingleRequestCallBackListener;
import com.taobao.gecko.service.exception.NotifyRemotingException;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.network.MetamorphosisWireFormatType;
import com.taobao.metamorphosis.network.PutCommand;
import com.taobao.metamorphosis.network.SyncCommand;
import com.taobao.metamorphosis.server.BrokerZooKeeper;
import com.taobao.metamorphosis.server.assembly.BrokerCommandProcessor;
import com.taobao.metamorphosis.server.assembly.ExecutorsManager;
import com.taobao.metamorphosis.server.network.PutCallback;
import com.taobao.metamorphosis.server.network.SessionContext;
import com.taobao.metamorphosis.server.stats.StatsManager;
import com.taobao.metamorphosis.server.store.AppendCallback;
import com.taobao.metamorphosis.server.store.Location;
import com.taobao.metamorphosis.server.store.MessageStore;
import com.taobao.metamorphosis.server.store.MessageStoreManager;
import com.taobao.metamorphosis.server.utils.MetaConfig;
import com.taobao.metamorphosis.utils.IdWorker;


/**
 * Master��broker command processor���ݲ�֧�������������
 * 
 * @author boyan(boyan@taobao.com)
 * @date 2011-12-14
 * 
 */
public class SamsaCommandProcessor extends BrokerCommandProcessor {

    /**
     * append��message store��callback
     * 
     * @author boyan(boyan@taobao.com)
     * @date 2011-12-7
     * 
     */
    public final class SyncAppendCallback implements AppendCallback, SingleRequestCallBackListener {
        private final int partition;
        private final String partitionString;
        private final PutCommand request;
        private final long messageId;
        private final PutCallback cb;
        private int respCount; // Ӧ�����
        private boolean masterSuccess; // master�Ƿ�ɹ�
        private boolean slaveSuccess; // slave�Ƿ�ɹ�
        private long appendOffset = -1L;; // ��ӵ�master��offset


        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.getOuterType().hashCode();
            result = prime * result + (int) (this.appendOffset ^ this.appendOffset >>> 32);
            result = prime * result + (this.cb == null ? 0 : this.cb.hashCode());
            result = prime * result + (int) (this.messageId ^ this.messageId >>> 32);
            result = prime * result + this.partition;
            result = prime * result + (this.partitionString == null ? 0 : this.partitionString.hashCode());
            result = prime * result + (this.request == null ? 0 : this.request.hashCode());
            return result;
        }


        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            final SyncAppendCallback other = (SyncAppendCallback) obj;
            if (!this.getOuterType().equals(other.getOuterType())) {
                return false;
            }
            if (this.appendOffset != other.appendOffset) {
                return false;
            }
            if (this.cb == null) {
                if (other.cb != null) {
                    return false;
                }
            }
            else if (!this.cb.equals(other.cb)) {
                return false;
            }
            if (this.messageId != other.messageId) {
                return false;
            }
            if (this.partition != other.partition) {
                return false;
            }
            if (this.partitionString == null) {
                if (other.partitionString != null) {
                    return false;
                }
            }
            else if (!this.partitionString.equals(other.partitionString)) {
                return false;
            }
            if (this.request == null) {
                if (other.request != null) {
                    return false;
                }
            }
            else if (!this.request.equals(other.request)) {
                return false;
            }

            return true;
        }


        public SyncAppendCallback(final int partition, final String partitionString, final PutCommand request,
                final long messageId, final PutCallback cb) {
            this.partition = partition;
            this.partitionString = partitionString;
            this.request = request;
            this.messageId = messageId;
            this.cb = cb;
        }


        /**
         * Master appendӦ��
         */
        @Override
        public void appendComplete(final Location location) {
            this.appendOffset = location.getOffset();
            if (location.isValid()) {
                synchronized (this) {
                    this.masterSuccess = true;
                }
            }
            this.tryComplete();
        }


        private synchronized void tryComplete() {
            if (this.respCount >= 2) {
                return;
            }
            this.respCount++;
            // ���߶�Ӧ���ˣ����Ը�producer����
            if (this.respCount == 2) {
                final String resultStr =
                        SamsaCommandProcessor.this
                        .genPutResultString(this.partition, this.messageId, this.appendOffset);
                // �������߶��ɹ�������£���Ϊ���ͳɹ�
                if (this.masterSuccess && this.slaveSuccess) {
                    if (this.cb != null) {
                        this.cb
                        .putComplete(new BooleanCommand(HttpStatus.Success, resultStr, this.request.getOpaque()));
                    }
                }
                else if (this.masterSuccess) {
                    SamsaCommandProcessor.this.statsManager.statsPutFailed(this.request.getTopic(),
                        this.partitionString, 1);
                    this.cb.putComplete(new BooleanCommand(HttpStatus.InternalServerError,
                        "Put message to slave failed", this.request.getOpaque()));
                }
                else if (this.slaveSuccess) {
                    SamsaCommandProcessor.this.statsManager.statsPutFailed(this.request.getTopic(),
                        this.partitionString, 1);
                    this.cb.putComplete(new BooleanCommand(HttpStatus.InternalServerError,
                        "Put message to master failed", this.request.getOpaque()));
                }
            }
        }


        @Override
        public void onResponse(final ResponseCommand responseCommand, final Connection conn) {
            // Slave��Ӧ�ɹ�
            if (responseCommand.getResponseStatus() == ResponseStatus.NO_ERROR) {
                synchronized (this) {
                    this.slaveSuccess = true;
                }
            }
            this.tryComplete();
        }


        @Override
        public void onException(final Exception e) {
            log.error("Put message to slave failed", e);
            this.slaveSuccess = false;
            this.tryComplete();
        }


        @Override
        public ThreadPoolExecutor getExecutor() {
            return SamsaCommandProcessor.this.callBackExecutor;
        }


        private SamsaCommandProcessor getOuterType() {
            return SamsaCommandProcessor.this;
        }
    }

    static final Log log = LogFactory.getLog(SamsaCommandProcessor.class);

    private RemotingClient remotingClient;

    private String slaveUrl;

    // ����Ӧ��ص����̳߳�,caller run����
    private ThreadPoolExecutor callBackExecutor;


    public SamsaCommandProcessor() {
        super();
    }


    public RemotingClient getRemotingClient() {
        return this.remotingClient;
    }


    void setRemotingClient(final RemotingClient remotingClient) {
        this.remotingClient = remotingClient;
    }


    public String getSlaveUrl() {
        return this.slaveUrl;
    }


    void setSlaveUrl(final String slaveUrl) {
        this.slaveUrl = slaveUrl;
    }


    public SamsaCommandProcessor(final MessageStoreManager storeManager, final ExecutorsManager executorsManager,
            final StatsManager statsManager, final RemotingServer remotingServer, final MetaConfig metaConfig,
            final IdWorker idWorker, final BrokerZooKeeper brokerZooKeeper, final RemotingClient remotingClient,
            final String slave, final int callbackThreadCount) throws NotifyRemotingException, InterruptedException {
        super(storeManager, executorsManager, statsManager, remotingServer, metaConfig, idWorker, brokerZooKeeper);
        this.slaveUrl = MetamorphosisWireFormatType.SCHEME + "://" + slave;
        this.remotingClient = remotingClient;
        this.callBackExecutor =
                new ThreadPoolExecutor(callbackThreadCount, callbackThreadCount, 60, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(10000), new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("Connecting to slave broker:" + this.slaveUrl);
        this.remotingClient.connect(this.slaveUrl);
        try {
            this.remotingClient.awaitReadyInterrupt(this.slaveUrl);
        }
        catch (final NotifyRemotingException e) {
            log.error("Connect to salve broker[" + this.slaveUrl + "] failed,it will retry to connect it.", e);
        }
    }


    /**
     * ����put����ֻ�е�master/slaveȫ��д��ɹ���ʱ�����Ϊд��ɹ�
     */
    @Override
    public void processPutCommand(final PutCommand request, final SessionContext sessionContext, final PutCallback cb) {
        final String partitionString = this.metaConfig.getBrokerId() + "-" + request.getPartition();
        this.statsManager.statsPut(request.getTopic(), partitionString, 1);
        this.statsManager.statsMessageSize(request.getTopic(), request.getData().length);
        try {
            if (this.metaConfig.isClosedPartition(request.getTopic(), request.getPartition())) {
                log.warn("Can not put message to partition " + request.getPartition() + " for topic="
                        + request.getTopic() + ",it was closed");
                if (cb != null) {
                    cb.putComplete(new BooleanCommand(HttpStatus.Forbidden, "Partition[" + partitionString
                        + "] has been closed", request.getOpaque()));
                }
                return;
            }
            // ����Ƕ�̬��ӵ�topic����Ҫע�ᵽzk
            this.brokerZooKeeper.registerTopicInZk(request.getTopic(), false);

            // ���slaveû�����ӣ����Ϸ���ʧ�ܣ���ֹmaster�ظ���Ϣ����
            if (!this.remotingClient.isConnected(this.slaveUrl)) {
                this.statsManager.statsPutFailed(request.getTopic(), partitionString, 1);
                cb.putComplete(new BooleanCommand(HttpStatus.InternalServerError, "Slave is disconnected ", request
                    .getOpaque()));
                return;
            }

            final int partition = this.getPartition(request);
            final MessageStore store = this.storeManager.getOrCreateMessageStore(request.getTopic(), partition);

            // �����store��ͬ������֤ͬһ�������ڵ���Ϣ����
            synchronized (store) {
                // idҲ��������
                final long messageId = this.idWorker.nextId();
                // ����callback
                final SyncAppendCallback syncCB =
                        new SyncAppendCallback(partition, partitionString, request, messageId, cb);
                // ����slave
                this.remotingClient.sendToGroup(this.slaveUrl,
                    new SyncCommand(request.getTopic(), partition, request.getData(), request.getFlag(), messageId,
                        request.getCheckSum(), OpaqueGenerator.getNextOpaque()), syncCB);
                // д��master
                store.append(messageId, request, syncCB);
            }
        }
        catch (final Exception e) {
            this.statsManager.statsPutFailed(request.getTopic(), partitionString, 1);
            log.error("Put message failed", e);
            if (cb != null) {
                cb.putComplete(new BooleanCommand(HttpStatus.InternalServerError, e.getMessage(), request.getOpaque()));
            }
        }
    }
}