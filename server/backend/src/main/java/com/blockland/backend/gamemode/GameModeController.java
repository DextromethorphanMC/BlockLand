package com.blockland.backend.gamemode;

import com.blockland.backend.gametype.GameTypeRegistry;
import com.blockland.backend.kit.KitItem;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@RestController
@RequestMapping("/api/admin")
public class GameModeController {
    private final GameModeService service;
    private final GameTypeRegistry types;

    public GameModeController(GameModeService service, GameTypeRegistry types) {
        this.service = service;
        this.types = types;
    }

    public record GameTypeView(String id, String displayName, String description, ObjectNode defaultSettings,
                               boolean usesKit, List<KitItem> defaultKit) {
    }

    public record GameModeView(@JsonUnwrapped GameMode mode, List<String> warnings) {
    }

    @GetMapping("/game-types")
    public List<GameTypeView> gameTypes() {
        return types.all().stream()
                .map(t -> new GameTypeView(t.id(), t.displayName(), t.description(), t.defaultSettings(), t.usesKit(), t.defaultKit()))
                .toList();
    }

    @GetMapping("/game-modes")
    public List<GameModeView> list() {
        return service.list().stream().map(this::view).toList();
    }

    @GetMapping("/game-modes/{id}")
    public GameModeView get(@PathVariable String id) {
        return view(service.get(id));
    }

    @PostMapping("/game-modes")
    @ResponseStatus(HttpStatus.CREATED)
    public GameModeView create(@RequestBody GameModeService.Request request) {
        return view(service.create(request));
    }

    @PutMapping("/game-modes/{id}")
    public GameModeView update(@PathVariable String id, @RequestBody GameModeService.Request request) {
        return view(service.update(id, request));
    }

    @PostMapping(value = "/game-modes/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GameModeView cover(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        return view(service.setCover(id, file.getBytes()));
    }

    @DeleteMapping("/game-modes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    private GameModeView view(GameMode mode) {
        return new GameModeView(mode, service.warnings(mode));
    }
}
