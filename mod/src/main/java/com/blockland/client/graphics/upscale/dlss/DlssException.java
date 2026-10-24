package com.blockland.client.graphics.upscale.dlss;

/**
 * Why DLSS cannot run: a translation key for the 画质 tab ({@code blockland.graphics.status.dlss.*}) and a detail shown as
 * is (the failing call, a Vulkan or Streamline result).
 */
public final class DlssException extends RuntimeException {
    public final String reason;
    public final String detail;

    public DlssException(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
        this.detail = detail;
    }

    public DlssException(String reason, String detail, Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = reason;
        this.detail = detail;
    }
}
