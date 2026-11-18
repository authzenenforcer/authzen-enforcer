package io.github.authzenenforcer.spring.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The view of a Spring WebFlux request. Parameters are those of the query and of a form body,
 * which is read before the request is decided only if an expression of the handler reads
 * parameters or the handler binds data; the names of multipart parts only in the latter case.
 * The fields of a multipart body are not parameters, so an expression that reads a parameter of a
 * multipart request is refused: the handler could act on a field of the same name.
 */
final class ReactiveRequestView extends RequestView {

    private final ServerHttpRequest request;
    private final MultiValueMap<String, String> form;
    private final Set<String> partNames;

    /**
     * Creates the view.
     *
     * @param request   the request
     * @param form      the fields of its form body, empty if it has none or it was not read
     * @param partNames the names of its multipart parts, empty if it has none or they were not read
     */
    ReactiveRequestView(ServerHttpRequest request, MultiValueMap<String, String> form, Set<String> partNames) {
        this.request = request;
        this.form = form;
        this.partNames = partNames;
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
    List<String> headerValues(String name) {
        List<String> values = request.getHeaders().get(name);
        return values == null ? List.of() : values;
    }

    @Override
    List<String> parameterValues(String name) {
        if (isMultipart(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))) {
            throw new MultipartParameterException();
        }
        List<String> values = new ArrayList<>(request.getQueryParams().getOrDefault(name, List.of()));
        values.addAll(form.getOrDefault(name, List.of()));
        return values;
    }

    @Override
    Collection<String> valueNames() {
        Set<String> names = new LinkedHashSet<>(request.getQueryParams().keySet());
        names.addAll(form.keySet());
        names.addAll(partNames);
        return names;
    }

    @Override
    Collection<String> headerNames() {
        return request.getHeaders().headerSet().stream().map(Map.Entry::getKey).toList();
    }
}
