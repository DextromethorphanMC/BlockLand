package com.blockland.backend.map;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map generators ({@link GeneratedMaps}), tools the shipped maps came from: what they build holds up in the arena
 * (nothing falls into the void, no water or lava pours out into it, {@link StructureSupport}), which shipped maps no
 * longer match their generator, and — only when asked — exporting them into the shipped maps:
 * {@code mvn test -Dtest=GeneratedMapsTest -Dblockland.exportGenerated=dust_a,bomb_demo} (or {@code all}).
 */
class GeneratedMapsTest {
    static final String EXPORT = "blockland.exportGenerated";
    /** The shipped maps' sources, from the backend's Maven base directory (where Surefire runs). */
    static final Path SHIPPED_SOURCES = Path.of("src/main/resources" + ShippedMaps.ROOT);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nothingFallsOrPoursIntoTheVoid() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<GeneratedMaps.Generated>> recipe : GeneratedMaps.recipes().entrySet()) {
            List<String> problems = StructureSupport.problems(recipe.getValue().get().data());
            counts.put(recipe.getKey(), problems.size());
            if (!problems.isEmpty()) examples.put(recipe.getKey(), problems.subList(0, Math.min(5, problems.size())));
        }
        System.out.println("Generated maps, blocks that would fall or pour into the void: " + counts);
        assertThat(examples).as("maps with blocks over the void").isEmpty();
    }

    /** Not a failure: a shipped map may be edited by hand. Lists the ones an export would change. */
    @Test
    void reportsShippedMapsThatDifferFromTheirGenerator() throws IOException {
        List<String> differ = new ArrayList<>();
        for (Map.Entry<String, Supplier<GeneratedMaps.Generated>> recipe : GeneratedMaps.recipes().entrySet()) {
            Path file = SHIPPED_SOURCES.resolve(recipe.getKey() + ".nbt");
            Path meta = SHIPPED_SOURCES.resolve(recipe.getKey() + ".json");
            if (!Files.isRegularFile(file) || !Files.isRegularFile(meta)) {
                differ.add(recipe.getKey() + " (not shipped)");
                continue;
            }
            GeneratedMaps.Generated generated = recipe.getValue().get();
            ObjectNode shipped = (ObjectNode) MAPPER.readTree(meta.toFile());
            ObjectNode exported = merged(shipped.deepCopy(), recipe.getKey(), generated);
            if (!Arrays.equals(Files.readAllBytes(file), generated.data())) differ.add(recipe.getKey() + " (file)");
            else if (!normalized(shipped).equals(normalized(exported))) differ.add(recipe.getKey() + " (spawns or settings)");
        }
        System.out.println("Shipped maps that differ from their generator: " + (differ.isEmpty() ? "none" : differ));
    }

    @Test
    @EnabledIfSystemProperty(named = EXPORT, matches = ".+")
    void exportsIntoTheShippedMaps() throws IOException {
        Map<String, Supplier<GeneratedMaps.Generated>> recipes = GeneratedMaps.recipes();
        String asked = System.getProperty(EXPORT).trim();
        List<String> ids = asked.equals("all") ? List.copyOf(recipes.keySet())
                : Arrays.stream(asked.split(",")).map(String::trim).toList();
        assertThat(recipes.keySet()).as("generated map ids").containsAll(ids);
        Files.createDirectories(SHIPPED_SOURCES);
        for (String id : ids) {
            GeneratedMaps.Generated generated = recipes.get(id).get();
            Files.write(SHIPPED_SOURCES.resolve(id + ".nbt"), generated.data());
            Path meta = SHIPPED_SOURCES.resolve(id + ".json");
            ObjectNode json = Files.isRegularFile(meta) ? (ObjectNode) MAPPER.readTree(meta.toFile()) : MAPPER.createObjectNode();
            writeJson(meta, merged(json, id, generated));
            Path cover = SHIPPED_SOURCES.resolve(id + ".png");
            if (generated.cover() != null && !Files.isRegularFile(cover)) Files.write(cover, generated.cover());
            System.out.println("exported " + id + " (" + generated.data().length + " bytes) into " + SHIPPED_SOURCES);
        }
    }

    /**
     * A shipped map's metadata after an export: the generator's spawns and game type settings; the name, the game type,
     * the void height and the other settings (the ambience) stay as the shipped map has them (a new map takes them from
     * the generator).
     */
    static ObjectNode merged(ObjectNode json, String id, GeneratedMaps.Generated generated) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("id", id);
        out.set("name", json.hasNonNull("name") ? json.get("name") : MAPPER.getNodeFactory().textNode(generated.name()));
        out.set("gameType", json.hasNonNull("gameType") ? json.get("gameType") : MAPPER.getNodeFactory().textNode(generated.gameType()));
        if (json.hasNonNull("spawnMarkerBlock")) out.set("spawnMarkerBlock", json.get("spawnMarkerBlock"));
        out.set("voidY", json.hasNonNull("voidY") ? json.get("voidY") : MAPPER.getNodeFactory().numberNode(generated.voidY()));
        out.set("teamSpawns", MAPPER.valueToTree(generated.spawns()));
        JsonNode settings = json.get("settings");
        if (generated.settings() != null) {
            ObjectNode merged = settings instanceof ObjectNode existing ? existing.deepCopy() : MAPPER.createObjectNode();
            merged.setAll(generated.settings().deepCopy());
            settings = merged;
        }
        out.set("settings", settings == null ? MAPPER.createObjectNode() : settings);
        return out;
    }

    /** Through text and back, so numbers compare by value (a float yaw against the double the file reads back). */
    private static JsonNode normalized(JsonNode node) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsString(node));
    }

    /** Written the way {@code tools/maps/export_maps.py} writes it (two-space indent, {@code "key": value}). */
    static void writeJson(Path file, JsonNode json) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""));
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        printer.indentArraysWith(indent);
        printer.indentObjectsWith(indent);
        Files.writeString(file, MAPPER.writer(printer).writeValueAsString(json) + "\n");
    }
}
