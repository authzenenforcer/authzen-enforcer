package io.github.authzenenforcer.spring.internal;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.servlet.ServletException;

import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The view of a Spring MVC request. Parameters are those of the query and of a form body, which
 * the servlet container parses. A multipart request is refused, as in Spring WebFlux: a part that
 * carries a file name is no parameter, yet {@code @RequestPart} reads it by name.
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
    List<String> headerValues(String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    @Override
    List<String> parameterValues(String name) {
        if (isMultipart(request.getContentType())) {
            throw new MultipartParameterException();
        }
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    @Override
    Collection<String> valueNames() {
        Set<String> names = new LinkedHashSet<>(request.getParameterMap().keySet());
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart != null) {
            names.addAll(multipart.getMultiFileMap().keySet());
        } else if (isMultipart(request.getContentType())) {
            // Without a multipart resolver, data binding reads the parts of the servlet container
            try {
                request.getParts().forEach(part -> names.add(part.getName()));
            } catch (IOException | ServletException e) {
                throw new IllegalStateException("Reading the parts of the request failed", e);
            }
        }
        return names;
    }

    @Override
    Collection<String> headerNames() {
        return Collections.list(request.getHeaderNames());
    }
}
