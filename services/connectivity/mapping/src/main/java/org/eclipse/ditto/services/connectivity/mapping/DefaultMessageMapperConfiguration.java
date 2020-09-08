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
package org.eclipse.ditto.services.connectivity.mapping;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.concurrent.Immutable;

/**
 * Default implementation for a message mapper configuration.
 */
@Immutable
public final class DefaultMessageMapperConfiguration implements MessageMapperConfiguration {

    private final String id;
    private final Map<String, String> properties;
    private final Map<String, String> incomingConditions;
    private final Map<String, String> outgoingConditions;

    private DefaultMessageMapperConfiguration(final String id, final Map<String, String> properties,
    final Map<String, String> incomingConditions, final Map<String, String> outgoingConditions) {
        this.id = id;
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
        this.incomingConditions = Collections.unmodifiableMap(new HashMap<>(incomingConditions));
        this.outgoingConditions = Collections.unmodifiableMap(new HashMap<>(outgoingConditions));
    }

    /**
     * Constructs a new {@code DefaultMessageMapperConfiguration} of the given map.
     *
     * @param id the id of the mapper
     * @param configuration the map holding configuration properties.
     * @return the instance.
     * @throws NullPointerException if {@code id} or {@code configuration} is {@code null}.
     */
    public static DefaultMessageMapperConfiguration of(final String id, final Map<String, String> configuration) {
        checkNotNull(id, "id");
        checkNotNull(configuration, "configuration");
        return new DefaultMessageMapperConfiguration(id, configuration, Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Constructs a new {@code DefaultMessageMapperConfiguration} of the given map.
     *
     * @param id the id of the mapper.
     * @param configuration the map holding configuration properties.
     * @param incomingConditions the conditions to be checked before mapping incoming messages.
     * @return the instance.
     * @throws NullPointerException if {@code id}, {@code configuration} or {@code conditions} is {@code null}.
     *
     * @since 1.3.0
     */
    public static DefaultMessageMapperConfiguration of(final String id, final Map<String, String> configuration,
            final Map<String, String> incomingConditions, final Map<String, String> outgoingConditions) {
        checkNotNull(id, "id");
        checkNotNull(configuration, "configuration");
        checkNotNull(incomingConditions, "incomingConditions");
        checkNotNull(outgoingConditions, "outgoingConditions");
        return new DefaultMessageMapperConfiguration(id, configuration, incomingConditions, outgoingConditions);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Map<String, String> getIncomingConditions() {
        return incomingConditions;
    }

    @Override
    public Map<String, String> getOutgoingConditions() {
        return outgoingConditions;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final DefaultMessageMapperConfiguration that = (DefaultMessageMapperConfiguration) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(properties, that.properties) &&
                Objects.equals(incomingConditions, that.incomingConditions) &&
                Objects.equals(outgoingConditions, that.outgoingConditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, properties, incomingConditions, outgoingConditions);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "id=" + id +
                ", properties=" + properties +
                ", incomingConditions=" + incomingConditions +
                ", outgoingConditions=" + outgoingConditions +
                "]";
    }
}
