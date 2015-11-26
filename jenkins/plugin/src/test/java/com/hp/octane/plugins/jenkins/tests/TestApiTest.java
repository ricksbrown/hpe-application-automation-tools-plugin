// (C) Copyright 2003-2015 Hewlett-Packard Development Company, L.P.

package com.hp.octane.plugins.jenkins.tests;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.hp.mqm.client.LogOutput;
import com.hp.mqm.client.MqmRestClient;
import com.hp.octane.plugins.jenkins.ExtensionUtil;
import com.hp.octane.plugins.jenkins.actions.BuildActions;
import com.hp.octane.plugins.jenkins.client.JenkinsMqmRestClientFactory;
import com.hp.octane.plugins.jenkins.client.RetryModel;
import com.hp.octane.plugins.jenkins.client.TestEventPublisher;
import com.hp.octane.plugins.jenkins.utils.Utils;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Maven;
import hudson.tasks.junit.JUnitResultArchiver;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;

public class TestApiTest {

    private static JenkinsMqmRestClientFactory clientFactory;
    private static MqmRestClient restClient;
    private static TestQueue queue;
    private static TestDispatcher testDispatcher;
    private static AbstractBuild build;
    private static JenkinsRule.WebClient client;

    @ClassRule
    final public static JenkinsRule rule = new JenkinsRule();

    @BeforeClass
    public static void init() throws Exception {
        restClient = Mockito.mock(MqmRestClient.class);
        Mockito.when(restClient.postTestResult(Mockito.<File>any(), Mockito.eq(false))).thenReturn(10001l);
        Mockito.doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                LogOutput logOutput = (LogOutput) invocationOnMock.getArguments()[1];
                logOutput.setContentType("text/plain");
                OutputStream os = logOutput.getOutputStream();
                os.write("This is the log".getBytes("UTF-8"));
                os.flush();
                return null;
            }
        }).when(restClient).getTestResultLog(Mockito.eq(10001l), Mockito.<LogOutput>any());

        clientFactory = Mockito.mock(JenkinsMqmRestClientFactory.class);
        Mockito.when(clientFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(restClient);

        BuildActions buildActions = ExtensionUtil.getInstance(rule, BuildActions.class);
        buildActions._setMqmRestClientFactory(clientFactory);

        testDispatcher = ExtensionUtil.getInstance(rule, TestDispatcher.class);
        testDispatcher._setMqmRestClientFactory(clientFactory);
        queue = new TestQueue();
        testDispatcher._setTestResultQueue(queue);
        TestListener testListener = ExtensionUtil.getInstance(rule, TestListener.class);
        testListener._setTestResultQueue(queue);
        queue.waitForTicks(1); // needed to avoid occasional interaction with the client we just overrode (race condition)

        TestEventPublisher testEventPublisher = new TestEventPublisher();
        RetryModel retryModel = new RetryModel(testEventPublisher);
        testDispatcher._setRetryModel(retryModel);
        testDispatcher._setEventPublisher(testEventPublisher);

        // server needs to be configured in order for the processing to happen
        HtmlPage configPage = rule.createWebClient().goTo("configure");
        HtmlForm form = configPage.getFormByName("config");
        form.getInputByName("_.uiLocation").setValueAttribute("http://localhost:8008/ui/?p=1001/1002");
        form.getInputByName("_.username").setValueAttribute("username");
        form.getInputByName("_.password").setValueAttribute("password");
        rule.submit(form);

        FreeStyleProject project = rule.createFreeStyleProject("test-api-test");
        Maven.MavenInstallation mavenInstallation = rule.configureDefaultMaven();
        project.getBuildersList().add(new Maven("test", mavenInstallation.getName(), "helloWorld/pom.xml", null, "-Dmaven.test.failure.ignore=true"));
        project.getPublishersList().add(new JUnitResultArchiver("**/target/surefire-reports/*.xml"));
        project.setScm(new CopyResourceSCM("/helloWorldRoot"));
        build = TestUtils.runAndCheckBuild(project);

        // make sure dispatcher logic was executed
        queue.waitForTicks(3);

        client = rule.createWebClient();
    }

    @Test
    public void testXml() throws Exception {
        Page testResults = client.goTo("job/test-api-test/" + build.getNumber() + "/octane/tests/xml", "application/xml");
        TestUtils.matchTests(new TestResultIterable(new StringReader(testResults.getWebResponse().getContentAsString())), "test-api-test", Utils.timestampInUTC(build.getStartTimeInMillis()), TestUtils.helloWorldTests);
    }

    @Test
    public void testAudit() throws Exception {
        Page auditLog = client.goTo("job/test-api-test/" + build.getNumber() + "/octane/tests/audit", "application/json");
        JSONArray audits = JSONArray.fromObject(auditLog.getWebResponse().getContentAsString());
        Assert.assertEquals(1, audits.size());
        JSONObject audit = audits.getJSONObject(0);
        Assert.assertEquals(10001l, audit.getLong("id"));
        Assert.assertTrue(audit.getBoolean("pushed"));
        Assert.assertEquals("http://localhost:8008", audit.getString("location"));
        Assert.assertEquals("1001", audit.getString("sharedSpace"));
        Assert.assertNotNull(audit.getString("date"));
    }

    @Test
    public void testLog() throws InterruptedException, IOException, SAXException {
        Page publishLog = client.goTo("job/test-api-test/" + build.getNumber() + "/octane/tests/log", "text/plain");
        Assert.assertEquals("This is the log", publishLog.getWebResponse().getContentAsString());
    }
}
