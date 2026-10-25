package com.blockland.backend.anticheat;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.player.PlayerAccount;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.JsonFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AntiCheatCaseServiceTest {
    private static final String A = "0a1b2c3d-0000-4000-8000-00000000000a";
    private static final String B = "0a1b2c3d-0000-4000-8000-00000000000b";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-25T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private record Setup(AntiCheatCaseService cases, AntiCheatConfigStore config, PlayerService players, TestClock clock) {
    }

    private Setup setup(Path dir) {
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        PlayerService players = new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class,
                PlayerAccount::uuid, null));
        players.login(A, "Alice");
        players.login(B, "Bob");
        AntiCheatConfigStore config = new AntiCheatConfigStore(mapper, properties, new ContentVersion(dir.resolve("version.txt")));
        TestClock clock = new TestClock();
        AntiCheatCaseService cases = new AntiCheatCaseService(new JsonFileRepository<>(mapper, dir.resolve("anticheat-cases.json"),
                AntiCheatCase.class, AntiCheatCase::id, null), config, players, clock);
        return new Setup(cases, config, players, clock);
    }

    private static AntiCheatCaseService.ReportRequest report(String uuid, String check, double confidence, boolean review) {
        return new AntiCheatCaseService.ReportRequest(uuid, uuid.equals(B) ? "Bob" : "Alice", "bomb_5v5", "枪战爆破 5v5", "bomb_demo", check, "movement",
                12.5, confidence, review, "setback", "水平 0.612 > 上限 0.338", 85, 12, "blockland:arena", 12.5, 64.0, -3.2, null);
    }

    @Test
    void reportsGatherIntoOneActiveCasePerPlayer(@TempDir Path dir) {
        Setup s = setup(dir);
        assertThat(s.cases().report(List.of(report(A, "movement.speed", 0.5, false), report(A, "movement.speed", 0.4, false),
                report(A, "packet.timer", 0.3, false), report(B, "combat.reach", 0.2, false),
                report("not-a-uuid", "movement.speed", 0.9, false), report(A, "Bad Check", 0.9, false)))).isEqualTo(4);

        List<AntiCheatCase.Summary> active = s.cases().list(null, null);
        assertThat(active).hasSize(2);
        AntiCheatCase alice = s.cases().get(s.cases().ofPlayer(A).get(0).id());
        assertThat(alice.status()).isEqualTo(AntiCheatCase.Status.OPEN);
        assertThat(alice.suspicion()).isCloseTo(1.2, within(1e-9));
        assertThat(alice.maxConfidence()).isEqualTo(0.5);
        assertThat(alice.checks().get("movement.speed").count()).isEqualTo(2);
        assertThat(alice.checks().get("movement.speed").maxConfidence()).isEqualTo(0.5);
        assertThat(alice.modes()).containsEntry("bomb_5v5", 3);
        assertThat(alice.evidence()).hasSize(3);
        assertThat(alice.evidence().get(0).at()).isEqualTo(s.clock().now);
        assertThat(s.cases().list("ALL", "bob")).singleElement().extracting(AntiCheatCase.Summary::playerUuid).isEqualTo(B);
        assertThatThrownBy(() -> s.cases().list("WHATEVER", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void suspicionHalvesOverItsHalfLifeAndSendsTheCaseToReview(@TempDir Path dir) {
        Setup s = setup(dir);
        s.cases().report(List.of(report(A, "movement.speed", 1.0, false), report(A, "movement.speed", 1.0, false)));
        s.clock().advance(Duration.ofDays(7));
        s.cases().report(List.of(report(A, "movement.speed", 0.5, false)));
        AntiCheatCase c = s.cases().get(s.cases().ofPlayer(A).get(0).id());
        // 2.0 halved after one half-life, plus 0.5
        assertThat(c.suspicion()).isCloseTo(1.5, within(1e-9));
        assertThat(c.status()).isEqualTo(AntiCheatCase.Status.OPEN);

        s.cases().report(List.of(report(A, "movement.speed", 1.0, false), report(A, "movement.speed", 0.6, false)));
        c = s.cases().get(c.id());
        assertThat(c.suspicion()).isGreaterThanOrEqualTo(3.0);
        assertThat(c.status()).isEqualTo(AntiCheatCase.Status.REVIEW);
        // review cases come first in the list
        s.cases().report(List.of(report(B, "combat.reach", 0.1, false)));
        assertThat(s.cases().list("ACTIVE", null).get(0).status()).isEqualTo(AntiCheatCase.Status.REVIEW);
        assertThat(s.cases().counts()).isEqualTo(new AntiCheatCaseService.Counts(1, 1, 0, 4));
    }

    @Test
    void aServerCanAskForReviewAndEvidenceIsCapped(@TempDir Path dir) throws Exception {
        Setup s = setup(dir);
        s.config().save(mapper.readTree("{\"evidence\": {\"maxPerCase\": 3}}"));
        s.cases().report(List.of(report(A, "packet.invalid", 0.1, true)));
        String id = s.cases().ofPlayer(A).get(0).id();
        assertThat(s.cases().get(id).status()).isEqualTo(AntiCheatCase.Status.REVIEW);
        for (int i = 0; i < 5; i++) s.cases().report(List.of(report(A, "check.n" + i, 0.01, false)));
        AntiCheatCase c = s.cases().get(id);
        assertThat(c.evidence()).extracting(AntiCheatCase.Report::check).containsExactly("check.n2", "check.n3", "check.n4");
        assertThat(c.checks()).hasSize(6);
    }

    @Test
    void reviewsBanClearDismissAndWatch(@TempDir Path dir) {
        Setup s = setup(dir);
        s.cases().report(List.of(report(A, "movement.speed", 0.9, true)));
        String first = s.cases().ofPlayer(A).get(0).id();

        AntiCheatCase watched = s.cases().review(first, new AntiCheatCaseService.ReviewRequest("watch", null, null, "再看看"));
        assertThat(watched.status()).isEqualTo(AntiCheatCase.Status.OPEN);
        assertThat(watched.watched()).isTrue();
        assertThat(watched.notes()).singleElement().extracting(AntiCheatCase.Note::text).isEqualTo("再看看");
        // suspicion alone no longer sends a watched case back to review
        s.cases().report(List.of(report(A, "movement.speed", 1, false), report(A, "movement.speed", 1, false),
                report(A, "movement.speed", 1, false), report(A, "movement.speed", 1, false)));
        assertThat(s.cases().get(first).status()).isEqualTo(AntiCheatCase.Status.OPEN);

        AntiCheatCase cleared = s.cases().review(first, new AntiCheatCaseService.ReviewRequest("CLEAR", null, null, null));
        assertThat(cleared.status()).isEqualTo(AntiCheatCase.Status.CLEARED);
        assertThat(cleared.closedAt()).isNotNull();
        assertThat(cleared.decision()).isEqualTo("CLEAR");

        // the next report opens a new case
        s.clock().advance(Duration.ofMinutes(1));
        s.cases().report(List.of(report(A, "combat.reach", 0.7, false)));
        assertThat(s.cases().ofPlayer(A)).hasSize(2);
        String second = s.cases().ofPlayer(A).get(0).id();
        assertThat(second).isNotEqualTo(first);
        // a closed case cannot be reopened while another is active
        assertThatThrownBy(() -> s.cases().review(first, new AntiCheatCaseService.ReviewRequest("WATCH", null, null, null)))
                .isInstanceOf(ApiException.class);

        AntiCheatCase banned = s.cases().review(second, new AntiCheatCaseService.ReviewRequest("BAN", 60, "穿墙加速", null));
        assertThat(banned.status()).isEqualTo(AntiCheatCase.Status.BANNED);
        assertThat(banned.punishments()).singleElement().satisfies(p -> {
            assertThat(p.by()).isEqualTo("admin");
            assertThat(p.minutes()).isEqualTo(60);
            assertThat(p.reason()).isEqualTo("穿墙加速 #" + second);
        });
        PlayerService.AccountView account = s.players().get(A);
        assertThat(account.banned()).isTrue();
        assertThat(account.account().ban().reason()).isEqualTo("穿墙加速 #" + second);
        assertThat(account.account().ban().until()).isNotNull();

        s.cases().report(List.of(report(B, "combat.reach", 0.7, false)));
        String bob = s.cases().ofPlayer(B).get(0).id();
        assertThat(s.cases().review(bob, new AntiCheatCaseService.ReviewRequest("DISMISS", null, null, null)).status())
                .isEqualTo(AntiCheatCase.Status.DISMISSED);
        assertThatThrownBy(() -> s.cases().review(bob, new AntiCheatCaseService.ReviewRequest("NUKE", null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThat(s.cases().note(bob, "误报：网络波动").notes()).hasSize(1);
        s.cases().delete(bob);
        assertThatThrownBy(() -> s.cases().get(bob)).isInstanceOf(ApiException.class);
    }

    @Test
    void autoBansOnlyWhenTheGlobalSwitchIsOn(@TempDir Path dir) throws Exception {
        Setup s = setup(dir);
        AntiCheatCaseService.PunishRequest request = new AntiCheatCaseService.PunishRequest(A, "Alice", "bomb_5v5",
                List.of("movement.speed", "packet.timer"), 0.985, "连续加速");

        AntiCheatCaseService.PunishResult refused = s.cases().punish(request);
        assertThat(refused.banned()).isFalse();
        AntiCheatCase c = s.cases().get(refused.caseId());
        assertThat(c.status()).isEqualTo(AntiCheatCase.Status.REVIEW);
        assertThat(c.maxConfidence()).isEqualTo(0.985);
        assertThat(c.notes()).singleElement().satisfies(n -> assertThat(n.text()).contains("未开启自动封禁").contains("movement.speed"));
        assertThat(s.players().get(A).banned()).isFalse();

        s.config().save(mapper.readTree("{\"autoBan\": {\"enabled\": true, \"durationMinutes\": 0, \"reason\": \"外挂\"}}"));
        AntiCheatCaseService.PunishResult done = s.cases().punish(request);
        assertThat(done.banned()).isTrue();
        assertThat(done.caseId()).isEqualTo(refused.caseId());
        c = s.cases().get(done.caseId());
        assertThat(c.status()).isEqualTo(AntiCheatCase.Status.BANNED);
        assertThat(c.decision()).isEqualTo("AUTO_BAN");
        assertThat(c.punishments()).singleElement().satisfies(p -> {
            assertThat(p.by()).isEqualTo("auto");
            assertThat(p.reason()).isEqualTo("外挂 #" + done.caseId());
        });
        PlayerService.AccountView account = s.players().get(A);
        assertThat(account.banned()).isTrue();
        assertThat(account.account().ban().until()).isNull();
        assertThat(s.cases().counts().bannedLast7Days()).isEqualTo(1);

        assertThatThrownBy(() -> s.cases().punish(new AntiCheatCaseService.PunishRequest("x", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void closedCasesExpireAfterTheRetention(@TempDir Path dir) {
        Setup s = setup(dir);
        s.cases().report(List.of(report(A, "movement.speed", 0.5, false), report(B, "movement.speed", 0.5, false)));
        String alice = s.cases().ofPlayer(A).get(0).id();
        s.cases().review(alice, new AntiCheatCaseService.ReviewRequest("DISMISS", null, null, null));
        s.clock().advance(Duration.ofDays(61));
        assertThat(s.cases().cleanup()).isEqualTo(1);
        // the open case stays however old it is
        assertThat(s.cases().list("ALL", null)).singleElement().extracting(AntiCheatCase.Summary::playerUuid).isEqualTo(B);
    }
}
