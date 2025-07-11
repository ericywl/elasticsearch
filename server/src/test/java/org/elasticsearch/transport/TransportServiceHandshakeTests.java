/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.transport;

import org.apache.logging.log4j.Level;
import org.elasticsearch.Build;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeUtils;
import org.elasticsearch.cluster.node.VersionInformation;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.TransportVersionUtils;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.Netty4Transport;
import org.elasticsearch.transport.netty4.SharedGroupFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptySet;
import static org.elasticsearch.transport.AbstractSimpleTransportTestCase.IGNORE_DESERIALIZATION_ERRORS_SETTING;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

public class TransportServiceHandshakeTests extends ESTestCase {

    private static ThreadPool threadPool;
    private static final TimeValue timeout = TimeValue.MAX_VALUE;

    @BeforeClass
    public static void startThreadPool() {
        threadPool = new TestThreadPool(TransportServiceHandshakeTests.class.getSimpleName());
    }

    private final List<TransportService> transportServices = new ArrayList<>();

    private TransportService startServices(
        String nodeNameAndId,
        Settings settings,
        TransportVersion transportVersion,
        VersionInformation nodeVersion,
        TransportInterceptor transportInterceptor
    ) {
        TcpTransport transport = new Netty4Transport(
            settings,
            transportVersion,
            threadPool,
            new NetworkService(Collections.emptyList()),
            PageCacheRecycler.NON_RECYCLING_INSTANCE,
            new NamedWriteableRegistry(Collections.emptyList()),
            new NoneCircuitBreakerService(),
            new SharedGroupFactory(settings)
        );
        TransportService transportService = new MockTransportService(
            settings,
            transport,
            threadPool,
            transportInterceptor,
            (boundAddress) -> DiscoveryNodeUtils.builder(nodeNameAndId)
                .name(nodeNameAndId)
                .address(boundAddress.publishAddress())
                .roles(emptySet())
                .version(nodeVersion)
                .build(),
            null,
            Collections.emptySet(),
            nodeNameAndId
        );
        transportService.start();
        transportService.acceptIncomingRequests();
        transportServices.add(transportService);
        return transportService;
    }

    @After
    public void tearDown() throws Exception {
        for (TransportService transportService : transportServices) {
            transportService.close();
        }
        super.tearDown();
    }

    @AfterClass
    public static void terminateThreadPool() {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        // since static must set to null to be eligible for collection
        threadPool = null;
    }

    public void testConnectToNodeLight() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();

        TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersionUtils.randomCompatibleVersion(random()),
            new VersionInformation(
                VersionUtils.randomVersionBetween(random(), Version.CURRENT.minimumCompatibilityVersion(), Version.CURRENT),
                IndexVersions.MINIMUM_COMPATIBLE,
                IndexVersion.current()
            ),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder("")
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current())
            .build();
        try (
            Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                transportServiceA,
                discoveryNode,
                TestProfiles.LIGHT_PROFILE
            )
        ) {
            DiscoveryNode connectedNode = safeAwait(listener -> transportServiceA.handshake(connection, timeout, listener));
            assertNotNull(connectedNode);
            // the name and version should be updated
            assertEquals(connectedNode.getName(), "TS_B");
            assertEquals(connectedNode.getVersion(), transportServiceB.getLocalNode().getVersion());
            assertFalse(transportServiceA.nodeConnected(discoveryNode));
        }
    }

    public void testMismatchedClusterName() {

        TransportService transportServiceA = startServices(
            "TS_A",
            Settings.builder().put("cluster.name", "a").build(),
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            Settings.builder().put("cluster.name", "b").build(),
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder("")
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current())
            .build();
        try (
            Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                transportServiceA,
                discoveryNode,
                TestProfiles.LIGHT_PROFILE
            )
        ) {
            assertThat(
                safeAwaitFailure(
                    IllegalStateException.class,
                    DiscoveryNode.class,
                    listener -> transportServiceA.handshake(connection, timeout, listener)
                ).getMessage(),
                containsString(
                    "handshake with [" + discoveryNode + "] failed: remote cluster name [b] does not match local cluster name [a]"
                )
            );
        }
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testIncompatibleNodeVersions() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersions.MINIMUM_COMPATIBLE,
            new VersionInformation(
                VersionUtils.getPreviousVersion(Version.CURRENT.minimumCompatibilityVersion()),
                IndexVersions.MINIMUM_COMPATIBLE,
                IndexVersion.current()
            ),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder("")
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current())
            .build();
        try (
            Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                transportServiceA,
                discoveryNode,
                TestProfiles.LIGHT_PROFILE
            )
        ) {
            assertThat(
                safeAwaitFailure(
                    IllegalStateException.class,
                    DiscoveryNode.class,
                    listener -> transportServiceA.handshake(connection, timeout, listener)
                ).getMessage(),
                containsString(
                    "handshake with ["
                        + discoveryNode
                        + "] failed: remote node version ["
                        + transportServiceB.getLocalNode().getVersion()
                        + "] is incompatible with local node version ["
                        + Version.CURRENT
                        + "]"
                )
            );
        }
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testIncompatibleTransportVersions() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersionUtils.getPreviousVersion(TransportVersions.MINIMUM_COMPATIBLE),
            new VersionInformation(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current()),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder("")
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current())
            .build();
        assertThat(
            safeAwaitFailure(
                Transport.Connection.class,
                listener -> transportServiceA.openConnection(discoveryNode, TestProfiles.LIGHT_PROFILE, listener)
            ),
            instanceOf(ConnectTransportException.class)
        );
        // the error is exposed as a general connection exception, the actual message is in the logs
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testNodeConnectWithDifferentNodeId() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder(randomAlphaOfLength(10))
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(transportServiceB.getLocalNode().getVersionInformation())
            .build();
        assertThat(
            safeAwaitFailure(
                ConnectTransportException.class,
                Releasable.class,
                listener -> transportServiceA.connectToNode(discoveryNode, TestProfiles.LIGHT_PROFILE, listener)
            ).getMessage(),
            allOf(
                containsString("Connecting to [" + discoveryNode.getAddress() + "] failed"),
                containsString("expected to connect to [" + discoveryNode.descriptionWithoutAttributes() + "]"),
                containsString("found [" + transportServiceB.getLocalNode().descriptionWithoutAttributes() + "] instead"),
                containsString("Ensure that each node has its own distinct publish address"),
                containsString("routed to the correct node"),
                containsString("https://www.elastic.co/docs/reference/elasticsearch/configuration-reference/networking-settings")
            )
        );
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testRejectsMismatchedBuildHash() {
        final DisruptingTransportInterceptor transportInterceptorA = new DisruptingTransportInterceptor();
        final DisruptingTransportInterceptor transportInterceptorB = new DisruptingTransportInterceptor();
        transportInterceptorA.setModifyBuildHash(true);
        transportInterceptorB.setModifyBuildHash(true);
        final Settings settings = Settings.builder()
            .put("cluster.name", "a")
            .put(IGNORE_DESERIALIZATION_ERRORS_SETTING.getKey(), true) // suppress assertions to test production error-handling
            .build();
        final TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            transportInterceptorA
        );
        final TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            transportInterceptorB
        );
        final DiscoveryNode discoveryNode = DiscoveryNodeUtils.builder("")
            .address(transportServiceB.getLocalNode().getAddress())
            .roles(emptySet())
            .version(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current())
            .build();
        try (
            MockLog mockLog = MockLog.capture(TransportService.class);
            Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                transportServiceA,
                discoveryNode,
                TestProfiles.LIGHT_PROFILE
            )
        ) {
            mockLog.addExpectation(
                new MockLog.SeenEventExpectation(
                    "message",
                    TransportService.class.getCanonicalName(),
                    Level.WARN,
                    "which has an incompatible wire format"
                )
            );

            DiscoveryNode connectedNode = safeAwait(listener -> transportServiceA.handshake(connection, timeout, listener));
            assertNotNull(connectedNode);

            mockLog.awaitAllExpectationsMatched();
        }
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testAcceptsMismatchedServerlessBuildHash() {
        assumeTrue("Current build needs to be a snapshot", Build.current().isSnapshot());
        final DisruptingTransportInterceptor transportInterceptorA = new DisruptingTransportInterceptor();
        final DisruptingTransportInterceptor transportInterceptorB = new DisruptingTransportInterceptor();
        transportInterceptorA.setModifyBuildHash(true);
        transportInterceptorB.setModifyBuildHash(true);
        final Settings settings = Settings.builder()
            .put("cluster.name", "a")
            .put(IGNORE_DESERIALIZATION_ERRORS_SETTING.getKey(), true) // suppress assertions to test production error-handling
            .build();
        final TransportService transportServiceA = startServices(
            "TS_A",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            transportInterceptorA
        );
        final TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            TransportVersion.current(),
            VersionInformation.CURRENT,
            transportInterceptorB
        );
        AbstractSimpleTransportTestCase.connectToNode(transportServiceA, transportServiceB.getLocalNode(), TestProfiles.LIGHT_PROFILE);
        assertTrue(transportServiceA.nodeConnected(transportServiceB.getLocalNode()));
    }

    public void testAcceptsMismatchedBuildHashFromDifferentVersion() {
        final DisruptingTransportInterceptor transportInterceptorA = new DisruptingTransportInterceptor();
        final DisruptingTransportInterceptor transportInterceptorB = new DisruptingTransportInterceptor();
        transportInterceptorA.setModifyBuildHash(true);
        transportInterceptorB.setModifyBuildHash(true);
        final TransportService transportServiceA = startServices(
            "TS_A",
            Settings.builder().put("cluster.name", "a").build(),
            TransportVersion.current(),
            VersionInformation.CURRENT,
            transportInterceptorA
        );
        final TransportService transportServiceB = startServices(
            "TS_B",
            Settings.builder().put("cluster.name", "a").build(),
            TransportVersions.MINIMUM_COMPATIBLE,
            new VersionInformation(Version.CURRENT.minimumCompatibilityVersion(), IndexVersions.MINIMUM_COMPATIBLE, IndexVersion.current()),
            transportInterceptorB
        );
        AbstractSimpleTransportTestCase.connectToNode(transportServiceA, transportServiceB.getLocalNode(), TestProfiles.LIGHT_PROFILE);
        assertTrue(transportServiceA.nodeConnected(transportServiceB.getLocalNode()));
    }

    private static class DisruptingTransportInterceptor implements TransportInterceptor {

        private boolean modifyBuildHash;

        public void setModifyBuildHash(boolean modifyBuildHash) {
            this.modifyBuildHash = modifyBuildHash;
        }

        @Override
        public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(
            String action,
            Executor executor,
            boolean forceExecution,
            TransportRequestHandler<T> actualHandler
        ) {

            if (TransportService.HANDSHAKE_ACTION_NAME.equals(action)) {
                return (request, channel, task) -> actualHandler.messageReceived(request, new TransportChannel() {
                    @Override
                    public String getProfileName() {
                        return channel.getProfileName();
                    }

                    @Override
                    public void sendResponse(TransportResponse response) {
                        assertThat(response, instanceOf(TransportService.HandshakeResponse.class));
                        if (modifyBuildHash) {
                            final TransportService.HandshakeResponse handshakeResponse = (TransportService.HandshakeResponse) response;
                            channel.sendResponse(
                                new TransportService.HandshakeResponse(
                                    handshakeResponse.getVersion(),
                                    handshakeResponse.getBuildHash() + "-modified",
                                    handshakeResponse.getDiscoveryNode(),
                                    handshakeResponse.getClusterName()
                                )
                            );
                        } else {
                            channel.sendResponse(response);
                        }
                    }

                    @Override
                    public void sendResponse(Exception exception) {
                        channel.sendResponse(exception);

                    }
                }, task);
            } else {
                return actualHandler;
            }
        }
    }

}
