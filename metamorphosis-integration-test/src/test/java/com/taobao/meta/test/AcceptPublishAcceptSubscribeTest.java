package com.taobao.meta.test;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.taobao.metamorphosis.client.MetaClientConfig;
import com.taobao.metamorphosis.client.MetaMessageSessionFactory;
import com.taobao.metamorphosis.exception.MetaClientException;


/**
 * meta���ɲ���_OneProducerOneConsumer
 * 
 * @author gongyangyu(gongyangyu@taobao.com)
 * 
 */
public class AcceptPublishAcceptSubscribeTest extends BaseMetaTest {

    private final String topic1 = "meta-test";

    private final String topic2 = "meta-test2";


    @Override
    @Before
    public void setUp() throws Exception {
        final MetaClientConfig metaClientConfig = new MetaClientConfig();
        metaClientConfig.setDiamondZKDataId(Utils.diamondZKDataId);
        this.sessionFactory = new MetaMessageSessionFactory(metaClientConfig);
        this.startServer("server4");
        System.out.println("before run");
    }


    @Test
    public void sendConsume() throws Exception {
        this.createProducer();
        this.producer.publish(this.topic1);
        this.producer.publish(this.topic2);
        // �����߱���ָ������
        this.createConsumer("group1");

        try {
            // ������Ϣ
            final int count = 5;
            this.sendMessage(count, "hello", this.topic1);

            // ���Ľ�����Ϣ����֤������ȷ
            this.subscribe(this.topic1, 1024 * 1024, count);

            // Send topic2 message failed;
            try {
                this.sendMessage(count, "hello", this.topic2);
                Assert.fail();
            }
            catch (MetaClientException e) {
                Assert.assertEquals(
                    "There is no aviable partition for topic meta-test2,maybe you don't publish it at first?",
                    e.getMessage());
            }
        }
        finally {
            this.producer.shutdown();
            this.consumer.shutdown();
        }

    }
}
