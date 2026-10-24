package com.blockland.backend.player;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.storage.JsonFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerPreferencesTest {
    private static final String PLAYER = "0a1b2c3d-0000-4000-8000-000000000001";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PlayerService service(Path dir) {
        return new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class, PlayerAccount::uuid, null));
    }

    @Test
    void accountsWrittenBeforePreferencesExistedGetTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("players.json"), """
                [ { "uuid": "%s", "name": "Steve", "firstSeen": "2026-01-01T00:00:00Z", "lastSeen": "2026-01-02T00:00:00Z",
                    "lastHeartbeat": null, "totalOnlineSeconds": 120, "ban": null, "mute": null, "entitlements": [] } ]
                """.formatted(PLAYER));
        PlayerService.PlayerStatus status = service(dir).status(PLAYER).orElseThrow();

        assertThat(status.totalOnlineSeconds()).isEqualTo(120);
        assertThat(status.allowPartyInvites()).isTrue();
        assertThat(status.readAnnouncements()).isEmpty();
        assertThat(status.builds()).isEmpty();
    }

    @Test
    void privacySettingIsStoredAndSurvivesHeartbeats(@TempDir Path dir) {
        PlayerService players = service(dir);
        assertThat(players.login(PLAYER, "Steve").allowPartyInvites()).isTrue();

        assertThat(players.setAllowPartyInvites(PLAYER, false).allowPartyInvites()).isFalse();
        List<PlayerService.PlayerStatus> beat = players.heartbeat(List.of(new PlayerService.SessionReport(PLAYER, "Steve", 15)));
        assertThat(beat.get(0).allowPartyInvites()).isFalse();
        assertThat(players.login(PLAYER, "Steve").allowPartyInvites()).isFalse();

        // a fresh service reads the file again
        assertThat(service(dir).status(PLAYER).orElseThrow().allowPartyInvites()).isFalse();
        assertThatThrownBy(() -> players.setAllowPartyInvites("0a1b2c3d-0000-4000-8000-00000000ffff", true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void buildsAreSavedReplacedAndRemovedPerWeapon(@TempDir Path dir) {
        PlayerService players = service(dir);
        players.login(PLAYER, "Steve");

        players.saveBuild(PLAYER, "p9", new PlayerAccount.WeaponBuild("gold", Map.of("optic", "reddot", "charm", "grass_charm")));
        players.saveBuild(PLAYER, "butterfly", new PlayerAccount.WeaponBuild("default", Map.of("charm", "grass_charm")));
        PlayerService.PlayerStatus status = players.saveBuild(PLAYER, "p9", new PlayerAccount.WeaponBuild("desert", Map.of("barrel", "suppressor")));
        assertThat(status.builds()).containsOnlyKeys("butterfly", "p9");
        assertThat(status.builds().get("p9").skin()).isEqualTo("desert");
        assertThat(status.builds().get("p9").slots()).containsExactlyEntriesOf(Map.of("barrel", "suppressor"));

        status = players.saveBuild(PLAYER, "butterfly", null);
        assertThat(status.builds()).containsOnlyKeys("p9");
        assertThat(service(dir).status(PLAYER).orElseThrow().builds().get("p9").slots()).containsEntry("barrel", "suppressor");
    }

    @Test
    void buildsAreValidated(@TempDir Path dir) {
        PlayerService players = service(dir);
        players.login(PLAYER, "Steve");

        assertThatThrownBy(() -> players.saveBuild(PLAYER, "P9!", new PlayerAccount.WeaponBuild("default", Map.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).hasSize(1).first().asString().startsWith("武器 ID"));
        assertThatThrownBy(() -> players.saveBuild(PLAYER, "p9", new PlayerAccount.WeaponBuild(null, Map.of("optic", "Red Dot"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).hasSize(2));

        Map<String, String> tooManySlots = new HashMap<>();
        for (int i = 0; i <= PlayerService.MAX_BUILD_SLOTS; i++) tooManySlots.put("slot_" + i, "none");
        assertThatThrownBy(() -> players.saveBuild(PLAYER, "p9", new PlayerAccount.WeaponBuild("default", tooManySlots)))
                .isInstanceOf(ApiException.class);

        for (int i = 0; i < PlayerService.MAX_BUILDS; i++) players.saveBuild(PLAYER, "gun_" + i, new PlayerAccount.WeaponBuild("default", Map.of()));
        assertThatThrownBy(() -> players.saveBuild(PLAYER, "one_more", new PlayerAccount.WeaponBuild("default", Map.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsExactly("最多保存 64 把武器的改装"));
        // replacing an existing weapon's build is still fine at the limit
        players.saveBuild(PLAYER, "gun_0", new PlayerAccount.WeaponBuild("gold", Map.of()));
        assertThat(players.status(PLAYER).orElseThrow().builds()).hasSize(PlayerService.MAX_BUILDS);
    }

    @Test
    void spraysAreKeptInOrderAndTheirShapeIsChecked(@TempDir Path dir) {
        PlayerService players = service(dir);
        players.login(PLAYER, "Steve");
        List<PlayerAccount.Spray> sprays = List.of(new PlayerAccount.Spray("skull", "left", 0.4f, 0.5f, 0.03f, 15f),
                new PlayerAccount.Spray("flame", "right", 0.7f, 0.2f, 0.05f, -90f));
        players.saveBuild(PLAYER, "ak47", new PlayerAccount.WeaponBuild("default", Map.of(), sprays));
        PlayerAccount.WeaponBuild saved = service(dir).status(PLAYER).orElseThrow().builds().get("ak47");
        assertThat(saved.sprays()).containsExactlyElementsOf(sprays);

        assertThatThrownBy(() -> players.saveBuild(PLAYER, "ak47", new PlayerAccount.WeaponBuild("default", Map.of(),
                List.of(new PlayerAccount.Spray("Skull!", "top", 1.5f, null, 2f, 720f)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).hasSize(5));
        List<PlayerAccount.Spray> tooMany = new ArrayList<>();
        for (int i = 0; i <= PlayerService.MAX_BUILD_SPRAYS; i++) tooMany.add(new PlayerAccount.Spray("star", "left", 0.5f, 0.5f, 0.03f, 0f));
        assertThatThrownBy(() -> players.saveBuild(PLAYER, "ak47", new PlayerAccount.WeaponBuild("default", Map.of(), tooMany)))
                .isInstanceOf(ApiException.class);
        // a build saved before sprays existed reads as none
        assertThat(new PlayerAccount.WeaponBuild("default", Map.of()).sprays()).isEmpty();
    }

    @Test
    void cosmeticsNeedTheItemOwnedOrFreeAndItemKeysStayLowerCase(@TempDir Path dir) {
        PlayerService players = service(dir);
        players.login(PLAYER, "Steve");

        assertThatThrownBy(() -> players.setCosmetic(PLAYER, "card", "card:aurora", false)).isInstanceOf(ApiException.class);
        assertThat(players.setCosmetic(PLAYER, "card", "card:default", true).slots()).containsEntry("card", "card:default");

        // an admin grant of an item key is stored the way the game names it
        players.grant(PLAYER, "Card:Aurora", 0, "admin");
        assertThat(players.status(PLAYER).orElseThrow().entitlements()).extracting(PlayerAccount.Entitlement::key).contains("card:aurora");
        players.grant(PLAYER, "priority_queue", 7, "admin");
        assertThat(players.status(PLAYER).orElseThrow().entitlements()).extracting(PlayerAccount.Entitlement::key).contains("PRIORITY_QUEUE");

        assertThat(players.setCosmetic(PLAYER, "card", "CARD:aurora", false).slots()).containsEntry("card", "card:aurora");
        assertThat(players.setCosmetic(PLAYER, "graffiti.1", "card:aurora", false).slots()).hasSize(2);
        assertThat(players.setCosmetic(PLAYER, "graffiti.1", null, false).slots()).containsOnlyKeys("card");
        assertThat(service(dir).cosmetics(PLAYER).slots()).containsEntry("card", "card:aurora");
        assertThat(players.status(PLAYER).orElseThrow().cosmetics()).containsEntry("card", "card:aurora");
        assertThatThrownBy(() -> players.setCosmetic(PLAYER, "Bad Slot!", "card:aurora", false)).isInstanceOf(ApiException.class);
    }
}
