/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sdk.dataproxy;

import org.apache.inlong.sdk.dataproxy.exception.ProxySdkException;
import org.apache.inlong.sdk.dataproxy.sender.BaseSender;
import org.apache.inlong.sdk.dataproxy.sender.tcp.InLongTcpMsgSender;
import org.apache.inlong.sdk.dataproxy.sender.tcp.TcpMsgSenderConfig;
import org.apache.inlong.sdk.dataproxy.utils.ProxyUtils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton Message Sender Factory
 *
 * Used to define the singleton sender factory
 */
public class MsgSenderSingleFactory implements MsgSenderFactory {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicLong singletonRefCounter = new AtomicLong(0);
    private static BaseMsgSenderFactory baseMsgSenderFactory;

    public MsgSenderSingleFactory() {
        if (singletonRefCounter.incrementAndGet() == 1) {
            baseMsgSenderFactory = new BaseMsgSenderFactory(this, "iSingleFct");
            initialized.set(true);
        }
        while (!initialized.get()) {
            ProxyUtils.sleepSomeTime(50L);
        }
    }

    @Override
    public void shutdownAll() throws ProxySdkException {
        if (!initialized.get()) {
            throw new ProxySdkException("Please initialize the factory first!");
        }
        if (singletonRefCounter.decrementAndGet() > 0) {
            return;
        }
        baseMsgSenderFactory.close();
    }

    @Override
    public void removeClient(BaseSender msgSender) {
        if (msgSender == null
                || msgSender.getSenderFactory() == null
                || msgSender.getSenderFactory() != this) {
            return;
        }
        baseMsgSenderFactory.removeClient(msgSender);
    }

    @Override
    public int getMsgSenderCount() {
        return baseMsgSenderFactory.getMsgSenderCount();
    }

    @Override
    public InLongTcpMsgSender genTcpSenderByGroupId(
            TcpMsgSenderConfig configure) throws ProxySdkException {
        return genTcpSenderByGroupId(configure, null);
    }

    @Override
    public InLongTcpMsgSender genTcpSenderByGroupId(
            TcpMsgSenderConfig configure, ThreadFactory selfDefineFactory) throws ProxySdkException {
        if (!initialized.get()) {
            throw new ProxySdkException("Please initialize the factory first!");
        }
        ProxyUtils.validProxyConfigNotNull(configure);
        return baseMsgSenderFactory.genTcpSenderByGroupId(configure, selfDefineFactory);
    }

    @Override
    public InLongTcpMsgSender genTcpSenderByClusterId(
            TcpMsgSenderConfig configure) throws ProxySdkException {
        return genTcpSenderByClusterId(configure, null);
    }

    @Override
    public InLongTcpMsgSender genTcpSenderByClusterId(
            TcpMsgSenderConfig configure, ThreadFactory selfDefineFactory) throws ProxySdkException {
        if (!initialized.get()) {
            throw new ProxySdkException("Please initialize the factory first!");
        }
        ProxyUtils.validProxyConfigNotNull(configure);
        return baseMsgSenderFactory.genTcpSenderByClusterId(configure, selfDefineFactory);
    }
}
