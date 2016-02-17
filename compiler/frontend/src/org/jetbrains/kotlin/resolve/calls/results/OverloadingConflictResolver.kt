/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.results

import gnu.trove.THashSet
import gnu.trove.TObjectHashingStrategy
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.calls.context.CheckArgumentTypesMode
import org.jetbrains.kotlin.resolve.calls.inference.toHandle
import org.jetbrains.kotlin.resolve.calls.model.MutableResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionMutableResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.Specificity
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.getSpecificityRelationTo

class OverloadingConflictResolver(private val builtIns: KotlinBuiltIns) {

    fun <D : CallableDescriptor> findMaximallySpecific(
            candidates: Set<MutableResolvedCall<D>>,
            checkArgumentsMode: CheckArgumentTypesMode,
            discriminateGenerics: Boolean,
            isDebuggerContext: Boolean
    ): MutableResolvedCall<D>? =
            if (candidates.size <= 1)
                candidates.firstOrNull()
            else when (checkArgumentsMode) {
                CheckArgumentTypesMode.CHECK_CALLABLE_TYPE ->
                    uniquifyCandidatesSet(candidates).filter {
                        isDefinitelyMostSpecific(it, candidates) {
                            call1, call2 ->
                            isNotLessSpecificCallableReference(call1.resultingDescriptor, call2.resultingDescriptor)
                        }
                    }.singleOrNull()

                CheckArgumentTypesMode.CHECK_VALUE_ARGUMENTS ->
                    findMaximallySpecificCall(candidates, discriminateGenerics, isDebuggerContext)
            }

    fun <D : CallableDescriptor> findMaximallySpecificVariableAsFunctionCalls(candidates: Set<MutableResolvedCall<D>>): Set<MutableResolvedCall<D>> {
        val variableCalls = candidates.mapTo(newResolvedCallSet<MutableResolvedCall<VariableDescriptor>>(candidates.size)) {
            if (it is VariableAsFunctionMutableResolvedCall)
                it.variableCall
            else
                throw AssertionError("Regular call among variable-as-function calls: $it")
        }

        val maxSpecificVariableCall = findMaximallySpecificCall(variableCalls, false, false) ?: return emptySet()

        return candidates.filterTo(newResolvedCallSet<MutableResolvedCall<D>>(2)) {
            it.resultingVariableDescriptor == maxSpecificVariableCall.resultingDescriptor
        }
    }

    private fun <D : CallableDescriptor> findMaximallySpecificCall(
            candidates: Set<MutableResolvedCall<D>>,
            discriminateGenerics: Boolean,
            isDebuggerContext: Boolean
    ): MutableResolvedCall<D>? {
        val filteredCandidates = uniquifyCandidatesSet(candidates)

        if (filteredCandidates.size <= 1) return filteredCandidates.singleOrNull()

        val conflictingCandidates = filteredCandidates.map {
            candidateCall ->
            FlatSignature.createFromResolvedCall(candidateCall)
        }

        val bestCandidatesByParameterTypes = conflictingCandidates.filter {
            candidate ->
            isMostSpecific(candidate, conflictingCandidates) {
                call1, call2 ->
                isNotLessSpecificCallWithArgumentMapping(call1, call2, discriminateGenerics)
            }
        }

        return bestCandidatesByParameterTypes.exactMaxWith {
            call1, call2 ->
            isOfNotLessSpecificShape(call1, call2) && isOfNotLessSpecificVisibilityForDebugger(call1, call2, isDebuggerContext)
        }?.origin
    }

    private inline fun <C : Any> Collection<C>.exactMaxWith(isNotWorse: (C, C) -> Boolean): C? {
        var result: C? = null
        for (candidate in this) {
            if (result == null || isNotWorse(candidate, result)) {
                result = candidate
            }
        }
        if (result == null) return null
        if (any { it != result && isNotWorse(it, result!!) }) {
            return null
        }
        return result
    }

    private inline fun <C> isMostSpecific(candidate: C, candidates: Collection<C>, isNotLessSpecific: (C, C) -> Boolean): Boolean =
            candidates.all {
                other ->
                candidate === other ||
                isNotLessSpecific(candidate, other)
            }

    private inline fun <C> isDefinitelyMostSpecific(candidate: C, candidates: Collection<C>, isNotLessSpecific: (C, C) -> Boolean): Boolean =
            candidates.all {
                other ->
                candidate === other ||
                isNotLessSpecific(candidate, other) && !isNotLessSpecific(other, candidate)
            }

    /**
     * `call1` is not less specific than `call2`
     */
    private fun <D : CallableDescriptor> isNotLessSpecificCallWithArgumentMapping(
            call1: FlatSignature<MutableResolvedCall<D>>,
            call2: FlatSignature<MutableResolvedCall<D>>,
            discriminateGenerics: Boolean
    ): Boolean {
        return tryCompareDescriptorsFromScripts(call1.candidateDescriptor(), call2.candidateDescriptor()) ?:
               compareCallsByUsedArguments(call1, call2, discriminateGenerics)
    }

    /**
     * Returns `true` if `d1` is definitely not less specific than `d2`,
     * `false` otherwise.
     */
    private fun <D : CallableDescriptor> compareCallsByUsedArguments(
            call1: FlatSignature<MutableResolvedCall<D>>,
            call2: FlatSignature<MutableResolvedCall<D>>,
            discriminateGenerics: Boolean
    ): Boolean =
            isSignatureNotLessSpecific(call1, call2, call1.callHandle(),
                                       if (discriminateGenerics)
                                           SpecificityComparisonDiscriminatingGenerics
                                       else
                                           DefaultSpecificityComparison)

    private abstract inner class SpecificityComparisonWithNumerics : SpecificityComparisonCallbacks<MutableResolvedCall<*>> {
        override fun isTypeNotLessSpecific(type1: KotlinType, type2: KotlinType): Boolean? =
                numericTypeMoreSpecific(type1, type2)
    }

    private val DefaultSpecificityComparison = object : SpecificityComparisonWithNumerics() {
        override fun isNotLessSpecificByOrigin(
                signature1: FlatSignature<MutableResolvedCall<*>>,
                signature2: FlatSignature<MutableResolvedCall<*>>
        ): Boolean? =
                null
    }

    private val SpecificityComparisonDiscriminatingGenerics = object : SpecificityComparisonWithNumerics() {
        override fun isNotLessSpecificByOrigin(
                signature1: FlatSignature<MutableResolvedCall<*>>,
                signature2: FlatSignature<MutableResolvedCall<*>>
        ): Boolean? {
            val isGeneric1 = signature1.isGeneric
            val isGeneric2 = signature2.isGeneric
            // generic loses to non-generic
            if (isGeneric1 && !isGeneric2) return false
            if (!isGeneric1 && isGeneric2) return true
            // two generics are non-comparable
            if (isGeneric1 && isGeneric2) return false
            // otherwise continue comparing signatures
            return null
        }
    }

    private fun <D: CallableDescriptor> isOfNotLessSpecificShape(
            call1: FlatSignature<MutableResolvedCall<D>>,
            call2: FlatSignature<MutableResolvedCall<D>>
    ): Boolean {
        val hasVarargs1 = call1.hasVarargs
        val hasVarargs2 = call2.hasVarargs
        if (hasVarargs1 && !hasVarargs2) return false
        if (!hasVarargs1 && hasVarargs2) return true

        if (call1.numDefaults > call2.numDefaults) {
            return false
        }

        return true
    }

    private fun <D: CallableDescriptor> isOfNotLessSpecificVisibilityForDebugger(
            call1: FlatSignature<MutableResolvedCall<D>>,
            call2: FlatSignature<MutableResolvedCall<D>>,
            isDebuggerContext: Boolean
    ): Boolean {
        if (isDebuggerContext) {
            val isMoreVisible1 = Visibilities.compare(call1.descriptorVisibility(), call2.descriptorVisibility())
            if (isMoreVisible1 != null && isMoreVisible1 < 0) return false
        }

        return true
    }

    /**
     * Returns `true` if `d1` is definitely not less specific than `d2`,
     * `false` if `d1` is definitely less specific than `d2`,
     * `null` if undecided.
     */
    private fun tryCompareDescriptorsFromScripts(d1: CallableDescriptor, d2: CallableDescriptor): Boolean? {
        val containingDeclaration1 = d1.containingDeclaration
        val containingDeclaration2 = d2.containingDeclaration

        if (containingDeclaration1 is ScriptDescriptor && containingDeclaration2 is ScriptDescriptor) {
            when {
                containingDeclaration1.priority > containingDeclaration2.priority -> return true
                containingDeclaration1.priority < containingDeclaration2.priority -> return false
            }
        }
        return null
    }

    /**
     * Returns `true` if `d1` is definitely not less specific than `d2`,
     * `false` if `d1` is definitely less specific than `d2`,
     * `null` if undecided.
     */
    private fun compareFunctionParameterTypes(f: CallableDescriptor, g: CallableDescriptor): Boolean {
        val fReceiverType = f.extensionReceiverType
        val gReceiverType = g.extensionReceiverType
        if (fReceiverType != null && gReceiverType != null) {
            if (!typeNotLessSpecific(fReceiverType, gReceiverType)) {
                return false
            }
        }

        val fParams = f.valueParameters
        val gParams = g.valueParameters

        val fSize = fParams.size
        val gSize = gParams.size

        if (fSize != gSize) return false

        for (i in 0..fSize - 1) {
            val fParam = fParams[i]
            val gParam = gParams[i]

            val fParamIsVararg = fParam.varargElementType != null
            val gParamIsVararg = gParam.varargElementType != null

            if (fParamIsVararg != gParamIsVararg) {
                return false
            }

            val fParamType = getVarargElementTypeOrType(fParam)
            val gParamType = getVarargElementTypeOrType(gParam)

            if (!typeNotLessSpecific(fParamType, gParamType)) {
                return false
            }
        }

        return true
    }

    private fun isNotLessSpecificCallableReference(f: CallableDescriptor, g: CallableDescriptor): Boolean =
            // TODO should we "discriminate generic descriptors" for callable references?
            tryCompareDescriptorsFromScripts(f, g) ?:
            compareFunctionParameterTypes(f, g)

    private fun getVarargElementTypeOrType(parameterDescriptor: ValueParameterDescriptor): KotlinType =
            parameterDescriptor.varargElementType ?: parameterDescriptor.type

    private fun typeNotLessSpecific(specific: KotlinType, general: KotlinType): Boolean {
        val isSubtype = KotlinTypeChecker.DEFAULT.isSubtypeOf(specific, general) || numericTypeMoreSpecific(specific, general)

        if (!isSubtype) return false

        if (isDefinitelyLessSpecificByTypeSpecificity(specific, general)) return false

        return true
    }

    private fun isDefinitelyLessSpecificByTypeSpecificity(specific: KotlinType, general: KotlinType): Boolean {
        val sThanG = specific.getSpecificityRelationTo(general)
        val gThanS = general.getSpecificityRelationTo(specific)
        return sThanG == Specificity.Relation.LESS_SPECIFIC &&
               gThanS != Specificity.Relation.LESS_SPECIFIC
    }

    private fun numericTypeMoreSpecific(specific: KotlinType, general: KotlinType): Boolean {
        val _double = builtIns.doubleType
        val _float = builtIns.floatType
        val _long = builtIns.longType
        val _int = builtIns.intType
        val _byte = builtIns.byteType
        val _short = builtIns.shortType

        when {
            TypeUtils.equalTypes(specific, _double) && TypeUtils.equalTypes(general, _float) -> return true
            TypeUtils.equalTypes(specific, _int) -> {
                when {
                    TypeUtils.equalTypes(general, _long) -> return true
                    TypeUtils.equalTypes(general, _byte) -> return true
                    TypeUtils.equalTypes(general, _short) -> return true
                }
            }
            TypeUtils.equalTypes(specific, _short) && TypeUtils.equalTypes(general, _byte) -> return true
        }

        return false
    }

    private val CallableDescriptor.extensionReceiverType: KotlinType?
        get() = extensionReceiverParameter?.type

    companion object {
        // Different smartcasts may lead to the same candidate descriptor wrapped into different ResolvedCallImpl objects
        @Suppress("CAST_NEVER_SUCCEEDS")
        private fun <D : CallableDescriptor> uniquifyCandidatesSet(candidates: Collection<MutableResolvedCall<D>>): Set<MutableResolvedCall<D>> =
                THashSet<MutableResolvedCall<D>>(candidates.size, getCallHashingStrategy<MutableResolvedCall<D>>()).apply { addAll(candidates) }

        @Suppress("CAST_NEVER_SUCCEEDS")
        private fun <C> newResolvedCallSet(expectedSize: Int): MutableSet<C> =
                THashSet<C>(expectedSize, getCallHashingStrategy<C>())

        private object ResolvedCallHashingStrategy : TObjectHashingStrategy<ResolvedCall<*>> {
            override fun equals(call1: ResolvedCall<*>?, call2: ResolvedCall<*>?): Boolean =
                    if (call1 != null && call2 != null)
                        call1.resultingDescriptor == call2.resultingDescriptor
                    else
                        call1 == call2

            override fun computeHashCode(call: ResolvedCall<*>?): Int =
                    call?.resultingDescriptor?.hashCode() ?: 0
        }

        private val MutableResolvedCall<*>.resultingVariableDescriptor: VariableDescriptor
            get() = (this as VariableAsFunctionResolvedCall).variableCall.resultingDescriptor

        @Suppress("UNCHECKED_CAST", "CAST_NEVER_SUCCEEDS")
        private fun <C> getCallHashingStrategy() =
                ResolvedCallHashingStrategy as TObjectHashingStrategy<C>

    }
}

internal fun <D : CallableDescriptor> FlatSignature<ResolvedCall<D>>.candidateDescriptor() =
        origin.candidateDescriptor.original

internal fun <D : CallableDescriptor> FlatSignature<ResolvedCall<D>>.callHandle() =
        origin.call.toHandle()

internal fun <D : CallableDescriptor> FlatSignature<ResolvedCall<D>>.descriptorVisibility() =
        candidateDescriptor().visibility
