package com.blockland.backend.announcement;

import java.time.Instant;

/**
 * An announcement (公告) shown in the game lobby. Game servers receive all of them with the bootstrap; which ones a
 * player has read is stored with the player ({@code PlayerAccount.Preferences}).
 *
 * @param content     plain text, line breaks kept
 * @param pinned      listed first (admin list and lobby)
 * @param publishedAt when it was published; editing keeps it
 * @param updatedAt   last edit (equals {@code publishedAt} until edited)
 */
public record Announcement(String id, String title, String content, boolean pinned, Instant publishedAt, Instant updatedAt) {
}
