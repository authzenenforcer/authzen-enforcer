package io.github.authzenenforcer.core.internal.wire;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.PageRequest;
import io.github.authzenenforcer.core.model.PageResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps model types to and from JSON trees in the AuthZEN wire format.
 *
 * <p>This is the only place that knows the field names of the specification. Readers ignore unknown
 * fields, as the specification requires.
 */
public final class WireFormat {

    private WireFormat() {
    }

    /**
     * Converts an Access Evaluation request to its JSON tree.
     *
     * @param request the request
     * @return an insertion-ordered map
     */
    public static Map<String, Object> evaluationRequest(EvaluationRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("subject", subject(request.getSubject()));
        json.put("action", action(request.getAction()));
        json.put("resource", resource(request.getResource()));
        request.getContext().ifPresent(context -> json.put("context", context.asMap()));
        return json;
    }

    /**
     * Converts the JSON tree of an Access Evaluation response to the model.
     *
     * @param json the parsed response body
     * @return the response
     * @throws PdpProtocolException if the tree is not a valid Access Evaluation response
     */
    public static EvaluationResponse evaluationResponse(Object json) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException("Evaluation response is not a JSON object");
        }
        Object decision = object.get("decision");
        if (decision == null) {
            throw new PdpProtocolException("Evaluation response has no decision");
        }
        if (!(decision instanceof Boolean permitted)) {
            throw new PdpProtocolException("Evaluation response decision is not a boolean");
        }
        Object context = object.get("context");
        if (context == null) {
            return EvaluationResponse.of(permitted);
        }
        return EvaluationResponse.of(permitted, context(context, "Evaluation response"));
    }

    /**
     * Converts the JSON tree of a PDP metadata document to the model.
     *
     * <p>Only the syntax is checked here. Whether the identifier matches the PDP and whether the
     * endpoints are acceptable is up to the caller.
     *
     * @param json the parsed metadata document
     * @return the metadata
     * @throws PdpProtocolException if the tree is not a valid metadata document
     */
    public static PdpMetadata pdpMetadata(Object json) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException("PDP metadata is not a JSON object");
        }
        String identifier = string(object, "policy_decision_point");
        URI evaluation = endpoint(object, "access_evaluation_endpoint");
        if (identifier == null) {
            throw new PdpProtocolException("PDP metadata has no policy_decision_point");
        }
        if (evaluation == null) {
            throw new PdpProtocolException("PDP metadata has no access_evaluation_endpoint");
        }
        PdpMetadata.Builder metadata = PdpMetadata.builder()
                .policyDecisionPoint(identifier)
                .accessEvaluationEndpoint(evaluation)
                .accessEvaluationsEndpoint(endpoint(object, "access_evaluations_endpoint"))
                .searchSubjectEndpoint(endpoint(object, "search_subject_endpoint"))
                .searchResourceEndpoint(endpoint(object, "search_resource_endpoint"))
                .searchActionEndpoint(endpoint(object, "search_action_endpoint"));
        Object capabilities = object.get("capabilities");
        if (capabilities != null) {
            if (!(capabilities instanceof List<?> list)) {
                throw new PdpProtocolException("PDP metadata capabilities is not a JSON array");
            }
            for (Object capability : list) {
                if (!(capability instanceof String urn)) {
                    throw new PdpProtocolException("PDP metadata capabilities contains a value that is not a string");
                }
                metadata.capability(urn);
            }
        }
        return metadata.build();
    }

    private static String string(Map<?, ?> object, String name) {
        Object value = object.get(name);
        if (value != null && !(value instanceof String)) {
            throw new PdpProtocolException("PDP metadata " + name + " is not a string");
        }
        return (String) value;
    }

    private static URI endpoint(Map<?, ?> object, String name) {
        String value = string(object, name);
        if (value == null) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new PdpProtocolException("PDP metadata " + name + " is not a valid URL", e);
        }
    }

    /**
     * Converts an Access Evaluations request to its JSON tree. Defaults and items are written as
     * set; the PDP applies the defaults.
     *
     * @param request the request
     * @return an insertion-ordered map
     */
    public static Map<String, Object> evaluationsRequest(EvaluationsRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        request.getSubject().ifPresent(subject -> json.put("subject", subject(subject)));
        request.getAction().ifPresent(action -> json.put("action", action(action)));
        request.getResource().ifPresent(resource -> json.put("resource", resource(resource)));
        request.getContext().ifPresent(context -> json.put("context", context.asMap()));
        if (!request.getEvaluations().isEmpty()) {
            List<Object> evaluations = new ArrayList<>();
            for (EvaluationItem item : request.getEvaluations()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                item.getSubject().ifPresent(subject -> entry.put("subject", subject(subject)));
                item.getAction().ifPresent(action -> entry.put("action", action(action)));
                item.getResource().ifPresent(resource -> entry.put("resource", resource(resource)));
                item.getContext().ifPresent(context -> entry.put("context", context.asMap()));
                evaluations.add(entry);
            }
            json.put("evaluations", evaluations);
        }
        if (request.getSemantic().isPresent() || !request.getOptions().isEmpty()) {
            Map<String, Object> options = new LinkedHashMap<>();
            request.getSemantic().ifPresent(semantic -> options.put("evaluations_semantic", semantic.getWireValue()));
            options.putAll(request.getOptions());
            json.put("options", options);
        }
        return json;
    }

    /**
     * Converts the JSON tree of an Access Evaluations response to the model and checks it against
     * the request.
     *
     * <p>More decisions than items, or fewer with {@link EvaluationsSemantic#EXECUTE_ALL}, make the
     * whole response invalid. With a short-circuit semantic the decisions must follow it: every
     * decision before the last is the one that does not stop (permit for
     * {@link EvaluationsSemantic#DENY_ON_FIRST_DENY}, deny for
     * {@link EvaluationsSemantic#PERMIT_ON_FIRST_PERMIT}), and an answer that stops early ends with
     * the one that does. The decisions not returned count as denies. A request without items is
     * answered with either a single decision or an array of one, not both.
     *
     * @param json    the parsed response body
     * @param request the request it answers
     * @return the response
     * @throws PdpProtocolException if the tree is not a valid answer to the request
     */
    public static EvaluationsResponse evaluationsResponse(Object json, EvaluationsRequest request) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException("Evaluations response is not a JSON object");
        }
        int requested = Math.max(1, request.getEvaluations().size());
        Object evaluations = object.get("evaluations");
        if (evaluations == null) {
            if (!request.getEvaluations().isEmpty()) {
                throw new PdpProtocolException("Evaluations response has no evaluations");
            }
            return EvaluationsResponse.of(List.of(evaluationResponse(object)), 1);
        }
        // Two answers to the same single evaluation could be read differently by different consumers
        if (request.getEvaluations().isEmpty() && object.get("decision") != null) {
            throw new PdpProtocolException("Evaluations response has both a decision and evaluations");
        }
        if (!(evaluations instanceof List<?> list)) {
            throw new PdpProtocolException("Evaluations response evaluations is not a JSON array");
        }
        if (list.size() > requested) {
            throw new PdpProtocolException("Evaluations response has " + list.size() + " decisions for "
                    + requested + " requested");
        }
        EvaluationsSemantic semantic = request.getSemantic().orElse(EvaluationsSemantic.EXECUTE_ALL);
        if (semantic == EvaluationsSemantic.EXECUTE_ALL && list.size() < requested) {
            throw new PdpProtocolException("Evaluations response has " + list.size() + " decisions for "
                    + requested + " requested with execute_all");
        }
        List<EvaluationResponse> decisions = new ArrayList<>();
        for (Object item : list) {
            decisions.add(evaluationResponse(item));
        }
        if (semantic != EvaluationsSemantic.EXECUTE_ALL) {
            checkShortCircuit(decisions, requested, semantic);
        }
        return EvaluationsResponse.of(decisions, requested);
    }

    private static void checkShortCircuit(List<EvaluationResponse> decisions, int requested,
            EvaluationsSemantic semantic) {
        // The decision that makes the PDP stop: a deny for deny_on_first_deny, a permit for permit_on_first_permit
        boolean stopping = semantic == EvaluationsSemantic.PERMIT_ON_FIRST_PERMIT;
        if (decisions.isEmpty()) {
            throw new PdpProtocolException("Evaluations response has no decisions for " + requested + " requested");
        }
        for (int i = 0; i < decisions.size() - 1; i++) {
            if (decisions.get(i).getDecision() == stopping) {
                throw new PdpProtocolException("Evaluations response continues after decision " + i
                        + ", where " + semantic.getWireValue() + " stops");
            }
        }
        if (decisions.size() < requested && decisions.get(decisions.size() - 1).getDecision() != stopping) {
            throw new PdpProtocolException("Evaluations response stops early without the decision that "
                    + semantic.getWireValue() + " stops at");
        }
    }

    /**
     * Converts a Subject Search request to its JSON tree. The subject searched for is written with
     * its type and properties only.
     *
     * @param request the request
     * @return an insertion-ordered map
     */
    public static Map<String, Object> subjectSearchRequest(SubjectSearchRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("subject", searchedEntity(request.getSubjectType(), request.getSubjectProperties()));
        json.put("action", action(request.getAction()));
        json.put("resource", resource(request.getResource()));
        request.getContext().ifPresent(context -> json.put("context", context.asMap()));
        request.getPage().ifPresent(page -> json.put("page", page(page)));
        return json;
    }

    /**
     * Converts a Resource Search request to its JSON tree. The resource searched for is written with
     * its type and properties only.
     *
     * @param request the request
     * @return an insertion-ordered map
     */
    public static Map<String, Object> resourceSearchRequest(ResourceSearchRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("subject", subject(request.getSubject()));
        json.put("action", action(request.getAction()));
        json.put("resource", searchedEntity(request.getResourceType(), request.getResourceProperties()));
        request.getContext().ifPresent(context -> json.put("context", context.asMap()));
        request.getPage().ifPresent(page -> json.put("page", page(page)));
        return json;
    }

    /**
     * Converts an Action Search request to its JSON tree.
     *
     * @param request the request
     * @return an insertion-ordered map
     */
    public static Map<String, Object> actionSearchRequest(ActionSearchRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("subject", subject(request.getSubject()));
        json.put("resource", resource(request.getResource()));
        request.getContext().ifPresent(context -> json.put("context", context.asMap()));
        request.getPage().ifPresent(page -> json.put("page", page(page)));
        return json;
    }

    /**
     * Converts the JSON tree of a Subject Search response to the model.
     *
     * @param json the parsed response body
     * @return the response
     * @throws PdpProtocolException if the tree is not a valid Search API response with subjects
     */
    public static SearchResponse<Subject> subjectSearchResponse(Object json) {
        return searchResponse(json, "Subject search response", WireFormat::subjectResult);
    }

    /**
     * Converts the JSON tree of a Resource Search response to the model.
     *
     * @param json the parsed response body
     * @return the response
     * @throws PdpProtocolException if the tree is not a valid Search API response with resources
     */
    public static SearchResponse<Resource> resourceSearchResponse(Object json) {
        return searchResponse(json, "Resource search response", WireFormat::resourceResult);
    }

    /**
     * Converts the JSON tree of an Action Search response to the model.
     *
     * @param json the parsed response body
     * @return the response
     * @throws PdpProtocolException if the tree is not a valid Search API response with actions
     */
    public static SearchResponse<Action> actionSearchResponse(Object json) {
        return searchResponse(json, "Action search response", WireFormat::actionResult);
    }

    private static <T> SearchResponse<T> searchResponse(Object json, String owner, Function<Map<?, ?>, T> reader) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException(owner + " is not a JSON object");
        }
        Object results = object.get("results");
        if (results == null) {
            throw new PdpProtocolException(owner + " has no results");
        }
        if (!(results instanceof List<?> list)) {
            throw new PdpProtocolException(owner + " results is not a JSON array");
        }
        SearchResponse.Builder<T> response = SearchResponse.builder();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> result)) {
                throw new PdpProtocolException(owner + " result " + i + " is not a JSON object");
            }
            try {
                response.result(reader.apply(result));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new PdpProtocolException(owner + " result " + i + " is not valid: " + e.getMessage(), e);
            }
        }
        Object page = object.get("page");
        if (page != null) {
            response.page(pageResponse(page, owner));
        }
        Object context = object.get("context");
        if (context != null) {
            response.context(context(context, owner));
        }
        return response.build();
    }

    // The builders reject a missing type, id or name, and properties that are no JSON object
    @SuppressWarnings("unchecked")
    private static Subject subjectResult(Map<?, ?> json) {
        return Subject.builder().type(member(json, "type")).id(member(json, "id"))
                .properties((Map<String, ?>) properties(json)).build();
    }

    @SuppressWarnings("unchecked")
    private static Resource resourceResult(Map<?, ?> json) {
        return Resource.builder().type(member(json, "type")).id(member(json, "id"))
                .properties((Map<String, ?>) properties(json)).build();
    }

    @SuppressWarnings("unchecked")
    private static Action actionResult(Map<?, ?> json) {
        return Action.builder().name(member(json, "name")).properties((Map<String, ?>) properties(json)).build();
    }

    private static String member(Map<?, ?> json, String name) {
        Object value = json.get(name);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(name + " is not a string");
        }
        return (String) value;
    }

    private static Map<?, ?> properties(Map<?, ?> json) {
        Object value = json.get("properties");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("properties is not a JSON object");
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static PageResponse pageResponse(Object json, String owner) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException(owner + " page is not a JSON object");
        }
        Object nextToken = object.get("next_token");
        if (nextToken == null) {
            throw new PdpProtocolException(owner + " page has no next_token");
        }
        if (!(nextToken instanceof String token)) {
            throw new PdpProtocolException(owner + " page next_token is not a string");
        }
        PageResponse.Builder page = PageResponse.builder().nextToken(token);
        Long count = count(object, "count", owner);
        if (count != null) {
            page.count(count);
        }
        Long total = count(object, "total", owner);
        if (total != null) {
            page.total(total);
        }
        Object properties = object.get("properties");
        if (properties != null) {
            if (!(properties instanceof Map<?, ?> map)) {
                throw new PdpProtocolException(owner + " page properties is not a JSON object");
            }
            page.properties((Map<String, ?>) map);
        }
        try {
            return page.build();
        } catch (IllegalArgumentException e) {
            throw new PdpProtocolException(owner + " page properties is not valid", e);
        }
    }

    // A non-negative integer; codecs may represent it as any integral Number type
    private static Long count(Map<?, ?> page, String name, String owner) {
        Object value = page.get(name);
        if (value == null) {
            return null;
        }
        BigInteger number;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            number = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger integer) {
            number = integer;
        } else {
            throw new PdpProtocolException(owner + " page " + name + " is not an integer");
        }
        if (number.signum() < 0 || number.bitLength() >= Long.SIZE) {
            throw new PdpProtocolException(owner + " page " + name + " is not a non-negative integer in range");
        }
        return number.longValue();
    }

    private static Map<String, Object> page(PageRequest page) {
        Map<String, Object> json = new LinkedHashMap<>();
        page.getToken().ifPresent(token -> json.put("token", token));
        page.getLimit().ifPresent(limit -> json.put("limit", limit));
        putProperties(json, page.getProperties());
        return json;
    }

    private static Map<String, Object> searchedEntity(String type, Map<String, Object> properties) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", type);
        putProperties(json, properties);
        return json;
    }

    private static Map<String, Object> subject(Subject subject) {
        return entity(subject.getType(), subject.getId(), subject.getProperties());
    }

    private static Map<String, Object> resource(Resource resource) {
        return entity(resource.getType(), resource.getId(), resource.getProperties());
    }

    private static Map<String, Object> entity(String type, String id, Map<String, Object> properties) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", type);
        json.put("id", id);
        putProperties(json, properties);
        return json;
    }

    private static Map<String, Object> action(Action action) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", action.getName());
        putProperties(json, action.getProperties());
        return json;
    }

    private static void putProperties(Map<String, Object> json, Map<String, Object> properties) {
        if (!properties.isEmpty()) {
            json.put("properties", properties);
        }
    }

    @SuppressWarnings("unchecked")
    private static Context context(Object json, String owner) {
        if (!(json instanceof Map<?, ?> object)) {
            throw new PdpProtocolException(owner + " context is not a JSON object");
        }
        try {
            // Context.of checks that every key is a string
            return Context.of((Map<String, ?>) object);
        } catch (IllegalArgumentException e) {
            throw new PdpProtocolException(owner + " context is not valid", e);
        }
    }
}
