package com.blockland.backend.storage;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Stores a list of entities in one pretty-printed JSON file; writes are atomic (temp file + move).
 * With a {@link ContentVersion}, every write marks the game configuration as changed (servers re-sync);
 * runtime data such as players or orders passes {@code null}.
 */
public class JsonFileRepository<T> implements Repository<T> {
    private final ObjectMapper mapper;
    private final Path file;
    private final JavaType listType;
    private final Function<T, String> idOf;
    private final ContentVersion version;
    private List<T> items;

    public JsonFileRepository(ObjectMapper mapper, Path file, Class<T> type, Function<T, String> idOf, ContentVersion version) {
        this.mapper = mapper;
        this.file = file;
        this.listType = mapper.getTypeFactory().constructCollectionType(List.class, type);
        this.idOf = idOf;
        this.version = version;
    }

    @Override
    public synchronized List<T> findAll() {
        return List.copyOf(load());
    }

    @Override
    public synchronized Optional<T> findById(String id) {
        return load().stream().filter(item -> Objects.equals(idOf.apply(item), id)).findFirst();
    }

    @Override
    public synchronized T save(T entity) {
        List<T> list = new ArrayList<>(load());
        String id = idOf.apply(entity);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (Objects.equals(idOf.apply(list.get(i)), id)) {
                list.set(i, entity);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(entity);
        write(list);
        return entity;
    }

    @Override
    public synchronized void saveAll(List<T> entities) {
        if (entities.isEmpty()) return;
        List<T> list = new ArrayList<>(load());
        for (T entity : entities) {
            String id = idOf.apply(entity);
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                if (Objects.equals(idOf.apply(list.get(i)), id)) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) list.set(index, entity);
            else list.add(entity);
        }
        write(list);
    }

    @Override
    public synchronized boolean deleteById(String id) {
        List<T> list = new ArrayList<>(load());
        boolean removed = list.removeIf(item -> Objects.equals(idOf.apply(item), id));
        if (removed) write(list);
        return removed;
    }

    private List<T> load() {
        if (items == null) {
            try {
                items = Files.isRegularFile(file) ? mapper.readValue(file.toFile(), listType) : List.of();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
        }
        return items;
    }

    private void write(List<T> list) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), list);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            items = List.copyOf(list);
            if (version != null) version.bump();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }
}
