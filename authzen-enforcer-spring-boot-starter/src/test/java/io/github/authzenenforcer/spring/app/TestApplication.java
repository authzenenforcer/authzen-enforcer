package io.github.authzenenforcer.spring.app;

import io.github.authzenenforcer.spring.CheckPermission;
import io.github.authzenenforcer.spring.SkipPermissionCheck;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
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
                context = {"ip=#request.remoteAddr", "caller=#claims['sub']", "agent=#request.header('User-Agent')",
                        "auth=#request.header('authorization')", "token=#request.parameter('access_token')"})
        @PostMapping("/orders/{id}/cancel")
        public String cancel(@PathVariable String id) {
            return "cancelled " + id;
        }

        @CheckPermission(resourceType = "belge")
        @GetMapping("/papers")
        public String papers() {
            return "papers";
        }

        @CheckPermission
        @GetMapping("/exports/{id}")
        public Callable<String> export(@PathVariable String id) {
            return () -> "export " + id;
        }

        @CheckPermission(action = "share", resourceType = "note", resourceId = "#request.parameter('ref')")
        @PostMapping("/notes/share")
        public String share() {
            return "shared";
        }

        @CheckPermission(action = "share", resourceType = "note", resourceId = "#request.parameter('ref')")
        @PostMapping("/parts/share")
        public String sharePart(@RequestPart("ref") String ref) {
            return "shared " + ref;
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

        @CheckPermission(resourceId = "#claims['missing']")
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

    /** Handlers whose arguments data binding fills. */
    @RestController
    public static class Bindings {

        @CheckPermission
        @PutMapping("/drafts/{id}")
        public String update(Draft draft) {
            return "updated " + draft.getId();
        }

        @CheckPermission(context = {"level=#request.parameter('level')", "tenant=#request.header('Tenant')"})
        @PutMapping("/memos/{id}")
        public String classify(@ModelAttribute Memo memo) {
            return "classified " + memo.id() + " " + memo.level() + " " + memo.tenant();
        }

        @CheckPermission
        @PutMapping("/letters/{id}")
        public String letter(@PathVariable String id, @RequestParam(required = false) String note) {
            return "letter " + id;
        }
    }

    /** Bound through its setters. */
    public static class Draft {

        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    /**
     * Bound through its constructor.
     *
     * @param id     the id
     * @param level  the level
     * @param tenant the tenant, also from the header Tenant
     */
    public record Memo(String id, String level, String tenant) {
    }

    /** A meta-annotation. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @CheckPermission(action = "approve")
    public @interface Approve {
    }
}
