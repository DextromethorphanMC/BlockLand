package com.blockland.backend.ranked;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankedServiceTest {
    private static final String A = "0a1b2c3d-0000-4000-8000-00000000000a";
    private static final String B = "0a1b2c3d-0000-4000-8000-00000000000b";
    private static final String C = "0a1b2c3d-0000-4000-8000-00000000000c";
    private static final String D = "0a1b2c3d-0000-4000-8000-00000000000d";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private RankedService service(Path dir) {
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        PlayerService players = new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class,
                PlayerAccount::uuid, null));
        players.login(A, "Alice");
        players.login(B, "Bob");
        return new RankedService(
                new JsonFileRepository<>(mapper, dir.resolve("ranked-ratings.json"), Rating.class, Rating::uuid, null),
                new JsonFileRepository<>(mapper, dir.resolve("ranked-matches.json"), RankedMatch.class, RankedMatch::matchId, null),
                new RankedConfigStore(mapper, properties), players);
    }

    private static RankedService.ResultRequest duel(String id, String winner, String loser) {
        return new RankedService.ResultRequest(id, "bomb_1v1", List.of(
                new RankedService.ResultTeam(List.of(winner), "VICTORY", 1),
                new RankedService.ResultTeam(List.of(loser), "DEFEAT", 2)));
    }

    @Test
    void unplacedPlayersStartAtTheStartScore(@TempDir Path dir) {
        Map<String, RankedService.Standing> standings = service(dir).standings(List.of(A, "not-a-uuid"));
        assertThat(standings).containsOnlyKeys(A);
        assertThat(standings.get(A)).isEqualTo(new RankedService.Standing(1000, "coal", 0, 0, 0));
    }

    @Test
    void aWinMovesBothPlayersByThePlacementKAndIsCountedOnce(@TempDir Path dir) {
        RankedService ranked = service(dir);
        List<RankedService.Change> changes = ranked.report(duel("m1", A, B));

        // equal scores: expected 0.5, placement K 64 → ±32
        assertThat(changes).extracting(RankedService.Change::uuid, RankedService.Change::delta, RankedService.Change::position)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(A, 32, 1), org.assertj.core.groups.Tuple.tuple(B, -32, 2));
        // the same match again: the same answer, no second change
        assertThat(ranked.report(duel("m1", A, B))).isEqualTo(changes);
        assertThat(ranked.standings(List.of(A)).get(A).score()).isEqualTo(1032);
        assertThat(ranked.standings(List.of(A)).get(A).wins()).isEqualTo(1);
        assertThat(ranked.leaderboard(10)).extracting(RankedService.LeaderEntry::name).containsExactly("Alice", "Bob");
    }

    @Test
    void afterThePlacementMatchesTheNormalKApplies(@TempDir Path dir) {
        RankedService ranked = service(dir);
        for (int i = 0; i < 5; i++) ranked.report(duel("p" + i, A, B));
        int before = ranked.standings(List.of(A)).get(A).score();
        int delta = ranked.report(duel("after", A, B)).stream().filter(c -> c.uuid().equals(A)).findFirst().orElseThrow().delta();
        assertThat(delta).isBetween(1, 32);
        assertThat(ranked.standings(List.of(A)).get(A).score()).isEqualTo(before + delta);
    }

    @Test
    void teamsPlayAsTheirAverageAndDrawsMoveTheStrongerSideDown(@TempDir Path dir) {
        RankedService ranked = service(dir);
        ranked.report(duel("warm", A, B)); // A 1032, B 968
        List<RankedService.Change> draw = ranked.report(new RankedService.ResultRequest("d", "fastpvp", List.of(
                new RankedService.ResultTeam(List.of(A, C), "DRAW", 0),
                new RankedService.ResultTeam(List.of(B, D), "DRAW", 0))));
        assertThat(draw.stream().filter(c -> c.uuid().equals(A)).findFirst().orElseThrow().delta()).isLessThan(0);
        assertThat(draw.stream().filter(c -> c.uuid().equals(B)).findFirst().orElseThrow().delta()).isGreaterThan(0);
    }

    @Test
    void oneSidedReportsChangeNobody(@TempDir Path dir) {
        RankedService ranked = service(dir);
        List<RankedService.Change> changes = ranked.report(new RankedService.ResultRequest("solo", "x",
                List.of(new RankedService.ResultTeam(List.of(A), "VICTORY", 1))));
        assertThat(changes).singleElement().extracting(RankedService.Change::delta).isEqualTo(0);
        assertThat(ranked.leaderboard(10)).isEmpty();
    }

    @Test
    void headhunterNeedsTheScoreAndATopPlace(@TempDir Path dir) {
        RankedConfig c = RankedConfig.DEFAULT;
        assertThat(RankedService.tier(999, 1, c)).isEqualTo(RankedService.Tier.COAL);
        assertThat(RankedService.tier(1150, 1, c)).isEqualTo(RankedService.Tier.IRON);
        assertThat(RankedService.tier(1350, 1, c)).isEqualTo(RankedService.Tier.GOLD);
        assertThat(RankedService.tier(1600, 1, c)).isEqualTo(RankedService.Tier.DIAMOND);
        assertThat(RankedService.tier(1900, 50, c)).isEqualTo(RankedService.Tier.HEADHUNTER);
        assertThat(RankedService.tier(1900, 51, c)).isEqualTo(RankedService.Tier.DIAMOND);
        assertThat(RankedService.tier(5000, 0, c)).isEqualTo(RankedService.Tier.DIAMOND);
    }

    @Test
    void aSeasonResetKeepsHowTheLastOneEnded(@TempDir Path dir) {
        RankedService ranked = service(dir);
        ranked.report(duel("m", A, B));
        RankedConfig next = ranked.resetSeason();

        assertThat(next.season()).isEqualTo(2);
        assertThat(ranked.standings(List.of(A)).get(A)).isEqualTo(new RankedService.Standing(1000, "coal", 0, 0, 0));
        assertThat(ranked.player(A).seasons()).singleElement().satisfies(end -> {
            assertThat(end.season()).isEqualTo(1);
            assertThat(end.score()).isEqualTo(1032);
            assertThat(end.position()).isEqualTo(1);
        });
        // the season survives a config edit
        assertThat(ranked.saveConfig(RankedConfig.DEFAULT).season()).isEqualTo(2);
    }

    @Test
    void brokenReportsAndConfigsAreRejected(@TempDir Path dir) {
        RankedService ranked = service(dir);
        assertThatThrownBy(() -> ranked.report(new RankedService.ResultRequest("bad", "x", List.of(
                new RankedService.ResultTeam(List.of(A), "WON", 1), new RankedService.ResultTeam(List.of(A), "DEFEAT", 2)))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ranked.saveConfig(new RankedConfig(1000, 32, 5, 64, 1500, 1400, 1600, 1900, 50, 1)))
                .isInstanceOf(ApiException.class);
    }
}
