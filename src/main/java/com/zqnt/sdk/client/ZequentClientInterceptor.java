package com.zqnt.sdk.client;

import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.grpc.ClientInterceptor} CDI bean that {@link ZequentClientProducer} puts on
 * every channel of the injected {@link ZequentClient} — the Quarkus/CDI counterpart of
 * {@code ZequentClient.builder().interceptor(...)}.
 *
 * <p>Use it when the credential is not one fixed token, e.g. a service that forwards its own
 * caller's token and otherwise sends a short-lived token of its own:</p>
 * <pre>{@code
 * @Produces @ZequentClientInterceptor
 * ClientInterceptor credentials(MyAuthInterceptor interceptor) { return interceptor; }
 * }</pre>
 *
 * <p>Contributed interceptors run before the SDK's own credential interceptor. An
 * {@code authorization} header they set wins: the configured client token is only added when no
 * interceptor has set one. The order among several contributed interceptors is not defined —
 * contribute one that composes them if the order matters.</p>
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface ZequentClientInterceptor {
}
