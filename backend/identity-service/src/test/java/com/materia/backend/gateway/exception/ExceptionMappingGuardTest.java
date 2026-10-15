package com.materia.backend.gateway.exception;

import com.materia.backend.contexts.auth.domain.exceptions.*;
import com.materia.backend.contexts.employee.domain.exceptions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [T119] Exception Mapping Guard Test, for identity's contexts (auth, employee).
 *
 * <p>Asserts that every in-scope business/domain exception resolves to a specific 4xx handler method and NEVER falls
 * through to the generic 500 {@code handleGlobalException(Exception, WebRequest)} handler. Handlers are looked up the
 * way Spring does: each context's own advice first ({@code @Order(HIGHEST_PRECEDENCE)}), {@link GlobalExceptionHandler}
 * last.
 *
 * <p>Prevents recurrence of FINDING-006 (AccountLockedException -> 500) and FINDING-007
 * (InvalidCredentialsException -> 500).
 */
class ExceptionMappingGuardTest {

    private Resolver resolver;

    /** Spring's lookup across advices: the first advice, in precedence order, with a matching method answers. */
    private record Resolver(List<ExceptionHandlerMethodResolver> advices) {
        Method resolveMethodByExceptionType(Class<? extends Throwable> exception) {
            for (ExceptionHandlerMethodResolver advice : advices) {
                Method method = advice.resolveMethodByExceptionType(exception);
                if (method != null) return method;
            }
            return null;
        }
    }

    /** Every @RestControllerAdvice of the contexts, then the shared handler. */
    static List<Class<?>> advices() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestControllerAdvice.class));
        List<Class<?>> advices = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.materia.backend.contexts")) {
            advices.add(Class.forName(candidate.getBeanClassName()));
        }
        advices.add(GlobalExceptionHandler.class);
        return advices;
    }

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        resolver = new Resolver(advices().stream().map(ExceptionHandlerMethodResolver::new).toList());
    }

    @Test
    @DisplayName("exception guard: each context's advice is checked before the shared handler, which is checked last")
    void contextAdvicesComeFirst() throws ClassNotFoundException {
        List<Class<?>> advices = advices();
        assertThat(advices).hasSizeGreaterThan(1);
        for (Class<?> advice : advices.subList(0, advices.size() - 1)) {
            assertThat(advice.getAnnotation(Order.class)).as(advice.getSimpleName()).isNotNull()
                    .extracting(Order::value).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
        assertThat(GlobalExceptionHandler.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    private static final List<Class<? extends Throwable>> IN_SCOPE_EXCEPTIONS = List.of(
            // Auth (US1)
            AccountLockedException.class,
            InvalidCredentialsException.class,
            AuthenticationFailedException.class,
            EmailAlreadyExistsException.class,
            UserAlreadyExistsException.class,
            UserNotFoundException.class,
            InvalidTokenException.class,
            TokenExpiredException.class,

            // Employees (US4)
            EmployeeNotFoundException.class,
            EmployeeCodeAlreadyExistsException.class,
            EmailAlreadyInUseException.class
    );

    @Test
    @DisplayName("exception guard: no in-scope domain exception resolves to the generic 500 handler")
    void noInScopeException_resolvesTo500Handler() {
        Method fallbackMethod = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class)
                .resolveMethodByExceptionType(Exception.class);
        assertThat(fallbackMethod).isNotNull();
        assertThat(fallbackMethod.getName()).isEqualTo("handleGlobalException");

        for (Class<? extends Throwable> exClass : IN_SCOPE_EXCEPTIONS) {
            Method resolved = resolver.resolveMethodByExceptionType(exClass);
            assertThat(resolved)
                    .as("Exception %s must have a dedicated handler and not fall through to 500", exClass.getSimpleName())
                    .isNotNull()
                    .isNotEqualTo(fallbackMethod);
        }
    }

    @Test
    @DisplayName("exception guard: FINDING-006 AccountLockedException resolves to unauthorized handler")
    void accountLockedException_doesNotMapTo500() {
        Method resolved = resolver.resolveMethodByExceptionType(AccountLockedException.class);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleAccountLockedException");
    }

    @Test
    @DisplayName("exception guard: FINDING-007 InvalidCredentialsException resolves to unauthorized handler")
    void invalidCredentialsException_doesNotMapTo500() {
        Method resolved = resolver.resolveMethodByExceptionType(InvalidCredentialsException.class);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleInvalidCredentialsException");
    }
}
