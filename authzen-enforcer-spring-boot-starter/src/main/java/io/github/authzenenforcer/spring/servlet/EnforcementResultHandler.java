package io.github.authzenenforcer.spring.servlet;

import io.github.authzenenforcer.core.enforce.EnforcementResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Answers a request that enforcement did not let through.
 *
 * <p>With Spring Security, the default throws {@code AuthenticationCredentialsNotFoundException}
 * for {@value EnforcementResult#UNAUTHORIZED}, so that the authentication entry point sends the
 * challenge, and {@code AccessDeniedException} otherwise; the status {@value
 * EnforcementResult#SERVICE_UNAVAILABLE} is sent as it is. Without Spring Security, it sends the
 * status of the result, with {@code WWW-Authenticate: Bearer} for {@value
 * EnforcementResult#UNAUTHORIZED}.
 *
 * <p>An application defines a bean of this type to answer differently, for example to log the PDP's
 * reason, which is not sent to the client by default. A handler that neither throws nor sends an
 * error status leaves the request denied: the status of the result is then sent for it.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface EnforcementResultHandler {

    /**
     * Answers a request that is not let through.
     *
     * @param request  the request
     * @param response the response
     * @param result   the result, which is not {@linkplain EnforcementResult#isAllowed() allowed}
     * @throws IOException if the response cannot be written
     */
    void handleDenied(HttpServletRequest request, HttpServletResponse response, EnforcementResult result)
            throws IOException;
}
