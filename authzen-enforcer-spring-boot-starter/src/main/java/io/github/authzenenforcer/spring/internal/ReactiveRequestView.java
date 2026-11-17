package io.github.authzenenforcer.spring.internal;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;

import java.net.InetSocketAddress;

/**
 * The view of a Spring WebFlux request. Parameters are those of the query and, as with a servlet
 * container, then those of a form body, which is read before the request is decided only if an
 * expression of the handler reads parameters.
 */
final class ReactiveRequestView extends RequestView {

    private final ServerHttpRequest request;
    private final MultiValueMap<String, String> form;

    /**
     * Creates the view.
     *
     * @param request the request
     * @param form    the fields of its form body, empty if it has none or it was not read
     */
    ReactiveRequestView(ServerHttpRequest request, MultiValueMap<String, String> form) {
        this.request = request;
        this.form = form;
    }

    @Override
    public String getMethod() {
        return request.getMethod().name();
    }

    @Override
    public String getRemoteAddr() {
        InetSocketAddress address = request.getRemoteAddress();
        if (address == null) {
            return null;
        }
        // The address as received, without a lookup of its name
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }

    @Override
    String headerValue(String name) {
        return request.getHeaders().getFirst(name);
    }

    @Override
    String parameterValue(String name) {
        String value = request.getQueryParams().getFirst(name);
        return value != null ? value : form.getFirst(name);
    }
}
