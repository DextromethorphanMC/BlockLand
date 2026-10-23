package com.blockland.backend.map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/admin/maps")
public class MapController {
    private final MapService service;

    public MapController(MapService service) {
        this.service = service;
    }

    @GetMapping
    public List<GameMap> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public GameMap get(@PathVariable String id) {
        return service.get(id);
    }

    /** 导入地图: multipart form with {@code file}, {@code id}, {@code name}, {@code gameType}, optional {@code spawnMarkerBlock}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public GameMap upload(@RequestParam("file") MultipartFile file,
                          @RequestParam("id") String id,
                          @RequestParam("name") String name,
                          @RequestParam(value = "gameType", defaultValue = "SKYWARS") String gameType,
                          @RequestParam(value = "spawnMarkerBlock", required = false) String spawnMarkerBlock) throws IOException {
        return service.upload(id, name, gameType, spawnMarkerBlock, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GameMap replaceFile(@PathVariable String id,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "spawnMarkerBlock", required = false) String spawnMarkerBlock) throws IOException {
        return service.replaceFile(id, spawnMarkerBlock, file.getOriginalFilename(), file.getBytes());
    }

    @PutMapping("/{id}")
    public GameMap update(@PathVariable String id, @RequestBody MapService.UpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** Cover for the lobby's map picker; scaled to 512×288 at most. */
    @PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GameMap cover(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        return service.setCover(id, file.getBytes());
    }

    @DeleteMapping("/{id}/cover")
    public GameMap removeCover(@PathVariable String id) {
        return service.removeCover(id);
    }

    @GetMapping("/{id}/preview")
    public MapFileAnalyzer.TopView preview(@PathVariable String id) {
        return service.preview(id);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        return fileResponse(service.file(id));
    }

    static ResponseEntity<Resource> fileResponse(Path file) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .body(new FileSystemResource(file));
    }
}
