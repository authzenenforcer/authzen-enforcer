package io.github.authzenenforcer.core.internal.enforce;

import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.enforce.InferredResource;
import io.github.authzenenforcer.core.enforce.RequestDescription;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;
import io.github.authzenenforcer.core.internal.text.LogText;
import io.github.authzenenforcer.core.model.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Infers the resource from the route pattern; see {@link ResourceInferrer} for the rules.
 */
public final class RouteResourceInferrer implements ResourceInferrer {

    private final boolean singularize;
    private final Map<String, String> typeNames;
    private final String collectionType;
    private final int maxIdLength;

    /**
     * Creates an inferrer.
     *
     * @param singularize    whether to singularize segments
     * @param typeNames      resource types by segment
     * @param collectionType the type of a collection
     * @param maxIdLength    the longest id accepted
     */
    public RouteResourceInferrer(boolean singularize, Map<String, String> typeNames, String collectionType,
            int maxIdLength) {
        this.singularize = singularize;
        this.typeNames = Map.copyOf(typeNames);
        this.collectionType = collectionType;
        this.maxIdLength = maxIdLength;
    }

    @Override
    public InferredResource infer(RequestDescription request, boolean actionGiven) {
        String pattern = request.getRoutePattern();
        Shape shape = checkedShape(request.getMethod(), pattern, actionGiven);
        List<Map<String, String>> ancestors = new ArrayList<>();
        Resource.Builder resource = Resource.builder();
        for (int i = 0; i < shape.variables().size(); i++) {
            Owned variable = shape.variables().get(i);
            String id = id(pattern, variable.name(), request.getPathVariables().get(variable.name()));
            String type = type(variable.segment());
            if (shape.collection() == null && i == shape.variables().size() - 1) {
                resource.type(type).id(id);
            } else {
                ancestors.add(Map.of("type", type, "id", id));
            }
        }
        if (shape.collection() != null) {
            resource.type(collectionType).id(type(shape.collection()));
        }
        if (!ancestors.isEmpty()) {
            resource.property("parent", ancestors.get(ancestors.size() - 1));
            resource.property("ancestors", ancestors);
        }
        return shape.collection() != null
                ? InferredResource.collection(resource.build())
                : InferredResource.single(resource.build());
    }

    @Override
    public void check(String method, String routePattern, boolean actionGiven) {
        checkedShape(method, routePattern, actionGiven);
    }

    // A variable of the collection type would let callers name a collection, such as
    // {collection, documents} for /collection/{id} with id documents
    private Shape checkedShape(String method, String pattern, boolean actionGiven) {
        Shape shape = shape(method, pattern, actionGiven);
        for (Owned variable : shape.variables()) {
            if (type(variable.segment()).equals(collectionType)) {
                throw refused(pattern, "the type of path variable " + variable.name() + " would be " + collectionType
                        + ", which stands for collections; name its type with typeName(...)");
            }
        }
        return shape;
    }

    /**
     * The variables of a route with the segment before each, and the collection segment, which is
     * {@code null} if the route names a single resource.
     */
    private record Shape(List<Owned> variables, String collection) {
    }

    private record Owned(String segment, String name) {
    }

    private static Shape shape(String method, String pattern, boolean actionGiven) {
        List<RoutePattern.Segment> segments = RoutePattern.parse(pattern);
        if (segments.isEmpty()) {
            throw refused(pattern, "it has no segments");
        }
        List<Owned> variables = new ArrayList<>();
        int last = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof RoutePattern.Variable variable) {
                if (i == 0 || !(segments.get(i - 1) instanceof RoutePattern.Literal literal)) {
                    throw refused(pattern, "path variable " + variable.name() + " does not follow a static segment");
                }
                variables.add(new Owned(literal.text(), variable.name()));
                last = i;
            }
        }
        RoutePattern.Literal end = segments.get(segments.size() - 1) instanceof RoutePattern.Literal literal
                ? literal
                : null;
        if (last < 0) {
            return new Shape(variables, end.text());
        }
        int trailing = segments.size() - 1 - last;
        if (trailing == 0) {
            return new Shape(variables, null);
        }
        String id = variables.get(variables.size() - 1).name();
        if (trailing > 1) {
            throw refused(pattern, "more than one static segment follows path variable " + id);
        }
        String verb = method.toUpperCase(Locale.ROOT);
        if (verb.equals("GET") || verb.equals("HEAD")) {
            return new Shape(variables, end.text());
        }
        if (actionGiven) {
            return new Shape(variables, null);
        }
        // The message leaves out the method, which the caller chooses, so that it is logged once per route
        throw new InferenceException("Cannot infer the action of a write to route " + LogText.of(pattern)
                + ": a static segment follows path variable " + id + ", which may name an action;"
                + " set the action explicitly or add a route override", true);
    }

    private String id(String pattern, String name, String value) {
        if (value == null) {
            throw new InferenceException("Route " + LogText.of(pattern) + " has no value for path variable " + name, true);
        }
        if (value.isEmpty()) {
            throw invalid(pattern, name, "is empty");
        }
        if (value.length() > maxIdLength) {
            throw invalid(pattern, name, "is longer than " + maxIdLength + " characters");
        }
        if (value.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
            throw invalid(pattern, name, "contains a control character");
        }
        return value;
    }

    private String type(String segment) {
        String named = typeNames.get(segment);
        if (named != null) {
            return named;
        }
        return singularize ? Singular.of(segment) : segment;
    }

    private static InferenceException refused(String pattern, String reason) {
        return new InferenceException("Cannot infer the resource of route " + LogText.of(pattern) + ": " + reason, true);
    }

    // The value is left out of the message, since callers control it
    private static InferenceException invalid(String pattern, String name, String problem) {
        return new InferenceException("Path variable " + name + " of route " + LogText.of(pattern) + " " + problem, false);
    }

    @Override
    public String toString() {
        return "RouteResourceInferrer{singularize=" + singularize + ", typeNames=" + typeNames
                + ", collectionType=" + collectionType + ", maxIdLength=" + maxIdLength + "}";
    }
}
