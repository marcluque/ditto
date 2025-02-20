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
package org.eclipse.ditto.things.service.persistence.actors.strategies.commands;

import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.entity.metadata.Metadata;
import org.eclipse.ditto.base.model.headers.DittoHeadersSettable;
import org.eclipse.ditto.base.model.headers.entitytag.EntityTag;
import org.eclipse.ditto.internal.utils.persistentactors.results.Result;
import org.eclipse.ditto.internal.utils.persistentactors.results.ResultFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.exceptions.ThingNotAccessibleException;
import org.eclipse.ditto.things.model.signals.commands.query.RetrieveThing;
import org.eclipse.ditto.things.model.signals.commands.query.RetrieveThingResponse;
import org.eclipse.ditto.things.model.signals.commands.query.ThingQueryCommand;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;

/**
 * This strategy handles the {@link RetrieveThing} command.
 */
@Immutable
final class RetrieveThingStrategy extends AbstractThingCommandStrategy<RetrieveThing> {

    /**
     * Constructs a new {@code RetrieveThingStrategy} object.
     */
    RetrieveThingStrategy() {
        super(RetrieveThing.class);
    }

    @Override
    public boolean isDefined(final Context<ThingId> context, @Nullable final Thing thing,
            final RetrieveThing command) {
        final boolean thingExists = Optional.ofNullable(thing)
                .map(t -> !t.isDeleted())
                .orElse(false);
        // when thing is null, there is nothing to retrieve.
        final boolean shouldRetrieveDeleted = thing != null && command.getDittoHeaders().shouldRetrieveDeleted();

        return Objects.equals(context.getState(), command.getEntityId()) && (thingExists || shouldRetrieveDeleted);
    }

    @Override
    protected Result<ThingEvent<?>> doApply(final Context<ThingId> context,
            @Nullable final Thing thing,
            final long nextRevision,
            final RetrieveThing command,
            @Nullable final Metadata metadata) {

        return ResultFactory.newQueryResult(command,
                appendETagHeaderIfProvided(command, getRetrieveThingResponse(thing, command), thing));
    }

    private static DittoHeadersSettable<?> getRetrieveThingResponse(@Nullable final Thing thing,
            final ThingQueryCommand<RetrieveThing> command) {
        if (thing != null) {
            return RetrieveThingResponse.of(command.getEntityId(), getThingJson(thing, command),
                    command.getDittoHeaders());
        } else {
            return notAccessible(command);
        }
    }

    private static JsonObject getThingJson(final Thing thing, final ThingQueryCommand<RetrieveThing> command) {
        return command.getSelectedFields()
                .map(selectedFields -> thing.toJson(command.getImplementedSchemaVersion(), selectedFields))
                .orElseGet(() -> thing.toJson(command.getImplementedSchemaVersion()));
    }

    private static ThingNotAccessibleException notAccessible(final ThingQueryCommand<?> command) {
        return new ThingNotAccessibleException(command.getEntityId(), command.getDittoHeaders());
    }

    @Override
    public Result<ThingEvent<?>> unhandled(final Context<ThingId> context, @Nullable final Thing thing,
            final long nextRevision, final RetrieveThing command) {
        return ResultFactory.newErrorResult(
                new ThingNotAccessibleException(context.getState(), command.getDittoHeaders()), command);
    }

    @Override
    public Optional<EntityTag> previousEntityTag(final RetrieveThing command, @Nullable final Thing previousEntity) {
        return nextEntityTag(command, previousEntity);
    }

    @Override
    public Optional<EntityTag> nextEntityTag(final RetrieveThing command, @Nullable final Thing newEntity) {
        return Optional.ofNullable(newEntity).flatMap(EntityTag::fromEntity);
    }
}
