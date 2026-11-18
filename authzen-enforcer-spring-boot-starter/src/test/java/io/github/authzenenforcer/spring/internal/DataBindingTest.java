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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBindingTest {

    private static boolean binds(String name) {
        return DataBinding.binds(method(name));
    }

    @Test
    void findsArgumentsThatDataBindingFills() {
        for (String name : List.of("implicit", "modelAttribute", "optional", "asynchronous", "custom", "unbound")) {
            assertTrue(binds(name), name);
        }
    }

    @Test
    void leavesArgumentsThatOtherResolversTake() {
        for (String name : List.of("none", "annotated", "simple", "framework")) {
            assertFalse(binds(name), name);
        }
    }

    @Test
    void namesTheBoundArgumentsAsSpringNamesModelAttributes() throws Exception {
        assertEquals(Set.of("form"), DataBinding.boundArguments(Handlers.class, method("implicit"), List.of()));
        assertEquals(Set.of("named", "form"),
                DataBinding.boundArguments(Handlers.class, method("named"), List.of()));
        assertEquals(Set.of(), DataBinding.boundArguments(Handlers.class, method("none"), List.of()));
    }

    @Test
    void includesTheModelAttributeMethodsOfTheControllerAndItsAdvice() throws Exception {
        assertEquals(Set.of("form"), DataBinding.boundArguments(Prepared.class, Prepared.class.getMethod("none"),
                List.of()));
        assertEquals(Set.of("form"), DataBinding.boundArguments(Handlers.class, method("none"),
                List.of(Advice.class)));
    }

    // A handler method without arguments, of a controller whose model attribute method binds data
    static class Prepared {

        @ModelAttribute("loaded")
        public String load(Form form) {
            return form.getId();
        }

        public void none() {
        }
    }

    static class Advice {

        @ModelAttribute
        public void prepare(Form form) {
        }
    }

    private static Method method(String name) {
        return Arrays.stream(Handlers.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unused")
    static class Handlers {

        void named(@ModelAttribute("named") Form form) {
        }


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
