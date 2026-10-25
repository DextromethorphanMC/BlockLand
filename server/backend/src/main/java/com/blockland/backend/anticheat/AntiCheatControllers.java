package com.blockland.backend.anticheat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The anti-cheat API (ANTICHEAT_SPEC §9): game servers publish their checks, report evidence and ask for auto-bans; the
 * admin UI reviews cases and edits the global config.
 */
public final class AntiCheatControllers {
    private AntiCheatControllers() {
    }

    @RestController
    @RequestMapping("/api/server/anticheat")
    public static class Server {
        private final AntiCheatCaseService cases;

        public Server(AntiCheatCaseService cases) {
            this.cases = cases;
        }

        @PostMapping("/reports")
        public Map<String, Integer> reports(@RequestBody AntiCheatCaseService.ReportBatch batch) {
            return Map.of("accepted", cases.report(batch == null ? List.of() : batch.reports()));
        }

        @PostMapping("/punish")
        public AntiCheatCaseService.PunishResult punish(@RequestBody AntiCheatCaseService.PunishRequest request) {
            return cases.punish(request);
        }
    }

    @RestController
    @RequestMapping("/api/server/registry/anticheat")
    public static class ServerRegistry {
        private final AntiCheatRegistryService registry;

        public ServerRegistry(AntiCheatRegistryService registry) {
            this.registry = registry;
        }

        @PostMapping
        public Map<String, Integer> publish(@RequestBody AntiCheatRegistryService.Catalogue catalogue) {
            return Map.of("count", registry.replace(catalogue));
        }
    }

    @RestController
    @RequestMapping("/api/admin/anticheat")
    public static class Admin {
        private final AntiCheatCaseService cases;
        private final AntiCheatConfigStore config;

        public Admin(AntiCheatCaseService cases, AntiCheatConfigStore config) {
            this.cases = cases;
            this.config = config;
        }

        @GetMapping("/cases")
        public List<AntiCheatCase.Summary> list(@RequestParam(value = "status", required = false) String status,
                                                @RequestParam(value = "q", required = false) String query) {
            return cases.list(status, query);
        }

        @GetMapping("/cases/{id}")
        public AntiCheatCase get(@PathVariable String id) {
            return cases.get(id);
        }

        @PostMapping("/cases/{id}/review")
        public AntiCheatCase review(@PathVariable String id, @RequestBody AntiCheatCaseService.ReviewRequest request) {
            return cases.review(id, request);
        }

        @PostMapping("/cases/{id}/notes")
        public AntiCheatCase note(@PathVariable String id, @RequestBody AntiCheatCaseService.NoteRequest request) {
            return cases.note(id, request == null ? null : request.text());
        }

        @DeleteMapping("/cases/{id}")
        public ResponseEntity<Void> delete(@PathVariable String id) {
            cases.delete(id);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/players/{uuid}/cases")
        public List<AntiCheatCase.Summary> ofPlayer(@PathVariable String uuid) {
            return cases.ofPlayer(uuid);
        }

        @GetMapping("/summary")
        public AntiCheatCaseService.Counts summary() {
            return cases.counts();
        }

        @GetMapping("/config")
        public AntiCheatConfig config() {
            return config.get();
        }

        /** Validated as a whole; the answer is what was stored (defaults filled in). */
        @PutMapping("/config")
        public AntiCheatConfig saveConfig(@RequestBody JsonNode body) {
            return config.save(body);
        }
    }

    @RestController
    @RequestMapping("/api/admin/registry/anticheat")
    public static class AdminRegistry {
        private final AntiCheatRegistryService registry;

        public AdminRegistry(AntiCheatRegistryService registry) {
            this.registry = registry;
        }

        /** The checks the game server published ({@code {"checks": [...]}}, empty until it started once). */
        @GetMapping
        public AntiCheatRegistryService.Catalogue catalogue() {
            return registry.catalogue();
        }
    }
}
