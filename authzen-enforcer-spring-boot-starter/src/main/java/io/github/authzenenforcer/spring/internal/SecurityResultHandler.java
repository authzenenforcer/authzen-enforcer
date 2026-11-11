package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.spring.servlet.EnforcementResultHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.io.IOException;

/**
 * Leaves the response to Spring Security's exception handling: its authentication entry point
 * answers {@value EnforcementResult#UNAUTHORIZED} with its challenge, and its access denied handler
 * {@value EnforcementResult#FORBIDDEN}.
 */
public final class SecurityResultHandler implements EnforcementResultHandler {

    /**
     * Creates the handler.
     */
    public SecurityResultHandler() {
        // Holds no state
    }

    @Override
    public void handleDenied(HttpServletRequest request, HttpServletResponse response, EnforcementResult result)
            throws IOException {
        int status = result.getStatus().orElse(EnforcementResult.FORBIDDEN);
        if (status == EnforcementResult.UNAUTHORIZED) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        if (status == EnforcementResult.SERVICE_UNAVAILABLE) {
            response.sendError(status);
            return;
        }
        throw new AccessDeniedException("Access is denied");
    }

    @Override
    public String toString() {
        return "SecurityResultHandler";
    }
}
