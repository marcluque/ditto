/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.things.service.persistence.actors.strategies.commands;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.Optional;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.commands.Command;
import org.eclipse.ditto.rql.parser.RqlPredicateParser;
import org.eclipse.ditto.rql.query.filter.QueryFilterCriteriaFactory;
import org.eclipse.ditto.rql.query.things.ThingPredicateVisitor;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.signals.commands.exceptions.ThingConditionFailedException;
import org.eclipse.ditto.things.model.signals.commands.modify.CreateThing;

@Immutable
final class ThingConditionValidator {

    private ThingConditionValidator() {
        throw new AssertionError();
    }

    /**
     * Validates if the given condition matches the actual thing state.
     *
     * @param command the command used for checking if validation should be applied.
     * @param condition the condition which should be verified against the thing.
     * @param entity the actual thing entity.
     * @return either void or the ThingConditionFailedException in case the condition couldN't be validated.
     */
    public static Optional<ThingConditionFailedException> validate(final Command<?> command,
            final String condition, @Nullable final Thing entity) {

        checkNotNull(command, "Command");

        final Optional<ThingConditionFailedException> result;
        if (!(command instanceof CreateThing) && entity != null) {
            result = validateConditionForEntity(condition, entity, command.getDittoHeaders());
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private static Optional<ThingConditionFailedException> validateConditionForEntity(final String condition,
            final Thing entity,
            final DittoHeaders dittoHeaders) {

        final var criteria = QueryFilterCriteriaFactory
                .modelBased(RqlPredicateParser.getInstance())
                .filterCriteria(condition, dittoHeaders);

        final var predicate = ThingPredicateVisitor.apply(criteria);

        final ThingConditionFailedException validationError;
        if (predicate.test(entity)) {
            validationError = null;
        } else {
            validationError = ThingConditionFailedException.newBuilder(dittoHeaders).build();
        }
        return Optional.ofNullable(validationError);
    }

}
