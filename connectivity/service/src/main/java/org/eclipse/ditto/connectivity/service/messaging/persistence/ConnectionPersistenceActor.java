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
package org.eclipse.ditto.connectivity.service.messaging.persistence;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.connectivity.api.ConnectivityMessagingConstants.CLUSTER_ROLE;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.headers.WithDittoHeaders;
import org.eclipse.ditto.base.model.json.JsonSchemaVersion;
import org.eclipse.ditto.base.model.signals.SignalWithEntityId;
import org.eclipse.ditto.base.model.signals.commands.Command;
import org.eclipse.ditto.base.model.signals.events.Event;
import org.eclipse.ditto.connectivity.api.BaseClientState;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.ConnectionLifecycle;
import org.eclipse.ditto.connectivity.model.ConnectionMetrics;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.signals.commands.ConnectivityCommand;
import org.eclipse.ditto.connectivity.model.signals.commands.ConnectivityCommandInterceptor;
import org.eclipse.ditto.connectivity.model.signals.commands.exceptions.ConnectionFailedException;
import org.eclipse.ditto.connectivity.model.signals.commands.exceptions.ConnectionNotAccessibleException;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.CheckConnectionLogsActive;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.CloseConnection;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.EnableConnectionLogs;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.OpenConnection;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.TestConnection;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.TestConnectionResponse;
import org.eclipse.ditto.connectivity.model.signals.commands.query.ConnectivityQueryCommand;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionLogs;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionLogsResponse;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionMetrics;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionMetricsResponse;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionStatus;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionStatusResponse;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionClosed;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionCreated;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionDeleted;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionModified;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionOpened;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectivityEvent;
import org.eclipse.ditto.connectivity.service.config.ConnectionConfig;
import org.eclipse.ditto.connectivity.service.config.ConnectionContextProvider;
import org.eclipse.ditto.connectivity.service.config.ConnectionContextProviderFactory;
import org.eclipse.ditto.connectivity.service.config.ConnectivityConfig;
import org.eclipse.ditto.connectivity.service.config.MonitoringConfig;
import org.eclipse.ditto.connectivity.service.config.MqttConfig;
import org.eclipse.ditto.connectivity.service.messaging.ClientActorPropsFactory;
import org.eclipse.ditto.connectivity.service.messaging.ClientActorRefs;
import org.eclipse.ditto.connectivity.service.messaging.amqp.AmqpValidator;
import org.eclipse.ditto.connectivity.service.messaging.httppush.HttpPushValidator;
import org.eclipse.ditto.connectivity.service.messaging.kafka.KafkaValidator;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.logs.ConnectionLogger;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.logs.ConnectionLoggerRegistry;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.logs.RetrieveConnectionLogsAggregatorActor;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.metrics.RetrieveConnectionMetricsAggregatorActor;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.metrics.RetrieveConnectionStatusAggregatorActor;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.Mqtt3Validator;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.Mqtt5Validator;
import org.eclipse.ditto.connectivity.service.messaging.persistence.stages.ConnectionAction;
import org.eclipse.ditto.connectivity.service.messaging.persistence.stages.ConnectionState;
import org.eclipse.ditto.connectivity.service.messaging.persistence.stages.StagedCommand;
import org.eclipse.ditto.connectivity.service.messaging.persistence.strategies.commands.ConnectionCreatedStrategies;
import org.eclipse.ditto.connectivity.service.messaging.persistence.strategies.commands.ConnectionDeletedStrategies;
import org.eclipse.ditto.connectivity.service.messaging.persistence.strategies.commands.ConnectionUninitializedStrategies;
import org.eclipse.ditto.connectivity.service.messaging.persistence.strategies.commands.ConnectivityCommandStrategies;
import org.eclipse.ditto.connectivity.service.messaging.persistence.strategies.events.ConnectionEventStrategies;
import org.eclipse.ditto.connectivity.service.messaging.rabbitmq.RabbitMQValidator;
import org.eclipse.ditto.connectivity.service.messaging.validation.CompoundConnectivityCommandInterceptor;
import org.eclipse.ditto.connectivity.service.messaging.validation.ConnectionValidator;
import org.eclipse.ditto.connectivity.service.messaging.validation.DittoConnectivityCommandValidator;
import org.eclipse.ditto.connectivity.service.util.ConnectivityMdcEntryKey;
import org.eclipse.ditto.internal.utils.akka.PingCommand;
import org.eclipse.ditto.internal.utils.akka.logging.CommonMdcEntryKey;
import org.eclipse.ditto.internal.utils.akka.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.cluster.DistPubSubAccess;
import org.eclipse.ditto.internal.utils.config.InstanceIdentifierSupplier;
import org.eclipse.ditto.internal.utils.persistence.mongo.config.ActivityCheckConfig;
import org.eclipse.ditto.internal.utils.persistence.mongo.config.SnapshotConfig;
import org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal;
import org.eclipse.ditto.internal.utils.persistentactors.AbstractShardedPersistenceActor;
import org.eclipse.ditto.internal.utils.persistentactors.EmptyEvent;
import org.eclipse.ditto.internal.utils.persistentactors.commands.CommandStrategy;
import org.eclipse.ditto.internal.utils.persistentactors.commands.DefaultContext;
import org.eclipse.ditto.internal.utils.persistentactors.events.EventStrategy;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.thingsearch.model.signals.commands.subscription.CreateSubscription;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.Status;
import akka.actor.SupervisorStrategy;
import akka.actor.Terminated;
import akka.cluster.Cluster;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.persistence.RecoveryCompleted;
import akka.routing.Broadcast;
import akka.routing.ConsistentHashingPool;
import akka.routing.ConsistentHashingRouter;
import akka.routing.Pool;

/**
 * Handles {@code *Connection} commands and manages the persistence of connection. The actual connection handling to the
 * remote server is delegated to a child actor that uses a specific client (AMQP 1.0 or 0.9.1).
 */
public final class ConnectionPersistenceActor
        extends AbstractShardedPersistenceActor<ConnectivityCommand<?>, Connection, ConnectionId, ConnectionState,
        ConnectivityEvent<?>> {

    /**
     * Prefix to prepend to the connection ID to construct the persistence ID.
     */
    public static final String PERSISTENCE_ID_PREFIX = "connection:";

    /**
     * The ID of the journal plugin this persistence actor uses.
     */
    public static final String JOURNAL_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-journal";

    /**
     * The ID of the snapshot plugin this persistence actor uses.
     */
    public static final String SNAPSHOT_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-snapshots";

    private static final Duration DEFAULT_RETRIEVE_STATUS_TIMEOUT = Duration.ofMillis(500L);
    private static final Duration GRACE_PERIOD_NOT_RETRIEVING_CONNECTION_STATUS_AFTER_RECOVERY = Duration.ofSeconds(10);
    private static final Duration SELF_RETRIEVE_CONNECTION_STATUS_TIMEOUT = Duration.ofSeconds(30);

    // never retry, just escalate. ConnectionSupervisorActor will handle restarting this actor
    private static final SupervisorStrategy ESCALATE_ALWAYS_STRATEGY = OneForOneEscalateStrategy.escalateStrategy();

    private final Cluster cluster;
    private final ActorRef proxyActor;
    private final ClientActorPropsFactory propsFactory;
    private final ActorRef pubSubMediator;
    private final boolean allClientActorsOnOneNode;
    private final ConnectionLogger connectionLogger;
    private final Duration clientActorAskTimeout;
    private final Duration checkLoggingActiveInterval;
    private final Duration loggingEnabledDuration;
    private final ConnectionConfig config;
    private final MonitoringConfig monitoringConfig;
    private final ClientActorRefs clientActorRefs = ClientActorRefs.empty();
    private final ConnectionPriorityProvider connectionPriorityProvider;
    private final ConnectionContextProvider connectionContextProvider;

    @Nullable private final ConnectivityCommandInterceptor customCommandValidator;
    private ConnectivityCommandInterceptor commandValidator;
    // Do nothing because nobody subscribes for connectivity events.

    private ConnectivityCommandStrategies createdStrategies;
    private ConnectivityCommandStrategies deletedStrategies;
    private int subscriptionCounter = 0;
    private Instant connectionClosedAt = Instant.now();
    @Nullable private Instant loggingEnabledUntil;
    @Nullable private ActorRef clientActorRouter;
    @Nullable private Integer priority;
    @Nullable private Instant recoveredAt;

    ConnectionPersistenceActor(final ConnectionId connectionId,
            final ActorRef proxyActor,
            final ActorRef pubSubMediator,
            final ClientActorPropsFactory propsFactory,

            @Nullable final ConnectivityCommandInterceptor customCommandValidator,
            final ConnectionPriorityProviderFactory connectionPriorityProviderFactory,
            final Trilean allClientActorsOnOneNode) {

        super(connectionId, new ConnectionMongoSnapshotAdapter());

        this.cluster = Cluster.get(getContext().getSystem());
        this.proxyActor = proxyActor;
        this.propsFactory = propsFactory;
        this.pubSubMediator = pubSubMediator;
        this.customCommandValidator = customCommandValidator;

        final ActorSystem actorSystem = getContext().getSystem();

        final ConnectivityConfig connectivityConfig = ConnectivityConfig.forActorSystem(actorSystem);
        config = connectivityConfig.getConnectionConfig();
        this.allClientActorsOnOneNode = allClientActorsOnOneNode.orElse(config.areAllClientActorsOnOneNode());
        connectionPriorityProvider = connectionPriorityProviderFactory.newProvider(self(), log);
        connectionContextProvider = ConnectionContextProviderFactory.get(actorSystem).getInstance();
        clientActorAskTimeout = config.getClientActorAskTimeout();
        monitoringConfig = connectivityConfig.getMonitoringConfig();
        final ConnectionLoggerRegistry loggerRegistry =
                ConnectionLoggerRegistry.fromConfig(monitoringConfig.logger());
        connectionLogger = loggerRegistry.forConnection(connectionId);

        loggingEnabledDuration = monitoringConfig.logger().logDuration();
        checkLoggingActiveInterval = monitoringConfig.logger().loggingActiveCheckInterval();
        // Make duration fuzzy to avoid all connections getting updated at once.
        final Duration fuzzyPriorityUpdateInterval =
                makeFuzzy(connectivityConfig.getConnectionConfig().getPriorityUpdateInterval());
        startUpdatePriorityPeriodically(fuzzyPriorityUpdateInterval);

        createdStrategies = ConnectionUninitializedStrategies.of(command -> stash());
        deletedStrategies = ConnectionUninitializedStrategies.of(this::stashAndInitialize);
    }

    /**
     * Makes the duration fuzzy. By returning a duration which is something between 5% shorter or 5% longer.
     *
     * @param duration the duration to make fuzzy.
     * @return the fuzzy duration.
     */
    private static Duration makeFuzzy(final Duration duration) {
        final long millis = duration.toMillis();
        final double fuzzyFactor =
                new Random().doubles(0.95, 1.05).findAny().orElse(0.0);
        final double fuzzedMillis = millis * fuzzyFactor;
        return Duration.ofMillis((long) fuzzedMillis);
    }

    @Override
    protected DittoDiagnosticLoggingAdapter createLogger() {
        return DittoLoggerFactory.getDiagnosticLoggingAdapter(this)
                .withMdcEntry(ConnectivityMdcEntryKey.CONNECTION_ID.toString(), entityId);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connectionId the connection ID.
     * @param proxyActor the actor used to send signals into the ditto cluster..
     * @param propsFactory factory of props of client actors for various protocols.
     * @param commandValidator validator for commands that should throw an exception if a command is invalid.
     * @param connectionPriorityProviderFactory Creates a new connection priority provider.
     * @return the Akka configuration Props object.
     */
    public static Props props(final ConnectionId connectionId,
            final ActorRef proxyActor,
            final ActorRef pubSubMediator,
            final ClientActorPropsFactory propsFactory,
            @Nullable final ConnectivityCommandInterceptor commandValidator,
            final ConnectionPriorityProviderFactory connectionPriorityProviderFactory
    ) {
        return Props.create(ConnectionPersistenceActor.class, connectionId, proxyActor, pubSubMediator, propsFactory,
                commandValidator, connectionPriorityProviderFactory, Trilean.UNKNOWN);
    }

    /**
     * Compute the length of the subscription ID prefix to identify the client actor owning a search session.
     *
     * @param clientCount the client count of the connection.
     * @return the length of the subscription prefix.
     */
    public static int getSubscriptionPrefixLength(final int clientCount) {
        return Integer.toHexString(clientCount).length();
    }

    @Override
    public String persistenceId() {
        return PERSISTENCE_ID_PREFIX + entityId;
    }

    @Override
    public String journalPluginId() {
        return JOURNAL_PLUGIN_ID;
    }

    @Override
    public String snapshotPluginId() {
        return SNAPSHOT_PLUGIN_ID;
    }

    @Override
    protected Class<?> getEventClass() {
        return ConnectivityEvent.class;
    }

    @Override
    protected CommandStrategy.Context<ConnectionState> getStrategyContext() {
        return DefaultContext.getInstance(ConnectionState.of(entityId, connectionLogger, commandValidator), log);
    }

    @Override
    protected ConnectivityCommandStrategies getCreatedStrategy() {
        return createdStrategies;
    }

    @Override
    protected ConnectivityCommandStrategies getDeletedStrategy() {
        return deletedStrategies;
    }

    @Override
    protected EventStrategy<ConnectivityEvent<?>, Connection> getEventStrategy() {
        return ConnectionEventStrategies.getInstance();
    }

    @Override
    protected ActivityCheckConfig getActivityCheckConfig() {
        return config.getActivityCheckConfig();
    }

    @Override
    protected SnapshotConfig getSnapshotConfig() {
        return config.getSnapshotConfig();
    }

    @Override
    protected boolean entityExistsAsDeleted() {
        return entity != null &&
                entity.getLifecycle().orElse(ConnectionLifecycle.ACTIVE) == ConnectionLifecycle.DELETED;
    }

    @Override
    protected DittoRuntimeExceptionBuilder<?> newNotAccessibleExceptionBuilder() {
        return ConnectionNotAccessibleException.newBuilder(entityId);
    }

    @Override
    protected void publishEvent(final ConnectivityEvent<?> event) {
        if (event instanceof ConnectionDeleted) {
            pubSubMediator.tell(DistPubSubAccess.publish(ConnectionDeleted.TYPE, event), getSelf());
        }
        // no other events are emitted
    }

    @Override
    protected JsonSchemaVersion getEntitySchemaVersion(final Connection entity) {
        return entity.getImplementedSchemaVersion();
    }

    @Override
    protected boolean shouldSendResponse(final DittoHeaders dittoHeaders) {
        return dittoHeaders.isResponseRequired();
    }

    @Override
    protected boolean isEntityAlwaysAlive() {
        return isDesiredStateOpen();
    }

    @Override
    public void postStop() throws Exception {
        log.info("stopped connection <{}>", entityId);
        super.postStop();
    }

    @Override
    protected void recoveryCompleted(final RecoveryCompleted event) {
        log.info("Connection <{}> was recovered: {}", entityId, entity);
        recoveredAt = Instant.now();
        if (entity != null && entity.getLifecycle().isEmpty()) {
            entity = entity.toBuilder().lifecycle(ConnectionLifecycle.ACTIVE).build();
        }
        if (isDesiredStateOpen()) {
            log.debug("Opening connection <{}> after recovery.", entityId);
            restoreOpenConnection();
        }
        if (entityExistsAsActive()) {
            // Initialize command validator by connectivity config.
            // For deleted connections, only start initiation on first command because the connection ID is not attached
            // to any solution.
            startInitialization(connectionContextProvider, entityId, DittoHeaders.empty(), getSelf());
        }
        super.recoveryCompleted(event);
    }

    @Override
    protected void onEntityModified() {
        // retrieve connectivity config again in case of missed events
        if (entityExistsAsActive()) {
            startInitialization(connectionContextProvider, entityId, DittoHeaders.empty(), getSelf());
        }
    }

    @Override
    protected ConnectivityEvent<?> modifyEventBeforePersist(final ConnectivityEvent<?> event) {
        final ConnectivityEvent<?> superEvent = super.modifyEventBeforePersist(event);

        final ConnectivityStatus targetConnectionStatus;
        if (event instanceof ConnectionCreated) {
            targetConnectionStatus = ((ConnectionCreated) event).getConnection().getConnectionStatus();
        } else if (event instanceof ConnectionModified) {
            targetConnectionStatus = ((ConnectionModified) event).getConnection().getConnectionStatus();
        } else if (event instanceof ConnectionDeleted) {
            targetConnectionStatus = ConnectivityStatus.CLOSED;
        } else if (event instanceof ConnectionOpened) {
            targetConnectionStatus = ConnectivityStatus.OPEN;
        } else if (event instanceof ConnectionClosed) {
            targetConnectionStatus = ConnectivityStatus.CLOSED;
        } else if (null != entity) {
            targetConnectionStatus = entity.getConnectionStatus();
        } else {
            targetConnectionStatus = ConnectivityStatus.UNKNOWN;
        }

        final var alwaysAlive = (targetConnectionStatus == ConnectivityStatus.OPEN);
        if (alwaysAlive) {
            final DittoHeaders headersWithJournalTags = superEvent.getDittoHeaders().toBuilder()
                    .journalTags(journalTags())
                    .build();
            return superEvent.setDittoHeaders(headersWithJournalTags);
        }
        return superEvent;
    }

    private Set<String> journalTags() {
        return Set.of(JOURNAL_TAG_ALWAYS_ALIVE,
                MongoReadJournal.PRIORITY_TAG_PREFIX + Optional.ofNullable(priority).orElse(0));
    }

    @Override
    protected void processPingCommand(final PingCommand ping) {

        super.processPingCommand(ping);
        final String journalTag = ping.getPayload()
                .filter(JsonValue::isString)
                .map(JsonValue::asString)
                .orElse(null);

        if (journalTag != null && journalTag.isEmpty() && isDesiredStateOpen()) {
            // persistence actor was sent a "ping" with empty journal tag:
            //  build in adding the "always-alive" tag here by persisting an "empty" event which is just tagged to be
            //  "always alive".  Stop persisting the empty event once every open connection has a tagged event, when
            // the persistence ping actor will have a non-empty journal tag configured.
            final EmptyEvent
                    emptyEvent = new EmptyEvent(EmptyEvent.EFFECT_ALWAYS_ALIVE, getRevisionNumber() + 1,
                    DittoHeaders.newBuilder()
                            .correlationId(ping.getCorrelationId().orElse(null))
                            .journalTags(journalTags())
                            .build());
            getSelf().tell(new PersistEmptyEvent(emptyEvent), ActorRef.noSender());
        }

        if (null != recoveredAt && recoveredAt.plus(GRACE_PERIOD_NOT_RETRIEVING_CONNECTION_STATUS_AFTER_RECOVERY)
                .isBefore(Instant.now())) {
            // only ask for connection status after initial recovery was completed + some grace period
            askSelfForRetrieveConnectionStatus(ping.getCorrelationId().orElse(null));
        }
    }

    private void askSelfForRetrieveConnectionStatus(@Nullable final CharSequence correlationId) {
        final var retrieveConnectionStatus = RetrieveConnectionStatus.of(entityId, DittoHeaders.newBuilder()
                .correlationId(correlationId)
                .build());
        Patterns.ask(getSelf(), retrieveConnectionStatus, SELF_RETRIEVE_CONNECTION_STATUS_TIMEOUT)
                .whenComplete((response, throwable) -> {
                    if (response instanceof RetrieveConnectionStatusResponse) {
                        final RetrieveConnectionStatusResponse rcsResp = (RetrieveConnectionStatusResponse) response;
                        final ConnectivityStatus liveStatus = rcsResp.getLiveStatus();
                        final DittoDiagnosticLoggingAdapter l = log
                                .withMdcEntries(
                                        ConnectivityMdcEntryKey.CONNECTION_ID, entityId,
                                        ConnectivityMdcEntryKey.CONNECTION_TYPE, Optional.ofNullable(entity)
                                                .map(Connection::getConnectionType).map(Object::toString).orElse("?"),
                                        CommonMdcEntryKey.DITTO_LOG_TAG,
                                        "connection-live-status-" + liveStatus.getName()
                                )
                                .withCorrelationId(rcsResp);
                        final String template = "Calculated <{}> live ConnectionStatus: <{}>";
                        if (liveStatus == ConnectivityStatus.FAILED) {
                            l.error(template, liveStatus, rcsResp);
                        } else if (liveStatus == ConnectivityStatus.MISCONFIGURED) {
                            l.info(template, liveStatus, rcsResp);
                        } else {
                            l.info(template, liveStatus, rcsResp);
                        }
                    } else if (null != throwable) {
                        log.withCorrelationId(correlationId)
                                .warning("Received throwable while waiting for RetrieveConnectionStatusResponse: " +
                                        "<{}: {}>", throwable.getClass().getSimpleName(), throwable.getMessage());
                    } else if (null != response) {
                        log.withCorrelationId(correlationId)
                                .warning("Unknown response while waiting for RetrieveConnectionStatusResponse: " +
                                        "<{}: {}>", response.getClass().getSimpleName(), response);
                    }
                });
    }

    @Override
    public void onMutation(final Command<?> command, final ConnectivityEvent<?> event,
            final WithDittoHeaders response, final boolean becomeCreated, final boolean becomeDeleted) {
        if (command instanceof StagedCommand) {
            interpretStagedCommand(((StagedCommand) command).withSenderUnlessDefined(getSender()));
        } else {
            super.onMutation(command, event, response, becomeCreated, becomeDeleted);
        }
    }

    @Override
    protected void checkForActivity(final CheckForActivity trigger) {
        if (isDesiredStateOpen()) {
            // stay in memory forever if desired state is open. check again later in case connection becomes closed.
            scheduleCheckForActivity(getActivityCheckConfig().getInactiveInterval());
        } else {
            super.checkForActivity(trigger);
        }
    }

    /**
     * Carry out the actions in a staged command. Synchronous actions are performed immediately followed by recursion
     * onto the next action. Asynchronous action are scheduled with the sending of the next staged command at the end.
     * Failures abort all asynchronous actions except OPEN_CONNECTION_IGNORE_ERRORS.
     *
     * @param command the staged command.
     */
    private void interpretStagedCommand(final StagedCommand command) {
        if (!command.hasNext()) {
            // execution complete
            return;
        }
        switch (command.nextAction()) {
            case TEST_CONNECTION:
                testConnection(command.next());
                break;
            case APPLY_EVENT:
                command.getEvent()
                        .ifPresentOrElse(event -> {
                                    entity = getEventStrategy().handle(event, entity, getRevisionNumber());
                                    interpretStagedCommand(command.next());
                                },
                                () -> log.error(
                                        "Failed to handle staged command because required event wasn't present: <{}>",
                                        command));
                break;
            case PERSIST_AND_APPLY_EVENT:
                command.getEvent().ifPresentOrElse(
                        event -> persistAndApplyEvent(event,
                                (unusedEvent, connection) -> interpretStagedCommand(command.next())),
                        () -> log.error("Failed to handle staged command because required event wasn't present: <{}>",
                                command));
                break;
            case SEND_RESPONSE:
                command.getSender().tell(command.getResponse(), getSelf());
                interpretStagedCommand(command.next());
                break;
            case PASSIVATE:
                // This actor will stop. Subsequent actions are ignored.
                passivate();
                break;
            case OPEN_CONNECTION:
                openConnection(command.next(), false);
                break;
            case OPEN_CONNECTION_IGNORE_ERRORS:
                openConnection(command.next(), true);
                break;
            case CLOSE_CONNECTION:
                closeConnection(command.next());
                break;
            case STOP_CLIENT_ACTORS:
                stopClientActors();
                interpretStagedCommand(command.next());
                break;
            case BECOME_CREATED:
                becomeCreatedHandler();
                interpretStagedCommand(command.next());
                break;
            case BECOME_DELETED:
                becomeDeletedHandler();
                interpretStagedCommand(command.next());
                break;
            case UPDATE_SUBSCRIPTIONS:
                prepareForSignalForwarding(command.next());
                break;
            case BROADCAST_TO_CLIENT_ACTORS_IF_STARTED:
                broadcastToClientActorsIfStarted(command.getCommand(), getSelf());
                interpretStagedCommand(command.next());
                break;
            case RETRIEVE_CONNECTION_LOGS:
                retrieveConnectionLogs((RetrieveConnectionLogs) command.getCommand(), command.getSender());
                interpretStagedCommand(command.next());
                break;
            case RETRIEVE_CONNECTION_STATUS:
                retrieveConnectionStatus((RetrieveConnectionStatus) command.getCommand(), command.getSender());
                interpretStagedCommand(command.next());
                break;
            case RETRIEVE_CONNECTION_METRICS:
                retrieveConnectionMetrics((RetrieveConnectionMetrics) command.getCommand(), command.getSender());
                interpretStagedCommand(command.next());
                break;
            case ENABLE_LOGGING:
                loggingEnabled();
                interpretStagedCommand(command.next());
                break;
            case DISABLE_LOGGING:
                loggingDisabled();
                interpretStagedCommand(command.next());
                break;
            default:
                log.error("Failed to handle staged command: <{}>", command);
        }
    }

    @Override
    protected Receive matchAnyAfterInitialization() {
        return ReceiveBuilder.create()
                .match(CreateSubscription.class, this::startThingSearchSession)
                .matchEquals(Control.CHECK_LOGGING_ACTIVE, this::checkLoggingEnabled)
                .matchEquals(Control.TRIGGER_UPDATE_PRIORITY, this::triggerUpdatePriority)
                .match(UpdatePriority.class, this::updatePriority)

                // maintain client actor refs
                .match(ActorRef.class, this::addClientActor)
                .match(Terminated.class, this::removeClientActor)
                .build()
                .orElse(createInitializationAndConfigUpdateBehavior())
                .orElse(super.matchAnyAfterInitialization());
    }

    @Override
    protected Receive matchAnyWhenDeleted() {
        return createInitializationAndConfigUpdateBehavior()
                .orElse(ReceiveBuilder.create()
                        .match(Control.class, msg ->
                                log.warning("Ignoring control message when deleted: <{}>", msg))
                        .match(ActorRef.class, msg ->
                                log.warning("Ignoring ActorRef message from client actor when deleted: <{}>", msg))
                        .build())
                .orElse(super.matchAnyWhenDeleted());
    }

    @Override
    protected void becomeDeletedHandler() {
        this.cancelPeriodicPriorityUpdate();
        super.becomeDeletedHandler();
    }

    /**
     * Route search commands according to subscription ID prefix. This is necessary so that for connections with
     * client count > 1, all commands related to 1 search session are routed to the same client actor. This is achieved
     * by using a prefix of fixed length of subscription IDs as the hash key of search commands. The length of the
     * prefix depends on the client count configured in the connection; it is 1 for connections with client count <= 15.
     * <p>
     * For the search protocol, all incoming messages are commands (ThingSearchCommand) and all outgoing messages are
     * events (SubscriptionEvent).
     * <p>
     * Message path for incoming search commands:
     * <pre>
     * ConsumerActor -> MessageMappingProcessorActor -> ConnectionPersistenceActor -> ClientActor -> SubscriptionManager
     * </pre>
     * Message path for outgoing search events:
     * <pre>
     * SubscriptionActor -> MessageMappingProcessorActor -> PublisherActor
     * </pre>
     *
     * @param command the command to route.
     */
    private void startThingSearchSession(final CreateSubscription command) {
        if (clientActorRouter == null) {
            logDroppedSignal(command, command.getType(), "Client actor not ready.");
            return;
        }
        if (entity == null) {
            logDroppedSignal(command, command.getType(), "No Connection configuration available.");
            return;
        }
        log.debug("Forwarding <{}> to client actors.", command);
        // compute the next prefix according to subscriptionCounter and the currently configured client actor count
        // ignore any "prefix" field from the command
        augmentWithPrefixAndForward(command, entity.getClientCount(), clientActorRouter);
    }

    private boolean entityExistsAsActive() {
        return entity != null &&
                entity.getLifecycle().orElse(ConnectionLifecycle.ACTIVE) == ConnectionLifecycle.ACTIVE;
    }

    private void augmentWithPrefixAndForward(final CreateSubscription createSubscription, final int clientCount,
            final ActorRef clientActorRouter) {
        subscriptionCounter = (subscriptionCounter + 1) % Math.max(1, clientCount);
        final int prefixLength = getSubscriptionPrefixLength(clientCount);
        final String prefix = String.format("%0" + prefixLength + "X", subscriptionCounter);
        final Optional<ActorRef> receiver = clientActorRefs.get(subscriptionCounter);
        final CreateSubscription commandWithPrefix = createSubscription.setPrefix(prefix);
        if (clientCount == 1) {
            clientActorRouter.tell(consistentHashableEnvelope(commandWithPrefix, prefix), ActorRef.noSender());
        } else if (receiver.isPresent()) {
            receiver.get().tell(commandWithPrefix, ActorRef.noSender());
        } else {
            logDroppedSignal(createSubscription, createSubscription.getType(), "Client actor not ready.");
        }
    }

    private void checkLoggingEnabled(final Control message) {
        final CheckConnectionLogsActive checkLoggingActive = CheckConnectionLogsActive.of(entityId, Instant.now());
        broadcastToClientActorsIfStarted(checkLoggingActive, getSelf());
    }

    private void triggerUpdatePriority(final Control message) {
        if (entity != null) {
            final String correlationId = UUID.randomUUID().toString();
            connectionPriorityProvider.getPriorityFor(entityId, correlationId)
                    .whenComplete((desiredPriority, error) -> {
                        if (error != null) {
                            log.withCorrelationId(correlationId)
                                    .warning("Got error when trying to get the desired priority for connection <{}>.",
                                            entityId);
                        } else {
                            final DittoHeaders headers = DittoHeaders.newBuilder().correlationId(correlationId).build();
                            self().tell(new UpdatePriority(desiredPriority, headers), ActorRef.noSender());
                        }
                    });
        }
    }

    private void updatePriority(final UpdatePriority updatePriority) {
        final Integer desiredPriority = updatePriority.getDesiredPriority();
        if (!desiredPriority.equals(priority)) {
            log.withCorrelationId(updatePriority)
                    .info("Updating priority of connection <{}> from <{}> to <{}>", entityId, priority,
                            desiredPriority);
            this.priority = desiredPriority;
            final DittoHeaders headersWithJournalTags =
                    updatePriority.getDittoHeaders().toBuilder().journalTags(journalTags()).build();
            final EmptyEvent emptyEvent = new EmptyEvent(EmptyEvent.EFFECT_PRIORITY_UPDATE, getRevisionNumber() + 1,
                    headersWithJournalTags);
            getSelf().tell(new PersistEmptyEvent(emptyEvent), ActorRef.noSender());
        }
    }

    private void prepareForSignalForwarding(final StagedCommand command) {
        if (isDesiredStateOpen()) {
            startEnabledLoggingChecker();
            updateLoggingIfEnabled();
        }
        interpretStagedCommand(command);
    }

    private void testConnection(final StagedCommand command) {
        final ActorRef origin = command.getSender();
        final ActorRef self = getSelf();
        final DittoHeaders headersWithDryRun = command.getDittoHeaders()
                .toBuilder()
                .dryRun(true)
                .build();
        final TestConnection testConnection = (TestConnection) command.getCommand().setDittoHeaders(headersWithDryRun);

        if (clientActorRouter != null) {
            // client actor is already running, so either another TestConnection command is currently executed or the
            // connection has been created in the meantime. In either case reject the new TestConnection command to
            // prevent strange behavior.
            origin.tell(TestConnectionResponse.alreadyCreated(entityId, command.getDittoHeaders()), self);
        } else {
            // no need to start more than 1 client for tests
            // set connection status to CLOSED so that client actors will not try to connect on startup
            setConnectionStatusClosedForTestConnection();
            startAndAskClientActors(testConnection, 1)
                    .thenAccept(response -> self.tell(
                            command.withResponse(TestConnectionResponse.success(testConnection.getEntityId(),
                                    response.toString(), command.getDittoHeaders())),
                            ActorRef.noSender()))
                    .exceptionally(error -> {
                        self.tell(
                                command.withResponse(
                                        toDittoRuntimeException(error, entityId, command.getDittoHeaders())),
                                ActorRef.noSender());
                        return null;
                    });
        }
    }

    private void setConnectionStatusClosedForTestConnection() {
        if (entity != null) {
            entity = entity.toBuilder().connectionStatus(ConnectivityStatus.CLOSED).build();
        }
    }

    private void openConnection(final StagedCommand command, final boolean ignoreErrors) {
        final OpenConnection openConnection = OpenConnection.of(entityId, command.getDittoHeaders());
        final Consumer<Object> successConsumer = response -> getSelf().tell(command, ActorRef.noSender());
        startAndAskClientActors(openConnection, getClientCount())
                .thenAccept(successConsumer)
                .exceptionally(error -> {
                    if (ignoreErrors) {
                        // log the exception and proceed
                        handleException("open-connection", command.getSender(), error, false);
                        successConsumer.accept(error);
                        return null;
                    } else {
                        return handleException("open-connection", command.getSender(), error);
                    }
                });
    }

    private void closeConnection(final StagedCommand command) {
        final CloseConnection closeConnection = CloseConnection.of(entityId, command.getDittoHeaders());
        broadcastToClientActorsIfStarted(closeConnection)
                .thenAccept(response -> getSelf().tell(command, ActorRef.noSender()))
                .exceptionally(error -> {
                    // stop client actors anyway---the closed status is already persisted.
                    stopClientActors();
                    return handleException("disconnect", command.getSender(), error);
                });
    }

    private void logDroppedSignal(final WithDittoHeaders withDittoHeaders, final String type, final String reason) {
        log.withCorrelationId(withDittoHeaders).debug("Signal ({}) dropped: {}", type, reason);
    }

    private void retrieveConnectionLogs(final RetrieveConnectionLogs command, final ActorRef sender) {
        this.updateLoggingIfEnabled();
        broadcastCommandWithDifferentSender(command,
                (existingConnection, timeout) -> RetrieveConnectionLogsAggregatorActor.props(
                        existingConnection, sender, command.getDittoHeaders(), timeout,
                        monitoringConfig.logger().maxLogSizeInBytes()),
                () -> respondWithEmptyLogs(command, sender));
    }

    private boolean isLoggingEnabled() {
        return loggingEnabledUntil != null && Instant.now().isBefore(loggingEnabledUntil);
    }

    private void loggingEnabled() {
        // start check logging scheduler
        startEnabledLoggingChecker();
        loggingEnabledUntil = Instant.now().plus(this.loggingEnabledDuration);
    }

    private void updateLoggingIfEnabled() {
        if (isLoggingEnabled()) {
            loggingEnabledUntil = Instant.now().plus(loggingEnabledDuration);
            broadcastToClientActorsIfStarted(EnableConnectionLogs.of(entityId, DittoHeaders.empty()),
                    ActorRef.noSender());
        }
    }

    private void loggingDisabled() {
        loggingEnabledUntil = null;
        cancelEnabledLoggingChecker();
    }

    private void cancelEnabledLoggingChecker() {
        timers().cancel(Control.CHECK_LOGGING_ACTIVE);
    }

    private void startEnabledLoggingChecker() {
        timers().startTimerWithFixedDelay(Control.CHECK_LOGGING_ACTIVE, Control.CHECK_LOGGING_ACTIVE,
                checkLoggingActiveInterval);
    }

    private void startUpdatePriorityPeriodically(final Duration priorityUpdateInterval) {
        log.info("Schedule update of priority periodically in an interval of <{}>", priorityUpdateInterval);
        timers().startTimerWithFixedDelay(Control.TRIGGER_UPDATE_PRIORITY, Control.TRIGGER_UPDATE_PRIORITY,
                priorityUpdateInterval);
    }

    private void cancelPeriodicPriorityUpdate() {
        timers().cancel(Control.TRIGGER_UPDATE_PRIORITY);
    }

    private void respondWithEmptyLogs(final WithDittoHeaders command, final ActorRef origin) {
        log.debug("ClientActor not started, responding with empty connection logs.");
        final RetrieveConnectionLogsResponse logsResponse = RetrieveConnectionLogsResponse.of(
                entityId,
                Collections.emptyList(),
                null,
                null,
                command.getDittoHeaders()
        );
        origin.tell(logsResponse, getSelf());
    }

    private CompletionStage<Object> startAndAskClientActors(final SignalWithEntityId<?> cmd, final int clientCount) {
        startClientActorsIfRequired(clientCount, cmd.getDittoHeaders());
        final Object msg = consistentHashableEnvelope(cmd, cmd.getEntityId().toString());
        return processClientAskResult(Patterns.ask(clientActorRouter, msg, clientActorAskTimeout));
    }

    private static Object consistentHashableEnvelope(final Object message, final String hashKey) {
        return new ConsistentHashingRouter.ConsistentHashableEnvelope(message, hashKey);
    }

    private void broadcastToClientActorsIfStarted(final Command<?> cmd, final ActorRef sender) {
        if (clientActorRouter != null && entity != null) {
            clientActorRouter.tell(new Broadcast(cmd), sender);
        }
    }

    /*
     * NOT thread-safe.
     */
    private CompletionStage<Object> broadcastToClientActorsIfStarted(final Command<?> cmd) {
        if (clientActorRouter != null && entity != null) {
            // wrap in Broadcast message because these management messages must be delivered to each client actor
            final Broadcast broadcast = new Broadcast(cmd);
            return processClientAskResult(Patterns.ask(clientActorRouter, broadcast, clientActorAskTimeout));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private void broadcastCommandWithDifferentSender(final ConnectivityQueryCommand<?> command,
            final BiFunction<Connection, Duration, Props> senderPropsForConnectionWithTimeout,
            final Runnable onClientActorNotStarted) {
        if (clientActorRouter != null && entity != null) {
            // timeout before sending the (partial) response
            final Duration timeout = extractTimeoutFromCommand(command.getDittoHeaders());
            final ActorRef aggregator =
                    getContext().actorOf(senderPropsForConnectionWithTimeout.apply(entity, timeout));

            // forward command to all client actors with aggregator as sender
            clientActorRouter.tell(new Broadcast(command), aggregator);
        } else {
            onClientActorNotStarted.run();
        }
    }

    private void forwardToClientActors(final Props aggregatorProps, final Command<?> cmd,
            final Runnable onClientActorNotStarted) {
        if (clientActorRouter != null && entity != null) {
            final ActorRef metricsAggregator = getContext().actorOf(aggregatorProps);

            // forward command to all client actors with aggregator as sender
            clientActorRouter.tell(new Broadcast(cmd), metricsAggregator);
        } else {
            onClientActorNotStarted.run();
        }
    }

    /*
     * Thread-safe because Actor.getSelf() is thread-safe.
     */
    private Void handleException(final String action, @Nullable final ActorRef origin, final Throwable exception) {
        return handleException(action, origin, exception, true);
    }

    private Void handleException(final String action,
            @Nullable final ActorRef origin,
            final Throwable error,
            final boolean sendExceptionResponse) {

        final DittoRuntimeException dre = toDittoRuntimeException(error, entityId, DittoHeaders.empty());

        if (sendExceptionResponse && origin != null) {
            origin.tell(dre, getSelf());
        }
        connectionLogger.failure("Operation {0} failed due to {1}", action, dre.getMessage());
        log.warning("Operation <{}> on connection <{}> failed due to {}: {}.", action, entityId,
                dre.getClass().getSimpleName(), dre.getMessage());
        return null;
    }


    private void retrieveConnectionStatus(final RetrieveConnectionStatus command, final ActorRef sender) {
        checkNotNull(entity, "Connection");
        // timeout before sending the (partial) response
        final Duration timeout = extractTimeoutFromCommand(command.getDittoHeaders());
        final long availableConnectivityInstances = StreamSupport.stream(cluster.state().getMembers().spliterator(), false)
                .filter(member -> member.getRoles().contains(CLUSTER_ROLE))
                .count();
        final Props props = RetrieveConnectionStatusAggregatorActor.props(entity, sender,
                command.getDittoHeaders(), timeout, availableConnectivityInstances);
        forwardToClientActors(props, command, () -> respondWithEmptyStatus(command, sender));
    }

    private static Duration extractTimeoutFromCommand(final DittoHeaders headers) {
        return Duration.ofMillis(
                (long) (headers.getTimeout().orElse(DEFAULT_RETRIEVE_STATUS_TIMEOUT).toMillis() * 0.75)
        );
    }

    private void retrieveConnectionMetrics(final RetrieveConnectionMetrics command, final ActorRef sender) {
        broadcastCommandWithDifferentSender(command,
                (existingConnection, timeout) -> RetrieveConnectionMetricsAggregatorActor.props(
                        existingConnection, sender, command.getDittoHeaders(), timeout),
                () -> respondWithEmptyMetrics(command, sender));
    }

    private void respondWithEmptyMetrics(final WithDittoHeaders command, final ActorRef origin) {
        log.debug("ClientActor not started, responding with empty connection metrics with status closed.");
        final ConnectionMetrics metrics =
                ConnectivityModelFactory.newConnectionMetrics(
                        ConnectivityModelFactory.newAddressMetric(Collections.emptySet()),
                        ConnectivityModelFactory.newAddressMetric(Collections.emptySet())
                );
        final RetrieveConnectionMetricsResponse metricsResponse =
                RetrieveConnectionMetricsResponse.getBuilder(entityId, command.getDittoHeaders())
                        .connectionMetrics(metrics)
                        .sourceMetrics(ConnectivityModelFactory.emptySourceMetrics())
                        .targetMetrics(ConnectivityModelFactory.emptyTargetMetrics())
                        .build();
        origin.tell(metricsResponse, getSelf());
    }

    private void respondWithEmptyStatus(final WithDittoHeaders command, final ActorRef origin) {
        log.debug("ClientActor not started, responding with empty connection status with status closed.");

        final RetrieveConnectionStatusResponse statusResponse =
                RetrieveConnectionStatusResponse.closedResponse(entityId,
                        InstanceIdentifierSupplier.getInstance().get(),
                        connectionClosedAt == null ? Instant.EPOCH : connectionClosedAt,
                        ConnectivityStatus.CLOSED,
                        "[" + BaseClientState.DISCONNECTED + "] connection is closed",
                        command.getDittoHeaders());
        origin.tell(statusResponse, getSelf());
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return ESCALATE_ALWAYS_STRATEGY;
    }

    private void startClientActorsIfRequired(final int clientCount, final DittoHeaders dittoHeaders) {
        if (entity != null && clientActorRouter == null && clientCount > 0) {
            log.info("Starting ClientActor for connection <{}> with <{}> clients.", entityId, clientCount);
            final Props props = propsFactory.getActorPropsForType(entity, proxyActor, getSelf(),
                    getContext().getSystem(), dittoHeaders);
            final ClusterRouterPoolSettings clusterRouterPoolSettings =
                    new ClusterRouterPoolSettings(clientCount, clientActorsPerNode(clientCount), true,
                            Set.of(CLUSTER_ROLE));
            final Pool pool = new ConsistentHashingPool(clientCount)
                    .withSupervisorStrategy(
                            OneForOneEscalateStrategy.withRetries(config.getClientActorRestartsBeforeEscalation()));
            final Props clusterRouterPoolProps =
                    new ClusterRouterPool(pool, clusterRouterPoolSettings).props(props);

            // start client actor without name so it does not conflict with its previous incarnation
            clientActorRouter = getContext().actorOf(clusterRouterPoolProps);
            updateLoggingIfEnabled();
        } else if (clientActorRouter != null) {
            log.debug("ClientActor already started.");
        } else {
            log.error(new IllegalStateException(), "Trying to start client actor without a connection");
        }
    }

    private int clientActorsPerNode(final int clientCount) {
        return allClientActorsOnOneNode ? clientCount : 1;
    }

    private int getClientCount() {
        return entity == null ? 0 : entity.getClientCount();
    }

    private void stopClientActors() {
        clientActorRefs.clear();
        if (clientActorRouter != null) {
            connectionClosedAt = Instant.now();
            log.debug("Stopping the client actor.");
            stopChildActor(clientActorRouter);
            clientActorRouter = null;
        }
    }

    private void addClientActor(final ActorRef newClientActor) {
        getContext().watch(newClientActor);
        clientActorRefs.add(newClientActor);
        final List<ActorRef> otherClientActors = clientActorRefs.getOtherActors(newClientActor);
        otherClientActors.forEach(otherClientActor -> {
            otherClientActor.tell(newClientActor, ActorRef.noSender());
            newClientActor.tell(otherClientActor, ActorRef.noSender());
        });
    }

    private void removeClientActor(final Terminated terminated) {
        clientActorRefs.remove(terminated.getActor());
    }

    private void stopChildActor(final ActorRef actor) {
        log.debug("Stopping child actor <{}>.", actor.path());
        getContext().stop(actor);
    }

    private boolean isDesiredStateOpen() {
        return entity != null &&
                !entity.hasLifecycle(ConnectionLifecycle.DELETED) &&
                entity.getConnectionStatus() == ConnectivityStatus.OPEN;
    }

    private void restoreOpenConnection() {
        final OpenConnection connect = OpenConnection.of(entityId, DittoHeaders.empty());
        final ConnectionOpened connectionOpened =
                ConnectionOpened.of(entityId, getRevisionNumber(), Instant.now(), DittoHeaders.empty(), null);
        final StagedCommand stagedCommand = StagedCommand.of(connect, connectionOpened, connect,
                Collections.singletonList(ConnectionAction.UPDATE_SUBSCRIPTIONS));
        openConnection(stagedCommand, false);
    }

    private ConnectivityCommandInterceptor updateValidator(final ConnectivityConfig connectivityConfig) {
        final var actorSystem = getContext().getSystem();
        final MqttConfig mqttConfig = connectivityConfig.getConnectionConfig().getMqttConfig();
        final ConnectionValidator connectionValidator =
                ConnectionValidator.of(actorSystem.log(),
                        connectivityConfig,
                        RabbitMQValidator.newInstance(),
                        AmqpValidator.newInstance(),
                        Mqtt3Validator.newInstance(mqttConfig),
                        Mqtt5Validator.newInstance(mqttConfig),
                        KafkaValidator.getInstance(),
                        HttpPushValidator.newInstance());

        final DittoConnectivityCommandValidator dittoCommandValidator =
                new DittoConnectivityCommandValidator(propsFactory, proxyActor, getSelf(), connectionValidator,
                        actorSystem);

        if (customCommandValidator != null) {
            return new CompoundConnectivityCommandInterceptor(dittoCommandValidator, customCommandValidator);
        } else {
            return dittoCommandValidator;
        }
    }

    private void setConnectivityConfig(final ConnectivityConfig connectivityConfig) {
        commandValidator = updateValidator(connectivityConfig);
    }

    private void updateConnectivityConfig(final Event<?> event) {
        connectionContextProvider.handleEvent(event).ifPresent(this::setConnectivityConfig);
    }

    private void initializeByConnectivityConfig(final ConnectivityConfig connectivityConfig) {
        setConnectivityConfig(connectivityConfig);
        createdStrategies = ConnectionCreatedStrategies.getInstance();
        deletedStrategies = ConnectionDeletedStrategies.getInstance();
        unstashAll();
    }

    private void stashAndInitialize(final WithDittoHeaders command) {
        startInitialization(connectionContextProvider, entityId, command.getDittoHeaders(), getSelf());
        stash();
    }

    private Receive createInitializationAndConfigUpdateBehavior() {
        return ReceiveBuilder.create()
                .match(Event.class, connectionContextProvider::canHandle, this::updateConnectivityConfig)
                .match(ConnectivityConfig.class, this::initializeByConnectivityConfig)
                .match(InitializationFailure.class, failure -> {
                    log.error(failure.cause, "Initialization failed");
                    // terminate self to restart after backoff
                    getContext().stop(getSelf());
                })
                .build();
    }

    private static void startInitialization(final ConnectionContextProvider provider,
            final ConnectionId connectionId, final DittoHeaders dittoHeaders, final ActorRef self) {

        provider.registerForConnectivityConfigChanges(connectionId, dittoHeaders, self);
        provider.getConnectivityConfig(connectionId, dittoHeaders)
                .thenAccept(config -> self.tell(config, ActorRef.noSender()))
                .exceptionally(error -> {
                    self.tell(new InitializationFailure(error), ActorRef.noSender());
                    return null;
                });
    }

    private static DittoRuntimeException toDittoRuntimeException(final Throwable error, final ConnectionId id,
            final DittoHeaders headers) {

        return DittoRuntimeException.asDittoRuntimeException(error,
                cause -> ConnectionFailedException.newBuilder(id)
                        .description(cause.getMessage())
                        .cause(cause)
                        .dittoHeaders(headers)
                        .build());
    }

    private static CompletionStage<Object> processClientAskResult(final CompletionStage<Object> askResultFuture) {
        return askResultFuture.thenCompose(response -> {
            if (response instanceof Status.Failure) {
                return CompletableFuture.failedStage(((Status.Failure) response).cause());
            } else if (response instanceof DittoRuntimeException) {
                return CompletableFuture.failedStage((DittoRuntimeException) response);
            } else {
                return CompletableFuture.completedFuture(response);
            }
        });
    }

    /**
     * Message that will be sent by scheduler.
     */
    enum Control {

        /**
         * Indicates a check if logging is still enabled for this connection.
         */
        CHECK_LOGGING_ACTIVE,

        /**
         * Indicates the the priority of the connection should get updated
         */
        TRIGGER_UPDATE_PRIORITY
    }

    private static final class InitializationFailure {

        private final Throwable cause;

        private InitializationFailure(final Throwable cause) {
            this.cause = cause;
        }
    }

    /**
     * Local message this actor may sent to itself in order to update the priority of the connection.
     */
    @Immutable
    static final class UpdatePriority implements WithDittoHeaders {

        private final Integer desiredPriority;
        private final DittoHeaders dittoHeaders;

        public UpdatePriority(final Integer desiredPriority, final DittoHeaders dittoHeaders) {
            this.desiredPriority = desiredPriority;
            this.dittoHeaders = dittoHeaders;
        }

        Integer getDesiredPriority() {
            return desiredPriority;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " [" +
                    "desiredPriority=" + desiredPriority +
                    ", dittoHeaders=" + dittoHeaders +
                    "]";
        }

        @Override
        public DittoHeaders getDittoHeaders() {
            return dittoHeaders;
        }

    }

}
