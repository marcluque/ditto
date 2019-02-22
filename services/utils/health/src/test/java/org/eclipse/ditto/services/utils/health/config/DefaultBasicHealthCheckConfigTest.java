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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.time.Duration;

import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link org.eclipse.ditto.services.utils.health.config.DefaultBasicHealthCheckConfig}.
 */
public final class DefaultBasicHealthCheckConfigTest {

    private static Config healthCheckConfig;

    @BeforeClass
    public static void initTestFixture() {
        healthCheckConfig = ConfigFactory.load("health-check-test");
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(DefaultBasicHealthCheckConfig.class, areImmutable());
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(DefaultBasicHealthCheckConfig.class)
                .usingGetClass()
                .verify();
    }

    @Test
    public void testSerializationAndDeserialization() throws IOException, ClassNotFoundException {
        final DefaultBasicHealthCheckConfig underTest = DefaultBasicHealthCheckConfig.of(healthCheckConfig);

        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final ObjectOutput objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(underTest);
        objectOutputStream.close();

        final byte[] underTestSerialized = byteArrayOutputStream.toByteArray();

        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(underTestSerialized);
        final ObjectInput objectInputStream = new ObjectInputStream(byteArrayInputStream);
        final Object underTestDeserialized = objectInputStream.readObject();

        assertThat(underTestDeserialized).isEqualTo(underTest);
    }

    @Test
    public void underTestReturnsDefaultValuesIfBaseConfigIsEmpty() {
        final DefaultBasicHealthCheckConfig underTest = DefaultBasicHealthCheckConfig.of(ConfigFactory.empty());

        assertThat(underTest.isEnabled())
                .as("isEnabled")
                .isEqualTo(BasicHealthCheckConfig.HealthCheckConfigValue.ENABLED.getDefaultValue());
        assertThat(underTest.getInterval())
                .as("interval")
                .isEqualTo(BasicHealthCheckConfig.HealthCheckConfigValue.INTERVAL.getDefaultValue());
    }

    @Test
    public void underTestReturnsValuesOfConfigFile() {
        final DefaultBasicHealthCheckConfig underTest = DefaultBasicHealthCheckConfig.of(healthCheckConfig);

        assertThat(underTest.isEnabled())
                .as("isEnabled")
                .isTrue();
        assertThat(underTest.getInterval())
                .as("interval")
                .isEqualTo(Duration.ofSeconds(13L));
    }

}