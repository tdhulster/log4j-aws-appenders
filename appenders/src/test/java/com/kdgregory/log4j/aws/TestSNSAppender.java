// Copyright (c) Keith D Gregory, all rights reserved
package com.kdgregory.log4j.aws;

import static net.sf.kdgcommons.test.StringAsserts.*;

import java.net.URL;
import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.*;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import net.sf.kdgcommons.lang.StringUtil;

import com.amazonaws.services.sns.model.CreateTopicResult;

import com.kdgregory.log4j.aws.internal.shared.AbstractLogWriter;
import com.kdgregory.log4j.aws.internal.shared.DefaultThreadFactory;
import com.kdgregory.log4j.aws.internal.shared.LogMessage;
import com.kdgregory.log4j.aws.internal.shared.MessageQueue;
import com.kdgregory.log4j.aws.internal.shared.MessageQueue.DiscardAction;
import com.kdgregory.log4j.aws.internal.sns.SNSWriterConfig;
import com.kdgregory.log4j.testhelpers.HeaderFooterLayout;
import com.kdgregory.log4j.testhelpers.InlineThreadFactory;
import com.kdgregory.log4j.testhelpers.NullThreadFactory;
import com.kdgregory.log4j.testhelpers.TestingException;
import com.kdgregory.log4j.testhelpers.ThrowingWriterFactory;
import com.kdgregory.log4j.testhelpers.aws.sns.MockSNSClient;
import com.kdgregory.log4j.testhelpers.aws.sns.MockSNSWriter;
import com.kdgregory.log4j.testhelpers.aws.sns.MockSNSWriterFactory;
import com.kdgregory.log4j.testhelpers.aws.sns.TestableSNSAppender;


public class TestSNSAppender
{
    private Logger logger;
    private TestableSNSAppender appender;


    private void initialize(String propsName)
    throws Exception
    {
        URL config = ClassLoader.getSystemResource(propsName);
        PropertyConfigurator.configure(config);

        logger = Logger.getLogger(getClass());

        Logger rootLogger = Logger.getRootLogger();
        appender = (TestableSNSAppender)rootLogger.getAppender("default");

        appender.setThreadFactory(new InlineThreadFactory());
        appender.setWriterFactory(new MockSNSWriterFactory());
    }


    /**
     *  A spin loop that waits for an writer running in another thread to
     *  finish initialization. Times out after 5 seconds, otherwise returns
     *  the initialization message.
     */
    private String waitForInitialization() throws Exception
    {
        for (int ii = 0 ; ii < 50 ; ii++)
        {
            AbstractLogWriter writer = (AbstractLogWriter)appender.getWriter();
            if ((writer != null) && (writer.getInitializationMessage() != null))
                return writer.getInitializationMessage();
            else
                Thread.sleep(100);
        }
        fail("timed out waiting for initialization");
        return null; // never reached
    }


//----------------------------------------------------------------------------
//  Tests
//----------------------------------------------------------------------------

    @Test
    public void testConfigurationByName() throws Exception
    {
        initialize("TestSNSAppender/testConfigurationByName.properties");

        assertEquals("topicName",     "example",    appender.getTopicName());
        assertEquals("topicArn",      null,         appender.getTopicArn());
        assertEquals("batch delay",   1L,           appender.getBatchDelay());
    }


    @Test
    public void testConfigurationByArn() throws Exception
    {
        initialize("TestSNSAppender/testConfigurationByArn.properties");

        assertEquals("topicName",     null,         appender.getTopicName());
        assertEquals("topicArn",      "example",    appender.getTopicArn());
        assertEquals("batch delay",   1L,           appender.getBatchDelay());
    }


    @Test
    public void testAppend() throws Exception
    {
        initialize("TestSNSAppender/testAppend.properties");
        MockSNSWriterFactory writerFactory = (MockSNSWriterFactory)appender.getWriterFactory();

        assertNull("before messages, writer is null",                   appender.getMockWriter());

        logger.debug("first message");

        MockSNSWriter writer = appender.getMockWriter();

        assertNotNull("after message 1, writer is initialized",         writer);
        assertEquals("after message 1, calls to writer factory",        1,                  writerFactory.invocationCount);
        assertRegex("topic name",                                       "name-[0-9]{8}",    writer.config.topicName);
        assertRegex("topic ARN",                                        "arn-[0-9]{8}",     writer.config.topicArn);
        assertEquals("last message appended",                           "first message",    writer.lastMessage.getMessage());
        assertEquals("number of messages in writer queue",              1,                  writer.messages.size());
        assertEquals("first message in queue",                          "first message",    writer.messages.get(0).getMessage());

        logger.debug("second message");

        assertEquals("last message appended",                           "second message",   writer.lastMessage.getMessage());
        assertEquals("number of messages in writer queue",              2,                  writer.messages.size());
        assertEquals("first message in queue",                          "first message",    writer.messages.get(0).getMessage());
        assertEquals("second message in queue",                         "second message",   writer.messages.get(1).getMessage());
    }


    @Test(expected=IllegalStateException.class)
    public void testThrowsIfAppenderClosed() throws Exception
    {
        initialize("TestSNSAppender/testAppend.properties");

        // write the first message to initialize the appender
        logger.debug("should not throw");

        appender.close();

        // second message should throw
        logger.error("blah blah blah");
    }


    @Test
    public void testWriteHeaderAndFooter() throws Exception
    {
        initialize("TestSNSAppender/testWriteHeaderAndFooter.properties");

        logger.debug("message");

        // must retrieve writer before we shut down
        MockSNSWriter writer = appender.getMockWriter();
        LogManager.shutdown();

        assertEquals("number of messages written to log",   3,                          writer.messages.size());
        assertEquals("header is first",                     HeaderFooterLayout.HEADER,  writer.getMessage(0));
        assertEquals("message is second",                   "message",                  writer.getMessage(1));
        assertEquals("footer is last",                      HeaderFooterLayout.FOOTER,  writer.getMessage(2));
    }


    @Test
    public void testMaximumMessageSize() throws Exception
    {
        final int snsMaximumMessageSize     = 262144;       // from http://docs.aws.amazon.com/sns/latest/api/API_Publish.html
        final int layoutOverhead            = 1;            // newline after message

        final String undersizeMessage       = StringUtil.repeat('A', snsMaximumMessageSize - 1 - layoutOverhead);
        final String okMessage              = undersizeMessage + "A";
        final String oversizeMessage        = undersizeMessage + "\u00A1";

        initialize("TestSNSAppender/testAppend.properties");

        logger.debug("this message triggers writer configuration");

        assertFalse("under max size",          appender.isMessageTooLarge(new LogMessage(0, undersizeMessage)));
        assertFalse("at max size",             appender.isMessageTooLarge(new LogMessage(0, okMessage)));
        assertFalse("over max size",           appender.isMessageTooLarge(new LogMessage(0, oversizeMessage)));
    }


    @Test
    public void testWriterOperationByArn() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByArn.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "example", "bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message one");
        mockClient.waitForWriter();

        logger.info("message two");
        mockClient.waitForWriter();

        assertEquals("no initialization error",         "",             ((AbstractLogWriter)appender.getWriter()).getInitializationMessage());
        assertEquals("invocations of listTopics",       1,              mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",      0,              mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",          2,              mockClient.publishInvocationCount);
        assertEquals("last message published",          "message two",  mockClient.lastMessage);
    }


    @Test
    public void testWriterOperationByArnMultipleTopicLists() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByArn.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "example"), Arrays.asList("bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message one");
        mockClient.waitForWriter();

        assertEquals("no initialization error",         "",             ((AbstractLogWriter)appender.getWriter()).getInitializationMessage());
        assertEquals("invocations of listTopics",       2,              mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",      0,              mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",          1,              mockClient.publishInvocationCount);
        assertEquals("last message published",          "message one",  mockClient.lastMessage);
    }


    @Test
    public void testWriterOperationByArnWithNoExistingTopic() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByArn.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message");

        String initializationError = waitForInitialization();
        assertTrue("initialization error mentions topic name (was: " + initializationError + ")",
                   initializationError.contains("example"));

        assertEquals("invocations of listTopics",           1,          mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",          0,          mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",              0,          mockClient.publishInvocationCount);
    }


    @Test
    public void testWriterOperationByName() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByName.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "example", "bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message one");
        mockClient.waitForWriter();

        logger.info("message two");
        mockClient.waitForWriter();

        assertEquals("no initialization error",         "",             ((AbstractLogWriter)appender.getWriter()).getInitializationMessage());
        assertEquals("invocations of listTopics",       1,              mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",      0,              mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",          2,              mockClient.publishInvocationCount);
        assertEquals("last message published",          "message two",  mockClient.lastMessage);
    }


    @Test
    public void testWriterOperationByNameMultipleTopicLists() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByName.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "example"), Arrays.asList("bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message one");
        mockClient.waitForWriter();

        assertEquals("no initialization error",         "",             ((AbstractLogWriter)appender.getWriter()).getInitializationMessage());
        assertEquals("invocations of listTopics",       2,              mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",      0,              mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",          1,              mockClient.publishInvocationCount);
        assertEquals("last message published",          "message one",  mockClient.lastMessage);
    }


    @Test
    public void testWriterOperationByNameNoExistingTopic() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByName.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "bargle"));
        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        logger.info("message one");
        mockClient.waitForWriter();

        assertEquals("no initialization error",         "",             ((AbstractLogWriter)appender.getWriter()).getInitializationMessage());
        assertEquals("invocations of listTopics",       1,              mockClient.listTopicsInvocationCount);
        assertEquals("invocations of createTopic",      1,              mockClient.createTopicInvocationCount);
        assertEquals("invocations of publish",          1,              mockClient.publishInvocationCount);
        assertEquals("last message published",          "message one",  mockClient.lastMessage);
    }


    @Test
    public void testExceptionInInitializer() throws Exception
    {
        initialize("TestSNSAppender/testWriterOperationByName.properties");

        MockSNSClient mockClient = new MockSNSClient("example", Arrays.asList("argle", "bargle"))
        {
            @Override
            protected CreateTopicResult createTopic(String name)
            {
                throw new TestingException("arbitrary failure");
            }
        };

        appender.setWriterFactory(mockClient.newWriterFactory());
        appender.setThreadFactory(new DefaultThreadFactory());

        // first message triggers writer creation

        logger.info("message one");
        waitForInitialization();

        AbstractLogWriter writer = (AbstractLogWriter)appender.getWriter();
        MessageQueue messageQueue = appender.getMessageQueue();

        assertTrue("initialization message was non-blank",  ! writer.getInitializationMessage().equals(""));
        assertEquals("initialization exception retained",   TestingException.class,     writer.getInitializationException().getClass());
        assertEquals("message queue set to discard all",    0,                          messageQueue.getDiscardThreshold());
        assertEquals("message queue set to discard all",    DiscardAction.oldest,       messageQueue.getDiscardAction());
        assertEquals("messages in queue (initial)",         1,                          messageQueue.toList().size());

        // trying to log another message should clear the queue

        logger.info("message two");
        assertEquals("messages in queue (second try)",      0,                          messageQueue.toList().size());
    }


    @Test
    public void testUncaughtExceptionHandling() throws Exception
    {
        initialize("TestSNSAppender/testUncaughtExceptionHandling.properties");

        // note that we will be running the writer on a separate thread

        appender.setThreadFactory(new DefaultThreadFactory());
        appender.setWriterFactory(new ThrowingWriterFactory<SNSWriterConfig>());

        logger.debug("this should trigger writer creation");

        assertNull("writer has not yet thrown", appender.getLastWriterException());

        logger.debug("this should trigger writer throwage");

        // without getting really clever, the best way to wait for the throw to be reported is to sit and spin
        for (int ii = 0 ; (ii < 10) && (appender.getLastWriterException() == null) ; ii++)
        {
            Thread.sleep(10);
        }

        assertNull("writer has been reset",         appender.getMockWriter());
        assertEquals("last writer exception class", TestingException.class, appender.getLastWriterException().getClass());
    }


    @Test
    public void testReconfigureDiscardProperties() throws Exception
    {
        initialize("TestSNSAppender/testReconfigureDiscardProperties.properties");

        // another test where we don't actually do anything but need to verify actual writer

        appender.setThreadFactory(new NullThreadFactory());
        appender.setWriterFactory(new MockSNSClient("example", Arrays.asList("example")).newWriterFactory());

        logger.debug("trigger writer creation");

        MessageQueue messageQueue = appender.getMessageQueue();

        assertEquals("initial discard threshold, from appender",    12345,                              appender.getDiscardThreshold());
        assertEquals("initial discard action, from appender",       DiscardAction.newest.toString(),    appender.getDiscardAction());

        assertEquals("initial discard threshold, from queue",       12345,                              messageQueue.getDiscardThreshold());
        assertEquals("initial discard action, from queue",          DiscardAction.newest.toString(),    messageQueue.getDiscardAction().toString());

        appender.setDiscardThreshold(54321);
        appender.setDiscardAction(DiscardAction.oldest.toString());

        assertEquals("updated discard threshold, from appender",    54321,                              appender.getDiscardThreshold());
        assertEquals("updated discard action, from appender",       DiscardAction.oldest.toString(),    appender.getDiscardAction());

        assertEquals("updated discard threshold, from queue",       54321,                              messageQueue.getDiscardThreshold());
        assertEquals("updated discard action, from queue",          DiscardAction.oldest.toString(),    messageQueue.getDiscardAction().toString());
    }
}
