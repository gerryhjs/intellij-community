// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import com.intellij.util.Consumer
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyze
import org.jetbrains.kotlin.idea.caches.resolve.variableCallOrThis
import org.jetbrains.kotlin.idea.core.compareDescriptors
import org.jetbrains.kotlin.idea.util.getReceiverTargetDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

class KotlinHighlightReceiverUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (!Registry.`is`("kotlin.receiver.usage.highlighting")) return null
        val receiverInfo = ReceiverInfoSearcher.findReceiverInfoForUsageHighlighting(target) ?: return null
        return KotlinHighlightReceiverUsagesHandler(receiverInfo, editor)
    }
}

object ReceiverInfoSearcher {
    fun findReceiverInfoForUsageHighlighting(target: PsiElement): ReceiverInfo? =
        checkIfInThisReference(target) ?: checkIfInReceiverTypeReference(target)

    private fun checkIfInReceiverTypeReference(target: PsiElement): ReceiverInfo.Function? {
        return target.parents(true)
            .takeWhile { e -> e !is KtFunction }
            .filterIsInstance<KtTypeReference>()
            .firstOrNull { e -> isReceiverReference(e) }
            ?.let { receiverRef -> receiverRef.parent as? KtNamedFunction }
            ?.takeIf { func -> func.hasBody() }
            ?.let { func -> ReceiverInfo.Function(func) }
    }

    private fun isReceiverReference(element: KtTypeReference): Boolean =
        (element.parent as? KtNamedFunction)?.receiverTypeReference == element

    private fun checkIfInThisReference(target: PsiElement): ReceiverInfo? {
        if (target.elementType != KtTokens.THIS_KEYWORD) return null
        val refExpr = target.parent as? KtReferenceExpression ?: return null
        val bindingContext = refExpr.analyze(BodyResolveMode.FULL)
        val resolvedCall = refExpr.getResolvedCall(bindingContext) ?: return null

        findReceiverByThisRef(resolvedCall)?.let { receiverTypeReference ->
            return receiverTypeReference
        }

        findReceiverByThisCall(resolvedCall, refExpr, bindingContext)?.let { receiverTypeReference ->
            return receiverTypeReference
        }

        return null
    }

    /**
     *
     * processes `this()` within a following case:
     * ```
     * fun String.foo() {
     *   this()
     * }
     *
     * operator fun String.invoke() {}
     * ```
     * */
    private fun findReceiverByThisCall(
        resolvedCall: ResolvedCall<out CallableDescriptor>,
        refExpr: KtReferenceExpression,
        bindingContext: BindingContext
    ): ReceiverInfo? {
        if ((refExpr.parent as? KtThisExpression)?.parent !is KtCallExpression) return null

        val call = resolvedCall.variableCallOrThis()
        val extensionReceiver = call.extensionReceiver
        val dispatchReceiver = call.dispatchReceiver
        if (extensionReceiver == null && dispatchReceiver == null) return null

        val receiverValue = extensionReceiver ?: dispatchReceiver
        if (receiverValue is ImplicitReceiver) return null

        val functionDescriptor = receiverValue.getReceiverTargetDescriptor(bindingContext) ?: return null
        return functionDescriptor.toInfo()
    }

    private fun findReceiverByThisRef(resolvedCall: ResolvedCall<out CallableDescriptor>): ReceiverInfo? {
        val receiverParameterDescriptor = resolvedCall.resultingDescriptor as? ReceiverParameterDescriptor ?: return null
        val descriptor = receiverParameterDescriptor.containingDeclaration
        return descriptor.toInfo()
    }

    private fun DeclarationDescriptor.toInfo(): ReceiverInfo? = when (val psi = findPsi()) {
        is KtFunctionLiteral -> ReceiverInfo.Lambda(psi)
        is KtNamedFunction -> ReceiverInfo.Function(psi)
        else -> null
    }
}

sealed class ReceiverInfo {
    abstract val psi: KtFunction
    protected abstract fun getDescriptor(bindingContext: BindingContext): CallableDescriptor?

    fun collectReceiverUsages(consumer: (TextRange) -> Unit) {
        val bindingContext = psi.safeAnalyze(BodyResolveMode.FULL)
        val callableDescriptor = getDescriptor(bindingContext) ?: return
        processInternalReferences(bindingContext, ReceiverUsageCollector(callableDescriptor, consumer))
    }

    class Function(override val psi: KtFunction) : ReceiverInfo() {
        override fun getDescriptor(bindingContext: BindingContext): CallableDescriptor? =
            bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, psi) as? CallableDescriptor
    }

    class Lambda(override val psi: KtFunctionLiteral) : ReceiverInfo() {
        override fun getDescriptor(bindingContext: BindingContext): CallableDescriptor? =
            bindingContext[BindingContext.FUNCTION, psi]
    }

    private fun processInternalReferences(
        bindingContext: BindingContext,
        visitor: KtTreeVisitor<BindingContext>
    ) {
        val body = psi.bodyExpression
        body?.accept(visitor, bindingContext)

        for (parameter in psi.valueParameters) {
            val defaultValue = parameter.defaultValue
            defaultValue?.accept(visitor, bindingContext)
        }
    }
}

class KotlinHighlightReceiverUsagesHandler(
    private val receiverInfo: ReceiverInfo, editor: Editor
) : HighlightUsagesHandlerBase<PsiElement>(editor, receiverInfo.psi.containingFile) {
    override fun getTargets(): List<PsiElement> =
        listOf(receiverInfo.psi)

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<PsiElement>) {
        receiverInfo.collectReceiverUsages {
            myReadUsages += it
        }
    }
}

private class ReceiverUsageCollector(
    val callableDescriptor: CallableDescriptor,
    val consumer: (TextRange) -> Unit
) : KtTreeVisitor<BindingContext>() {
    private fun processExplicitThis(
        expression: KtSimpleNameExpression,
        receiverDescriptor: ReceiverParameterDescriptor
    ) {
        if (expression.parent !is KtThisExpression) return

        if (receiverDescriptor === callableDescriptor.extensionReceiverParameter) {
            consumer(expression.textRange)
        }
    }

    private fun processImplicitThis(
        callElement: KtElement,
        implicitReceiver: ImplicitReceiver
    ) {
        val targetDescriptor = implicitReceiver.declarationDescriptor
        if (compareDescriptors(callElement.project, targetDescriptor, callableDescriptor)) {

            val textRange = when (callElement) {
                is KtCallExpression -> (callElement.calleeExpression ?: callElement).textRange
                else -> callElement.textRange
            }
            consumer(textRange)
        }
    }

    private fun processThisCall(
        expression: KtSimpleNameExpression,
        context: BindingContext,
        extensionReceiver: ReceiverValue?,
        dispatchReceiver: ReceiverValue?
    ) {
        if ((expression.parent as? KtThisExpression)?.parent !is KtCallExpression) return
        when (callableDescriptor) {
            extensionReceiver.getReceiverTargetDescriptor(context) -> consumer(expression.textRange)
            dispatchReceiver.getReceiverTargetDescriptor(context) -> consumer(expression.textRange)
        }
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression, context: BindingContext): Void? {
        val resolvedCall = expression.getResolvedCall(context) ?: return null

        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor is ReceiverParameterDescriptor) {
            processExplicitThis(expression, resultingDescriptor)
            return null
        }

        val call = resolvedCall.variableCallOrThis()
        val extensionReceiver = call.extensionReceiver
        val dispatchReceiver = call.dispatchReceiver
        if (extensionReceiver == null && dispatchReceiver == null) return null

        val receiverValue = extensionReceiver ?: dispatchReceiver
        if (receiverValue is ImplicitReceiver) {
            processImplicitThis(resolvedCall.call.callElement, receiverValue)
        } else {
            processThisCall(expression, context, extensionReceiver, dispatchReceiver)
        }

        return null
    }
}
