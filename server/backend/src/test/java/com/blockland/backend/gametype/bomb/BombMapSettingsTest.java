package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gametype.bomb.BombMapSettings.Site;
import com.blockland.backend.map.GameMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BombMapSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesSitesAndSortsCorners() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = BombMapSettings.normalize(mapper, mapper.readTree("""
                {"sites": [{"name": " A ", "x1": 10, "y1": 3, "z1": 4, "x2": 6, "y2": 1, "z2": 8},
                           {"name": "B", "x1": 20, "y1": 1, "z1": 20, "x2": 24, "y2": 3, "z2": 24}],
                 "junk": true}"""), errors);
        assertThat(errors).isEmpty();
        assertThat(BombMapSettings.read(out)).containsExactly(new Site("A", 6, 1, 4, 10, 3, 8), new Site("B", 20, 1, 20, 24, 3, 24));
        assertThat(out.has("junk")).isFalse();
        assertThat(BombMapSettings.normalize(mapper, null, errors).get("sites")).isEmpty();
    }

    @Test
    void reportsBrokenSites() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = BombMapSettings.normalize(mapper, mapper.readTree("""
                {"sites": [{"name": "A", "x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1},
                           {"name": "a", "x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1},
                           {"name": "", "x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1},
                           {"name": "TOOLONGNAME", "x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1},
                           {"name": "C", "x1": "a", "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1},
                           {"name": "D", "x1": 1.5, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1}]}"""), errors);
        assertThat(errors).containsExactly(
                "包点 2: 名称「a」重复",
                "包点 3: 名称必须是 1 到 8 个字符",
                "包点 4: 名称必须是 1 到 8 个字符",
                "包点 5: 坐标必须是整数",
                "包点 6: 坐标必须是整数");
        assertThat(BombMapSettings.read(out)).hasSize(1);

        errors.clear();
        BombMapSettings.normalize(mapper, mapper.readTree("{\"sites\": {}}"), errors);
        assertThat(errors).containsExactly("包点列表必须是数组");
    }

    @Test
    void replacedFileKeepsSitesAndBoundsAreChecked() {
        ObjectNode previous = BombMapSettings.toJson(mapper, List.of(new Site("A", 1, 1, 1, 3, 2, 3)));
        assertThat(BombMapSettings.read(BombMapSettings.fromFile(mapper, previous))).containsExactly(new Site("A", 1, 1, 1, 3, 2, 3));
        assertThat(BombMapSettings.read(BombMapSettings.fromFile(mapper, null))).isEmpty();

        GameMap.Size size = new GameMap.Size(10, 5, 10);
        assertThat(new Site("A", 0, 0, 0, 9, 4, 9).leaves(size)).isFalse();
        assertThat(new Site("A", 0, 0, 0, 10, 4, 9).leaves(size)).isTrue();
        assertThat(new Site("A", -1, 0, 0, 3, 4, 3).leaves(size)).isTrue();
    }
}
