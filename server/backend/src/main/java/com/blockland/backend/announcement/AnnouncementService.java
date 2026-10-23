package com.blockland.backend.announcement;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Announcements (公告): admins publish, edit and delete them; game servers get them with the bootstrap and report
 * which ones a player read. The read state itself is stored by the player module, this module only decides which
 * ids are valid (and clears them from every player when an announcement is deleted).
 */
@Service
public class AnnouncementService {
    static final int MAX_TITLE = 60;
    static final int MAX_CONTENT = 2000;
    private static final Comparator<Announcement> OLDEST_FIRST =
            Comparator.comparing(Announcement::publishedAt).thenComparing(Announcement::id);

    private final Repository<Announcement> announcements;
    private final PlayerService players;
    private final Clock clock;

    @Autowired
    public AnnouncementService(@Qualifier("announcements") Repository<Announcement> announcements, PlayerService players) {
        this(announcements, players, Clock.systemUTC());
    }

    AnnouncementService(Repository<Announcement> announcements, PlayerService players, Clock clock) {
        this.announcements = announcements;
        this.players = players;
        this.clock = clock;
    }

    /** @param pinned null = not pinned */
    public record Request(String title, String content, Boolean pinned) {
    }

    /** Newest first (the admin list). */
    public List<Announcement> list() {
        return announcements.findAll().stream().sorted(OLDEST_FIRST.reversed()).toList();
    }

    /** Oldest first, as game servers receive them. */
    public List<Announcement> published() {
        return announcements.findAll().stream().sorted(OLDEST_FIRST).toList();
    }

    public Announcement get(String id) {
        return announcements.findById(id).orElseThrow(() -> ApiException.notFound("公告", id));
    }

    public synchronized Announcement create(Request request) {
        Text text = validate(request);
        Instant now = clock.instant();
        return announcements.save(new Announcement(newId(now), text.title, text.content, Boolean.TRUE.equals(request.pinned()), now, now));
    }

    /** Edits keep the publish time; {@code updatedAt} records the edit. */
    public synchronized Announcement update(String id, Request request) {
        Announcement current = get(id);
        Text text = validate(request);
        return announcements.save(new Announcement(id, text.title, text.content, Boolean.TRUE.equals(request.pinned()),
                current.publishedAt(), clock.instant()));
    }

    public synchronized void delete(String id) {
        get(id);
        announcements.deleteById(id);
        players.forgetAnnouncement(id);
    }

    /** A player read announcements (the lobby's 已阅): ids of announcements that do not exist (any more) are ignored. */
    public synchronized PlayerService.PlayerStatus markRead(String uuid, List<String> ids) {
        Set<String> existing = announcements.findAll().stream().map(Announcement::id).collect(Collectors.toSet());
        List<String> valid = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).filter(existing::contains).distinct().toList();
        return players.addReadAnnouncements(uuid, valid);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private record Text(String title, String content) {
    }

    private static Text validate(Request request) {
        List<String> errors = new ArrayList<>();
        // a title is one line; the content keeps its line breaks
        String title = request.title() == null ? "" : request.title().strip().replaceAll("\\s+", " ");
        String content = request.content() == null ? "" : request.content().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (title.isEmpty()) errors.add("公告标题不能为空");
        else if (length(title) > MAX_TITLE) errors.add("公告标题不能超过 " + MAX_TITLE + " 个字");
        if (content.isEmpty()) errors.add("公告内容不能为空");
        else if (length(content) > MAX_CONTENT) errors.add("公告内容不能超过 " + MAX_CONTENT + " 个字");
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        return new Text(title, content);
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Short, lowercase ({@code [a-z0-9_]}) and roughly in publishing order, e.g. {@code a_mg3k2x9f_4q7z}. */
    private String newId(Instant now) {
        String id;
        do {
            id = "a_" + Long.toString(now.toEpochMilli(), 36) + "_" + Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36), 36);
        } while (announcements.findById(id).isPresent());
        return id;
    }
}
