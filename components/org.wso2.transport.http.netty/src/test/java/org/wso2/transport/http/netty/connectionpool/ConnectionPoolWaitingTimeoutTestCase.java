package org.wso2.transport.http.netty.connectionpool;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.TransportsConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.message.HTTPConnectorUtil;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.HTTPConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.SendChannelIDServerInitializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tests the timeout for waiting for idle connection in connection pool.
 */
public class ConnectionPoolWaitingTimeoutTestCase {

    private HttpServer httpServer;
    private HttpClientConnector httpClientConnector;

    private static final int MAX_ACTIVE_CONNECTIONS = 2;
    private static final int MAX_WAIT_TIME_FOR_CONNECTION_POOL = 1000;


    @BeforeClass
    public void setup() {
        httpServer = TestUtil.startHTTPServer(TestUtil.HTTP_SERVER_PORT, new SendChannelIDServerInitializer(5000));

        HttpWsConnectorFactory connectorFactory = new HttpWsConnectorFactoryImpl();
        TransportsConfiguration transportsConfiguration = TestUtil.getConfiguration(
                "/simple-test-config" + File.separator + "netty-transports.yml");
        Map<String, Object> transportProperties = HTTPConnectorUtil.getTransportProperties(transportsConfiguration);
        transportProperties.put(Constants.MAX_ACTIVE_CONNECTIONS_PER_POOL, MAX_ACTIVE_CONNECTIONS);
        transportProperties.put(Constants.MAX_WAIT_FOR_CLIENT_CONNECTION_POOL, MAX_WAIT_TIME_FOR_CONNECTION_POOL);
        httpClientConnector = connectorFactory.createHttpClientConnector(transportProperties,
                HTTPConnectorUtil.getSenderConfiguration(transportsConfiguration, Constants.HTTP_SCHEME));
    }

    @Test
    public void testWaitingForConnectionTimeout() {
        try {
            int noOfRequests = 3;

            // Create countdown latches
            CountDownLatch[] countDownLatches = new CountDownLatch[noOfRequests];
            for (int i = 0; i < noOfRequests; i++) {
                countDownLatches[i] = new CountDownLatch(1);
            }

            // Send multiple requests asynchronously to force the creation of multiple connections
            HTTPConnectorListener[] responseListeners = new HTTPConnectorListener[noOfRequests];
            for (int i = 0; i < countDownLatches.length; i++) {
                responseListeners[i] = TestUtil.sendRequestAsync(countDownLatches[i], httpClientConnector);
            }

            // Wait for the response
            for (CountDownLatch countDownLatch : countDownLatches) {
                countDownLatch.await(10, TimeUnit.SECONDS);
            }

            // Check the responses.
            Throwable throwable = null;
            HashSet<String> channelIds = new HashSet<>();
            for (HTTPConnectorListener responseListener : responseListeners) {
                if (responseListener.getHttpErrorMessage() != null) {
                    if (throwable != null) {
                        Assert.assertTrue(false, "Cannot have more than one error");
                    }
                    throwable = responseListener.getHttpErrorMessage();
                } else {
                    String channelId = new BufferedReader(new InputStreamReader(
                            new HttpMessageDataStreamer(responseListener.getHttpResponseMessage()).getInputStream()))
                            .lines().collect(Collectors.joining("\n"));
                    channelIds.add(channelId);
                }
            }

            Assert.assertTrue(channelIds.size() <= MAX_ACTIVE_CONNECTIONS);
            Assert.assertTrue(throwable instanceof NoSuchElementException);
            Assert.assertEquals(throwable.getMessage(), Constants.TIMEOUT_WAITING_FOR_IDLE_CONNECTION);
        } catch (Exception e) {
            TestUtil.handleException("IOException occurred while running testMaxActiveConnectionsPerPool", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        TestUtil.cleanUp(new ArrayList<>(), httpServer);
    }

}
