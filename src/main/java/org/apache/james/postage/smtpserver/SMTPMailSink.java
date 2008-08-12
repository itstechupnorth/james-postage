/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/


package org.apache.james.postage.smtpserver;

import org.apache.avalon.cornerstone.blocks.sockets.DefaultServerSocketFactory;
import org.apache.avalon.cornerstone.blocks.sockets.DefaultSocketFactory;
import org.apache.avalon.cornerstone.services.sockets.ServerSocketFactory;
import org.apache.avalon.cornerstone.services.sockets.SocketFactory;
import org.apache.avalon.cornerstone.services.sockets.SocketManager;
import org.apache.avalon.cornerstone.services.threads.ThreadManager;
import org.apache.avalon.excalibur.thread.impl.DefaultThreadPool;
import org.apache.avalon.framework.configuration.DefaultConfiguration;
import org.apache.avalon.framework.service.ServiceManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.excalibur.thread.ThreadPool;
import org.apache.james.api.dnsservice.DNSService;
import org.apache.james.dnsserver.DNSServer;
import org.apache.james.postage.SamplingException;
import org.apache.james.postage.execution.Sampler;
import org.apache.james.postage.result.PostageRunnerResult;
import org.apache.james.smtpserver.SMTPServer;
import org.apache.james.socket.JamesConnectionManager;
import org.apache.james.util.connection.SimpleConnectionManager;

/**
 * puts up a gateway SMTP server acting as a mail sink for the external mail sent out by James.<br/>
 * mails are catched, test mails are identified and tracked
 */
public class SMTPMailSink implements Sampler, SocketManager, ThreadManager {

    private static Log log = LogFactory.getLog(SMTPMailSink.class);

    private int m_smtpListenerPort = 2525;
    private SimpleMailServer m_mailServer = new SimpleMailServer();
    private SMTPServer m_smtpServer = new SMTPServer();

    public int getSmtpListenerPort() {
        return m_smtpListenerPort;
    }

    public void setSmtpListenerPort(int smtpListenerPort) {
        m_smtpListenerPort = smtpListenerPort;
    }

    public void setResults(PostageRunnerResult results) {
        m_mailServer.setResults(results);
    }
    public void initialize() throws Exception {
        m_smtpServer.enableLogging(new AvalonToPostageLogger());

        m_smtpServer.service(setUpServiceManager());

        SimpleSMTPServerConfiguration testConfiguration = new SimpleSMTPServerConfiguration(m_smtpListenerPort);
        testConfiguration.init();
        m_smtpServer.configure(testConfiguration);

        m_smtpServer.initialize();
    }

    private ServiceManager setUpServiceManager() throws Exception {
        SimpleServiceManager serviceManager = new SimpleServiceManager();
        SimpleConnectionManager connectionManager = new SimpleConnectionManager();
        connectionManager.enableLogging(new AvalonToPostageLogger());
        serviceManager.put(JamesConnectionManager.ROLE, connectionManager);
        serviceManager.put("org.apache.mailet.MailetContext", new TrivialMailContext());
        serviceManager.put("org.apache.james.services.MailServer", m_mailServer);
        serviceManager.put("org.apache.james.services.UsersRepository", null); //m_usersRepository);
        DNSServer server = new DNSServer();
        server.enableLogging(new AvalonToPostageLogger());
        server.configure(new DefaultConfiguration("dnsserver"));
        server.initialize();
                serviceManager.put(DNSService.ROLE, server);
        serviceManager.put(SocketManager.ROLE, this);
        serviceManager.put(ThreadManager.ROLE, this);
        return serviceManager;
    }

    public ServerSocketFactory getServerSocketFactory(String string) throws Exception {
        return new DefaultServerSocketFactory();
    }

    public SocketFactory getSocketFactory(String string) throws Exception {
        return new DefaultSocketFactory();
    }

    public ThreadPool getThreadPool(String string) throws IllegalArgumentException {
        return getDefaultThreadPool();
    }

    public ThreadPool getDefaultThreadPool() {
        try {
            DefaultThreadPool defaultThreadPool = new DefaultThreadPool(1);
            defaultThreadPool.enableLogging(new AvalonToPostageLogger());
            return defaultThreadPool;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void doSample() throws SamplingException {
        log.debug("sampling while mails are coming in.");
    }
}
