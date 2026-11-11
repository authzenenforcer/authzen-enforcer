package io.github.authzenenforcer.spring.app;

import io.github.authzenenforcer.spring.CheckPermission;
import io.github.authzenenforcer.spring.SkipPermissionCheck;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Callable;

/** An application with enforced and open handlers. */
@SpringBootApplication
public class TestApplication {

    /** Documents, enforced by method annotations. */
    @RestController
    public static class Documents {

        @CheckPermission
        @GetMapping("/documents/{id}")
        public String get(@PathVariable String id) {
            return "document " + id;
        }

        @GetMapping("/documents/{id}/preview")
        public String preview(@PathVariable String id) {
            return "preview " + id;
        }

        @CheckPermission(action = "cancel", resourceType = "order", resourceId = "#id",
                context = {"ip=#request.remoteAddr", "caller=#authentication?.name"})
        @PostMapping("/orders/{id}/cancel")
        public String cancel(@PathVariable String id) {
            return "cancelled " + id;
        }

        @CheckPermission
        @GetMapping("/exports/{id}")
        public Callable<String> export(@PathVariable String id) {
            return () -> "export " + id;
        }

        @Approve
        @PostMapping("/invoices/{id}/approve")
        public String approve(@PathVariable String id) {
            return "approved " + id;
        }

        @CheckPermission
        @GetMapping("/{tenant}/things")
        public String things(@PathVariable String tenant) {
            return "things";
        }

        @CheckPermission(resourceId = "#missing.length()")
        @GetMapping("/broken/{id}")
        public String broken(@PathVariable String id) {
            return "broken";
        }
    }

    /** Reports, enforced by a class annotation. */
    @RestController
    @RequestMapping("/reports")
    @CheckPermission(resourceType = "report", context = "level='type'")
    public static class Reports {

        @GetMapping("/{id}")
        public String get(@PathVariable String id) {
            return "report " + id;
        }

        @CheckPermission(action = "export", context = "level='method'")
        @PostMapping("/{id}/export")
        public String export(@PathVariable String id) {
            return "exported " + id;
        }

        @SkipPermissionCheck
        @GetMapping("/public")
        public String open() {
            return "public";
        }
    }

    /** A meta-annotation. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @CheckPermission(action = "approve")
    public @interface Approve {
    }
}
