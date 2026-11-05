package io.github.authzenenforcer.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
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
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CallCompletedTest {

    private static final String MASK = EventContent.MASK;
    private static final Subject ALICE = Subject.builder().type("user").id("alice").property("email", "a@x").build();
    private static final Resource ACCOUNT = Resource.builder().type("account").id("123").property("owner", "bob").build();
    private static final Action READ = Action.builder().name("can_read").property("why", "audit").build();
    private static final Context CONTEXT = Context.builder().put("ip", "10.0.0.1").build();

    private static <Q, R> CallCompleted.Builder<Q, R> builder(Operation<Q, R> operation, Q request) {
        return CallCompleted.builder(operation).request(request).startTime(Instant.EPOCH).duration(Duration.ZERO);
    }

    @Test
    void masksAnEvaluationAndItsResponse() {
        EvaluationRequest request = EvaluationRequest.builder()
                .subject(ALICE).action(READ).resource(ACCOUNT).context(CONTEXT).build();
        CallCompleted<EvaluationRequest, EvaluationResponse> event = builder(Operation.EVALUATION, request)
                .response(EvaluationResponse.of(false, Context.builder().put("reason", "x").build()))
                .build();

        EvaluationRequest masked = event.getRequest();
        assertEquals(Subject.builder().type("user").id(MASK).property("email", MASK).build(), masked.getSubject());
        assertEquals(Resource.builder().type("account").id("123").property("owner", MASK).build(), masked.getResource());
        assertEquals(Action.builder().name("can_read").property("why", MASK).build(), masked.getAction());
        assertEquals(Map.of("ip", MASK), masked.getContext().orElseThrow().asMap());
        assertEquals(Map.of("reason", MASK), event.getResponse().orElseThrow().getContext().orElseThrow().asMap());
        assertEquals(Outcome.DENY, event.getOutcome());
        assertSame(masked, event.getRequest());
    }

    @Test
    void masksNothingTheContentIncludes() {
        EvaluationRequest request = EvaluationRequest.of(ALICE, READ, ACCOUNT);
        EventContent content = EventContent.defaults().include("subject.id").include("subject.properties.*")
                .include("resource.properties.*").include("action.properties.*");

        CallCompleted<EvaluationRequest, EvaluationResponse> event = builder(Operation.EVALUATION, request)
                .response(EvaluationResponse.of(true)).content(content).build();

        assertEquals(request, event.getRequest());
        assertEquals(Outcome.PERMIT, event.getOutcome());
    }

    @Test
    void masksTheDefaultsAndItemsOfABatch() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(ALICE)
                .action(READ)
                .context(CONTEXT)
                .evaluation(EvaluationItem.builder().resource(ACCOUNT).build())
                .evaluation(EvaluationItem.builder().subject(Subject.of("user", "carol")).resource(ACCOUNT).build())
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
                .option("trace", true)
                .build();
        CallCompleted<EvaluationsRequest, EvaluationsResponse> event = builder(Operation.EVALUATIONS, request)
                .response(EvaluationsResponse.of(List.of(EvaluationResponse.of(true), EvaluationResponse.of(false)), 2))
                .build();

        EvaluationsRequest masked = event.getRequest();
        assertEquals(MASK, masked.getSubject().orElseThrow().getId());
        assertEquals(MASK, masked.getEvaluations().get(1).getSubject().orElseThrow().getId());
        assertEquals(MASK, masked.getEvaluations().get(0).getResource().orElseThrow().getProperties().get("owner"));
        assertEquals(EvaluationsSemantic.DENY_ON_FIRST_DENY, masked.getSemantic().orElseThrow());
        assertEquals(Map.of("trace", true), masked.getOptions());
        assertEquals(2, event.getResponse().orElseThrow().getEvaluations().size());
        assertEquals(Outcome.COMPLETED, event.getOutcome());
    }

    @Test
    void masksSearchRequestsAndResults() {
        SubjectSearchRequest subjects = SubjectSearchRequest.builder()
                .subjectType("user").subjectProperty("department", "sales")
                .action(READ).resource(ACCOUNT).page(PageRequest.ofLimit(10)).build();
        CallCompleted<SubjectSearchRequest, SearchResponse<Subject>> subjectEvent =
                builder(Operation.SUBJECT_SEARCH, subjects)
                        .response(SearchResponse.<Subject>builder().result(ALICE).context(CONTEXT).build())
                        .build();

        assertEquals(Map.of("department", MASK), subjectEvent.getRequest().getSubjectProperties());
        assertEquals(PageRequest.ofLimit(10), subjectEvent.getRequest().getPage().orElseThrow());
        SearchResponse<Subject> results = subjectEvent.getResponse().orElseThrow();
        assertEquals(MASK, results.getResults().get(0).getId());
        assertEquals(Map.of("ip", MASK), results.getContext().orElseThrow().asMap());

        ResourceSearchRequest resources = ResourceSearchRequest.builder()
                .subject(ALICE).action(READ).resourceType("account").resourceProperty("owner", "bob").build();
        CallCompleted<ResourceSearchRequest, SearchResponse<Resource>> resourceEvent =
                builder(Operation.RESOURCE_SEARCH, resources)
                        .response(SearchResponse.<Resource>builder().result(ACCOUNT).build())
                        .build();
        assertEquals(Map.of("owner", MASK), resourceEvent.getRequest().getResourceProperties());
        assertEquals(MASK, resourceEvent.getRequest().getSubject().getId());
        assertEquals("123", resourceEvent.getResponse().orElseThrow().getResults().get(0).getId());

        CallCompleted<ActionSearchRequest, SearchResponse<Action>> actionEvent =
                builder(Operation.ACTION_SEARCH, ActionSearchRequest.of(ALICE, ACCOUNT))
                        .response(SearchResponse.<Action>builder().result(READ).build())
                        .build();
        assertEquals(MASK, actionEvent.getRequest().getSubject().getId());
        assertEquals(Map.of("why", MASK), actionEvent.getResponse().orElseThrow().getResults().get(0).getProperties());
    }

    @Test
    void readsTheTypesOfItsOwnOperationOnly() {
        CallCompleted<?, ?> event = builder(Operation.EVALUATION, EvaluationRequest.of(ALICE, READ, ACCOUNT))
                .response(EvaluationResponse.of(true)).build();

        assertTrue(event.as(Operation.EVALUATION).isPresent());
        assertFalse(event.as(Operation.EVALUATIONS).isPresent());
        assertFalse(event.as(Operation.SUBJECT_SEARCH).isPresent());
    }

    @Test
    void reportsAFailure() {
        PdpCommunicationException failure = new PdpCommunicationException("unreachable");

        CallCompleted<EvaluationRequest, EvaluationResponse> event =
                builder(Operation.EVALUATION, EvaluationRequest.of(ALICE, READ, ACCOUNT))
                        .failure(failure).requestId("r1").requestId("r2").build();

        assertEquals(Outcome.FAILED, event.getOutcome());
        assertSame(failure, event.getFailure().orElseThrow());
        assertTrue(event.getResponse().isEmpty());
        assertEquals(List.of("r1", "r2"), event.getRequestIds());
        assertEquals(2, event.getAttempts());
        assertTrue(event.toString().contains("FAILED"));
        assertFalse(event.toString().contains("alice"));
    }

    @Test
    void requiresExactlyOneOfResponseAndFailure() {
        CallCompleted.Builder<EvaluationRequest, EvaluationResponse> neither =
                builder(Operation.EVALUATION, EvaluationRequest.of(ALICE, READ, ACCOUNT));
        assertThrows(IllegalStateException.class, neither::build);

        CallCompleted.Builder<EvaluationRequest, EvaluationResponse> both =
                builder(Operation.EVALUATION, EvaluationRequest.of(ALICE, READ, ACCOUNT))
                        .response(EvaluationResponse.of(true))
                        .failure(new PdpCommunicationException("unreachable"));
        assertThrows(IllegalStateException.class, both::build);

        assertThrows(NullPointerException.class, () -> CallCompleted.builder(Operation.EVALUATION)
                .response(EvaluationResponse.of(true)).startTime(Instant.EPOCH).duration(Duration.ZERO).build());
    }

    @Test
    void namesItsOperations() {
        assertEquals(List.of("evaluation", "evaluations", "subject_search", "resource_search", "action_search",
                "metadata"), Operation.values().stream().map(Operation::getName).toList());
    }
}
