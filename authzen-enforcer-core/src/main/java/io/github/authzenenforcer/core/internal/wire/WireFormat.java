package io.github.authzenenforcer.core.internal.wire;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.util.LinkedHashMap;
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
