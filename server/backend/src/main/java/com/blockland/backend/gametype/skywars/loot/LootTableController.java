package com.blockland.backend.gametype.skywars.loot;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 空岛战争 · 物资表. */
@RestController
@RequestMapping("/api/admin/skywars/loot-tables")
public class LootTableController {
    private final LootTableService service;

    public LootTableController(LootTableService service) {
        this.service = service;
    }

    @GetMapping
    public List<LootTable> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public LootTable get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LootTable create(@RequestBody LootTable request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public LootTable update(@PathVariable String id, @RequestBody LootTable request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
