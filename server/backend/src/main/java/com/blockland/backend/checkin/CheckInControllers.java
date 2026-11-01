package com.blockland.backend.checkin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The daily check-in's API: game servers read a player's cycle and check them in, the admin UI configures it. */
public final class CheckInControllers {
    private CheckInControllers() {
    }

    @RestController
    @RequestMapping("/api/server/checkin")
    public static class Server {
        private final CheckInService checkIn;

        public Server(CheckInService checkIn) {
            this.checkIn = checkIn;
        }

        @GetMapping("/{uuid}")
        public CheckInService.State state(@PathVariable String uuid) {
            return checkIn.state(uuid);
        }

        /** Checks the player in for today; the same day again only answers {@code already}. */
        @PostMapping("/{uuid}/claim")
        public CheckInService.ClaimResult claim(@PathVariable String uuid) {
            return checkIn.claim(uuid);
        }
    }

    /** 每日签到. */
    @RestController
    @RequestMapping("/api/admin/checkin")
    public static class Admin {
        private final CheckInService checkIn;

        public Admin(CheckInService checkIn) {
            this.checkIn = checkIn;
        }

        @GetMapping("/config")
        public CheckInConfig config() {
            return checkIn.config();
        }

        @PutMapping("/config")
        public CheckInConfig save(@RequestBody CheckInConfig config) {
            return checkIn.saveConfig(config);
        }

        @GetMapping("/stats")
        public CheckInService.Stats stats() {
            return checkIn.stats();
        }

        @DeleteMapping("/players/{uuid}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void reset(@PathVariable String uuid) {
            checkIn.reset(uuid);
        }
    }
}
