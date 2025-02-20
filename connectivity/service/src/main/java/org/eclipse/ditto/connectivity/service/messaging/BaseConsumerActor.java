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
package org.eclipse.ditto.connectivity.service.messaging;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.acks.Acknowledgements;
import org.eclipse.ditto.base.model.signals.commands.CommandResponse;
import org.eclipse.ditto.connectivity.api.ExternalMessage;
import org.eclipse.ditto.connectivity.api.ExternalMessageBuilder;
import org.eclipse.ditto.connectivity.api.ExternalMessageFactory;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.ConnectionType;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.ResourceStatus;
import org.eclipse.ditto.connectivity.model.Source;
import org.eclipse.ditto.connectivity.service.config.ConnectivityConfig;
import org.eclipse.ditto.connectivity.service.config.DittoConnectivityConfig;
import org.eclipse.ditto.connectivity.service.messaging.InboundMappingSink.ExternalMessageWithSender;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.DefaultConnectionMonitorRegistry;
import org.eclipse.ditto.internal.models.acks.config.AcknowledgementConfig;
import org.eclipse.ditto.internal.utils.akka.logging.ThreadSafeDittoLoggingAdapter;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.config.InstanceIdentifierSupplier;
import org.eclipse.ditto.internal.utils.metrics.DittoMetrics;
import org.eclipse.ditto.internal.utils.metrics.instruments.timer.StartedTimer;
import org.eclipse.ditto.internal.utils.tracing.DittoTracing;
import org.eclipse.ditto.internal.utils.tracing.TracingTags;

import akka.NotUsed;
import akka.actor.AbstractActorWithTimers;
import akka.actor.ActorRef;
import akka.pattern.Patterns;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import kamon.context.Context;

/**
 * Base class for consumer actors that holds common fields and handles the address status.
 */
public abstract class BaseConsumerActor extends AbstractActorWithTimers {

    private static final String TIMER_ACK_HANDLING = "connectivity_ack_handling";

    protected final String sourceAddress;
    protected final Source source;
    private final ConnectionType connectionType;
    protected final ConnectionMonitor inboundMonitor;
    protected final ConnectionMonitor inboundAcknowledgedMonitor;
    protected final ConnectionId connectionId;
    protected final ConnectivityStatusResolver connectivityStatusResolver;

    private final Sink<Object, ?> inboundMappingSink;
    private final AcknowledgementConfig acknowledgementConfig;

    @Nullable private ResourceStatus resourceStatus;

    protected BaseConsumerActor(final Connection connection,
            final String sourceAddress,
            final Sink<Object, ?> inboundMappingSink,
            final Source source,
            final ConnectivityStatusResolver connectivityStatusResolver) {

        connectionId = checkNotNull(connection, "connection").getId();
        this.sourceAddress = checkNotNull(sourceAddress, "sourceAddress");
        this.inboundMappingSink = checkNotNull(inboundMappingSink, "inboundMappingSink");
        this.source = checkNotNull(source, "source");
        connectionType = connection.getConnectionType();
        this.connectivityStatusResolver = checkNotNull(connectivityStatusResolver, "connectivityStatusResolver");
        resetResourceStatus();

        final ConnectivityConfig connectivityConfig = DittoConnectivityConfig.of(
                DefaultScopedConfig.dittoScoped(getContext().getSystem().settings().config()));

        acknowledgementConfig = connectivityConfig.getAcknowledgementConfig();

        inboundMonitor = DefaultConnectionMonitorRegistry.fromConfig(connectivityConfig.getMonitoringConfig())
                .forInboundConsumed(connection, sourceAddress);

        inboundAcknowledgedMonitor =
                DefaultConnectionMonitorRegistry.fromConfig(connectivityConfig.getMonitoringConfig())
                        .forInboundAcknowledged(connection, sourceAddress);
    }

    /**
     * @return the logging adapter of this actor.
     */
    protected abstract ThreadSafeDittoLoggingAdapter log();

    protected final Sink<AcknowledgeableMessage, NotUsed> getMessageMappingSink() {
        return Flow.fromFunction(this::withSender)
                .map(Object.class::cast)
                .recoverWithRetries(-1, Throwable.class, akka.stream.javadsl.Source::empty)
                .to(inboundMappingSink);
    }

    private ExternalMessageWithSender withSender(final AcknowledgeableMessage acknowledgeableMessage) {
        // Start per-inbound-signal actor to collect acks of all thing-modify-commands mapped from incoming signal
        final Duration collectorLifetime = acknowledgementConfig.getCollectorFallbackLifetime();
        final ActorRef responseCollector = getContext().actorOf(ResponseCollectorActor.props(collectorLifetime));
        prepareResponseHandler(acknowledgeableMessage, responseCollector);

        final ExternalMessage messageWithSourceAndReplyTarget =
                addSourceAndReplyTarget(acknowledgeableMessage.getMessage());
        log().debug("Forwarding incoming message for mapping. ResponseCollector=<{}>", responseCollector);
        return new ExternalMessageWithSender(messageWithSourceAndReplyTarget, responseCollector);
    }

    private void prepareResponseHandler(final AcknowledgeableMessage acknowledgeableMessage,
            final ActorRef responseCollector) {

        final StartedTimer timer = DittoMetrics.timer(TIMER_ACK_HANDLING)
                .tag(TracingTags.CONNECTION_ID, connectionId.toString())
                .tag(TracingTags.CONNECTION_TYPE, connectionType.getName())
                .start();
        final Context traceContext = DittoTracing.extractTraceContext(acknowledgeableMessage.getMessage().getHeaders());
        DittoTracing.wrapTimer(traceContext, timer);

        final Duration askTimeout = acknowledgementConfig.getCollectorFallbackAskTimeout();
        // Ask response collector actor to get the collected responses in a future
        Patterns.ask(responseCollector, ResponseCollectorActor.query(), askTimeout).thenCompose(output -> {
            if (output instanceof ResponseCollectorActor.Output) {
                return CompletableFuture.completedFuture((ResponseCollectorActor.Output) output);
            } else if (output instanceof Throwable) {
                log().debug("Patterns.ask failed. ResponseCollector=<{}>", responseCollector);
                return CompletableFuture.failedFuture((Throwable) output);
            } else {
                log().error("Expect ResponseCollectorActor.Output, got: <{}>. ResponseCollector=<{}>", output,
                        responseCollector);
                return CompletableFuture.failedFuture(new ClassCastException("Unexpected acknowledgement type."));
            }
        }).handle((output, error) -> {
            log().debug("Result from ResponseCollector=<{}>: output=<{}> error=<{}>",
                    responseCollector, output, error);
            if (output != null) {
                final List<CommandResponse<?>> failedResponses = output.getFailedResponses();
                if (output.allExpectedResponsesArrived() && failedResponses.isEmpty()) {
                    timer.tag(TracingTags.ACK_SUCCESS, true).stop();
                    acknowledgeableMessage.settle();
                } else {
                    // empty failed responses indicate that SetCount was missing
                    final boolean shouldRedeliver = failedResponses.isEmpty() ||
                            someFailedResponseRequiresRedelivery(failedResponses);
                    log().debug("Rejecting [redeliver={}] due to failed responses <{}>. " +
                            "ResponseCollector=<{}>", shouldRedeliver, failedResponses, responseCollector);
                    timer.tag(TracingTags.ACK_SUCCESS, false)
                            .tag(TracingTags.ACK_REDELIVER, shouldRedeliver)
                            .stop();
                    acknowledgeableMessage.reject(shouldRedeliver);
                }
            } else {
                // don't count this as "failure" in the "source consumed" metric as the consumption
                // itself was successful
                final DittoRuntimeException dittoRuntimeException =
                        DittoRuntimeException.asDittoRuntimeException(error, rootCause -> {
                            // Redeliver and pray this unexpected error goes away
                            log().debug("Rejecting [redeliver=true] due to error <{}>. " +
                                    "ResponseCollector=<{}>", rootCause, responseCollector);
                            timer.tag(TracingTags.ACK_SUCCESS, false)
                                    .tag(TracingTags.ACK_REDELIVER, true)
                                    .stop();
                            acknowledgeableMessage.reject(true);
                            return null;
                        });
                if (dittoRuntimeException != null) {
                    if (isConsideredSuccess(dittoRuntimeException)) {
                        timer.tag(TracingTags.ACK_SUCCESS, true).stop();
                        acknowledgeableMessage.settle();
                    } else {
                        final var shouldRedeliver = requiresRedelivery(dittoRuntimeException.getHttpStatus());
                        log().debug("Rejecting [redeliver={}] due to error <{}>. ResponseCollector=<{}>",
                                shouldRedeliver, dittoRuntimeException, responseCollector);
                        timer.tag(TracingTags.ACK_SUCCESS, false)
                                .tag(TracingTags.ACK_REDELIVER, shouldRedeliver)
                                .stop();
                        acknowledgeableMessage.reject(shouldRedeliver);
                    }
                }
            }
            return null;
        }).exceptionally(e -> {
            log().error(e, "Unexpected error during manual acknowledgement. ResponseCollector=<{}>",
                    responseCollector);
            return null;
        });
    }

    /**
     * Send an error to the mapping sink to be published in the reply-target.
     */
    protected final Sink<DittoRuntimeException, ?> getDittoRuntimeExceptionSink() {
        return Flow.<DittoRuntimeException, DittoRuntimeException>fromFunction(
                        message -> message.setDittoHeaders(enrichHeadersWithReplyInformation(message.getDittoHeaders())))
                .via(Flow.<DittoRuntimeException, Object>fromFunction(value -> {
                    inboundMonitor.failure(value.getDittoHeaders(), value);
                    return value;
                }))
                .recoverWithRetries(-1, Throwable.class, akka.stream.javadsl.Source::empty)
                .to(inboundMappingSink);
    }

    protected void resetResourceStatus() {
        resourceStatus = ConnectivityModelFactory.newSourceStatus(getInstanceIdentifier(),
                ConnectivityStatus.OPEN, sourceAddress, "Consumer started.", Instant.now());
    }

    protected ResourceStatus getCurrentSourceStatus() {
        final Optional<ResourceStatus> statusOptional = Optional.ofNullable(resourceStatus);
        return ConnectivityModelFactory.newSourceStatus(getInstanceIdentifier(),
                statusOptional.map(ResourceStatus::getStatus).orElse(ConnectivityStatus.UNKNOWN),
                sourceAddress,
                statusOptional.flatMap(ResourceStatus::getStatusDetails).orElse(null),
                statusOptional.flatMap(ResourceStatus::getInStateSince).orElse(null));
    }

    protected void handleAddressStatus(final ResourceStatus resourceStatus) {
        if (resourceStatus.getResourceType() == ResourceStatus.ResourceType.UNKNOWN) {
            this.resourceStatus = ConnectivityModelFactory.newSourceStatus(getInstanceIdentifier(),
                    resourceStatus.getStatus(), sourceAddress,
                    resourceStatus.getStatusDetails().orElse(null),
                    resourceStatus.getInStateSince().orElse(Instant.now()));
        } else {
            this.resourceStatus = resourceStatus;
        }
    }

    private ExternalMessage addSourceAndReplyTarget(final ExternalMessage message) {
        final ExternalMessageBuilder externalMessageBuilder =
                ExternalMessageFactory.newExternalMessageBuilder(message)
                        .withSource(source);
        externalMessageBuilder.withInternalHeaders(enrichHeadersWithReplyInformation(message.getInternalHeaders()));
        return externalMessageBuilder.build();
    }

    private DittoHeaders enrichHeadersWithReplyInformation(final DittoHeaders headers) {
        return source.getReplyTarget()
                .<DittoHeaders>map(replyTarget -> headers.toBuilder()
                        .replyTarget(source.getIndex())
                        .expectedResponseTypes(replyTarget.getExpectedResponseTypes())
                        .build())
                .orElse(headers);
    }

    private static String getInstanceIdentifier() {
        return InstanceIdentifierSupplier.getInstance().get();
    }

    private static boolean someFailedResponseRequiresRedelivery(final Collection<CommandResponse<?>> failedResponses) {
        return failedResponses.stream()
                .flatMap(BaseConsumerActor::extractAggregatedResponses)
                .map(CommandResponse::getHttpStatus)
                .anyMatch(BaseConsumerActor::requiresRedelivery);
    }

    private static Stream<? extends CommandResponse<?>> extractAggregatedResponses(final CommandResponse<?> response) {
        if (response instanceof Acknowledgements) {
            return ((Acknowledgements) response).stream();
        } else {
            return Stream.of(response);
        }
    }

    /**
     * Decide whether an Acknowledgement or DittoRuntimeException requires redelivery based on the status.
     * Client errors excluding 408 request-timeout and 424 failed-dependency are considered unrecoverable and no
     * redelivery will be attempted.
     *
     * @param status HTTP status of the Acknowledgement or DittoRuntimeException.
     * @return whether it requires redelivery.
     */
    private static boolean requiresRedelivery(final HttpStatus status) {
        if (HttpStatus.REQUEST_TIMEOUT.equals(status) || HttpStatus.FAILED_DEPENDENCY.equals(status)) {
            return true;
        }
        return status.isServerError();
    }

    /**
     * Decide whether a DittoRuntimeException is considered successful processing.
     * This happens with ThingPreconditionFailedException and PolicyPreconditionFailedException.
     * All DittoRuntimeException with status 412 Precondition Failed are considered success.
     *
     * @param dittoRuntimeException the DittoRuntimeException.
     * @return whether it is considered successful processing.
     */
    private static boolean isConsideredSuccess(final DittoRuntimeException dittoRuntimeException) {
        return HttpStatus.PRECONDITION_FAILED.equals(dittoRuntimeException.getHttpStatus());
    }

    /**
     * Reject an incoming message.
     */
    @FunctionalInterface
    public interface Reject {

        /**
         * Reject a message.
         *
         * @param shouldRedeliver whether the broker should redeliver.
         */
        void reject(boolean shouldRedeliver);

    }

}
