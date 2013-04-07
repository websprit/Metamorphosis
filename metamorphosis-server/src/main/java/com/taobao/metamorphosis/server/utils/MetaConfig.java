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
package com.taobao.metamorphosis.server.utils;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ini4j.Ini;
import org.ini4j.InvalidFileFormatException;
import org.ini4j.Profile.Section;

import com.googlecode.aviator.AviatorEvaluator;
import com.taobao.metamorphosis.server.exception.MetamorphosisServerStartupException;
import com.taobao.metamorphosis.utils.Config;
import com.taobao.metamorphosis.utils.DiamondUtils;
import com.taobao.metamorphosis.utils.ZkUtils.ZKConfig;


/**
 * ������������
 * 
 * @author boyan
 * @Date 2011-4-21
 * @author wuhua
 */
public class MetaConfig extends Config implements Serializable, MetaConfigMBean {
    static final long serialVersionUID = -1L;
    private int brokerId = 0;
    private String dataPath = System.getProperty("user.home") + File.separator + "meta";
    private int serverPort = 8123;
    private int dashboardHttpPort = 8120;
    private String hostName;
    private int numPartitions = 1;
    private int unflushThreshold = 1000;
    private int unflushInterval = 10000;
    private int maxSegmentSize = 1 * 1024 * 1024 * 1024;
    private int maxTransferSize = 1024 * 1024;

    private List<String> topics = new ArrayList<String>();

    // Async slave config
    private SlaveConfig slaveConfig;

    private int getProcessThreadCount = 10 * Runtime.getRuntime().availableProcessors();

    private int putProcessThreadCount = 10 * Runtime.getRuntime().availableProcessors();

    private ZKConfig zkConfig;

    private String diamondZKDataId = DiamondUtils.DEFAULT_ZK_DATAID;
    private String diamondZKGroup = "DEFAULT_GROUP";// Constants.DEFAULT_GROUP;

    // �ļ�ɾ������:"��������,�趨ֵ�б�"��Ĭ��Ϊ����7��
    private String deletePolicy = "delete,168";

    private ConcurrentHashMap<String/* topic */, TopicConfig> topicConfigMap =
            new ConcurrentHashMap<String, TopicConfig>();

    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    private long lastModified = -1;

    private String path;

    // �����������
    // ��󱣴��checkpoint��Ŀ����������̭���ϵ�
    private int maxCheckpoints = 3;

    // �Զ�checkpoint�����Ĭ��1Сʱ
    private long checkpointInterval = 60 * 60 * 1000L;

    // �������ʱʱ�������Ĭ��3���
    private int maxTxTimeoutTimerCapacity = 30000;
    // ������־ˢ�����ã�0��ʾ�ò���ϵͳ������1��ʾÿ��commit��ˢ�̣�2��ʾÿ��һ��ˢ��һ��
    private int flushTxLogAtCommit = 1;

    // �������ʱʱ�䣬Ĭ��һ����
    private int maxTxTimeoutInSeconds = 60;

    // ��־�洢Ŀ¼��Ĭ��ʹ��dataPath
    private String dataLogPath = this.dataPath;

    /**
     * ȫ�ֵ�ɾ��crontab���ʽ��ÿ������6�������6��ִ��
     */
    private String deleteWhen = "0 0 6,18 * * ?";

    /**
     * quartzʹ�õ��̳߳ش�С
     */
    private int quartzThreadCount = 5;

    // Added by dennis zhuang
    private long configFileChecksum;

    // added by dennis,2012-05-19
    private boolean acceptPublish = true;
    private boolean acceptSubscribe = true;

    private boolean stat;

    private boolean updateConsumerOffsets = Boolean.parseBoolean(System.getProperty("meta.get.tellMaxOffset", "false"));

    private boolean loadMessageStoresInParallel = false;


    public int getDashboardHttpPort() {
        return this.dashboardHttpPort;
    }


    public void setDashboardHttpPort(int dashboardHttpPort) {
        this.dashboardHttpPort = dashboardHttpPort;
    }


    public boolean isLoadMessageStoresInParallel() {
        return this.loadMessageStoresInParallel;
    }


    public void setLoadMessageStoresInParallel(boolean loadMessageStoresInParallel) {
        this.loadMessageStoresInParallel = loadMessageStoresInParallel;
    }


    public int getQuartzThreadCount() {
        return this.quartzThreadCount;
    }


    public boolean isAcceptPublish() {
        return this.acceptPublish;
    }


    public boolean isUpdateConsumerOffsets() {
        return this.updateConsumerOffsets;
    }


    public void setUpdateConsumerOffsets(boolean updateConsumerOffsets) {
        this.updateConsumerOffsets = updateConsumerOffsets;
    }


    public boolean isStat() {
        return this.stat;
    }


    public void setStat(boolean stat) {
        this.stat = stat;
    }


    public void setAcceptPublish(boolean acceptPublish) {
        this.acceptPublish = acceptPublish;
    }


    public boolean isAcceptSubscribe() {
        return this.acceptSubscribe;
    }


    public void setAcceptSubscribe(boolean acceptSubscribe) {
        this.acceptSubscribe = acceptSubscribe;
    }


    public SlaveConfig getSlaveConfig() {
        return this.slaveConfig;
    }


    public int getSlaveId() {
        return this.slaveConfig == null ? -1 : this.slaveConfig.getSlaveId();
    }


    public String getConfigFilePath() {
        return this.path;
    }


    public void setSlaveConfig(SlaveConfig slaveConfig) {
        this.slaveConfig = slaveConfig;
    }


    public void setQuartzThreadCount(final int quartzThreadCount) {
        this.quartzThreadCount = quartzThreadCount;
    }


    public int getMaxTxTimeoutTimerCapacity() {
        return this.maxTxTimeoutTimerCapacity;
    }


    public String getDeleteWhen() {
        return this.deleteWhen;
    }


    public void setDeleteWhen(final String deleteWhen) {
        this.deleteWhen = deleteWhen;
    }


    public void setMaxTxTimeoutTimerCapacity(final int maxTxTimeoutTimerCapacity) {
        this.maxTxTimeoutTimerCapacity = maxTxTimeoutTimerCapacity;
    }


    public int getMaxTxTimeoutInSeconds() {
        return this.maxTxTimeoutInSeconds;
    }


    public void setMaxTxTimeoutInSeconds(final int maxTxTimeoutInSeconds) {
        this.maxTxTimeoutInSeconds = maxTxTimeoutInSeconds;
    }


    public int getFlushTxLogAtCommit() {
        return this.flushTxLogAtCommit;
    }


    public void setFlushTxLogAtCommit(final int flushTxLogAtCommit) {
        this.flushTxLogAtCommit = flushTxLogAtCommit;
    }


    public int getMaxCheckpoints() {
        return this.maxCheckpoints;
    }


    public long getCheckpointInterval() {
        return this.checkpointInterval;
    }


    public void setCheckpointInterval(final long checkpointInterval) {
        this.checkpointInterval = checkpointInterval;
    }


    public void setMaxCheckpoints(final int maxCheckpoints) {
        this.maxCheckpoints = maxCheckpoints;
    }


    public long getLastModified() {
        return this.lastModified;
    }


    public void addPropertyChangeListener(final String propertyName, final PropertyChangeListener listener) {
        this.propertyChangeSupport.addPropertyChangeListener(propertyName, listener);
    }


    public void removePropertyChangeListener(final PropertyChangeListener listener) {
        this.propertyChangeSupport.removePropertyChangeListener(listener);
    }


    public String getDiamondZKDataId() {
        return this.diamondZKDataId;
    }


    public void setDiamondZKDataId(final String diamondZKDataId) {
        this.diamondZKDataId = diamondZKDataId;
    }


    public String getDiamondZKGroup() {
        return this.diamondZKGroup;
    }


    public void setDiamondZKGroup(final String diamondZKGroup) {
        this.diamondZKGroup = diamondZKGroup;
    }


    public void loadFromFile(final String path) {
        try {
            this.path = path;
            final File file = new File(path);
            if (!file.exists()) {
                throw new MetamorphosisServerStartupException("File " + path + " is not exists");
            }
            final Ini conf = this.createIni(file);
            this.populateAttributes(conf);
        }
        catch (final IOException e) {
            throw new MetamorphosisServerStartupException("Parse configuration failed,path=" + path, e);
        }
    }


    public void loadFromString(final String str) {
        try {
            StringReader reader = new StringReader(str);
            final Ini conf = new Ini(reader);
            this.populateAttributes(conf);
        }
        catch (final IOException e) {
            throw new MetamorphosisServerStartupException("Parse configuration failed,path=" + this.path, e);
        }
    }


    private Ini createIni(final File file) throws IOException, InvalidFileFormatException {
        final Ini conf = new Ini(file);
        this.lastModified = file.lastModified();
        this.configFileChecksum = org.apache.commons.io.FileUtils.checksumCRC32(file);
        this.propertyChangeSupport.firePropertyChange("configFileChecksum", null, null);
        return conf;
    }


    public long getConfigFileChecksum() {
        return this.configFileChecksum;
    }


    public void setConfigFileChecksum(long configFileChecksum) {
        this.configFileChecksum = configFileChecksum;
        this.propertyChangeSupport.firePropertyChange("configFileChecksum", null, null);
    }


    public String getDeletePolicy() {
        return this.deletePolicy;
    }


    public void setDeletePolicy(final String deletePolicy) {
        this.deletePolicy = deletePolicy;
    }


    public ZKConfig getZkConfig() {
        return this.zkConfig;
    }


    public List<String> getTopics() {
        return this.topics;
    }


    public void setTopics(final List<String> topics) {
        this.topics = topics;
    }


    public void setZkConfig(final ZKConfig zkConfig) {
        this.zkConfig = zkConfig;
    }


    public int getNumPartitions() {
        return this.numPartitions;
    }


    public void setNumPartitions(final int numPartitions) {
        this.numPartitions = numPartitions;
    }


    public int getBrokerId() {
        return this.brokerId;
    }


    public void setBrokerId(final int brokerId) {
        this.brokerId = brokerId;
    }


    public String getHostName() {
        return this.hostName;
    }


    public void setHostName(final String hostName) {
        this.hostName = hostName;
    }


    protected void populateAttributes(final Ini conf) {
        this.populateSystemConf(conf);
        this.populateZookeeperConfig(conf);
        this.populateTopicsConfig(conf);
    }


    private void populateTopicsConfig(final Ini conf) {
        final Set<String> set = conf.keySet();
        final List<String> newTopics = new ArrayList<String>();
        final ConcurrentHashMap<String/* topic */, TopicConfig> newTopicConfigMap =
                new ConcurrentHashMap<String, TopicConfig>();
        for (final String name : set) {
            // Is it a topic section?
            if (name != null && name.startsWith("topic=")) {
                final Section section = conf.get(name);
                final String topic = name.substring("topic=".length());

                final TopicConfig topicConfig = new TopicConfig(topic, this);
                Set<String> validKeySet = topicConfig.getFieldSet();
                Set<String> configKeySet = section.keySet();
                this.checkConfigKeys(configKeySet, validKeySet);

                if (StringUtils.isNotBlank(section.get("numPartitions"))) {
                    topicConfig.setNumPartitions(this.getInt(section, "numPartitions"));
                }
                if (StringUtils.isNotBlank(section.get("stat"))) {
                    topicConfig.setStat(Boolean.valueOf(section.get("stat")));
                }
                if (StringUtils.isNotBlank(section.get("deletePolicy"))) {
                    topicConfig.setDeletePolicy(section.get("deletePolicy"));
                }

                if (StringUtils.isNotBlank(section.get("deleteWhen"))) {
                    topicConfig.setDeleteWhen(section.get("deleteWhen"));
                }

                if (StringUtils.isNotBlank(section.get("dataPath"))) {
                    topicConfig.setDataPath(section.get("dataPath"));
                }

                if (StringUtils.isNotBlank(section.get("unflushInterval"))) {
                    topicConfig.setUnflushInterval(this.getInt(section, "unflushInterval"));
                }

                if (StringUtils.isNotBlank(section.get("unflushThreshold"))) {
                    topicConfig.setUnflushThreshold(this.getInt(section, "unflushThreshold"));
                }
                // added by dennis,2012-05-19
                if (!StringUtils.isBlank(section.get("acceptSubscribe"))) {
                    topicConfig.setAcceptSubscribe(this.getBoolean(section, "acceptSubscribe"));
                }
                if (!StringUtils.isBlank(section.get("acceptPublish"))) {
                    topicConfig.setAcceptPublish(this.getBoolean(section, "acceptPublish"));
                }

                // this.topicPartitions.put(topic, numPartitions);
                newTopicConfigMap.put(topic, topicConfig);
                newTopics.add(topic);
            }
        }
        Collections.sort(newTopics);
        if (!newTopicConfigMap.equals(this.topicConfigMap)) {
            this.topics = newTopics;
            this.topicConfigMap = newTopicConfigMap;
            this.propertyChangeSupport.firePropertyChange("topics", null, null);
            this.propertyChangeSupport.firePropertyChange("topicConfigMap", null, null);
        }

        this.propertyChangeSupport.firePropertyChange("unflushInterval", null, null);
    }


    private void populateZookeeperConfig(final Ini conf) {
        final Section zkConf = conf.get("zookeeper");
        Set<String> configKeySet = zkConf.keySet();
        Set<String> validKeySet = new ZKConfig().getFieldSet();
        validKeySet.addAll(this.getFieldSet());
        this.checkConfigKeys(configKeySet, validKeySet);
        if (StringUtils.isNotBlank(zkConf.get("diamondZKDataId"))) {
            this.diamondZKDataId = zkConf.get("diamondZKDataId");
        }
        if (StringUtils.isNotBlank(zkConf.get("diamondZKGroup"))) {
            this.diamondZKGroup = zkConf.get("diamondZKGroup");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkConnect"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkConnect = zkConf.get("zk.zkConnect");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkSessionTimeoutMs"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkSessionTimeoutMs = this.getInt(zkConf, "zk.zkSessionTimeoutMs");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkConnectionTimeoutMs"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkConnectionTimeoutMs = this.getInt(zkConf, "zk.zkConnectionTimeoutMs");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkSyncTimeMs"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkSyncTimeMs = this.getInt(zkConf, "zk.zkSyncTimeMs");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkEnable"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkEnable = this.getBoolean(zkConf, "zk.zkEnable");
        }
        if (!StringUtils.isBlank(zkConf.get("zk.zkRoot"))) {
            this.newZkConfigIfNull();
            this.zkConfig.zkRoot = zkConf.get("zk.zkRoot");
        }
    }


    private int getInt(final Section section, final String key, final int defaultValue) {
        final String value = section.get(key);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        else {
            final Long rt = (Long) AviatorEvaluator.execute(value);
            return rt.intValue();
        }
    }


    private int getInt(final Section section, final String key) {
        final String value = section.get(key);
        if (StringUtils.isBlank(value)) {
            throw new NullPointerException("Blank value for " + key);
        }
        else {
            final Long rt = (Long) AviatorEvaluator.execute(value);
            return rt.intValue();
        }
    }


    private boolean getBoolean(final Section section, final String key) {
        final String value = section.get(key);
        if (StringUtils.isBlank(value)) {
            throw new NullPointerException("Blank value for " + key);
        }
        else {
            final Boolean rt = (Boolean) AviatorEvaluator.execute(value);
            return rt;
        }
    }


    private long getLong(final Section section, final String key) {
        final String value = section.get(key);
        if (StringUtils.isBlank(value)) {
            throw new NullPointerException("Blank value for " + key);
        }
        else {
            final Long rt = (Long) AviatorEvaluator.execute(value);
            return rt.longValue();
        }
    }


    private void populateSystemConf(final Ini conf) {
        final Section sysConf = conf.get("system");

        Set<String> configKeySet = sysConf.keySet();
        Set<String> validKeySet = this.getFieldSet();
        this.checkConfigKeys(configKeySet, validKeySet);

        this.brokerId = this.getInt(sysConf, "brokerId");
        this.serverPort = this.getInt(sysConf, "serverPort", 8123);
        this.dashboardHttpPort = this.getInt(sysConf, "dashboardHttpPort", 8120);
        if (!StringUtils.isBlank(sysConf.get("dataPath"))) {
            this.setDataPath(sysConf.get("dataPath"));
        }
        if (!StringUtils.isBlank(sysConf.get("dataLogPath"))) {
            this.dataLogPath = sysConf.get("dataLogPath");
        }
        if (!StringUtils.isBlank(sysConf.get("hostName"))) {
            this.hostName = sysConf.get("hostName");
        }
        this.numPartitions = this.getInt(sysConf, "numPartitions");
        this.unflushThreshold = this.getInt(sysConf, "unflushThreshold");
        this.unflushInterval = this.getInt(sysConf, "unflushInterval");
        this.maxSegmentSize = this.getInt(sysConf, "maxSegmentSize");
        this.maxTransferSize = this.getInt(sysConf, "maxTransferSize");
        if (!StringUtils.isBlank(sysConf.get("getProcessThreadCount"))) {
            this.getProcessThreadCount = this.getInt(sysConf, "getProcessThreadCount");
        }
        if (!StringUtils.isBlank(sysConf.get("putProcessThreadCount"))) {
            this.putProcessThreadCount = this.getInt(sysConf, "putProcessThreadCount");
        }
        if (!StringUtils.isBlank(sysConf.get("deletePolicy"))) {
            this.deletePolicy = sysConf.get("deletePolicy");
        }
        if (!StringUtils.isBlank(sysConf.get("deleteWhen"))) {
            this.deleteWhen = sysConf.get("deleteWhen");
        }
        if (!StringUtils.isBlank(sysConf.get("quartzThreadCount"))) {
            this.quartzThreadCount = this.getInt(sysConf, "quartzThreadCount");
        }
        if (!StringUtils.isBlank(sysConf.get("maxCheckpoints"))) {
            this.maxCheckpoints = this.getInt(sysConf, "maxCheckpoints");
        }
        if (!StringUtils.isBlank(sysConf.get("checkpointInterval"))) {
            this.checkpointInterval = this.getLong(sysConf, "checkpointInterval");
        }
        if (!StringUtils.isBlank(sysConf.get("maxTxTimeoutTimerCapacity"))) {
            this.maxTxTimeoutTimerCapacity = this.getInt(sysConf, "maxTxTimeoutTimerCapacity");
        }
        if (!StringUtils.isBlank(sysConf.get("flushTxLogAtCommit"))) {
            this.flushTxLogAtCommit = this.getInt(sysConf, "flushTxLogAtCommit");
        }
        if (!StringUtils.isBlank(sysConf.get("maxTxTimeoutInSeconds"))) {
            this.maxTxTimeoutInSeconds = this.getInt(sysConf, "maxTxTimeoutInSeconds");
        }

        // added by dennis,2012-05-19
        if (!StringUtils.isBlank(sysConf.get("acceptSubscribe"))) {
            this.acceptSubscribe = this.getBoolean(sysConf, "acceptSubscribe");
        }
        if (!StringUtils.isBlank(sysConf.get("acceptPublish"))) {
            this.acceptPublish = this.getBoolean(sysConf, "acceptPublish");
        }
        // added by dennis,2012-06-21
        if (!StringUtils.isBlank(sysConf.get("stat"))) {
            this.stat = this.getBoolean(sysConf, "stat");
        }
        if (!StringUtils.isBlank(sysConf.get("updateConsumerOffsets"))) {
            this.updateConsumerOffsets = this.getBoolean(sysConf, "updateConsumerOffsets");
        }
        if (!StringUtils.isBlank(sysConf.get("loadMessageStoresInParallel"))) {
            this.loadMessageStoresInParallel = this.getBoolean(sysConf, "loadMessageStoresInParallel");
        }
    }


    /**
     * Reload topics configuration
     */
    @Override
    public void reload() {
        final File file = new File(this.path);
        if (file.lastModified() != this.lastModified) {
            try {
                log.info("Reloading topics......");
                final Ini conf = this.createIni(file);
                MetaConfig.this.populateTopicsConfig(conf);
                log.info("Reload topics successfully");
            }
            catch (final Exception e) {
                log.error("Reload config failed", e);
            }
        }
    }

    static final Log log = LogFactory.getLog(MetaConfig.class);


    private void newZkConfigIfNull() {
        if (this.zkConfig == null) {
            this.zkConfig = new ZKConfig();
        }
    }


    public MetaConfig() {
        super();
        MetaMBeanServer.registMBean(this, null);
    }


    public int getGetProcessThreadCount() {
        return this.getProcessThreadCount;
    }


    public void setGetProcessThreadCount(final int getProcessThreadCount) {
        this.getProcessThreadCount = getProcessThreadCount;
    }


    public int getPutProcessThreadCount() {
        return this.putProcessThreadCount;
    }


    public void setPutProcessThreadCount(final int putProcessThreadCount) {
        this.putProcessThreadCount = putProcessThreadCount;
    }


    public int getServerPort() {
        return this.serverPort;
    }


    public void setUnflushInterval(final int unflushInterval) {
        this.unflushInterval = unflushInterval;
    }


    public int getMaxTransferSize() {
        return this.maxTransferSize;
    }


    public void setMaxTransferSize(final int maxTransferSize) {
        this.maxTransferSize = maxTransferSize;
    }


    public void setMaxSegmentSize(final int maxSegmentSize) {
        this.maxSegmentSize = maxSegmentSize;
    }


    public int getUnflushInterval() {
        return this.unflushInterval;
    }


    public int getMaxSegmentSize() {
        return this.maxSegmentSize;
    }


    public void setUnflushThreshold(final int storeFlushThreshold) {
        this.unflushThreshold = storeFlushThreshold;
    }


    public int getUnflushThreshold() {
        return this.unflushThreshold;
    }


    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
    }


    public void setServerPort(final int serverPort) {
        this.serverPort = serverPort;
    }


    /**
     * У�������Ƿ���ȷ
     */
    public void verify() {
        if (this.getTopics().isEmpty()) {
            throw new MetamorphosisServerStartupException("Empty topics list");
        }
        ZKConfig zkconfig = this.zkConfig;
        if (zkconfig == null) {
            throw new IllegalStateException("Null zookeeper config");
        }
        if (StringUtils.isBlank(this.zkConfig.zkConnect)) {
            throw new IllegalArgumentException("Empty zookeeper servers");
        }
    }


    public void setDataPath(final String dataPath) {
        final String oldDataPath = this.dataPath;
        this.dataPath = dataPath;
        // ���dataLogPathû�иı������ôҲ��Ҫ��dataLogPathָ���µ�dataPath
        if (oldDataPath.equals(this.dataLogPath)) {
            this.dataLogPath = this.dataPath;
        }
    }


    public String getDataPath() {
        return this.dataPath;
    }


    public String getDataLogPath() {
        return this.dataLogPath;
    }


    public void setDataLogPath(final String dataLogPath) {
        this.dataLogPath = dataLogPath;
    }


    public boolean isSlave() {
        return this.getSlaveId() >= 0;
    }

    private final Map<String/* topic */, Set<Integer/* partition */>> closedPartitionMap =
            new CopyOnWriteMap<String, Set<Integer>>();


    @Override
    public void closePartitions(final String topic, final int start, final int end) {
        if (StringUtils.isBlank(topic) || !this.topics.contains(topic)) {
            log.warn("topic=[" + topic + "]Ϊ�ջ�δ����");
            return;
        }
        if (start < 0 || start > end) {
            log.warn("��ʼ������ķ����ŷǷ�,start=" + start + ",end=" + end);
            return;
        }

        for (int i = start; i <= end; i++) {
            this.closePartition(topic, i);
        }

    }


    private void closePartition(final String topic, final int partition) {
        Set<Integer> closedPartitions = this.closedPartitionMap.get(topic);
        if (closedPartitions == null) {
            closedPartitions = new HashSet<Integer>();
            this.closedPartitionMap.put(topic, closedPartitions);
        }
        if (closedPartitions.add(partition)) {
            log.info("close partition=" + partition + ",topic=" + topic);
        }
        else {
            log.info("partition=" + partition + " closed yet,topic=" + topic);
        }

    }


    public boolean isClosedPartition(final String topic, final int partition) {
        final Set<Integer> closedPartitions = this.closedPartitionMap.get(topic);
        return closedPartitions == null ? false : closedPartitions.contains(partition);
    }


    @Override
    public void openPartitions(final String topic) {
        final Set<Integer> partitions = this.closedPartitionMap.remove(topic);
        if (partitions == null || partitions.isEmpty()) {
            log.info("topic[" + topic + "] has no closed partitions");
        }
        else {
            log.info("open partitions " + partitions + ",topic=" + topic);
        }
    }


    public TopicConfig getTopicConfig(final String topic) {
        TopicConfig topicConfig = this.topicConfigMap.get(topic);
        if (topicConfig == null) {
            topicConfig = new TopicConfig(topic, this);
            TopicConfig old = this.topicConfigMap.putIfAbsent(topic, topicConfig);
            if (old != null) {
                topicConfig = old;
            }
        }
        return topicConfig;
    }


    public Map<String, TopicConfig> getTopicConfigMap() {
        return this.topicConfigMap;
    }
}