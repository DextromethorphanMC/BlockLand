package com.blockland.backend.announcement;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.player.PlayerAccount;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.JsonFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnouncementServiceTest {
    private static final String PLAYER = "0a1b2c3d-0000-4000-8000-000000000001";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** Every call to the clock is one minute later, so publish order is unambiguous. */
    private final AtomicLong minutes = new AtomicLong();
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-10-01T08:00:00Z").plusSeconds(60 * minutes.getAndIncrement());
        }
    };
    private ContentVersion version;
    private PlayerService players;
    private AnnouncementService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        version = new ContentVersion(dir.resolve("version.txt"));
        players = new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class, PlayerAccount::uuid, null));
        service = new AnnouncementService(new JsonFileRepository<>(mapper, dir.resolve("announcements.json"), Announcement.class, Announcement::id, version),
                players, clock);
        players.login(PLAYER, "Steve");
    }

    @Test
    void adminListIsNewestFirstAndServersGetOldestFirst() {
        Announcement first = service.create(new AnnouncementService.Request("维护公告", "今晚停服维护", false));
        Announcement second = service.create(new AnnouncementService.Request("新模式", "枪战感染上线", true));
        Announcement third = service.create(new AnnouncementService.Request("活动", "周末双倍", null));

        assertThat(service.list()).extracting(Announcement::id).containsExactly(third.id(), second.id(), first.id());
        assertThat(service.published()).extracting(Announcement::id).containsExactly(first.id(), second.id(), third.id());
        assertThat(second.pinned()).isTrue();
        assertThat(third.pinned()).isFalse();
        assertThat(first.id()).matches("[a-z0-9_]+").isNotEqualTo(second.id());
        assertThat(first.publishedAt()).isEqualTo(first.updatedAt());
    }

    @Test
    void editingKeepsThePublishTime() {
        Announcement created = service.create(new AnnouncementService.Request("标题", "内容", false));
        Announcement edited = service.update(created.id(), new AnnouncementService.Request("新标题", "新内容", true));

        assertThat(edited.id()).isEqualTo(created.id());
        assertThat(edited.title()).isEqualTo("新标题");
        assertThat(edited.content()).isEqualTo("新内容");
        assertThat(edited.pinned()).isTrue();
        assertThat(edited.publishedAt()).isEqualTo(created.publishedAt());
        assertThat(edited.updatedAt()).isAfter(created.updatedAt());
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void validatesTitleAndContent() {
        assertThatThrownBy(() -> service.create(new AnnouncementService.Request("  ", "", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsExactly("公告标题不能为空", "公告内容不能为空"));
        assertThatThrownBy(() -> service.create(new AnnouncementService.Request("字".repeat(61), "内容", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsExactly("公告标题不能超过 60 个字"));
        assertThatThrownBy(() -> service.create(new AnnouncementService.Request("标题", "字".repeat(2001), false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsExactly("公告内容不能超过 2000 个字"));
        assertThatThrownBy(() -> service.update("missing", new AnnouncementService.Request("标题", "内容", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));

        // limits are inclusive; a title is one line, the content keeps its (normalised) line breaks
        Announcement ok = service.create(new AnnouncementService.Request("  第一行\n第二行  ", "\r\n第一段\r\n\r\n第二段\n", false));
        assertThat(ok.title()).isEqualTo("第一行 第二行");
        assertThat(ok.content()).isEqualTo("第一段\n\n第二段");
        assertThat(service.create(new AnnouncementService.Request("字".repeat(60), "字".repeat(2000), false)).title()).hasSize(60);
    }

    @Test
    void everyChangeBumpsTheContentVersion() {
        long start = version.current();
        Announcement created = service.create(new AnnouncementService.Request("标题", "内容", false));
        long afterCreate = version.current();
        service.update(created.id(), new AnnouncementService.Request("标题", "改", false));
        long afterUpdate = version.current();
        service.delete(created.id());

        assertThat(afterCreate).isGreaterThan(start);
        assertThat(afterUpdate).isGreaterThan(afterCreate);
        assertThat(version.current()).isGreaterThan(afterUpdate);
    }

    @Test
    void readStateKeepsOnlyExistingAnnouncementsAndForgetsDeletedOnes() {
        Announcement a = service.create(new AnnouncementService.Request("A", "a", false));
        Announcement b = service.create(new AnnouncementService.Request("B", "b", false));

        PlayerService.PlayerStatus status = service.markRead(PLAYER, List.of(a.id(), "unknown", a.id()));
        assertThat(status.readAnnouncements()).containsExactly(a.id());
        status = service.markRead(PLAYER.toUpperCase(), List.of(b.id(), a.id()));
        assertThat(status.readAnnouncements()).containsExactly(a.id(), b.id());

        service.delete(a.id());
        assertThat(players.status(PLAYER).orElseThrow().readAnnouncements()).containsExactly(b.id());
        assertThatThrownBy(() -> service.delete(a.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.markRead("0a1b2c3d-0000-4000-8000-00000000ffff", List.of(b.id())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }
}
