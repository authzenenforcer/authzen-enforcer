package io.github.authzenenforcer.spring.internal;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;

/**
 * The request as expressions see it: the {@code #request} variable. It exposes a few values and
 * nothing that carries credentials, so that an expression cannot put them into an evaluation
 * request.
 */
public final class RequestView {

    // Headers that carry credentials or session identifiers
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "dpop");
    // The form and query parameter of RFC 6750
    private static final Set<String> CREDENTIAL_PARAMETERS = Set.of("access_token");

    private final HttpServletRequest request;

    /**
     * Creates the view.
     *
     * @param request the request
     */
    public RequestView(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method
     */
    public String getMethod() {
        return request.getMethod();
    }

    /**
     * Returns the address of the client or of the last proxy.
     *
     * @return the address
     */
    public String getRemoteAddr() {
        return request.getRemoteAddr();
    }

    /**
     * Returns the first value of a header, unless it carries credentials.
     *
     * @param name the header name
     * @return the value, or {@code null} if there is none or it carries credentials
     */
    public String header(String name) {
        return name == null || CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? null
                : request.getHeader(name);
    }

    /**
     * Returns the first value of a query or form parameter, unless it carries credentials.
     *
     * @param name the parameter name
     * @return the value, or {@code null} if there is none or it carries credentials
     */
    public String parameter(String name) {
        return name == null || CREDENTIAL_PARAMETERS.contains(name) ? null : request.getParameter(name);
    }

    @Override
    public String toString() {
        return "RequestView";
    }
}
