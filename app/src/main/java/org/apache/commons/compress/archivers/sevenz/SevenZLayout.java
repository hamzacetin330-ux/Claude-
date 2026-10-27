package org.apache.commons.compress.archivers.sevenz;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Read-only peek at how a 7z archive is laid out, which commons-compress doesn't expose:
 * which compressed block ("folder") each entry lives in, and the largest LZMA dictionary.
 * Used to split extraction between several threads along block boundaries.
 * Lives in this package because the archive model is package-private.
 */
public final class SevenZLayout {

    private static final byte[] LZMA = {3, 1, 1};
    private static final byte[] LZMA2 = {0x21};

    /** Block index of every entry (-1 for directories / empty files), or null if unknown. */
    public final int[] entryBlock;
    /** Largest dictionary any block needs, in bytes (a guess if unknown). */
    public final long maxDictionary;

    private SevenZLayout(int[] entryBlock, long maxDictionary) {
        this.entryBlock = entryBlock;
        this.maxDictionary = maxDictionary;
    }

    public static SevenZLayout of(SevenZFile file) {
        try {
            Field f = SevenZFile.class.getDeclaredField("archive");
            f.setAccessible(true);
            Archive archive = (Archive) f.get(file);
            long dict = 0;
            for (Folder folder : archive.folders) {
                for (Coder coder : folder.coders) {
                    dict = Math.max(dict, dictionarySize(coder));
                }
            }
            int[] blocks = archive.streamMap == null ? null : archive.streamMap.fileFolderIndex.clone();
            return new SevenZLayout(blocks, dict > 0 ? dict : 64L << 20);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new SevenZLayout(null, 64L << 20);
        }
    }

    private static long dictionarySize(Coder coder) {
        byte[] id = coder.decompressionMethodId;
        byte[] p = coder.properties;
        if (p == null) return 0;
        if (Arrays.equals(id, LZMA2) && p.length >= 1) {
            int b = p[0] & 0x3f;
            if (b >= 40) return 0xFFFFFFFFL;
            return (2L | (b & 1)) << (b / 2 + 11);
        }
        if (Arrays.equals(id, LZMA) && p.length >= 5) {
            return (p[1] & 0xffL) | (p[2] & 0xffL) << 8 | (p[3] & 0xffL) << 16 | (p[4] & 0xffL) << 24;
        }
        return 0;
    }
}
