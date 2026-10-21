package com.blockland.backend.modegroup;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** 模式分组. */
@RestController
@RequestMapping("/api/admin/mode-groups")
public class ModeGroupController {
    private final ModeGroupService groups;

    public ModeGroupController(ModeGroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public List<ModeGroup> list() {
        return groups.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModeGroup create(@RequestBody ModeGroup group) {
        return groups.create(group);
    }

    @PutMapping("/{id}")
    public ModeGroup update(@PathVariable String id, @RequestBody ModeGroup group) {
        return groups.update(id, group);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        groups.delete(id);
    }

    @PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ModeGroup cover(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        return groups.setCover(id, file.getBytes());
    }
}
