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

    private static CheckInConfig.Milestone milestone(String id, CheckInConfig.Kind kind, int days, String date, boolean repeat, String reward) {
        return new CheckInConfig.Milestone(id, kind, "", days, date, repeat, true, List.of(new CheckInConfig.Reward("", reward, 0)));
    }

    private static void withMilestones(Setup s, CheckInConfig.Milestone... milestones) {
        s.checkIn().saveConfig(new CheckInConfig(true, "Asia/Shanghai", CheckInConfig.DEFAULT.days(), List.of(milestones)));
    }

    private static List<String> paid(CheckInService.ClaimResult r) {
        return r.bonuses().stream().map(CheckInService.Bonus::id).toList();
    }

    @Test
    void aStreakMilestonePaysOnceUnlessItRepeats(@TempDir Path dir) {
        Setup s = setup(dir);
        withMilestones(s, milestone("three", CheckInConfig.Kind.STREAK, 3, "", false, "card:aurora"),
                milestone("every2", CheckInConfig.Kind.STREAK, 2, "", true, "PRIORITY_QUEUE"));
        List<List<String>> got = new java.util.ArrayList<>();
        for (int day = 0; day < 4; day++) {
            got.add(paid(s.checkIn().claim(PLAYER)));
            clock.days(1);
        }
        assertThat(got).containsExactly(List.of(), List.of("every2"), List.of("three"), List.of("every2"));
        assertThat(owns(s.players(), "card:aurora")).isTrue();

        // a broken streak: the repeating one pays again at 2, the one-time one never again
        clock.days(1);
        got.clear();
        for (int day = 0; day < 3; day++) {
            got.add(paid(s.checkIn().claim(PLAYER)));
            clock.days(1);
        }
        assertThat(got).containsExactly(List.of(), List.of("every2"), List.of());
    }

    @Test
    void aTotalMilestoneCountsEveryDayAndCatchesUp(@TempDir Path dir) {
        Setup s = setup(dir);
        // three check-ins with gaps before the milestone exists
        for (int day = 0; day < 3; day++) {
            s.checkIn().claim(PLAYER);
            clock.days(2);
        }
        withMilestones(s, milestone("total2", CheckInConfig.Kind.TOTAL, 2, "", false, "card:aurora"),
                milestone("total5", CheckInConfig.Kind.TOTAL, 5, "", false, "graffiti:crown"));
        CheckInService.Milestone before = s.checkIn().state(PLAYER).milestones().get(0);
        assertThat(before.achieved()).isFalse();
        assertThat(before.checkInsLeft()).isEqualTo(1);

        CheckInService.ClaimResult fourth = s.checkIn().claim(PLAYER);
        assertThat(paid(fourth)).containsExactly("total2");
        assertThat(fourth.state().total()).isEqualTo(4);
        CheckInService.Milestone five = fourth.state().milestones().get(1);
        assertThat(five.progress()).isEqualTo(4);
        assertThat(five.target()).isEqualTo(5);
        assertThat(five.checkInsLeft()).isEqualTo(1);
        assertThat(fourth.state().milestones().get(0).achieved()).isTrue();

        clock.days(3);
        assertThat(paid(s.checkIn().claim(PLAYER))).containsExactly("total5");
        clock.days(1);
        assertThat(paid(s.checkIn().claim(PLAYER))).isEmpty();
    }

    @Test
    void aDateMilestonePaysOnItsDay(@TempDir Path dir) {
        Setup s = setup(dir);
        withMilestones(s, milestone("once", CheckInConfig.Kind.DATE, 0, "2026-11-02", false, "card:aurora"),
                milestone("yearly", CheckInConfig.Kind.DATE, 0, "11-01", false, "graffiti:crown"),
                milestone("gone", CheckInConfig.Kind.DATE, 0, "2026-10-01", false, "graffiti:skull"));
        CheckInService.State state = s.checkIn().state(PLAYER);
        assertThat(state.milestones()).extracting(CheckInService.Milestone::id).containsExactly("once", "yearly");
        assertThat(state.milestones().get(0).checkInsLeft()).isEqualTo(1);
        assertThat(state.milestones().get(1).nextDate()).isEqualTo("2026-11-01");

        CheckInService.ClaimResult first = s.checkIn().claim(PLAYER);
        assertThat(paid(first)).containsExactly("yearly");
        assertThat(first.state().milestones().get(1).achieved()).isTrue();
        clock.days(1);
        assertThat(paid(s.checkIn().claim(PLAYER))).containsExactly("once");
        assertThat(owns(s.players(), "card:aurora")).isTrue();
        // a year on: the yearly one is next on 2027-11-01, the one-off is over
        clock.days(1);
        CheckInService.State later = s.checkIn().state(PLAYER);
        assertThat(later.milestones()).extracting(CheckInService.Milestone::id).containsExactly("yearly");
        assertThat(later.milestones().get(0).nextDate()).isEqualTo("2027-11-01");
    }

    @Test
    void disabledMilestonesDoNotPayAndClearedRecordsRememberOneTimeOnes(@TempDir Path dir) {
        Setup s = setup(dir);
        CheckInConfig.Milestone off = new CheckInConfig.Milestone("off", CheckInConfig.Kind.TOTAL, "", 1, "", false, false,
                List.of(new CheckInConfig.Reward("", "graffiti:skull", 0)));
        withMilestones(s, off, milestone("first", CheckInConfig.Kind.TOTAL, 1, "", false, "card:aurora"));
        assertThat(paid(s.checkIn().claim(PLAYER))).containsExactly("first");
        assertThat(owns(s.players(), "graffiti:skull")).isFalse();

        s.checkIn().reset(PLAYER);
        assertThat(s.checkIn().stats().players()).isZero();
        assertThatThrownBy(() -> s.checkIn().reset(PLAYER)).isInstanceOf(ApiException.class);
        clock.days(1);
        CheckInService.ClaimResult again = s.checkIn().claim(PLAYER);
        assertThat(again.state().total()).isEqualTo(1);
        assertThat(paid(again)).isEmpty();
    }

    @Test
    void milestonesAreValidated(@TempDir Path dir) {
        Setup s = setup(dir);
        assertThatThrownBy(() -> withMilestones(s, milestone("Bad Id", CheckInConfig.Kind.STREAK, 3, "", false, "card:aurora")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> withMilestones(s, milestone("a", CheckInConfig.Kind.STREAK, 0, "", false, "card:aurora")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> withMilestones(s, milestone("a", CheckInConfig.Kind.DATE, 0, "13-40", false, "card:aurora")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> withMilestones(s, milestone("a", CheckInConfig.Kind.TOTAL, 3, "", false, ""))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> withMilestones(s, milestone("a", CheckInConfig.Kind.TOTAL, 3, "", false, "card:aurora"),
                milestone("a", CheckInConfig.Kind.STREAK, 3, "", false, "card:aurora"))).isInstanceOf(ApiException.class);

        withMilestones(s, milestone("leap", CheckInConfig.Kind.DATE, 0, "02-29", false, "card:aurora"));
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        assertThat(new CheckInConfigStore(mapper, properties).get().milestones()).hasSize(1);
        assertThat(s.checkIn().state(PLAYER).milestones().get(0).nextDate()).isEqualTo("2028-02-29");
    }

    @Test
    void anOldConfigFileWithoutMilestonesStillLoads(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("checkin-config.json"),
                "{\"enabled\":true,\"zone\":\"UTC\",\"days\":[{\"label\":\"\",\"entitlement\":\"card:aurora\",\"durationDays\":0}]}");
        java.nio.file.Files.writeString(dir.resolve("checkin-records.json"),
                "[{\"uuid\":\"" + PLAYER + "\",\"lastDay\":\"2026-10-31\",\"streak\":2,\"best\":2,\"total\":5}]");
        Setup s = setup(dir);
        assertThat(s.store().get().zone()).isEqualTo("UTC");
        assertThat(s.store().get().milestones()).isEmpty();
        CheckInService.ClaimResult r = s.checkIn().claim(PLAYER);
        assertThat(r.state().streak()).isEqualTo(3);
        assertThat(r.state().total()).isEqualTo(6);
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
