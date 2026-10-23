package com.blockland.craft;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every universal attachment, read once from {@code /data/blockland/craft/attachments.json} on the classpath (the same
 * file on client and server, so both agree on what an attachment does). Weapons only say which slots they have; any
 * attachment of a slot's kind fits unless the weapon narrows the options.
 */
public final class AttachmentCatalog {
    private static final String RESOURCE = "/data/blockland/craft/attachments.json";
    private static AttachmentCatalog instance;

    private final Map<String, CraftData.Attachment> attachments;

    /** JSON layout of the file. */
    private static final class File {
        Map<String, CraftData.Attachment> attachments = new LinkedHashMap<>();
    }

    private AttachmentCatalog(File file) {
        file.attachments.remove(CraftData.NONE);
        file.attachments.forEach((id, attachment) -> attachment.id = id);
        this.attachments = Collections.unmodifiableMap(file.attachments);
        BlockLand.LOGGER.info("[BlockLand] Attachment catalog: {} attachments", attachments.size());
    }

    public static synchronized AttachmentCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static AttachmentCatalog load() {
        try (InputStream in = AttachmentCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new AttachmentCatalog(file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load attachment catalog", e);
            return new AttachmentCatalog(new File());
        }
    }

    public Collection<CraftData.Attachment> all() {
        return attachments.values();
    }

    /** The attachment with this id, null for {@link CraftData#NONE} and unknown ids. */
    @Nullable
    public CraftData.Attachment get(String id) {
        return attachments.get(id);
    }

    /** Attachments that go into slots of this kind, in file order. */
    public List<CraftData.Attachment> ofSlot(String slot) {
        return attachments.values().stream().filter(a -> slot.equals(a.slot)).toList();
    }
}
