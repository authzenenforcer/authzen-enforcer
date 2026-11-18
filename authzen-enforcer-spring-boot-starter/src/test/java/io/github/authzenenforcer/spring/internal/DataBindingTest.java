package io.github.authzenenforcer.spring.internal;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBindingTest {

    private static boolean binds(String name) {
        Method method = Arrays.stream(Handlers.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
        return DataBinding.binds(method);
    }

    @Test
    void findsArgumentsThatDataBindingFills() {
        for (String name : List.of("implicit", "modelAttribute", "optional", "asynchronous", "custom")) {
            assertTrue(binds(name), name);
        }
    }

    @Test
    void leavesArgumentsThatOtherResolversTake() {
        for (String name : List.of("none", "annotated", "simple", "framework", "unbound")) {
            assertFalse(binds(name), name);
        }
    }

    @SuppressWarnings("unused")
    static class Handlers {

        void implicit(Form form) {
        }

        void modelAttribute(@ModelAttribute("form") Form form) {
        }

        void optional(Optional<Form> form) {
        }

        void asynchronous(Mono<Form> form) {
        }

        // A resolver of the application may take it, but nothing shows that it does
        void custom(@Deprecated Form form) {
        }

        void none() {
        }

        void annotated(@PathVariable String id, @RequestParam Form form, @RequestBody Form body,
                @AuthenticationPrincipal Form principal) {
        }

        void simple(String id, int count, String[] ids) {
        }

        void framework(HttpServletRequest request, ServerWebExchange exchange, Principal principal, Model model,
                Map<String, Object> map, Mono<Principal> caller) {
        }

        void unbound(@ModelAttribute(binding = false) Form form) {
        }
    }

    static class Form {

        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
