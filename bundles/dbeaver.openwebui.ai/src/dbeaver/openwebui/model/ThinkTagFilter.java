/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import org.jkiss.code.NotNull;

/**
 * Streaming filter which removes reasoning blocks ({@code <think>...</think>},
 * {@code <thinking>...</thinking>}) emitted by DeepSeek-R1, Qwen3, QwQ and similar models.
 * Works on arbitrary chunk boundaries: a tag split between two chunks is still recognized.
 * Not thread-safe; one instance per response.
 */
final class ThinkTagFilter {

    private static final String[] OPEN_TAGS = {"<think>", "<thinking>"};
    private static final String[] CLOSE_TAGS = {"</think>", "</thinking>"};

    private final StringBuilder buffer = new StringBuilder();
    private boolean inside;
    private String closeTag;
    private boolean trimLeading;

    @NotNull
    String accept(@NotNull String chunk) {
        buffer.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (!inside) {
                int bestPos = -1;
                int bestTag = -1;
                for (int i = 0; i < OPEN_TAGS.length; i++) {
                    int pos = buffer.indexOf(OPEN_TAGS[i]);
                    if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                        bestPos = pos;
                        bestTag = i;
                    }
                }
                if (bestPos >= 0) {
                    emit(out, buffer.substring(0, bestPos));
                    buffer.delete(0, bestPos + OPEN_TAGS[bestTag].length());
                    inside = true;
                    closeTag = CLOSE_TAGS[bestTag];
                    continue;
                }
                int keep = partialSuffix(buffer, OPEN_TAGS);
                emit(out, buffer.substring(0, buffer.length() - keep));
                buffer.delete(0, buffer.length() - keep);
                break;
            } else {
                int pos = buffer.indexOf(closeTag);
                if (pos >= 0) {
                    buffer.delete(0, pos + closeTag.length());
                    inside = false;
                    trimLeading = true;
                    continue;
                }
                int keep = partialSuffix(buffer, new String[]{closeTag});
                buffer.delete(0, buffer.length() - keep);
                break;
            }
        }
        return out.toString();
    }

    /**
     * Returns buffered text at the end of the stream. Unclosed reasoning is dropped.
     */
    @NotNull
    String flush() {
        String rest = inside ? "" : buffer.toString();
        buffer.setLength(0);
        inside = false;
        StringBuilder out = new StringBuilder();
        emit(out, rest);
        return out.toString();
    }

    private void emit(@NotNull StringBuilder out, @NotNull String text) {
        if (trimLeading) {
            int i = 0;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            text = text.substring(i);
            if (!text.isEmpty()) {
                trimLeading = false;
            }
        }
        out.append(text);
    }

    /** Length of the longest buffer suffix which is a proper prefix of one of the tags. */
    private static int partialSuffix(@NotNull StringBuilder buffer, @NotNull String[] tags) {
        int best = 0;
        String text = buffer.toString();
        for (String tag : tags) {
            int max = Math.min(tag.length() - 1, text.length());
            for (int len = max; len > best; len--) {
                if (text.regionMatches(text.length() - len, tag, 0, len)) {
                    best = len;
                    break;
                }
            }
        }
        return best;
    }

    /** Non-streaming helper. */
    @NotNull
    static String strip(@NotNull String text) {
        ThinkTagFilter f = new ThinkTagFilter();
        return f.accept(text) + f.flush();
    }
}
