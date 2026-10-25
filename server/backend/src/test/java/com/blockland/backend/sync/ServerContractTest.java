package com.blockland.backend.sync;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The HTTP contract the Forge mod is written against: announcements in the bootstrap, the player status fields
 * and the per-player endpoints, plus the admin announcement API and map covers. Runs the whole application on a
 * temporary data directory (no demo data).
 */
@SpringBootTest(properties = {"blockland.seed-demo-data=false", "blockland.admin-token=test-admin",
        "blockland.server-token=test-server", "blockland.web-dir="})
@AutoConfigureMockMvc
class ServerContractTest {
    private static final String PLAYER = "0a1b2c3d-0000-4000-8000-000000000001";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("blockland.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private Repository<GameMap> maps;

    @Test
    void announcementsReachServersAndPlayersKeepTheirReadStateSettingsAndBuilds() throws Exception {
        // ---- admin publishes ----------------------------------------------------------------------
        JsonNode first = json(call(admin(post("/api/admin/announcements")), "{\"title\":\"维护公告\",\"content\":\"今晚 22:00 停服\\n预计一小时\",\"pinned\":false}", 201));
        assertThat(fieldNames(first)).containsExactlyInAnyOrder("id", "title", "content", "pinned", "publishedAt", "updatedAt");
        assertThat(first.path("content").asText()).isEqualTo("今晚 22:00 停服\n预计一小时");
        assertThat(Instant.parse(first.path("publishedAt").asText())).isNotNull();
        String firstId = first.path("id").asText();
        String etagBefore = call(server(get("/api/server/bootstrap")), null, 200).getResponse().getHeader("ETag");

        Thread.sleep(5);
        JsonNode second = json(call(admin(post("/api/admin/announcements")), "{\"title\":\"新模式\",\"content\":\"枪战感染上线\",\"pinned\":true}", 201));
        String secondId = second.path("id").asText();
        JsonNode edited = json(call(admin(put("/api/admin/announcements/" + firstId)), "{\"title\":\"维护公告（改）\",\"content\":\"改期\",\"pinned\":false}", 200));
        assertThat(edited.path("publishedAt").asText()).isEqualTo(first.path("publishedAt").asText());
        call(admin(post("/api/admin/announcements")), "{\"title\":\"\",\"content\":\"x\"}", 400);

        JsonNode adminList = json(call(admin(get("/api/admin/announcements")), null, 200));
        assertThat(adminList.findValuesAsText("id")).containsExactly(secondId, firstId);

        // ---- servers get them with the bootstrap, oldest first; the ETag changed -------------------
        MvcResult boot = call(server(get("/api/server/bootstrap")), null, 200);
        assertThat(boot.getResponse().getHeader("ETag")).isNotEqualTo(etagBefore);
        JsonNode announcements = json(boot).path("announcements");
        assertThat(announcements.findValuesAsText("id")).containsExactly(firstId, secondId);
        assertThat(fieldNames(announcements.get(0))).containsExactlyInAnyOrder("id", "title", "content", "pinned", "publishedAt", "updatedAt");
        assertThat(announcements.get(0).path("title").asText()).isEqualTo("维护公告（改）");
        assertThat(announcements.get(1).path("pinned").asBoolean()).isTrue();

        // ---- player status ------------------------------------------------------------------------
        JsonNode status = json(call(server(post("/api/server/players/login")), "{\"uuid\":\"" + PLAYER + "\",\"name\":\"Steve\"}", 200));
        assertThat(status.path("allowPartyInvites").asBoolean()).isTrue();
        assertThat(status.path("readAnnouncements").isArray()).isTrue();
        assertThat(status.path("readAnnouncements")).isEmpty();
        assertThat(status.path("builds").isObject()).isTrue();

        status = json(call(server(post("/api/server/players/" + PLAYER + "/settings")), "{\"allowPartyInvites\":false}", 200));
        assertThat(status.path("allowPartyInvites").asBoolean()).isFalse();
        call(server(post("/api/server/players/" + PLAYER + "/settings")), "{}", 400);

        status = json(call(server(post("/api/server/players/" + PLAYER + "/announcements/read")),
                "{\"ids\":[\"" + firstId + "\",\"no_such_announcement\"]}", 200));
        assertThat(texts(status.path("readAnnouncements"))).containsExactly(firstId);

        status = json(call(server(post("/api/server/players/" + PLAYER + "/builds")),
                "{\"weapon\":\"p9\",\"build\":{\"skin\":\"gold\",\"slots\":{\"optic\":\"reddot\",\"barrel\":\"suppressor\"}}}", 200));
        JsonNode p9 = status.path("builds").path("p9");
        assertThat(p9.path("skin").asText()).isEqualTo("gold");
        assertThat(p9.path("slots").path("optic").asText()).isEqualTo("reddot");
        assertThat(p9.path("slots").path("barrel").asText()).isEqualTo("suppressor");
        call(server(post("/api/server/players/" + PLAYER + "/builds")), "{\"weapon\":\"p9\",\"build\":{\"skin\":\"Gold!\",\"slots\":{}}}", 400);
        // sprays: kept in order with their placement; the shape is checked
        status = json(call(server(post("/api/server/players/" + PLAYER + "/builds")),
                "{\"weapon\":\"ak47\",\"build\":{\"skin\":\"default\",\"slots\":{},\"sprays\":[{\"id\":\"skull\",\"side\":\"left\","
                        + "\"u\":0.25,\"v\":0.5,\"size\":0.03,\"rotation\":30},{\"id\":\"star\",\"side\":\"right\",\"u\":0.6,"
                        + "\"v\":0.4,\"size\":0.02,\"rotation\":0}]}}", 200));
        JsonNode ak = status.path("builds").path("ak47").path("sprays");
        assertThat(ak.size()).isEqualTo(2);
        assertThat(fieldNames(ak.get(0))).containsExactlyInAnyOrder("id", "side", "u", "v", "size", "rotation");
        assertThat(ak.get(0).path("id").asText()).isEqualTo("skull");
        assertThat(ak.get(0).path("u").asDouble()).isEqualTo(0.25);
        assertThat(ak.get(1).path("side").asText()).isEqualTo("right");
        call(server(post("/api/server/players/" + PLAYER + "/builds")),
                "{\"weapon\":\"ak47\",\"build\":{\"skin\":\"default\",\"slots\":{},\"sprays\":[{\"id\":\"skull\",\"side\":\"top\","
                        + "\"u\":2,\"v\":0.5,\"size\":0.03,\"rotation\":0}]}}", 400);
        status = json(call(server(post("/api/server/players/" + PLAYER + "/builds")), "{\"weapon\":\"p9\",\"build\":null}", 200));
        assertThat(status.path("builds").has("p9")).isFalse();

        // unknown players and missing tokens
        call(server(post("/api/server/players/0a1b2c3d-0000-4000-8000-00000000ffff/settings")), "{\"allowPartyInvites\":true}", 404);
        call(post("/api/server/players/" + PLAYER + "/settings").contentType(MediaType.APPLICATION_JSON), "{\"allowPartyInvites\":true}", 401);
        call(get("/api/admin/announcements"), null, 401);

        // ---- deleting an announcement removes it from the read state; settings survive heartbeats ---
        call(admin(delete("/api/admin/announcements/" + firstId)), null, 204);
        call(admin(delete("/api/admin/announcements/" + firstId)), null, 404);
        JsonNode beat = json(call(server(post("/api/server/players/heartbeat")),
                "{\"players\":[{\"uuid\":\"" + PLAYER + "\",\"name\":\"Steve\",\"seconds\":15}]}", 200));
        assertThat(texts(beat.get(0).path("readAnnouncements"))).isEmpty();
        assertThat(beat.get(0).path("allowPartyInvites").asBoolean()).isFalse();
        assertThat(json(call(server(get("/api/server/bootstrap")), null, 200)).path("announcements").findValuesAsText("id")).containsExactly(secondId);

        // the admin user list shows the player's preferences
        JsonNode users = json(call(admin(get("/api/admin/users")), null, 200));
        assertThat(users.get(0).path("preferences").path("allowPartyInvites").asBoolean()).isFalse();
    }

    @Test
    void mapsCanHaveACoverForTheLobbyMapPicker() throws Exception {
        maps.save(new GameMap("cover_test", "封面测试", "SKYWARS", "nbt", "cover_test.nbt", "abc", 1, new GameMap.Size(1, 1, 1),
                null, List.of(), -10, mapper.createObjectNode(), null, Instant.now()));

        BufferedImage image = new BufferedImage(1024, 576, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        MvcResult uploaded = mvc.perform(multipart("/api/admin/maps/cover_test/cover")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", png.toByteArray()))
                        .header("Authorization", "Bearer test-admin"))
                .andReturn();
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(200);
        String version = json(uploaded).path("coverVersion").asText();
        assertThat(version).isNotBlank();

        JsonNode bootMap = findMap(json(call(server(get("/api/server/bootstrap")), null, 200)), "cover_test");
        assertThat(bootMap.path("coverVersion").asText()).isEqualTo(version);
        MvcResult media = call(server(get("/api/server/media/map/cover_test")), null, 200);
        BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(media.getResponse().getContentAsByteArray()));
        assertThat(stored.getWidth()).isEqualTo(512);
        assertThat(stored.getHeight()).isEqualTo(288);

        JsonNode removed = json(call(admin(delete("/api/admin/maps/cover_test/cover")), null, 200));
        assertThat(removed.path("coverVersion").isNull()).isTrue();
        call(server(get("/api/server/media/map/cover_test")), null, 404);
    }

    @Test
    void antiCheatChecksReportsCasesAndConfig() throws Exception {
        // ---- the bootstrap carries the global config; saving it changes the ETag -------------------
        MvcResult boot = call(server(get("/api/server/bootstrap")), null, 200);
        JsonNode antiCheat = json(boot).path("antiCheat");
        assertThat(fieldNames(antiCheat)).containsExactlyInAnyOrder("enabled", "defaultPreset", "presets", "autoBan", "alerts",
                "evidence", "suspicion");
        assertThat(fieldNames(antiCheat.path("presets"))).contains("strict", "standard", "relaxed", "observe");
        assertThat(fieldNames(antiCheat.path("presets").path("standard"))).containsExactlyInAnyOrder("name", "description",
                "toleranceScale", "ping", "confidence", "actions", "checks");
        JsonNode saved = json(call(admin(put("/api/admin/anticheat/config")), "{\"alerts\":{\"staff\":false}}", 200));
        assertThat(saved.path("alerts").path("staff").asBoolean()).isFalse();
        assertThat(call(server(get("/api/server/bootstrap")), null, 200).getResponse().getHeader("ETag"))
                .isNotEqualTo(boot.getResponse().getHeader("ETag"));
        JsonNode invalid = json(call(admin(put("/api/admin/anticheat/config")), "{\"presets\":{\"strict\":{}}}", 400));
        assertThat(texts(invalid.path("details"))).anyMatch(d -> d.contains("standard"));

        // ---- the registry the mod publishes, as the admin reads it ---------------------------------
        JsonNode published = json(call(server(post("/api/server/registry/anticheat")), "{\"checks\":[{\"id\":\"movement.speed\","
                + "\"category\":\"movement\",\"name\":\"移动速度\",\"description\":\"…\",\"latencySensitive\":true,\"signal\":false,"
                + "\"defaults\":{\"enabled\":true,\"tolerance\":1.0,\"setbackVl\":3,\"kickVl\":60,\"decay\":0.4,\"weight\":0.7,"
                + "\"punish\":true}}]}", 200));
        assertThat(published.path("count").asInt()).isEqualTo(1);
        JsonNode registry = json(call(admin(get("/api/admin/registry/anticheat")), null, 200));
        assertThat(registry.path("checks").get(0).path("id").asText()).isEqualTo("movement.speed");
        assertThat(fieldNames(registry.path("checks").get(0).path("defaults"))).containsExactlyInAnyOrder("enabled", "tolerance",
                "setbackVl", "kickVl", "decay", "weight", "punish");

        // ---- reports open a case; the admin reviews it ---------------------------------------------
        String player = "0a1b2c3d-0000-4000-8000-0000000000ac";
        call(server(post("/api/server/players/login")), "{\"uuid\":\"" + player + "\",\"name\":\"Cheater\"}", 200);
        JsonNode accepted = json(call(server(post("/api/server/anticheat/reports")), "{\"reports\":[{\"uuid\":\"" + player
                + "\",\"name\":\"Cheater\",\"modeId\":\"bomb_5v5\",\"modeTitle\":\"枪战爆破 5v5\",\"mapId\":null,\"check\":"
                + "\"movement.speed\",\"category\":\"movement\",\"vl\":12.5,\"confidence\":0.82,\"review\":true,\"action\":\"setback\","
                + "\"detail\":\"水平 0.612 > 上限 0.338\",\"ping\":85,\"jitter\":12,\"world\":\"blockland:arena\",\"x\":12.5,\"y\":64.0,"
                + "\"z\":-3.2,\"at\":\"2026-10-25T08:00:00Z\"}]}", 200));
        assertThat(accepted.path("accepted").asInt()).isEqualTo(1);
        JsonNode list = json(call(admin(get("/api/admin/anticheat/cases?status=ACTIVE&q=cheat")), null, 200));
        assertThat(list).hasSize(1);
        JsonNode summary = list.get(0);
        assertThat(summary.path("status").asText()).isEqualTo("REVIEW");
        assertThat(summary.has("evidence")).isFalse();
        assertThat(summary.path("evidenceCount").asInt()).isEqualTo(1);
        String caseId = summary.path("id").asText();
        JsonNode detail = json(call(admin(get("/api/admin/anticheat/cases/" + caseId)), null, 200));
        assertThat(fieldNames(detail)).containsExactlyInAnyOrder("id", "playerUuid", "playerName", "status", "openedAt", "updatedAt",
                "closedAt", "suspicion", "maxConfidence", "watched", "checks", "modes", "evidence", "punishments", "notes", "decision",
                "reviewedAt");
        assertThat(detail.path("evidence").get(0).path("at").asText()).isEqualTo("2026-10-25T08:00:00Z");
        assertThat(detail.path("checks").path("movement.speed").path("count").asInt()).isEqualTo(1);
        assertThat(json(call(admin(get("/api/admin/anticheat/summary")), null, 200)).path("review").asInt()).isEqualTo(1);

        // auto-ban is off by default: refused, the case stays in review
        JsonNode punish = json(call(server(post("/api/server/anticheat/punish")), "{\"uuid\":\"" + player + "\",\"name\":\"Cheater\","
                + "\"modeId\":\"bomb_5v5\",\"checks\":[\"movement.speed\"],\"confidence\":0.99,\"detail\":\"…\"}", 200));
        assertThat(fieldNames(punish)).containsExactlyInAnyOrder("banned", "caseId", "message");
        assertThat(punish.path("banned").asBoolean()).isFalse();
        assertThat(punish.path("caseId").asText()).isEqualTo(caseId);

        JsonNode banned = json(call(admin(post("/api/admin/anticheat/cases/" + caseId + "/review")),
                "{\"decision\":\"BAN\",\"minutes\":0,\"reason\":\"加速\",\"note\":\"录像确认\"}", 200));
        assertThat(banned.path("status").asText()).isEqualTo("BANNED");
        JsonNode status = json(call(server(post("/api/server/players/heartbeat")),
                "{\"players\":[{\"uuid\":\"" + player + "\",\"name\":\"Cheater\",\"seconds\":1}]}", 200));
        assertThat(status.get(0).path("ban").path("reason").asText()).isEqualTo("加速 #" + caseId);
        assertThat(json(call(admin(get("/api/admin/anticheat/players/" + player + "/cases")), null, 200))).hasSize(1);
        call(admin(post("/api/admin/anticheat/cases/" + caseId + "/notes")), "{\"text\":\"\"}", 400);
        call(admin(delete("/api/admin/anticheat/cases/" + caseId)), null, 204);
        call(admin(get("/api/admin/anticheat/cases/" + caseId)), null, 404);
        call(post("/api/server/anticheat/reports").contentType(MediaType.APPLICATION_JSON), "{\"reports\":[]}", 401);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-admin");
    }

    private static MockHttpServletRequestBuilder server(MockHttpServletRequestBuilder request) {
        return request.header("X-Server-Token", "test-server");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, int expectedStatus) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return result;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static JsonNode findMap(JsonNode bootstrap, String id) {
        for (JsonNode map : bootstrap.path("maps")) if (map.path("id").asText().equals(id)) return map;
        throw new AssertionError("map " + id + " not in bootstrap");
    }
}
