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
package com.taobao.metamorphosis.client.producer;

import java.util.concurrent.TimeUnit;

import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.client.Shutdownable;
import com.taobao.metamorphosis.exception.MetaClientException;


/**
 * ��Ϣ�����ߣ��̰߳�ȫ���Ƽ�����
 * 
 * @author boyan
 * @Date 2011-4-21
 * 
 */
public interface MessageProducer extends Shutdownable {

    /**
     * ����topic���Ա�producer��zookeeper��ȡbroker�б����ӣ��ڷ�����Ϣǰ�����ȵ��ô˷���
     * 
     * @param topic
     */
    public void publish(String topic);


    /**
     * ���÷�����Ϣ��Ĭ��topic�������͵�message��topicû���ҵ�����broker�ͷ�����ʱ��ѡ�����Ĭ��topicָ����broker����
     * �����ñ��������Զ�publish��topic��
     * 
     * @param topic
     */
    public void setDefaultTopic(String topic);


    /**
     * ������Ϣ
     * 
     * @param message
     *            ��Ϣ����
     * 
     * @return ���ͽ��
     * @throws MetaClientException
     *             �ͻ����쳣
     * @throws InterruptedException
     *             ��Ӧ�ж�
     */
    public SendResult sendMessage(Message message) throws MetaClientException, InterruptedException;


    /**
     * �첽������Ϣ����ָ��ʱ���ڻص�callback,��ģʽ���޷�ʹ������
     * 
     * @param message
     * @param cb
     * @param time
     * @param unit
     * @since 1.4
     */
    public void sendMessage(Message message, SendMessageCallback cb, long time, TimeUnit unit);


    /**
     * �첽������Ϣ����Ĭ��ʱ���ڣ�3�룩�ص�callback����ģʽ���޷�ʹ������
     * 
     * @param message
     * @param cb
     * @since 1.4
     */
    public void sendMessage(Message message, SendMessageCallback cb);


    /**
     * ������Ϣ,�������ָ����ʱ����û�з��أ����׳��쳣
     * 
     * @param message
     *            ��Ϣ����
     * @param timeout
     *            ��ʱʱ��
     * @param unit
     *            ��ʱ��ʱ�䵥λ
     * @return ���ͽ��
     * @throws MetaClientException
     *             �ͻ����쳣
     * @throws InterruptedException
     *             ��Ӧ�ж�
     */
    public SendResult sendMessage(Message message, long timeout, TimeUnit unit) throws MetaClientException,
    InterruptedException;


    /**
     * �ر������ߣ��ͷ���Դ
     */
    @Override
    public void shutdown() throws MetaClientException;


    /**
     * ���ر������ߵķ���ѡ����
     * 
     * @return
     */
    public PartitionSelector getPartitionSelector();


    /**
     * ���ر������߷�����Ϣ�Ƿ�����,�����������ָ����ͬһ��partition����Ϣ���򡣴˷����Ѿ����������Ƿ���false
     * 
     * @return true��ʾ����
     */
    @Deprecated
    public boolean isOrdered();


    /**
     * ����һ�����񲢹�������ǰ�̣߳��������ڷ��͵���Ϣ����Ϊһ����Ԫ�ύ����������Ҫôȫ�����ͳɹ���Ҫôȫ��ʧ��
     * 
     * @throws MetaClientException
     *             ����Ѿ����������У����׳�TransactionInProgressException�쳣
     */
    public void beginTransaction() throws MetaClientException;


    /**
     * ��������ʱʱ�䣬������ʼ��ʱ����������趨ʱ�仹û���ύ���߻ع��������˽��������ع�������
     * 
     * @param seconds
     *            ����ʱʱ�䣬��λ����
     * @throws MetaClientException
     * @see #beginTransaction()
     * @see #rollback()
     * @see #commit()
     */
    public void setTransactionTimeout(int seconds) throws MetaClientException;


    /**
     * Set transaction command request timeout.default is five seconds.
     * 
     * @param time
     * @param timeUnit
     */
    public void setTransactionRequestTimeout(long time, TimeUnit timeUnit);


    /**
     * ���ص�ǰ���õ�����ʱʱ�䣬Ĭ��Ϊ0,��ʾ������ʱ
     * 
     * @return ����ʱʱ�䣬��λ����
     * @throws MetaClientException
     */
    public int getTransactionTimeout() throws MetaClientException;


    /**
     * �ع���ǰ�����������͵��κ���Ϣ���˷���������beginTransaction֮�����
     * 
     * @throws MetaClientException
     * @see #beginTransaction()
     */
    public void rollback() throws MetaClientException;


    /**
     * �ύ��ǰ���񣬽������ڷ��͵���Ϣ�־û����˷���������beginTransaction֮�����
     * 
     * @see #beginTransaction()
     * @throws MetaClientException
     */
    public void commit() throws MetaClientException;

}