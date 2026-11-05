package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Copies model objects with the values an {@link EventContent} masks replaced by
 * {@link EventContent#MASK}.
 */
final class Masking {

    private static final String SUBJECT_PROPERTIES = "subject.properties.";
    private static final String RESOURCE_PROPERTIES = "resource.properties.";
    private static final String ACTION_PROPERTIES = "action.properties.";
    private static final String CONTEXT = "context.";

    private Masking() {
    }

    static Subject subject(Subject subject, EventContent content) {
        if (subject == null) {
            return null;
        }
        return Subject.builder()
                .type(subject.getType())
                .id(content.isMasked("subject.id") ? EventContent.MASK : subject.getId())
                .properties(values(subject.getProperties(), SUBJECT_PROPERTIES, content))
                .build();
    }

    static Resource resource(Resource resource, EventContent content) {
        if (resource == null) {
            return null;
        }
        return Resource.builder()
                .type(resource.getType())
                .id(content.isMasked("resource.id") ? EventContent.MASK : resource.getId())
                .properties(values(resource.getProperties(), RESOURCE_PROPERTIES, content))
                .build();
    }

    static Action action(Action action, EventContent content) {
        if (action == null) {
            return null;
        }
        return Action.builder()
                .name(action.getName())
                .properties(values(action.getProperties(), ACTION_PROPERTIES, content))
                .build();
    }

    static Context context(Context context, EventContent content) {
        return context == null ? null : Context.of(values(context.asMap(), CONTEXT, content));
    }

    private static Map<String, Object> values(Map<String, Object> values, String group, EventContent content) {
        Map<String, Object> masked = new LinkedHashMap<>();
        values.forEach((name, value) -> masked.put(name, content.isMasked(group, name) ? EventContent.MASK : value));
        return masked;
    }

    static EvaluationRequest evaluationRequest(EvaluationRequest request, EventContent content) {
        return EvaluationRequest.builder()
                .subject(subject(request.getSubject(), content))
                .action(action(request.getAction(), content))
                .resource(resource(request.getResource(), content))
                .context(context(request.getContext().orElse(null), content))
                .build();
    }

    static EvaluationResponse evaluationResponse(EvaluationResponse response, EventContent content) {
        return response.getContext()
                .map(context -> EvaluationResponse.of(response.getDecision(), context(context, content)))
                .orElse(response);
    }

    static EvaluationsRequest evaluationsRequest(EvaluationsRequest request, EventContent content) {
        EvaluationsRequest.Builder builder = EvaluationsRequest.builder()
                .subject(subject(request.getSubject().orElse(null), content))
                .action(action(request.getAction().orElse(null), content))
                .resource(resource(request.getResource().orElse(null), content))
                .context(context(request.getContext().orElse(null), content));
        for (EvaluationItem item : request.getEvaluations()) {
            builder.evaluation(EvaluationItem.builder()
                    .subject(subject(item.getSubject().orElse(null), content))
                    .action(action(item.getAction().orElse(null), content))
                    .resource(resource(item.getResource().orElse(null), content))
                    .context(context(item.getContext().orElse(null), content))
                    .build());
        }
        request.getSemantic().ifPresent(builder::semantic);
        request.getOptions().forEach(builder::option);
        return builder.build();
    }

    static EvaluationsResponse evaluationsResponse(EvaluationsResponse response, EventContent content) {
        List<EvaluationResponse> evaluations = new ArrayList<>();
        response.getEvaluations().forEach(evaluation -> evaluations.add(evaluationResponse(evaluation, content)));
        return EvaluationsResponse.of(evaluations, response.getRequested());
    }

    static SubjectSearchRequest subjectSearchRequest(SubjectSearchRequest request, EventContent content) {
        return SubjectSearchRequest.builder()
                .subjectType(request.getSubjectType())
                .subjectProperties(values(request.getSubjectProperties(), SUBJECT_PROPERTIES, content))
                .action(action(request.getAction(), content))
                .resource(resource(request.getResource(), content))
                .context(context(request.getContext().orElse(null), content))
                .page(request.getPage().orElse(null))
                .build();
    }

    static ResourceSearchRequest resourceSearchRequest(ResourceSearchRequest request, EventContent content) {
        return ResourceSearchRequest.builder()
                .subject(subject(request.getSubject(), content))
                .action(action(request.getAction(), content))
                .resourceType(request.getResourceType())
                .resourceProperties(values(request.getResourceProperties(), RESOURCE_PROPERTIES, content))
                .context(context(request.getContext().orElse(null), content))
                .page(request.getPage().orElse(null))
                .build();
    }

    static ActionSearchRequest actionSearchRequest(ActionSearchRequest request, EventContent content) {
        return ActionSearchRequest.builder()
                .subject(subject(request.getSubject(), content))
                .resource(resource(request.getResource(), content))
                .context(context(request.getContext().orElse(null), content))
                .page(request.getPage().orElse(null))
                .build();
    }

    static <T> SearchResponse<T> searchResponse(SearchResponse<T> response, EventContent content,
            BiFunction<T, EventContent, T> result) {
        List<T> results = new ArrayList<>();
        response.getResults().forEach(each -> results.add(result.apply(each, content)));
        return SearchResponse.<T>builder()
                .results(results)
                .page(response.getPage().orElse(null))
                .context(context(response.getContext().orElse(null), content))
                .build();
    }
}
