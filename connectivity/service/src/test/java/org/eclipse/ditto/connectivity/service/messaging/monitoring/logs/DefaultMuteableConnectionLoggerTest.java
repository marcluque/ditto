/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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

package org.eclipse.ditto.connectivity.service.messaging.monitoring.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;

import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.Signal;
import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.LogEntry;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionLogs;
import org.eclipse.ditto.connectivity.service.messaging.TestConstants;
import org.eclipse.ditto.connectivity.service.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingIdInvalidException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;

/**
 * Unit test for {@link DefaultMuteableConnectionLogger}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class DefaultMuteableConnectionLoggerTest {

    private static final ConnectionMonitor.InfoProvider INFO_PROVIDER = InfoProviderFactory.empty();
    private static final String MESSAGE = "something happened to {0}";
    private static final Object[] MESSAGE_ARGUMENTS = {"ditto"};
    private static final ThingId THING_ID = ThingId.of("the:thing");
    private static final Signal<?> SIGNAL =
            RetrieveConnectionLogs.of(TestConstants.createRandomConnectionId(), DittoHeaders.empty());
    private static final DittoRuntimeException DITTO_RUNTIME_EXCEPTION =
            ThingIdInvalidException.newBuilder("invalid").build();
    private static final Exception EXCEPTION = new IllegalArgumentException();

    @Mock
    private ConnectionLogger delegate;

    @Test
    public void mute() {
        final MuteableConnectionLogger logger = logger();
        logger.mute();
        assertThat(logger.isMuted()).isTrue();
    }

    @Test
    public void unmute() {
        final MuteableConnectionLogger logger = logger();
        logger.unmute();
        assertThat(logger.isMuted()).isFalse();
    }

    @Test
    public void testMutingAndUnmuting() {
        final MuteableConnectionLogger logger = logger();
        // initially should be muted
        assertThat(logger.isMuted()).isTrue();
        // can be unmuted
        logger.unmute();
        assertThat(logger.isMuted()).isFalse();
        // and be muted again
        logger.mute();
        assertThat(logger.isMuted()).isTrue();
    }

    @Test
    public void mutedLoggerShouldNotCallDelegate() {
        final MuteableConnectionLogger logger = logger();

        logger.success(INFO_PROVIDER);
        logger.success(INFO_PROVIDER, MESSAGE, MESSAGE_ARGUMENTS);
        logger.failure(INFO_PROVIDER, DITTO_RUNTIME_EXCEPTION);
        logger.failure(SIGNAL, DITTO_RUNTIME_EXCEPTION);
        logger.failure(INFO_PROVIDER);
        logger.failure(INFO_PROVIDER, MESSAGE, MESSAGE_ARGUMENTS);
        logger.exception(INFO_PROVIDER, EXCEPTION);
        logger.exception(INFO_PROVIDER);
        logger.exception(INFO_PROVIDER, MESSAGE, MESSAGE_ARGUMENTS);
        logger.getLogs();

        verifyZeroInteractions(delegate);
    }

    @Test
    public void success() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.success(INFO_PROVIDER);
        verify(delegate).success(INFO_PROVIDER);
    }

    @Test
    public void success1() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.success(INFO_PROVIDER, MESSAGE, THING_ID);
        verify(delegate).success(INFO_PROVIDER, MESSAGE, THING_ID);
    }

    @Test
    public void failure() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.failure(INFO_PROVIDER, DITTO_RUNTIME_EXCEPTION);
        verify(delegate).failure(INFO_PROVIDER, DITTO_RUNTIME_EXCEPTION);
    }

    @Test
    public void failure1() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.failure(INFO_PROVIDER, MESSAGE, THING_ID);
        verify(delegate).failure(INFO_PROVIDER, MESSAGE, THING_ID);
    }

    @Test
    public void exception() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.exception(INFO_PROVIDER, DITTO_RUNTIME_EXCEPTION);
        verify(delegate).exception(INFO_PROVIDER, DITTO_RUNTIME_EXCEPTION);
    }

    @Test
    public void exception1() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        unmuted.exception(INFO_PROVIDER, MESSAGE, THING_ID);
        verify(delegate).exception(INFO_PROVIDER, MESSAGE, THING_ID);
    }

    @Test
    public void getLogs() {
        final DefaultMuteableConnectionLogger unmuted = unmuted();

        final Collection<LogEntry> entries = Mockito.mock(Collection.class);
        when(delegate.getLogs()).thenReturn(entries);

        assertThat(unmuted.getLogs()).isEqualTo(entries);
        verify(delegate).getLogs();
    }

    @Test
    public void exceptionInLoggingLeadsToExceptionalDelegate() {
        final var randomConnectionId = TestConstants.createRandomConnectionId();
        final var mockDelegate = Mockito.mock(ConnectionLogger.class);
        final var exception = new IllegalArgumentException();
        final var expected = logger(randomConnectionId, new ExceptionalConnectionLogger(randomConnectionId, exception));
        expected.unmute();
        doThrow(exception).when(mockDelegate).success(INFO_PROVIDER);
        final var underTest = logger(randomConnectionId, mockDelegate);
        underTest.unmute();

        assertThatNoException().as("Exception is not propagated").isThrownBy(() -> underTest.success(INFO_PROVIDER));
        assertThat(underTest).as("delegate changes to ExceptionalConnectionLogger").isEqualTo(expected);
    }

    @Test
    public void testEqualsAndHashcode() {
        EqualsVerifier.forClass(DefaultMuteableConnectionLogger.class)
                .suppress(Warning.NONFINAL_FIELDS)
                .withIgnoredFields("logger")
                .verify();
    }

    private DefaultMuteableConnectionLogger unmuted() {
        final DefaultMuteableConnectionLogger logger = logger();
        logger.unmute();
        return logger;
    }

    private DefaultMuteableConnectionLogger logger() {
        return new DefaultMuteableConnectionLogger(TestConstants.createRandomConnectionId(), delegate);
    }

    private static DefaultMuteableConnectionLogger logger(final ConnectionId connectionId,
            final ConnectionLogger delegate) {

        return new DefaultMuteableConnectionLogger(connectionId, delegate);
    }

}
