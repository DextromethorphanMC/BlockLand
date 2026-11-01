package com.blockland.backend.checkin;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.player.PlayerAccount;
import com.blockland.backend.player.PlayerService;
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

class CheckInServiceTest {
    private static final String PLAYER = "0a1b2c3d-0000-4000-8000-000000000001";
    /** 2026-11-01 10:00 in Shanghai. */
    private static final Instant MORNING = Instant.parse("2026-11-01T02:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** A clock the test moves on. */
    private static final class TestClock extends Clock {
        Instant now = MORNING;

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

        void days(long days) {
            now = now.plus(Duration.ofDays(days));
        }
    }

    private final TestClock clock = new TestClock();

    private record Setup(CheckInService checkIn, PlayerService players, CheckInConfigStore store) {
    }

    private Setup setup(Path dir) {
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        PlayerService players = new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class,
                PlayerAccount::uuid, null));
        players.login(PLAYER, "Steve");
        CheckInConfigStore store = new CheckInConfigStore(mapper, properties);
        CheckInService checkIn = new CheckInService(new JsonFileRepository<>(mapper, dir.resolve("checkin-records.json"), CheckInRecord.class,
                CheckInRecord::uuid, null), store, players, clock);
        return new Setup(checkIn, players, store);
    }

    private static boolean owns(PlayerService players, String key) {
        return players.status(PLAYER).orElseThrow().entitlements().stream().anyMatch(e -> e.key().equals(key));
    }

    @Test
    void aNewPlayerStartsOnTheFirstDay(@TempDir Path dir) {
        CheckInService.State state = setup(dir).checkIn().state(PLAYER);

        assertThat(state.enabled()).isTrue();
        assertThat(state.today()).isEqualTo("2026-11-01");
        assertThat(state.claimedToday()).isFalse();
        assertThat(state.streak()).isZero();
        assertThat(state.position()).isZero();
        assertThat(state.days()).hasSize(7);
        assertThat(state.days().get(0).today()).isTrue();
        assertThat(state.days()).noneMatch(CheckInService.Day::claimed);
        // 10:00 in Shanghai: 14 hours to midnight
        assertThat(state.secondsToNextDay()).isEqualTo(14 * 3600);
    }

    @Test
    void checkingInGrantsTheDaysRewardOncePerDay(@TempDir Path dir) {
        Setup s = setup(dir);
        CheckInService.ClaimResult first = s.checkIn().claim(PLAYER);

        assertThat(first.claimed()).isTrue();
        assertThat(first.reward().entitlement()).isEqualTo("graffiti:paw");
        assertThat(first.state().claimedToday()).isTrue();
        assertThat(first.state().streak()).isEqualTo(1);
        assertThat(first.state().total()).isEqualTo(1);
        assertThat(first.state().days().get(0).claimed()).isTrue();
        assertThat(owns(s.players(), "graffiti:paw")).isTrue();

        CheckInService.ClaimResult again = s.checkIn().claim(PLAYER);
        assertThat(again.claimed()).isFalse();
        assertThat(again.reason()).isEqualTo("already");
        assertThat(again.state().total()).isEqualTo(1);
        assertThat(again.reward().index()).isZero();
    }

    @Test
    void consecutiveDaysWalkTheCycleAndStartItAgain(@TempDir Path dir) {
        Setup s = setup(dir);
        for (int day = 0; day < 7; day++) {
            CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
            assertThat(r.reward().index()).isEqualTo(day);
            clock.days(1);
        }
        assertThat(owns(s.players(), "graffiti:neon_heart")).isTrue();
        // a timed card stays timed: granted for 7 days on day 2
        PlayerAccount.Entitlement card = s.players().status(PLAYER).orElseThrow().entitlements().stream()
                .filter(e -> e.key().equals("card:ocean")).findFirst().orElseThrow();
        assertThat(card.until()).isNotNull();

        CheckInService.State eighth = s.checkIn().state(PLAYER);
        assertThat(eighth.streak()).isEqualTo(7);
        assertThat(eighth.position()).isZero();
        assertThat(eighth.days()).noneMatch(CheckInService.Day::claimed);
        CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
        assertThat(r.reward().index()).isZero();
        assertThat(r.state().streak()).isEqualTo(8);
        assertThat(r.state().best()).isEqualTo(8);
    }

    @Test
    void aMissedDayBreaksTheStreakAndRestartsTheCycle(@TempDir Path dir) {
        Setup s = setup(dir);
        s.checkIn().claim(PLAYER);
        clock.days(1);
        s.checkIn().claim(PLAYER);
        clock.days(1);
        s.checkIn().claim(PLAYER);
        clock.days(2);

        CheckInService.State broken = s.checkIn().state(PLAYER);
        assertThat(broken.streak()).isZero();
        assertThat(broken.position()).isZero();
        assertThat(broken.best()).isEqualTo(3);

        CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
        assertThat(r.reward().index()).isZero();
        assertThat(r.state().streak()).isEqualTo(1);
        assertThat(r.state().best()).isEqualTo(3);
        assertThat(r.state().total()).isEqualTo(4);
    }

    @Test
    void theDayStartsAtMidnightInTheConfiguredZone(@TempDir Path dir) {
        Setup s = setup(dir);
        // 23:30 in Shanghai on 2026-11-01, then 00:30 on the 2nd
        clock.now = Instant.parse("2026-11-01T15:30:00Z");
        s.checkIn().claim(PLAYER);
        clock.now = Instant.parse("2026-11-01T16:30:00Z");
        CheckInService.State next = s.checkIn().state(PLAYER);
        assertThat(next.today()).isEqualTo("2026-11-02");
        assertThat(next.claimedToday()).isFalse();
        assertThat(next.streak()).isEqualTo(1);
        assertThat(next.position()).isEqualTo(1);
    }

    @Test
    void disabledCheckInsChangeNothing(@TempDir Path dir) {
        Setup s = setup(dir);
        s.checkIn().saveConfig(new CheckInConfig(false, "Asia/Shanghai", CheckInConfig.DEFAULT.days()));
        CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
        assertThat(r.claimed()).isFalse();
        assertThat(r.reason()).isEqualTo("disabled");
        assertThat(r.state().enabled()).isFalse();
        assertThat(owns(s.players(), "graffiti:paw")).isFalse();
    }

    @Test
    void theConfigIsValidatedAndKept(@TempDir Path dir) {
        Setup s = setup(dir);
        assertThatThrownBy(() -> s.checkIn().saveConfig(new CheckInConfig(true, "Mars/Olympus", List.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> s.checkIn().saveConfig(new CheckInConfig(true, "UTC",
                List.of(new CheckInConfig.Reward("x", "not a key", 0)))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> s.checkIn().saveConfig(new CheckInConfig(true, "UTC",
                List.of(new CheckInConfig.Reward("x", "graffiti:skull", -1)))))
                .isInstanceOf(ApiException.class);

        CheckInConfig three = new CheckInConfig(true, "UTC", List.of(new CheckInConfig.Reward("Hello", "", 0),
                new CheckInConfig.Reward("", "PRIORITY_QUEUE", 3), new CheckInConfig.Reward("", "card:aurora", 0)));
        s.checkIn().saveConfig(three);
        // a fresh store reads the file again
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        assertThat(new CheckInConfigStore(mapper, properties).get()).isEqualTo(three);

        CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
        assertThat(r.reward().label()).isEqualTo("Hello");
        assertThat(r.state().days()).hasSize(3);
        clock.days(1);
        s.checkIn().claim(PLAYER);
        assertThat(owns(s.players(), "PRIORITY_QUEUE")).isTrue();
    }

    @Test
    void unknownPlayersCannotCheckIn(@TempDir Path dir) {
        Setup s = setup(dir);
        assertThatThrownBy(() -> s.checkIn().claim("0a1b2c3d-0000-4000-8000-00000000ffff"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void statsCountTodaysCheckIns(@TempDir Path dir) {
        Setup s = setup(dir);
        s.checkIn().claim(PLAYER);
        CheckInService.Stats stats = s.checkIn().stats();
        assertThat(stats.players()).isEqualTo(1);
        assertThat(stats.claimedToday()).isEqualTo(1);
        assertThat(stats.recent().get(0).name()).isEqualTo("Steve");
        assertThat(stats.recent().get(0).streak()).isEqualTo(1);

        s.checkIn().reset(PLAYER);
        assertThat(s.checkIn().state(PLAYER).total()).isZero();
        assertThatThrownBy(() -> s.checkIn().reset(PLAYER)).isInstanceOf(ApiException.class);
    }
}
