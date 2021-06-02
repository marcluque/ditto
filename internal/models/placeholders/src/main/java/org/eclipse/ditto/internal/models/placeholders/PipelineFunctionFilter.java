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
package org.eclipse.ditto.internal.models.placeholders;


import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.internal.models.placeholders.filter.FilterFunction;
import org.eclipse.ditto.internal.models.placeholders.filter.FilterFunctions;

/**
 * Provides the {@code fn:filter(filterValue, rqlFunction, comparedValue)} function implementation.
 */
@Immutable
final class PipelineFunctionFilter implements PipelineFunction {

    private static final String FUNCTION_NAME = "filter";

    @Override
    public String getName() {
        return FUNCTION_NAME;
    }

    @Override
    public Signature getSignature() {
        return FilterFunctionSignature.INSTANCE;
    }

    @Override
    public PipelineElement apply(final PipelineElement value, final String paramsIncludingParentheses,
            final ExpressionResolver expressionResolver) {

        final Map<String, String> parameters = parseAndResolve(paramsIncludingParentheses, expressionResolver);

        return value.onResolved(valueThatShouldBeFilteredConditionally -> {

            final Optional<FilterFunction> rqlFunctionOpt =
                    FilterFunctions.fromName(parameters.get(RqlFunctionParam.NAME));
            final boolean shouldKeepValue = rqlFunctionOpt
                    .map(rqlFunction -> applyRqlFunction(parameters, rqlFunction))
                    .orElse(false);


            if (shouldKeepValue) {
                return PipelineElement.resolved(valueThatShouldBeFilteredConditionally);
            } else {
                return PipelineElement.unresolved();
            }

        });
    }

    private Boolean applyRqlFunction(final Map<String, String> parameters, final FilterFunction rqlFunction) {
        if (rqlFunction == FilterFunctions.EXISTS) {
            final String filterValue = parameters.get(FilterValueParam.NAME);
            return rqlFunction.apply(filterValue);
        } else {
            final String filterValue = parameters.get(FilterValueParam.NAME);
            final String comparedValue = parameters.get(ComparedValueParam.NAME);
            return rqlFunction.apply(filterValue, comparedValue);
        }
    }

    private Map<String, String> parseAndResolve(final String paramsIncludingParentheses,
            final ExpressionResolver expressionResolver) {

        final boolean hasComparedValue = hasComparedValue(paramsIncludingParentheses);
        final List<PipelineElement> parameterElements = getPipelineElements(paramsIncludingParentheses,
                expressionResolver, hasComparedValue);

        final Map<String, String> parameters = new HashMap<>();

        final PipelineElement filterValueParamElement = parameterElements.get(0);
        final String filterValueParam = filterValueParamElement.toOptional().orElse("");
        parameters.put(FilterValueParam.NAME, filterValueParam);

        final PipelineElement rqlFunctionParamElement = parameterElements.get(1);
        final String rqlFunctionParam = rqlFunctionParamElement.toOptional().orElseThrow(() ->
                PlaceholderFunctionSignatureInvalidException.newBuilder(paramsIncludingParentheses, this)
                        .build());
        parameters.put(RqlFunctionParam.NAME, rqlFunctionParam);

        if (hasComparedValue) {
            final PipelineElement comparedValueParamElement = parameterElements.get(2);
            final String comparedValueParam = comparedValueParamElement.toOptional().orElse("");
            parameters.put(ComparedValueParam.NAME, comparedValueParam);
        }

        return parameters;
    }

    private boolean hasComparedValue(final String paramsIncludingParentheses) {
        final Pattern pattern =
                Pattern.compile(PipelineFunctionParameterResolverFactory.ParameterResolver.EXISTS_FUNCTION);
        final Matcher matcher = pattern.matcher(paramsIncludingParentheses);
        return !matcher.matches();
    }

    private List<PipelineElement> getPipelineElements(final String paramsIncludingParentheses,
            final ExpressionResolver expressionResolver, final boolean hasComparedValue) {
        if (hasComparedValue) {
            return PipelineFunctionParameterResolverFactory.forTripleStringOrPlaceholderParameter()
                    .apply(paramsIncludingParentheses, expressionResolver, this);
        } else {
            return PipelineFunctionParameterResolverFactory.forDoubleStringOrPlaceholderParameter()
                    .apply(paramsIncludingParentheses, expressionResolver, this);
        }
    }

    /**
     * Describes the signature of the {@code filter(filterValue, rqlFunction, comparedValue)} function.
     */
    static final class FilterFunctionSignature implements Signature {

        private static final FilterFunctionSignature INSTANCE = new FilterFunctionSignature();

        private final ParameterDefinition<String> filterValueParam;
        private final ParameterDefinition<String> rqlFunctionParam;
        private final ParameterDefinition<String> comparedValueParam;

        private FilterFunctionSignature() {
            filterValueParam = new FilterValueParam();
            rqlFunctionParam = new RqlFunctionParam();
            comparedValueParam = new ComparedValueParam();
        }

        @Override
        public List<ParameterDefinition<?>> getParameterDefinitions() {
            return Arrays.asList(filterValueParam, rqlFunctionParam, comparedValueParam);
        }

        @Override
        public String toString() {
            return renderSignature();
        }

    }

    /**
     * The param that contains the value that should be taken into account for filtering.
     */
    private static final class FilterValueParam implements ParameterDefinition<String> {

        static final String NAME = "filterValue";

        private FilterValueParam() {
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public Class<String> getType() {
            return String.class;
        }

        @Override
        public String getDescription() {
            return "Specifies the value that should be taken into account for filtering. " +
                    "It may be a constant in single or double quotes or a placeholder";
        }

    }

    /**
     * Describes param that contains the rql function that should be applied for comparison.
     */
    private static final class RqlFunctionParam implements ParameterDefinition<String> {

        static final String NAME = "rqlFunction";

        private RqlFunctionParam() {
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public Class<String> getType() {
            return String.class;
        }

        @Override
        public String getDescription() {
            return "Specifies the rql function that should be applied for comparison.";
        }

    }

    /**
     * The param that contains the value that should compared to {@link FilterValueParam}.
     */
    private static final class ComparedValueParam implements ParameterDefinition<String> {

        static final String NAME = "comparedValue";

        private ComparedValueParam() {
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public Class<String> getType() {
            return String.class;
        }

        @Override
        public String getDescription() {
            return "The param that contains the value that should compared to the filter value.";
        }

    }

}
