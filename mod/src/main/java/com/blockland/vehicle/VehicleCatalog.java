package com.blockland.vehicle;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Every vehicle, read once from {@code /data/blockland/vehicles/vehicles.json} on the classpath (the same file on client
 * and server, so both run the same movement and agree on seats and weapons). Entity types and spawn items are
 * registered from it, so it is loaded during mod construction.
 */
public final class VehicleCatalog {
    private static final String RESOURCE = "/data/blockland/vehicles/vehicles.json";
    /** Seats and weapons the entity syncs (its data accessors). */
    public static final int MAX_SEATS = 6, MAX_WEAPONS = 2;
    private static VehicleCatalog instance;

    private final Map<String, VehicleData.Vehicle> vehicles;

    private VehicleCatalog(VehicleData.File file) {
        file.vehicles.forEach((id, v) -> {
            v.id = id;
            if (v.seats.size() > MAX_SEATS) {
                BlockLand.LOGGER.warn("[BlockLand] Vehicle {} has {} seats; only {} are used", id, v.seats.size(), MAX_SEATS);
                v.seats = new ArrayList<>(v.seats.subList(0, MAX_SEATS));
            }
            if (v.weapons.size() > MAX_WEAPONS) {
                BlockLand.LOGGER.warn("[BlockLand] Vehicle {} has {} weapons; only {} are used", id, v.weapons.size(), MAX_WEAPONS);
                v.weapons = new ArrayList<>(v.weapons.subList(0, MAX_WEAPONS));
            }
            if (v.seats.isEmpty() || !v.seats.get(0).driver()) BlockLand.LOGGER.warn("[BlockLand] Vehicle {}: seat 0 must be the driver", id);
            v.weapons.forEach(w -> {
                if (w.seat < 0 || w.seat >= v.seats.size()) BlockLand.LOGGER.warn("[BlockLand] Vehicle {}: weapon {} has no seat {}", id, w.id, w.seat);
            });
        });
        this.vehicles = Collections.unmodifiableMap(file.vehicles);
        BlockLand.LOGGER.info("[BlockLand] Vehicle catalog: {} vehicles", vehicles.size());
    }

    public static synchronized VehicleCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static VehicleCatalog load() {
        try (InputStream in = VehicleCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new VehicleCatalog(new Gson().fromJson(reader, VehicleData.File.class));
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load vehicle catalog", e);
            return new VehicleCatalog(new VehicleData.File());
        }
    }

    public Collection<VehicleData.Vehicle> vehicles() {
        return vehicles.values();
    }

    @Nullable
    public VehicleData.Vehicle vehicle(String id) {
        return vehicles.get(id);
    }
}
