package com.huntercoles.splatman.library.domain

import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.TestAbortedException
import java.lang.reflect.Method

/**
 * Strict expected failure (JUnit 5 has no xfail): the test passes while its body throws, and fails once the body
 * passes, so the builder must delete the marker when the behaviour lands. [ticket] names the card, e.g. "#214".
 * A copy of splat-core's, which this module's tests cannot see.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(ExpectedToFailExtension::class)
annotation class ExpectedToFail(val ticket: String)

class ExpectedToFailExtension : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        val ticket = extensionContext.requiredTestMethod.getAnnotation(ExpectedToFail::class.java).ticket
        try {
            invocation.proceed()
        } catch (aborted: TestAbortedException) {
            throw aborted
        } catch (expected: Throwable) {
            return
        }
        throw AssertionError("expected to fail ($ticket) but passed: remove @ExpectedToFail")
    }
}
