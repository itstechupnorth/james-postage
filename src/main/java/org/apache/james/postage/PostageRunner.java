/***********************************************************************
 * Copyright (c) 2006 The Apache Software Foundation.                  *
 * All rights reserved.                                                *
 * ------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License"); you *
 * may not use this file except in compliance with the License. You    *
 * may obtain a copy of the License at:                                *
 *                                                                     *
 *     http://www.apache.org/licenses/LICENSE-2.0                      *
 *                                                                     *
 * Unless required by applicable law or agreed to in writing, software *
 * distributed under the License is distributed on an "AS IS" BASIS,   *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or     *
 * implied.  See the License for the specific language governing       *
 * permissions and limitations under the License.                      *
 ***********************************************************************/


package org.apache.james.postage;

import org.apache.james.postage.configuration.PostageConfiguration;
import org.apache.james.postage.configuration.SendProfile;
import org.apache.james.postage.configuration.MailSender;
import org.apache.james.postage.result.PostageRunnerResultImpl;
import org.apache.james.postage.result.PostageRunnerResult;
import org.apache.james.postage.client.RemoteManagerClient;
import org.apache.james.postage.client.POP3Client;
import org.apache.james.postage.client.SMTPClient;
import org.apache.james.postage.execution.SampleController;
import org.apache.james.postage.smtpserver.SMTPMailSink;
import org.apache.james.postage.jmx.JVMResourceSampler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.io.File;

/**
 * central controlling class for the testing process. starts all workers, collects data and stops when time is out. 
 * relates to one and only one Scenario section from the configuration file.
 */
public class PostageRunner implements Runnable {

    private static Log log = LogFactory.getLog(PostageRunner.class);

    public static final int PHASE_CREATED    = 0;
    public static final int PHASE_STARTING   = 1;
    public static final int PHASE_RUNNING    = 2;
    public static final int PHASE_ABORTED    = 3;
    public static final int PHASE_COMPLETED  = 4;

    private int m_currentPhase = PHASE_CREATED;

    private final PostageConfiguration m_postageConfiguration;
    private final PostageRunnerResult m_results = new PostageRunnerResultImpl();

    private POP3Client m_inboundMailingChecker;
    private SampleController m_inboundMailingController;

    private SMTPMailSink m_smtpMailSink;
    private SampleController m_outboundMailingInterceptorController;

    private List m_sendControllers = new ArrayList();

    private JVMResourceSampler m_jvmResourceSampler = null;
    private SampleController m_jvmResourceController = null;

    private int  m_minutesRunning = 0;

    /**
     * sends messages to James in two ways:
     * 1. internal users relay to internal or external users using (inbound) SMTP
     * 2. external users send mail to internal users using (inbound) SMTP
     * 
     * the correct mail delivery is checked in two ways:
     * 1. by checking internal users mails using POP3
     * 2. by checking mail to external users by receiving all mail forwarded by James to outbound/forwarded SMTP
     *  
     * @param postageConfiguration
     */
    public PostageRunner(PostageConfiguration postageConfiguration) {
        m_postageConfiguration = postageConfiguration;
        
        int totalMailsPerMin = m_postageConfiguration.getTotalMailsPerMin();
        int durationMinutes = m_postageConfiguration.getDurationMinutes();
        
        m_postageConfiguration.addDescriptionItem("mails_per_min", "" + totalMailsPerMin);
        m_postageConfiguration.addDescriptionItem("totally_running_min", "" + durationMinutes);
        m_postageConfiguration.addDescriptionItem("totally_mails_target", "" + totalMailsPerMin * durationMinutes);
        
        m_results.setEnvironmentDescription(m_postageConfiguration.getDescriptionItems());
    }

    private void execute() {
        if (m_postageConfiguration != null) m_currentPhase = PHASE_STARTING;

        // do initialisation, check if all services can be connected
        try {
            setupInternalUserAccounts();
            setupExternalUserAccounts();
            setupInboundMailing();
            setupInboundMailingChecker();
            setupForwardedMailInterceptor();
            setupJMXRemoting();
            prepareResultFile(getCanonicalMailResultFileName());
            prepareResultFile(getCanonicalJVMStatisticsFileName());
            prepareResultFile(getCanonicalErrorsFileName());
        } catch (StartupException e) {
            log.fatal("could not even start the runner successfully", e);
            return;
        }


        m_currentPhase = PHASE_RUNNING;

        log.info("starting scenario " + m_postageConfiguration.getId());

        // fork the timeout controller thread. it issues the oneMinute checkpoint event, too.
        startTimer();

        // start all threads
        try {
            recordData();
        } catch (Exception e) {
            log.error("recording data was aborted!", e);
        }

        // has to be set by method stopRecording() 
        // m_currentPhase = PHASE_COMPLETED;

        // writeMatchedMailResults (remaining) collected data
        log.info("completing by writing data for scenario " + m_postageConfiguration.getId());
        writeData(false);
    }

    private void prepareResultFile(String canonicalMailResultFileName) {
        File writeCandidate = new File(canonicalMailResultFileName);
        if (writeCandidate.exists()) {
            // rename existing result file from previous run 
            // to something like "result___.cvs.64906993" to make place for new results
            writeCandidate.renameTo(new File(canonicalMailResultFileName + "." + writeCandidate.lastModified()));
        }
    }

    /**
     * for checking the running status of this PostageRunner.
     * @return one of the values PHASE_CREATED (0), PHASE_STARTING (1), PHASE_RUNNING (2), PHASE_ABORTED (3), PHASE_COMPLETED (4)
     */
    public int getCurrentPhase() {
        return m_currentPhase;
    }

    public void run() {
        execute();
    }

    public PostageRunnerResult getResult() {
        return m_results;
    }

    /**
     * set up a thread issueing one-minute events and finally shutting down data recording when time has run out.
     */
    private void startTimer() {
        new Thread(
                new Runnable() {
                    public void run() {
                        try {
                            int durationMinutes = m_postageConfiguration.getDurationMinutes();
                            log.info("running for " + durationMinutes + " minute(s)");
                            for (int i = 0; i < durationMinutes; i++) {
                                Thread.sleep(60*1000);
                                oneMinuteCheckpoint();
                            }
                            stopRecording();
                        } catch (InterruptedException e) {
                            ; // exit
                        }
                    }

                }
        ).start();
    }

    /**
     * called after each fully completed minute in the running phase
     */
    private void oneMinuteCheckpoint() {
        m_minutesRunning++;
        log.info("reached checkpoint after " + m_minutesRunning + " of " 
                  + m_postageConfiguration.getDurationMinutes() + " minute(s) running.");

        //TODO do this in a separate thread?
        writeData(true);
    }

    private void stopRecording() {
        log.info("stopping");
        if (m_sendControllers != null) {
            Iterator iterator = m_sendControllers.iterator();
            while (iterator.hasNext()) {
                SampleController sendController = (SampleController)iterator.next();
                sendController.stop();
            }
        }
        if (m_inboundMailingController != null) m_inboundMailingController.stop();
        
        if (m_outboundMailingInterceptorController != null) m_outboundMailingInterceptorController.stop();
        m_currentPhase = PHASE_COMPLETED;
    }

    /**
     * interrupt the runner from outside
     */
    public void terminate() {
        stopRecording();
        m_currentPhase = PHASE_ABORTED;
        writeData(false);
    }

    private void writeData(boolean flushMatchedMailOnly) {
        logElapsedData();

        String filenameMailResult = getCanonicalMailResultFileName();
        String filenameJVMStatistics = getCanonicalJVMStatisticsFileName();
        String filenameErrors = getCanonicalErrorsFileName();
        m_results.writeResults(filenameMailResult, filenameJVMStatistics, filenameErrors, flushMatchedMailOnly);
    }
                             
    public String getCanonicalMailResultFileName() {
        return "postage_mailResults." + m_postageConfiguration.getId() + ".csv";
    }

    public String getCanonicalJVMStatisticsFileName() {
        return "postage_jvmStatistics." + m_postageConfiguration.getId() + ".csv";
    }

    public String getCanonicalErrorsFileName() {
        return "postage_errors." + m_postageConfiguration.getId() + ".csv";
    }

    private void logElapsedData() {
        log.info("unmatched messages: " + m_results.getUnmatchedMails());
        log.info("matched messages:   " + m_results.getMatchedMails());
        log.info("recorded errors:    " + m_results.getErrorCount());
    }

    private void recordData() {

        Iterator iterator = m_sendControllers.iterator();
        while (iterator.hasNext()) {
            SampleController sendController = (SampleController)iterator.next();
            sendController.runThreaded();
        }

        m_inboundMailingController = new SampleController(m_inboundMailingChecker, m_postageConfiguration.getTestserverPOP3FetchesPerMinute());
        m_inboundMailingController.runThreaded();

        m_outboundMailingInterceptorController = new SampleController(m_smtpMailSink, 10, m_postageConfiguration.getTestserverSMTPForwardingWaitSeconds());
        m_outboundMailingInterceptorController.runThreaded();

        if (m_jvmResourceSampler != null) {
            m_jvmResourceController = new SampleController(m_jvmResourceSampler, 4);
            m_jvmResourceController.runThreaded();
        }

        while(m_currentPhase == PHASE_RUNNING) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                ; // leave
            }
        }

        if (m_currentPhase == PHASE_COMPLETED) {
            // walk through all internal users and check for un-matched mails  
            log.info("checking all internal accounts for unmatched mail...");
            m_inboundMailingChecker.doMatchMailForAllUsers();
            log.info("...done checking internal accounts");
        } else {
            // if we didn't COMPLETE, we'd better skip this
            log.info("skip checking internal accounts for unmatched mail.");
        }
    }

    private void setupExternalUserAccounts() {
        int externalUserCount = m_postageConfiguration.getExternalUsers().getCount();
        String externalUsernamePrefix = m_postageConfiguration.getExternalUsers().getNamePrefix();

        ArrayList externalUsers = new ArrayList();
        for (int i = 1; i <= externalUserCount; i++) {
            String username = externalUsernamePrefix + i;
            externalUsers.add(username);
        }
        m_postageConfiguration.getExternalUsers().setExistingUsers(externalUsers);
    }

    /**
     * sets up the profile where mail is send from external users to internal users
     * @throws StartupException
     */
    private void setupInboundMailing() throws StartupException {
        if (m_postageConfiguration.getTestserverPortSMTPInbound() <= 0) return;

        Iterator profileIterator = m_postageConfiguration.getProfiles().iterator();
        while (profileIterator.hasNext()) {
            SendProfile sendProfile = (SendProfile)profileIterator.next();
            Iterator mailSenderIterator = sendProfile.mailSenderIterator();
            while (mailSenderIterator.hasNext()) {
                MailSender mailSender = (MailSender)mailSenderIterator.next();
                int sendPerMinute = mailSender.getSendPerMinute();

                if (sendPerMinute < 1) continue;

                SMTPClient smtpClient = new SMTPClient(m_postageConfiguration.getTestserverHost(),
                        m_postageConfiguration.getTestserverPortSMTPInbound(),
                        m_postageConfiguration.getInternalUsers(),
                        m_postageConfiguration.getExternalUsers(),
                        m_results,
                        mailSender
                );

                boolean available = smtpClient.checkAvailability();
                log.info("availability of inbound mailing " + (available ? "": "NOT ") + "verified");
                if (!available) continue;

                SampleController sendController = new SampleController(smtpClient, sendPerMinute);
                m_sendControllers.add(sendController);
            }
        }

    }


    /**
     * sets up the part for checking accounts via POP3, which are then aligned with sent test mails
     * @throws StartupException
     */
    private void setupInboundMailingChecker() throws StartupException {
        if (m_postageConfiguration.getTestserverPortPOP3() <= 0) return;

        m_inboundMailingChecker = new POP3Client(m_postageConfiguration.getTestserverHost(),
                m_postageConfiguration.getTestserverPortPOP3(),
                m_postageConfiguration.getInternalUsers(),
                m_results
        );
        m_inboundMailingChecker.checkAvailability();
        boolean available = m_inboundMailingChecker.checkAvailability();
        if (available) {
            log.info("availability of checking for inbound mailing (POP3) verified");
        }
    }

    private void setupInternalUserAccounts() throws StartupException {
        try {
            String host = m_postageConfiguration.getTestserverHost();
            int remoteManagerPort = m_postageConfiguration.getTestserverRemoteManagerPort();
            String remoteManagerUsername = m_postageConfiguration.getTestserverRemoteManagerUsername();
            String remoteManagerPassword = m_postageConfiguration.getTestserverRemoteManagerPassword();
            int internalUserCount = m_postageConfiguration.getInternalUsers().getCount();
            String internalUsernamePrefix = m_postageConfiguration.getInternalUsers().getNamePrefix();
            String internalPassword = m_postageConfiguration.getInternalUsers().getPassword();

            Set existingUsers = getExistingUsers(host, remoteManagerPort, remoteManagerUsername, remoteManagerPassword);

            RemoteManagerClient remoteManagerClient = new RemoteManagerClient(host, remoteManagerPort, remoteManagerUsername, remoteManagerPassword);
            boolean loginSuccess = remoteManagerClient.login();
            ArrayList internalUsers = new ArrayList();
            for (int i = 1; i <= internalUserCount; i++) {
                String username = internalUsernamePrefix + i;
                if (existingUsers.contains(username)) {
                    log.info("user already exists: " + username);
                    if (!m_postageConfiguration.isInternalReuseExisting()) {
                        remoteManagerClient.executeCommand("deluser " + username);
                        List answers = remoteManagerClient.readAnswer();
                        addUser(remoteManagerClient, username, internalPassword);
                        answers = remoteManagerClient.readAnswer();
                        log.info("user deleted and re-created: " + username);
                    }
                    remoteManagerClient.executeCommand("setpassword " + username + " " + internalPassword);
                    List answers = remoteManagerClient.readAnswer();
                } else {
                    addUser(remoteManagerClient, username, internalPassword);
                }
                internalUsers.add(username);
            }
            m_postageConfiguration.getInternalUsers().setExistingUsers(internalUsers);
            remoteManagerClient.disconnect();
        } catch (Exception e) {
            throw new StartupException("error setting up internal user accounts", e);
        }
    }

    private void setupForwardedMailInterceptor() throws StartupException {
        SMTPMailSink smtpMailSink = new SMTPMailSink();
        smtpMailSink.setSmtpListenerPort(m_postageConfiguration.getTestserverPortSMTPForwarding());
        smtpMailSink.setResults(m_results);
        try {
            smtpMailSink.initialize();
        } catch (Exception e) {
            throw new StartupException("failed to setup");
        }
        m_smtpMailSink = smtpMailSink;
        log.info("forwarded mail interceptor is set up.");
    }


    private void setupJMXRemoting() throws StartupException {
        boolean jmxAvailable = JVMResourceSampler.isJMXAvailable();
        int jmxPort = m_postageConfiguration.getTestserverPortJMXRemoting();
        if (!jmxAvailable || jmxPort <= 0) {
            return;
        }
        JVMResourceSampler jvmResourceSampler = new JVMResourceSampler("localhost", jmxPort, m_results);
        try {
            jvmResourceSampler.connectRemoteJamesJMXServer();
            log.info("connected to remote JMX");
            m_jvmResourceSampler = jvmResourceSampler;
        } catch (Exception e) {
            throw new StartupException("failed to setup JMX remoting for JVM resource sampling", e);
        }
    }

    private void addUser(RemoteManagerClient remoteManagerClient, String username, String internalPassword) {
        remoteManagerClient.executeCommand("adduser " + username + " " + internalPassword);
        List answers = remoteManagerClient.readAnswer();
        log.info("user created: " + username);
    }

    /**
     * aquire a list of all existing internal James accounts 
     * @return Set<String>, each String a username
     */
    private Set getExistingUsers(String host, int remoteManagerPort, String remoteManagerUsername, String remoteManagerPassword) {
        RemoteManagerClient remoteManagerClient = new RemoteManagerClient(host, remoteManagerPort, remoteManagerUsername, remoteManagerPassword);
        boolean loginSuccess = remoteManagerClient.login();
        if (!loginSuccess) throw new Error("failed to login to remote manager");
        List rawUserList = remoteManagerClient.executeCommand("listusers");
        remoteManagerClient.disconnect();

        Set existingUsers = new LinkedHashSet();
        Iterator iterator = rawUserList.iterator();
        while (iterator.hasNext()) {
            String line = (String)iterator.next();
            if (!line.startsWith("user: ")) continue;

            existingUsers.add(line.substring(6));
        }
        return existingUsers;
    }

}