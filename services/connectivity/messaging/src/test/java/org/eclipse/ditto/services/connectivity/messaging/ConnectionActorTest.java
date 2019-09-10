/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.ditto.services.connectivity.messaging.MockClientActor.mockClientActorPropsFactory;
import static org.eclipse.ditto.services.connectivity.messaging.TestConstants.INSTANT;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ConnectivityStatus;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.connectivity.Topic;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.utils.test.Retry;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.cleanup.CleanupPersistence;
import org.eclipse.ditto.signals.commands.cleanup.CleanupPersistenceResponse;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionNotAccessibleException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionUnavailableException;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.EnableConnectionLogs;
import org.eclipse.ditto.signals.commands.connectivity.modify.EnableConnectionLogsResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.ModifyConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.ModifyConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.ResetConnectionLogs;
import org.eclipse.ditto.signals.commands.connectivity.modify.ResetConnectionLogsResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.ResetConnectionMetrics;
import org.eclipse.ditto.signals.commands.connectivity.modify.ResetConnectionMetricsResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnection;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionLogs;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionLogsResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionMetrics;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionMetricsResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatus;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatusResponse;
import org.eclipse.ditto.signals.events.connectivity.ConnectionCreated;
import org.eclipse.ditto.signals.events.connectivity.ConnectionModified;
import org.eclipse.ditto.signals.events.connectivity.ConnectivityEvent;
import org.hamcrest.CoreMatchers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Status;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.cluster.sharding.ShardRegion;
import akka.japi.Creator;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;

/**
 * Unit test for {@link ConnectionActor}.
 */
public final class ConnectionActorTest extends WithMockServers {

    private static ActorSystem actorSystem;
    private static ActorRef pubSubMediator;
    private static ActorRef conciergeForwarder;
    private String connectionId;
    private CreateConnection createConnection;
    private CreateConnection createClosedConnection;
    private ModifyConnection modifyConnection;
    private ModifyConnection modifyClosedConnection;
    private DeleteConnection deleteConnection;
    private CreateConnectionResponse createConnectionResponse;
    private CreateConnectionResponse createClosedConnectionResponse;
    private ModifyConnectionResponse modifyConnectionResponse;
    private OpenConnection openConnection;
    private CloseConnection closeConnection;
    private CloseConnectionResponse closeConnectionResponse;
    private DeleteConnectionResponse deleteConnectionResponse;
    private RetrieveConnection retrieveConnection;
    private RetrieveConnectionStatus retrieveConnectionStatus;
    private ResetConnectionMetrics resetConnectionMetrics;
    private RetrieveConnectionResponse retrieveModifiedConnectionResponse;
    private RetrieveConnectionStatusResponse retrieveConnectionStatusOpenResponse;
    private ConnectionNotAccessibleException connectionNotAccessibleException;

    @BeforeClass
    public static void setUp() {
        actorSystem = ActorSystem.create("AkkaTestSystem", TestConstants.CONFIG);
        pubSubMediator = DistributedPubSub.get(actorSystem).mediator();
        conciergeForwarder = actorSystem.actorOf(TestConstants.ConciergeForwarderActorMock.props());
    }

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(actorSystem, scala.concurrent.duration.Duration.apply(5, TimeUnit.SECONDS), false);
    }

    @Before
    public void init() {
        connectionId = TestConstants.createRandomConnectionId();
        final Connection connection = TestConstants.createConnection(connectionId);
        final Connection closedConnection =
                TestConstants.createConnection(connectionId, ConnectivityStatus.CLOSED,
                        TestConstants.Sources.SOURCES_WITH_AUTH_CONTEXT);
        createConnection = CreateConnection.of(connection, DittoHeaders.empty());
        createClosedConnection = CreateConnection.of(closedConnection, DittoHeaders.empty());
        final Connection modifiedConnection =
                connection.toBuilder()
                        .failoverEnabled(false)
                        .targets(Collections.singletonList(TestConstants.Targets.MESSAGE_TARGET))
                        .build();
        modifyConnection = ModifyConnection.of(modifiedConnection, DittoHeaders.empty());
        modifyClosedConnection = ModifyConnection.of(closedConnection, DittoHeaders.empty());
        deleteConnection = DeleteConnection.of(connectionId, DittoHeaders.empty());
        createConnectionResponse = CreateConnectionResponse.of(connection, DittoHeaders.empty());
        createClosedConnectionResponse = CreateConnectionResponse.of(closedConnection, DittoHeaders.empty());
        modifyConnectionResponse = ModifyConnectionResponse.modified(connectionId, DittoHeaders.empty());
        openConnection = OpenConnection.of(connectionId, DittoHeaders.empty());
        closeConnection = CloseConnection.of(connectionId, DittoHeaders.empty());
        closeConnectionResponse = CloseConnectionResponse.of(connectionId, DittoHeaders.empty());
        deleteConnectionResponse = DeleteConnectionResponse.of(connectionId, DittoHeaders.empty());
        retrieveConnection = RetrieveConnection.of(connectionId, DittoHeaders.empty());
        retrieveConnectionStatus = RetrieveConnectionStatus.of(connectionId, DittoHeaders.empty());
        resetConnectionMetrics = ResetConnectionMetrics.of(connectionId, DittoHeaders.empty());
        retrieveModifiedConnectionResponse =
                RetrieveConnectionResponse.of(modifiedConnection.toJson(), DittoHeaders.empty());
        retrieveConnectionStatusOpenResponse =
                RetrieveConnectionStatusResponse.getBuilder(connectionId, DittoHeaders.empty())
                .connectionStatus(ConnectivityStatus.OPEN)
                .liveStatus(ConnectivityStatus.OPEN)
                .connectedSince(INSTANT)
                .clientStatus(asList(ConnectivityModelFactory.newClientStatus("client1", ConnectivityStatus.OPEN,
                                "connection is open", INSTANT)))
                .sourceStatus(asList(
                        ConnectivityModelFactory.newSourceStatus("client1",  ConnectivityStatus.OPEN, "source1", "consumer started"),
                        ConnectivityModelFactory.newSourceStatus("client1", ConnectivityStatus.OPEN, "source2", "consumer started")
                ))
                .targetStatus(asList(ConnectivityModelFactory.newTargetStatus("client1", ConnectivityStatus.OPEN, "target1",
                                "publisher started")))
                .build();
        connectionNotAccessibleException = ConnectionNotAccessibleException.newBuilder(connectionId).build();
    }

    @Test
    public void tryToSendOtherCommandThanCreateDuringInitialization() {
        new TestKit(actorSystem) {{
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder);

            underTest.tell(deleteConnection, getRef());

            final ConnectionNotAccessibleException expectedMessage =
                    ConnectionNotAccessibleException.newBuilder(connectionId).build();
            expectMsg(expectedMessage);
        }};
    }

    @Test
    public void manageConnection() {
        new ConnectionActorTestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSub.ref(),
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // close connection
            underTest.tell(closeConnection, getRef());
            probe.expectMsg(closeConnection);
            expectMsg(closeConnectionResponse);

            // delete connection
            underTest.tell(deleteConnection, getRef());
            expectMsg(deleteConnectionResponse);
            probe.expectNoMessage();
            expectTerminated(underTest);
        }};
    }

    @Test
    public void createConnectionAfterDeleted() {
        new TestKit(actorSystem) {{
            final TestProbe clientActorMock = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder,
                            (connection, concierge) -> MockClientActor.props(clientActorMock.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            clientActorMock.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // delete connection
            underTest.tell(deleteConnection, getRef());
            expectMsg(deleteConnectionResponse);

            // create connection again (while ConnectionActor is in deleted state)
            underTest.tell(createConnection, getRef());
            expectMsg(createConnectionResponse);
            clientActorMock.expectMsg(openConnection);
        }};
    }

    @Test
    public void openConnectionAfterDeletedFails() {
        new TestKit(actorSystem) {{
            final TestProbe clientActorMock = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder,
                            (connection, concierge) -> MockClientActor.props(clientActorMock.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            clientActorMock.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // delete connection
            underTest.tell(deleteConnection, getRef());
            expectMsg(deleteConnectionResponse);

            // open connection should fail
            underTest.tell(openConnection, getRef());
            expectMsg(connectionNotAccessibleException);
        }};
    }

    @Test
    public void createConnectionInClosedState() {
        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createClosedConnection, getRef());
            expectMsg(createClosedConnectionResponse);

            // assert that client actor is not called for closed connection
            probe.expectNoMessage();
        }};
    }

    @Test
    public void modifyConnectionInClosedState() {
        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // create connection
            underTest.tell(closeConnection, getRef());
            probe.expectMsg(closeConnection);
            expectMsg(closeConnectionResponse);

            // modify connection
            underTest.tell(modifyClosedConnection, getRef());
            // client actor is not informed about modification as it is not started
            probe.expectNoMessage();
            expectMsg(modifyConnectionResponse);
        }};
    }

    @Test
    public void retrieveMetricsInClosedStateDoesNotStartClientActor() {
        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createClosedConnection, getRef());
            expectMsg(createClosedConnectionResponse);
            probe.expectNoMessage();

            // retrieve metrics
            underTest.tell(RetrieveConnectionMetrics.of(connectionId, DittoHeaders.empty()), getRef());
            probe.expectNoMessage();

            final RetrieveConnectionMetricsResponse metricsResponse =
                    RetrieveConnectionMetricsResponse.getBuilder(connectionId, DittoHeaders.empty())
                            .connectionMetrics(ConnectivityModelFactory.emptyConnectionMetrics())
                            .sourceMetrics(ConnectivityModelFactory.emptySourceMetrics())
                            .targetMetrics(ConnectivityModelFactory.emptyTargetMetrics())
                            .build();
            expectMsg(metricsResponse);
        }};
    }

    @Test
    public void modifyConnectionClosesAndRestartsClientActor() {
        new ConnectionActorTestKit(actorSystem) {{
            final TestProbe mockClientProbe = TestProbe.apply(actorSystem);
            final TestProbe commandSender = TestProbe.apply(actorSystem);
            final AtomicReference<Connection> latestConnection = new AtomicReference<>();
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSub.ref(),
                            conciergeForwarder, (connection, concierge) -> {
                                latestConnection.set(connection);
                                return MockClientActor.props(mockClientProbe.ref());
                            });

            // create connection
            underTest.tell(createConnection, commandSender.ref());
            mockClientProbe.expectMsg(openConnection);
            commandSender.expectMsg(createConnectionResponse);

            final ActorRef clientActor = watch(mockClientProbe.sender());

            expectConnectionEvent(pubSub, ConnectionCreated.class);
            expectSubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS);

            // modify connection
            underTest.tell(modifyConnection, commandSender.ref());
            // modify triggers a CloseConnection
            mockClientProbe.expectMsg(CloseConnection.of(connectionId, DittoHeaders.empty()));

            expectConnectionEvent(pubSub, ConnectionModified.class);
            // unsubscribe is called for topics of unmodified connection
            expectUnsubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS);
            expectTerminated(clientActor);

            // and sends an open connection (if desired state is open)
            mockClientProbe.expectMsg(openConnection);
            // finally the response is sent
            commandSender.expectMsg(modifyConnectionResponse);

            // modified connection contains an additional target for messages
            expectSubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS, Topic.LIVE_MESSAGES);

            Awaitility.await().untilAtomic(latestConnection, CoreMatchers.is(modifyConnection.getConnection()));
        }};
    }

    @Test
    public void recoverOpenConnection() {
        new TestKit(actorSystem) {{

            final TestProbe mockClientProbe = TestProbe.apply(actorSystem);
            ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder,
                            (connection, concierge) -> MockClientActor.props(mockClientProbe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            expectMsg(createConnectionResponse);

            // stop actor
            getSystem().stop(underTest);
            expectTerminated(underTest);

            // recover actor
            underTest = Retry.untilSuccess(() ->
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder));

            // connection is opened after recovery -> client actor receives OpenConnection command
            mockClientProbe.expectMsg(OpenConnection.of(connectionId, DittoHeaders.empty()));

            // retrieve connection status
            underTest.tell(retrieveConnectionStatus, getRef());
            expectMsg(retrieveConnectionStatusOpenResponse);
        }};
    }

    @Test
    public void recoverModifiedConnection() {
        new ConnectionActorTestKit(actorSystem) {{

            ActorRef underTest = TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSub.ref(),
                    conciergeForwarder);
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            expectMsg(createConnectionResponse);

            expectConnectionEvent(pubSub, ConnectionCreated.class);
            expectSubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS);

            // modify connection
            underTest.tell(modifyConnection, getRef());
            expectMsg(modifyConnectionResponse);

            expectConnectionEvent(pubSub, ConnectionModified.class);
            expectUnsubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS);

            // stop actor
            getSystem().stop(underTest);
            expectTerminated(underTest);

            // recover actor
            underTest = TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                    conciergeForwarder);

            expectSubscribe(pubSub, Topic.TWIN_EVENTS, Topic.LIVE_EVENTS, Topic.LIVE_MESSAGES);

            // retrieve connection status
            underTest.tell(retrieveConnection, getRef());
            expectMsg(retrieveModifiedConnectionResponse);
        }};
    }

    @Test
    public void recoverClosedConnection() {
        new TestKit(actorSystem) {{
            ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder);
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            expectMsg(createConnectionResponse);

            // close connection
            underTest.tell(closeConnection, getRef());
            expectMsg(closeConnectionResponse);

            // stop actor
            getSystem().stop(underTest);
            expectTerminated(underTest);

            // recover actor
            underTest = Retry.untilSuccess(() ->
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder));

            // retrieve connection status
            underTest.tell(retrieveConnectionStatus, getRef());
            final RetrieveConnectionStatusResponse response = expectMsgClass(RetrieveConnectionStatusResponse.class);

            assertThat((Object) response.getConnectionStatus()).isEqualTo(ConnectivityStatus.CLOSED);
            assertThat(response.getSourceStatus()).isEmpty();
            assertThat(response.getTargetStatus()).isEmpty();
            assertThat(response.getClientStatus()).hasSize(1);
            assertThat((CharSequence) response.getClientStatus().get(0).getStatus()).isEqualTo(
                    ConnectivityStatus.CLOSED);
            assertThat(response.getClientStatus().get(0).getStatusDetails())
                    .contains(String.format("[%s] connection is closed", BaseClientState.DISCONNECTED));
        }};
    }

    @Test
    public void recoverDeletedConnection() {
        new TestKit(actorSystem) {{
            ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder);
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            expectMsg(createConnectionResponse);

            // delete connection
            underTest.tell(deleteConnection, getRef());
            expectMsg(deleteConnectionResponse);
            expectTerminated(underTest);

            // recover actor
            underTest = Retry.untilSuccess(() ->
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder));

            // retrieve connection status
            underTest.tell(retrieveConnectionStatus, getRef());
            expectMsg(connectionNotAccessibleException);
        }};
    }

    @Test
    public void exceptionDuringClientActorPropsCreation() {
        new TestKit(actorSystem) {{
            final Props connectionActorProps =
                    ConnectionActor.props(TestConstants.createRandomConnectionId(), pubSubMediator, conciergeForwarder,
                            (connection, conciergeForwarder) -> {
                                throw ConnectionConfigurationInvalidException.newBuilder("validation failed...")
                                        .build();
                            }, null);
            // create another actor because this it is stopped and we want to test if the child is terminated
            final TestKit parent = new TestKit(actorSystem);
            final ActorRef connectionActorRef = watch(parent.childActorOf(connectionActorProps));

            // create connection
            connectionActorRef.tell(createConnection, parent.getRef());
            parent.expectMsgClass(ConnectionSupervisorActor.ManualReset.class); // is sent after "empty" recovery

            // expect ConnectionConfigurationInvalidException sent to parent
            final Exception exception = parent.expectMsgClass(ConnectionConfigurationInvalidException.class);
            assertThat(exception).hasMessageContaining("validation failed...");

            parent.expectMsgClass(ShardRegion.Passivate.class);
            parent.send(parent.getLastSender(), PoisonPill.getInstance());

            // expect the connection actor is terminated
            expectTerminated(connectionActorRef);
        }};
    }

    @Test
    public void exceptionDueToCustomValidator() {
        new TestKit(actorSystem) {{
            final Props connectionActorProps =
                    ConnectionActor.props(TestConstants.createRandomConnectionId(), pubSubMediator,
                            conciergeForwarder, mockClientActorPropsFactory,
                            command -> {
                                throw ConnectionUnavailableException.newBuilder(connectionId)
                                        .dittoHeaders(command.getDittoHeaders())
                                        .message("not valid")
                                        .build();
                            });

            // create another actor because we want to test if the child is terminated
            final TestKit parent = new TestKit(actorSystem);
            final ActorRef connectionActorRef = watch(parent.childActorOf(connectionActorProps));

            // create connection
            connectionActorRef.tell(createConnection, parent.getRef());
            parent.expectMsgClass(ConnectionSupervisorActor.ManualReset.class); // is sent after "empty" recovery

            // expect ConnectionUnavailableException sent to parent
            final ConnectionUnavailableException exception =
                    parent.expectMsgClass(ConnectionUnavailableException.class);
            assertThat(exception).hasMessageContaining("not valid");

            parent.expectMsgClass(ShardRegion.Passivate.class);
            parent.send(parent.getLastSender(), PoisonPill.getInstance());

            // expect the connection actor is terminated
            expectTerminated(connectionActorRef);
        }};
    }

    @Test
    public void testThingEventWithAuthorizedSubjectExpectIsForwarded() {
        final Set<String> valid = Collections.singleton(TestConstants.Authorization.SUBJECT_ID);
        testForwardThingEvent(true, TestConstants.thingModified(valid));
    }

    @Test
    public void testThingEventIsForwardedToFilteredTarget() {
        final Connection connection = TestConstants.createConnection(connectionId,
                TestConstants.Targets.TARGET_WITH_PLACEHOLDER);

        // expect that address is still with placeholders (as replacement was moved to MessageMappingProcessorActor
        final Target expectedTarget = ConnectivityModelFactory.newTarget(TestConstants.Targets.TARGET_WITH_PLACEHOLDER,
                TestConstants.Targets.TARGET_WITH_PLACEHOLDER.getAddress(), null);

        final CreateConnection createConnection = CreateConnection.of(connection, DittoHeaders.empty());
        final Set<String> valid = Collections.singleton(TestConstants.Authorization.SUBJECT_ID);
        testForwardThingEvent(createConnection, true, TestConstants.thingModified(valid), expectedTarget);
    }

    @Test
    public void testThingEventWithUnauthorizedSubjectExpectIsNotForwarded() {
        final Set<String> invalid = Collections.singleton("iot:user");
        testForwardThingEvent(false, TestConstants.thingModified(invalid));
    }

    @Test
    public void testLiveMessageWithAuthorizedSubjectExpectIsNotForwarded() {
        final Set<String> valid = Collections.singleton(TestConstants.Authorization.SUBJECT_ID);
        testForwardThingEvent(false, TestConstants.sendThingMessage(valid));
    }

    @Test
    public void testResetConnectionMetrics() {
        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // reset metrics
            underTest.tell(resetConnectionMetrics, getRef());
            probe.expectMsg(resetConnectionMetrics);

            final ResetConnectionMetricsResponse resetResponse =
                    ResetConnectionMetricsResponse.of(connectionId, DittoHeaders.empty());
            expectMsg(resetResponse);
        }};
    }

    @Test
    public void testConnectionActorRespondsToCleanupCommand() {
        new TestKit(actorSystem) {{

            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // send cleanup command
            underTest.tell(
                    CleanupPersistence.of(ConnectionActor.PERSISTENCE_ID_PREFIX + connectionId, DittoHeaders.empty()),
                    getRef());
            expectMsg(CleanupPersistenceResponse.success(ConnectionActor.PERSISTENCE_ID_PREFIX + connectionId,
                    DittoHeaders.empty()));
        }};
    }

    @Test
    public void enableConnectionLogs() {
        final EnableConnectionLogs enableConnectionLogs = EnableConnectionLogs.of(connectionId, DittoHeaders.empty());
        final EnableConnectionLogsResponse enableConnectionLogsResponse =
                EnableConnectionLogsResponse.of(connectionId, enableConnectionLogs.getDittoHeaders());
        new TestKit(actorSystem) {{

            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // enable connection logs
            underTest.tell(enableConnectionLogs, getRef());
            probe.expectMsg(enableConnectionLogs);

            expectMsg(enableConnectionLogsResponse);

        }};
    }

    @Test
    public void retrieveLogsInClosedStateDoesNotStartClientActor() {
        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createClosedConnection, getRef());
            expectMsg(createClosedConnectionResponse);
            probe.expectNoMessage();

            // retrieve logs
            underTest.tell(RetrieveConnectionLogs.of(connectionId, DittoHeaders.empty()), getRef());
            probe.expectNoMessage();

            final RetrieveConnectionLogsResponse logsResponse =
                    RetrieveConnectionLogsResponse.of(connectionId,
                            Collections.emptyList(),
                            null,
                            null,
                            DittoHeaders.empty());
            expectMsg(logsResponse);
        }};
    }

    @Test
    public void retrieveLogsIsAggregated() {
        final Instant now = Instant.now();
        final RetrieveConnectionLogsResponse innerResponse = RetrieveConnectionLogsResponse.of(connectionId,
                TestConstants.Monitoring.LOG_ENTRIES,
                now.minusSeconds(312),
                now.plusSeconds(123),
                DittoHeaders.empty());

        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // retrieve logs
            final RetrieveConnectionLogs retrieveConnectionLogs =
                    RetrieveConnectionLogs.of(connectionId, DittoHeaders.empty());
            underTest.tell(retrieveConnectionLogs, getRef());
            probe.expectMsg(retrieveConnectionLogs);

            // send answer to aggregator actor
            final ActorRef aggregatorActor = probe.sender();
            probe.send(aggregatorActor, innerResponse);

            expectMsg(innerResponse);
        }};
    }

    @Test
    public void resetConnectionLogs() {
        final ResetConnectionLogs resetConnectionLogs = ResetConnectionLogs.of(connectionId, DittoHeaders.empty());
        final ResetConnectionLogsResponse expectedResponse =
                ResetConnectionLogsResponse.of(connectionId, resetConnectionLogs.getDittoHeaders());

        new TestKit(actorSystem) {{
            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // reset logs
            underTest.tell(resetConnectionLogs, getRef());
            probe.expectMsg(resetConnectionLogs);

            expectMsg(expectedResponse);
        }};
    }

    @Test
    public void enabledConnectionLogsAreEnabledAgainAfterModify() {
        final EnableConnectionLogs enableConnectionLogs = EnableConnectionLogs.of(connectionId, DittoHeaders.empty());
        final EnableConnectionLogsResponse enableConnectionLogsResponse =
                EnableConnectionLogsResponse.of(connectionId, enableConnectionLogs.getDittoHeaders());
        new TestKit(actorSystem) {{

            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // enable connection logs
            underTest.tell(enableConnectionLogs, getRef());
            probe.expectMsg(enableConnectionLogs);

            expectMsg(enableConnectionLogsResponse);

            // modify connection
            underTest.tell(modifyConnection, getRef());
            probe.expectMsg(closeConnection);
            probe.expectMsg(openConnection);
            expectMsg(modifyConnectionResponse);

            // expect the message twice, once for each client
            probe.expectMsg(enableConnectionLogs);
        }};
    }

    @Test
    public void disabledConnectionLogsAreNotEnabledAfterModify() {
        new TestKit(actorSystem) {{

            final TestProbe probe = TestProbe.apply(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, concierge) -> MockClientActor.props(probe.ref()));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            probe.expectMsg(openConnection);
            expectMsg(createConnectionResponse);

            // modify connection
            underTest.tell(modifyConnection, getRef());
            probe.expectMsg(closeConnection);
            probe.expectMsg(openConnection);
            expectMsg(modifyConnectionResponse);

            probe.expectNoMsg();
        }};
    }

    private void testForwardThingEvent(final boolean isForwarded, final Signal<?> signal) {
        testForwardThingEvent(createConnection, isForwarded, signal, TestConstants.Targets.TWIN_TARGET);
    }

    private void testForwardThingEvent(final CreateConnection createConnection, final boolean isForwarded,
            final Signal<?> signal, final Target expectedTarget) {
        new TestKit(actorSystem) {{
            final TestKit probe = new TestKit(actorSystem);
            final ActorRef underTest =
                    TestConstants.createConnectionSupervisorActor(connectionId, actorSystem, pubSubMediator,
                            conciergeForwarder, (connection, conciergeForwarder) -> TestActor.props(probe));
            watch(underTest);

            // create connection
            underTest.tell(createConnection, getRef());
            expectMsgClass(Object.class);

            underTest.tell(signal, getRef());

            if (isForwarded) {
                final OutboundSignal unmappedOutboundSignal =
                        probe.expectMsgClass(OutboundSignal.class);
                assertThat(unmappedOutboundSignal.getSource()).isEqualTo(signal);
                assertThat(unmappedOutboundSignal.getTargets()).isEqualTo(Collections.singletonList(expectedTarget));
            } else {
                probe.expectNoMessage();
            }
        }};
    }

    static class TestActor extends AbstractActor {

        private final TestKit probe;

        private TestActor(final TestKit probe) {
            this.probe = probe;
        }

        static Props props(final TestKit probe) {
            return Props.create(TestActor.class, new Creator<TestActor>() {
                private static final long serialVersionUID = 1L;

                @Override
                public TestActor create() {
                    return new TestActor(probe);
                }
            });
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(OpenConnection.class, cc -> sender().tell(new Status.Success("connected"), self()))
                    .matchAny(m -> probe.getRef().forward(m, context())).build();
        }
    }

    private void expectSubscribe(final TestProbe pubSub, final Topic... topics) {
        expectPubSubMsg(() -> pubSub.expectMsgClass(DistributedPubSubMediator.Subscribe.class).topic(), topics);
    }

    private void expectUnsubscribe(final TestProbe pubSub, final Topic... topics) {
        expectPubSubMsg(() -> pubSub.expectMsgClass(DistributedPubSubMediator.Unsubscribe.class).topic(), topics);
    }

    private void expectPubSubMsg(final Supplier<String> receivedTopic, final Topic... topics) {
        final List<String> expected = Stream.of(topics).map(Topic::getPubSubTopic).collect(Collectors.toList());
        for (int i = 0; i < topics.length; i++) {
            expected.remove(receivedTopic.get());
        }
        assertThat(expected).isEmpty();
    }

    private void expectConnectionEvent(final TestProbe pubSub,
            final Class<? extends ConnectivityEvent> expectedEventClass) {
        final DistributedPubSubMediator.Publish publish =
                pubSub.expectMsgClass(DistributedPubSubMediator.Publish.class);
        assertThat(publish.msg()).isInstanceOf(expectedEventClass);
    }

    static class ConnectionActorTestKit extends TestKit {

        final TestProbe pubSub;

        ConnectionActorTestKit(final ActorSystem system) {
            super(system);
            pubSub = new TestProbe(system, "pubSubMediator");
        }
    }
}
