package com.blockland.backend.storage;

import java.util.List;
import java.util.Optional;

/** Minimal persistence port. Services only see this, so JSON files can later be swapped for a database. */
public interface Repository<T> {

    List<T> findAll();

    Optional<T> findById(String id);

    T save(T entity);

    /** Saves several entities in one write. */
    void saveAll(List<T> entities);

    boolean deleteById(String id);
}
