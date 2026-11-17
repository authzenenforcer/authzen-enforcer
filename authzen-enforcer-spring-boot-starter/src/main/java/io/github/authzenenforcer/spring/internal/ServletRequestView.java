package io.github.authzenenforcer.spring.internal;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * The view of a Spring MVC request. Parameters are those of the query and of a form body, which
 * the servlet container parses.
 */
final class ServletRequestView extends RequestView {

    private final HttpServletRequest request;

    ServletRequestView(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public String getRemoteAddr() {
        return request.getRemoteAddr();
    }

    @Override
    String headerValue(String name) {
        return request.getHeader(name);
    }

    @Override
    List<String> parameterValues(String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }
}
