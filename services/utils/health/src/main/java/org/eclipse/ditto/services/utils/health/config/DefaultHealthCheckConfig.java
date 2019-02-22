/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.utils.health.config;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

import com.typesafe.config.Config;

/**
 * This class is the default implementation of {@link HealthCheckConfig}.
 */
public final class DefaultHealthCheckConfig implements HealthCheckConfig, Serializable {

    private static final long serialVersionUID = 5197182470324016557L;

    private final BasicHealthCheckConfig basicHealthCheckConfig;
    private final PersistenceConfig persistenceConfig;

    private DefaultHealthCheckConfig(final BasicHealthCheckConfig basicHealthCheckConfig,
            final PersistenceConfig persistenceConfig) {

        this.basicHealthCheckConfig = basicHealthCheckConfig;
        this.persistenceConfig = persistenceConfig;
    }

    /**
     * Returns an instance of {@code DefaultHealthCheckConfig} based on the settings of the specified Config.
     *
     * @param config is supposed to provide the settings of the health check as nested Config.
     * @return the instance.
     * @throws org.eclipse.ditto.services.utils.config.DittoConfigError if {@code config} is invalid.
     */
    public static DefaultHealthCheckConfig of(final Config config) {
        final DefaultBasicHealthCheckConfig basicHealthCheckConfig = DefaultBasicHealthCheckConfig.of(config);

        final PersistenceConfig persistenceConfig =
                DefaultPersistenceConfig.of(config.getConfig(basicHealthCheckConfig.getConfigPath()));

        return new DefaultHealthCheckConfig(basicHealthCheckConfig, persistenceConfig);
    }

    @Override
    public boolean isEnabled() {
        return basicHealthCheckConfig.isEnabled();
    }

    @Override
    public Duration getInterval() {
        return basicHealthCheckConfig.getInterval();
    }

    @Override
    public PersistenceConfig getPersistenceConfig() {
        return persistenceConfig;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultHealthCheckConfig that = (DefaultHealthCheckConfig) o;
        return Objects.equals(basicHealthCheckConfig, that.basicHealthCheckConfig) &&
                Objects.equals(persistenceConfig, that.persistenceConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(basicHealthCheckConfig, persistenceConfig);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "basicHealthCheckConfig=" + basicHealthCheckConfig +
                ", persistenceConfig=" + persistenceConfig +
                "]";
    }

}
