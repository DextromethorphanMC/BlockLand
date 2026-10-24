package com.blockland.backend.ranked;

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

/** The ranked ladder's API: game servers read standings and report matches, the admin UI configures and watches it. */
public final class RankedControllers {
    private RankedControllers() {
    }

    @RestController
    @RequestMapping("/api/server/ranked")
    public static class Server {
        private final RankedService ranked;

        public Server(RankedService ranked) {
            this.ranked = ranked;
        }

        public record PlayersRequest(List<String> uuids) {
        }

        /** Standings of the given players (keyed by the uuid as sent), e.g. for matchmaking or the lobby. */
        @PostMapping("/players")
        public Map<String, RankedService.Standing> players(@RequestBody PlayersRequest request) {
            return ranked.standings(request == null ? List.of() : request.uuids());
        }

        /** A ranked match was decided; reporting the same match id again returns the same changes. */
        @PostMapping("/results")
        public List<RankedService.Change> results(@RequestBody RankedService.ResultRequest request) {
            return ranked.report(request);
        }

        @GetMapping("/leaderboard")
        public List<RankedService.LeaderEntry> leaderboard(@RequestParam(value = "limit", defaultValue = "100") int limit) {
            return ranked.leaderboard(limit);
        }
    }

    @RestController
    @RequestMapping("/api/admin/ranked")
    public static class Admin {
        private final RankedService ranked;

        public Admin(RankedService ranked) {
            this.ranked = ranked;
        }

        @GetMapping("/config")
        public RankedConfig config() {
            return ranked.config();
        }

        /** The season is kept: only a reset counts it up. */
        @PutMapping("/config")
        public RankedConfig saveConfig(@RequestBody RankedConfig config) {
            return ranked.saveConfig(config);
        }

        /** 新赛季: every score back to the start, the old season's end kept per player. */
        @PostMapping("/reset")
        public RankedConfig reset() {
            return ranked.resetSeason();
        }

        @GetMapping("/leaderboard")
        public List<RankedService.LeaderEntry> leaderboard(@RequestParam(value = "limit", defaultValue = "100") int limit) {
            return ranked.leaderboard(limit);
        }

        @GetMapping("/players/{uuid}")
        public RankedService.PlayerView player(@PathVariable String uuid) {
            return ranked.player(uuid);
        }
    }
}
