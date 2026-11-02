package io.github.authzenenforcer.core.internal.wire;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
