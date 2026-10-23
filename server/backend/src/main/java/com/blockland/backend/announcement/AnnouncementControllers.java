package com.blockland.backend.announcement;

import com.blockland.backend.player.PlayerService;
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

import java.util.List;

/** HTTP endpoints of the announcement module: one controller for admins, one for Minecraft servers. */
public final class AnnouncementControllers {
    private AnnouncementControllers() {
    }

    /** 公告管理. */
    @RestController
    @RequestMapping("/api/admin/announcements")
    public static class Admin {
        private final AnnouncementService announcements;

        public Admin(AnnouncementService announcements) {
            this.announcements = announcements;
        }

        /** Newest first. */
        @GetMapping
        public List<Announcement> list() {
            return announcements.list();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public Announcement create(@RequestBody AnnouncementService.Request request) {
            return announcements.create(request);
        }

        @PutMapping("/{id}")
        public Announcement update(@PathVariable String id, @RequestBody AnnouncementService.Request request) {
            return announcements.update(id, request);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable String id) {
            announcements.delete(id);
        }
    }

    /** Read state reported by Minecraft servers (the lobby's 已阅 button). */
    @RestController
    @RequestMapping("/api/server/players")
    public static class Server {
        private final AnnouncementService announcements;

        public Server(AnnouncementService announcements) {
            this.announcements = announcements;
        }

        public record ReadRequest(List<String> ids) {
        }

        @PostMapping("/{uuid}/announcements/read")
        public PlayerService.PlayerStatus read(@PathVariable String uuid, @RequestBody ReadRequest request) {
            return announcements.markRead(uuid, request.ids());
        }
    }
}
