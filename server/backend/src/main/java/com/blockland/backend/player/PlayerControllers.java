package com.blockland.backend.player;

import com.blockland.backend.common.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP endpoints of the player module: one controller for admins, one for Minecraft servers. */
public final class PlayerControllers {
    private PlayerControllers() {
    }

    /** 用户管理. */
    @RestController
    @RequestMapping("/api/admin/users")
    public static class Admin {
        private final PlayerService players;

        public Admin(PlayerService players) {
            this.players = players;
        }

        public record RestrictionRequest(String reason, Integer minutes) {
        }

        public record GrantRequest(String key, Integer days) {
        }

        @GetMapping
        public List<PlayerService.AccountView> list(@RequestParam(value = "q", required = false) String query) {
            return players.list(query);
        }

        @GetMapping("/{uuid}")
        public PlayerService.AccountView get(@PathVariable String uuid) {
            return players.get(uuid);
        }

        @PostMapping("/{uuid}/ban")
        public PlayerService.AccountView ban(@PathVariable String uuid, @RequestBody RestrictionRequest request) {
            return players.ban(uuid, request.reason(), request.minutes());
        }

        @DeleteMapping("/{uuid}/ban")
        public PlayerService.AccountView unban(@PathVariable String uuid) {
            return players.unban(uuid);
        }

        @PostMapping("/{uuid}/mute")
        public PlayerService.AccountView mute(@PathVariable String uuid, @RequestBody RestrictionRequest request) {
            return players.mute(uuid, request.reason(), request.minutes());
        }

        @DeleteMapping("/{uuid}/mute")
        public PlayerService.AccountView unmute(@PathVariable String uuid) {
            return players.unmute(uuid);
        }

        @PostMapping("/{uuid}/entitlements")
        public PlayerService.AccountView grant(@PathVariable String uuid, @RequestBody GrantRequest request) {
            return players.grant(uuid, request.key(), request.days(), "admin");
        }

        @DeleteMapping("/{uuid}/entitlements/{key}")
        public PlayerService.AccountView revoke(@PathVariable String uuid, @PathVariable String key) {
            return players.revoke(uuid, key);
        }
    }

    /** Session accounting and status for Minecraft servers. */
    @RestController
    @RequestMapping("/api/server/players")
    public static class Server {
        private final PlayerService players;

        public Server(PlayerService players) {
            this.players = players;
        }

        public record LoginRequest(String uuid, String name) {
        }

        public record HeartbeatRequest(List<PlayerService.SessionReport> players) {
        }

        @PostMapping("/login")
        public PlayerService.PlayerStatus login(@RequestBody LoginRequest request) {
            return players.login(request.uuid(), request.name());
        }

        /** Called periodically with the seconds each online player played since the last report. */
        @PostMapping("/heartbeat")
        public List<PlayerService.PlayerStatus> heartbeat(@RequestBody HeartbeatRequest request) {
            return players.heartbeat(request.players() == null ? List.of() : request.players());
        }

        @PostMapping("/logout")
        public void logout(@RequestBody PlayerService.SessionReport report) {
            players.logout(report);
        }

        public record SettingsRequest(Boolean allowPartyInvites) {
        }

        /** @param build null removes the weapon's build */
        public record BuildRequest(String weapon, PlayerAccount.WeaponBuild build) {
        }

        /** The player changed a setting in the lobby (隐私 → 允许被邀请组队). */
        @PostMapping("/{uuid}/settings")
        public PlayerService.PlayerStatus settings(@PathVariable String uuid, @RequestBody SettingsRequest request) {
            if (request.allowPartyInvites() == null) throw ApiException.badRequest("缺少 allowPartyInvites");
            return players.setAllowPartyInvites(uuid, request.allowPartyInvites());
        }

        /** The player saved a weapon build in the 工匠 screen (already validated against the weapon by the game server). */
        @PostMapping("/{uuid}/builds")
        public PlayerService.PlayerStatus build(@PathVariable String uuid, @RequestBody BuildRequest request) {
            return players.saveBuild(uuid, request.weapon(), request.build());
        }
    }
}
