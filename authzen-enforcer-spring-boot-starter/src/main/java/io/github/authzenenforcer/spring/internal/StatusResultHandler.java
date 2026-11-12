package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.spring.servlet.EnforcementResultHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Sends the status of the result, for applications without Spring Security.
 */
public final class StatusResultHandler implements EnforcementResultHandler {

    /**
     * Creates the handler.
     */
    public StatusResultHandler() {
        // Holds no state
    }

    @Override
    public void handleDenied(HttpServletRequest request, HttpServletResponse response, EnforcementResult result)
            throws IOException {
        int status = result.getStatus().orElse(EnforcementResult.FORBIDDEN);
        if (status == EnforcementResult.UNAUTHORIZED) {
            // RFC 9110 requires a challenge with 401
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.sendError(status);
    }

    @Override
    public String toString() {
        return "StatusResultHandler";
    }
}
