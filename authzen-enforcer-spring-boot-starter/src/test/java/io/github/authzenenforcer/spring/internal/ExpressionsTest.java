package io.github.authzenenforcer.spring.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"#id", "'literal'", "#request.method", "#request.remoteAddr", "#request.header('X-Trace')",
            "#request.parameter('ref')", "#claims", "#claims['sub']", "#claims.realm_access.roles", "#claims?.email",
            "#claims['groups'][0]", "#id + '-' + #request.method", "#request.header(#id)"})
    void acceptsWhatExpressionsMayRead(String expression) {
        assertDoesNotThrow(() -> Expressions.parse("the test expression", expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#authentication.name", "#authentication.credentials", "#request.getSession()",
            "#request.session", "#request.cookies", "#request.getHeader('Authorization')", "#request['method']",
            "T(java.lang.Runtime).getRuntime()", "new java.lang.Object()", "@someBean", "#id.length()", "#id.bytes",
            "#id[0]", "#request.header('X').length()", "#request.header('X').bytes", "name", "#claims = null",
            "#claims.sub.getClass()", "#this.class"})
    void rejectsEverythingElseWhenParsing(String expression) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Expressions.parse("the test expression", expression));
        assertTrue(e.getMessage().startsWith("The test expression uses "), e.getMessage());
    }

    @Test
    void rejectsInvalidSyntax() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Expressions.parse("the test expression", "#request.("));
        assertTrue(e.getMessage().startsWith("The test expression is invalid"), e.getMessage());
    }

    @Test
    void hidesCredentialsOfTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notes");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Proxy-Authorization", "Basic secret");
        request.addHeader("DPoP", "proof");
        request.setCookies(new Cookie("SESSION", "secret"));
        request.addParameter("access_token", "secret");
        request.addHeader("X-Trace", "t-1");
        request.addParameter("ref", "n-1");
        EvaluationContext context = context(request);

        for (String header : List.of("Authorization", "authorization", "Proxy-Authorization", "DPoP", "Cookie")) {
            assertNull(evaluate("#request.header('" + header + "')", context), header);
        }
        assertNull(evaluate("#request.parameter('access_token')", context));
        assertEquals("t-1", evaluate("#request.header('X-Trace')", context));
        assertEquals("n-1", evaluate("#request.parameter('ref')", context));
        assertEquals("POST", evaluate("#request.method", context));
        assertEquals("alice", evaluate("#claims['sub']", context));
        assertEquals("42", evaluate("#id", context));
    }

    @Test
    void resolvesNothingElseWhenEvaluating() {
        // The context is the second line of defence, behind the check when parsing
        EvaluationContext context = context(new MockHttpServletRequest("GET", "/notes"));
        SpelExpressionParser parser = new SpelExpressionParser();
        for (String expression : List.of("#request.getSession()", "#request.session", "#id.length()",
                "T(java.lang.Runtime).getRuntime()", "new java.lang.Object()", "#claims.sub.getClass()",
                "#claims['sub'] = 'bob'")) {
            assertThrows(EvaluationException.class, () -> parser.parseExpression(expression).getValue(context),
                    expression);
        }
    }

    private static EvaluationContext context(MockHttpServletRequest request) {
        return Expressions.context(Map.of("id", "42", "request", "spoofed"), new RequestView(request),
                Map.of("sub", "alice"));
    }

    private static Object evaluate(String expression, EvaluationContext context) {
        return Expressions.parse("the test expression", expression).getValue(context);
    }
}
