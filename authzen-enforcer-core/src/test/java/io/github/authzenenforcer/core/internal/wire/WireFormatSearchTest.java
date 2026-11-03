package io.github.authzenenforcer.core.internal.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.PageRequest;
import io.github.authzenenforcer.core.model.PageResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Search API requests and responses, checked against the examples of AuthZEN 1.0 section 8.
 */
class WireFormatSearchTest {

    private final JsonCodec codec = JsonCodec.defaultCodec();

    private Object written(Map<String, Object> json) {
        return codec.read(codec.write(json));
    }

    @Test
    void writesASubjectSearchRequestWithTheSubjectTypeOnly() {
        SubjectSearchRequest request = SubjectSearchRequest.builder()
                .subjectType("user")
                .action(Action.of("can_read"))
                .resource(Resource.of("account", "123"))
                .context(Context.of(Map.of("time", "2024-10-26T01:22-07:00")))
                .build();

        assertEquals(codec.read("""
                {
                  "subject": {"type": "user"},
                  "action": {"name": "can_read"},
                  "resource": {"type": "account", "id": "123"},
                  "context": {"time": "2024-10-26T01:22-07:00"}
                }
                """), written(WireFormat.subjectSearchRequest(request)));
    }

    @Test
    void writesAResourceSearchRequestWithAPage() {
        ResourceSearchRequest request = ResourceSearchRequest.builder()
                .subject(Subject.of("user", "alice@example.com"))
                .action(Action.of("can_read"))
                .resourceType("account")
                .page(PageRequest.ofLimit(2))
                .build();

        assertEquals(codec.read("""
                {
                  "subject": {"type": "user", "id": "alice@example.com"},
                  "action": {"name": "can_read"},
                  "resource": {"type": "account"},
                  "page": {"limit": 2}
                }
                """), written(WireFormat.resourceSearchRequest(request)));
    }

    @Test
    void writesTheNextPageWithTheTokenAndTheUnchangedLimit() {
        ResourceSearchRequest first = ResourceSearchRequest.builder()
                .subject(Subject.of("user", "alice@example.com"))
                .action(Action.of("can_read"))
                .resourceType("account")
                .page(PageRequest.builder().limit(2).property("sort", "id").build())
                .build();

        assertEquals(Map.of("token", "a3M9NDU2O3N6PTI=", "limit", 2L, "properties", Map.of("sort", "id")),
                ((Map<?, ?>) written(WireFormat.resourceSearchRequest(first.withPageToken("a3M9NDU2O3N6PTI="))))
                        .get("page"));
    }

    @Test
    void writesSearchedEntityPropertiesAndAnActionSearchRequestWithoutAction() {
        SubjectSearchRequest subjects = SubjectSearchRequest.builder()
                .subjectType("user").subjectProperty("department", "Sales")
                .action(Action.of("can_read")).resource(Resource.of("account", "123"))
                .build();
        ActionSearchRequest actions = ActionSearchRequest.of(
                Subject.of("user", "alice@example.com"), Resource.of("account", "123"));

        assertEquals(Map.of("type", "user", "properties", Map.of("department", "Sales")),
                WireFormat.subjectSearchRequest(subjects).get("subject"));
        assertEquals(codec.read("""
                {"subject": {"type": "user", "id": "alice@example.com"}, "resource": {"type": "account", "id": "123"}}
                """), written(WireFormat.actionSearchRequest(actions)));
    }

    @Test
    void writesContextAndPageOfEverySearch() {
        Context context = Context.of(Map.of("time", "now"));
        PageRequest page = PageRequest.builder().token("t").build();
        Map<String, Object> subjects = WireFormat.subjectSearchRequest(SubjectSearchRequest.builder()
                .subjectType("user").action(Action.of("read")).resource(Resource.of("account", "1"))
                .page(page).build());
        Map<String, Object> resources = WireFormat.resourceSearchRequest(ResourceSearchRequest.builder()
                .subject(Subject.of("user", "alice")).action(Action.of("read")).resourceType("account")
                .context(context).build());
        Map<String, Object> actions = WireFormat.actionSearchRequest(ActionSearchRequest.builder()
                .subject(Subject.of("user", "alice")).resource(Resource.of("account", "1"))
                .context(context).page(page).build());

        assertEquals(Map.of("token", "t"), subjects.get("page"));
        assertEquals(Map.of("time", "now"), resources.get("context"));
        assertEquals(Map.of("time", "now"), actions.get("context"));
        assertEquals(Map.of("token", "t"), actions.get("page"));
    }

    @Test
    void readsResultsPageAndContext() {
        SearchResponse<Resource> response = WireFormat.resourceSearchResponse(codec.read("""
                {
                  "page": {"next_token": "a3M9NDU2O3N6PTI=", "count": 2, "total": 3, "properties": {"remaining": 1}},
                  "context": {"query_execution_time_ms": 42},
                  "results": [
                    {"type": "account", "id": "123"},
                    {"type": "account", "id": "456", "properties": {"owner": "alice"}}
                  ]
                }
                """));

        assertEquals(List.of(Resource.of("account", "123"),
                        Resource.builder().type("account").id("456").property("owner", "alice").build()),
                response.getResults());
        PageResponse page = response.getPage().orElseThrow();
        assertEquals("a3M9NDU2O3N6PTI=", page.getNextToken());
        assertEquals(2L, page.getCount().orElseThrow());
        assertEquals(3L, page.getTotal().orElseThrow());
        assertEquals(Map.of("remaining", 1L), page.getProperties());
        assertEquals("a3M9NDU2O3N6PTI=", response.getNextPageToken().orElseThrow());
        assertEquals(42L, response.getContext().orElseThrow().asMap().get("query_execution_time_ms"));
    }

    @Test
    void readsTheLastPageAndAResponseWithoutPage() {
        SearchResponse<Subject> last = WireFormat.subjectSearchResponse(codec.read("""
                {"page": {"next_token": "", "count": 1, "total": 3}, "results": [{"type": "user", "id": "bob"}]}
                """));
        SearchResponse<Action> all = WireFormat.actionSearchResponse(codec.read("""
                {"results": [{"name": "can_read"}, {"name": "can_write"}]}
                """));

        assertFalse(last.getPage().orElseThrow().hasNextPage());
        assertTrue(last.getNextPageToken().isEmpty());
        assertEquals(List.of(Action.of("can_read"), Action.of("can_write")), all.getResults());
        assertTrue(all.getPage().isEmpty());
        assertTrue(all.getNextPageToken().isEmpty());
    }

    @Test
    void acceptsAnEmptyResultAndResultsOfAnotherTypeThanRequested() {
        assertTrue(WireFormat.subjectSearchResponse(codec.read("{\"results\": []}")).getResults().isEmpty());
        // The specification asks for the right kind of entity, not for the type that was searched for
        assertEquals("group", WireFormat.subjectSearchResponse(codec.read(
                "{\"results\": [{\"type\": \"group\", \"id\": \"admins\"}]}")).getResults().get(0).getType());
    }

    @Test
    void acceptsCountsOfAnyIntegralNumberType() {
        Map<String, Object> json = Map.of("results", List.of(),
                "page", Map.of("next_token", "", "count", 1, "total", BigInteger.TEN));

        PageResponse page = WireFormat.subjectSearchResponse(json).getPage().orElseThrow();

        assertEquals(1L, page.getCount().orElseThrow());
        assertEquals(10L, page.getTotal().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{}",
            "{\"results\": {}}",
            "{\"results\": [null]}",
            "{\"results\": [\"alice\"]}",
            "{\"results\": [{\"type\": \"user\"}]}",
            "{\"results\": [{\"id\": \"alice\"}]}",
            "{\"results\": [{\"type\": \"user\", \"id\": 7}]}",
            "{\"results\": [{\"type\": \"user\", \"id\": \"alice\", \"properties\": []}]}",
            "{\"results\": [], \"page\": []}",
            "{\"results\": [], \"page\": {}}",
            "{\"results\": [], \"page\": {\"next_token\": null}}",
            "{\"results\": [], \"page\": {\"next_token\": 1}}",
            "{\"results\": [], \"page\": {\"next_token\": \"\", \"count\": -1}}",
            "{\"results\": [], \"page\": {\"next_token\": \"\", \"count\": 1.5}}",
            "{\"results\": [], \"page\": {\"next_token\": \"\", \"total\": \"3\"}}",
            "{\"results\": [], \"page\": {\"next_token\": \"\", \"total\": 9223372036854775808}}",
            "{\"results\": [], \"page\": {\"next_token\": \"\", \"properties\": 1}}",
            "{\"results\": [], \"context\": []}"
    })
    void rejectsInvalidSubjectSearchResponses(String json) {
        Object tree = codec.read(json);

        assertThrows(PdpProtocolException.class, () -> WireFormat.subjectSearchResponse(tree));
    }

    @Test
    void rejectsActionsWithoutName() {
        Object tree = codec.read("{\"results\": [{\"type\": \"user\", \"id\": \"alice\"}]}");

        PdpProtocolException e = assertThrows(PdpProtocolException.class, () -> WireFormat.actionSearchResponse(tree));

        assertTrue(e.getMessage().startsWith("Action search response result 0 is not valid"), e.getMessage());
    }

    @Test
    void rejectsPagePropertiesWithNonStringKeys() {
        Map<Object, Object> properties = Map.of(1, "x");
        Object tree = Map.of("results", List.of(), "page", Map.of("next_token", "", "properties", properties));

        assertThrows(PdpProtocolException.class, () -> WireFormat.subjectSearchResponse(tree));
    }

    @Test
    void rejectsResultPropertiesWithNonStringKeys() {
        Map<Object, Object> properties = Map.of(1, "x");
        Object tree = Map.of("results", List.of(Map.of("type", "account", "id", "1", "properties", properties)));

        assertThrows(PdpProtocolException.class, () -> WireFormat.resourceSearchResponse(tree));
    }
}
