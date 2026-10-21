package com.blockland.backend.media;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves stored images. The public path has no authentication so {@code <img>} tags can load it;
 * Minecraft servers use the server path and relay the bytes to game clients.
 */
@RestController
public class MediaController {
    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @GetMapping({"/api/public/media/{namespace}/{id}", "/api/server/media/{namespace}/{id}"})
    public ResponseEntity<Resource> image(@PathVariable String namespace, @PathVariable String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .body(new FileSystemResource(media.file(namespace, id)));
    }
}
