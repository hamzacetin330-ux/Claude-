package org.apache.commons.compress.archivers.sevenz;

import org.apache.commons.compress.PasswordRequiredException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Drop-in replacement for commons-compress' AES256SHA256Decoder that is fast on archives made of
 * many encrypted blocks (non-solid 7z).
 *
 * The stock decoder re-derives the key (2^19 SHA-256 rounds) for every block and decrypts through
 * CipherInputStream in 512-byte steps. On a phone that adds up to hours for an archive with
 * thousands of files. Here derived keys are cached (7-Zip does the same) and data is decrypted in
 * 64 KB chunks. Lives in this package because the coder API is package-private.
 */
public final class CachingAes256Decoder extends AbstractCoder {

    private static final int MAX_KEYS = 16;
    private static final Map<String, byte[]> KEYS = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_KEYS;
        }
    };

    private final AbstractCoder original;

    private CachingAes256Decoder(AbstractCoder original) {
        super(AES256Options.class);
        this.original = original;
    }

    /** Swaps this decoder in for the stock one. Safe to call more than once. */
    @SuppressWarnings("unchecked")
    public static synchronized void install() {
        try {
            Field f = Coders.class.getDeclaredField("CODER_MAP");
            f.setAccessible(true);
            Map<SevenZMethod, AbstractCoder> map = (Map<SevenZMethod, AbstractCoder>) f.get(null);
            AbstractCoder current = map.get(SevenZMethod.AES256SHA256);
            if (!(current instanceof CachingAes256Decoder)) {
                map.put(SevenZMethod.AES256SHA256, new CachingAes256Decoder(current));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Fall back to the stock (slow but correct) decoder.
        }
    }

    /** Puts the stock decoder back (used to retry when an archive fails to unpack). */
    @SuppressWarnings("unchecked")
    public static synchronized void uninstall() {
        try {
            Field f = Coders.class.getDeclaredField("CODER_MAP");
            f.setAccessible(true);
            Map<SevenZMethod, AbstractCoder> map = (Map<SevenZMethod, AbstractCoder>) f.get(null);
            AbstractCoder current = map.get(SevenZMethod.AES256SHA256);
            if (current instanceof CachingAes256Decoder) {
                map.put(SevenZMethod.AES256SHA256, ((CachingAes256Decoder) current).original);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // nothing to undo
        }
    }

    @Override
    InputStream decode(String archiveName, InputStream in, long uncompressedLength, Coder coder, byte[] password,
                       int maxMemoryLimitInKb) throws IOException {
        if (coder.properties == null || coder.properties.length < 2) {
            throw new IOException("Missing AES256 properties in " + archiveName);
        }
        int byte0 = 0xff & coder.properties[0];
        int numCyclesPower = byte0 & 0x3f;
        int byte1 = 0xff & coder.properties[1];
        int ivSize = (byte0 >> 6 & 1) + (byte1 & 0x0f);
        int saltSize = (byte0 >> 7 & 1) + (byte1 >> 4);
        if (2 + saltSize + ivSize > coder.properties.length) {
            throw new IOException("Salt size + IV size too long in " + archiveName);
        }
        byte[] salt = Arrays.copyOfRange(coder.properties, 2, 2 + saltSize);
        byte[] iv = new byte[16];
        System.arraycopy(coder.properties, 2 + saltSize, iv, 0, ivSize);
        if (password == null) throw new PasswordRequiredException(archiveName);

        byte[] key;
        if (numCyclesPower == 0x3f) {
            key = new byte[32];
            System.arraycopy(salt, 0, key, 0, saltSize);
            System.arraycopy(password, 0, key, saltSize, Math.min(password.length, key.length - saltSize));
        } else {
            String cacheKey = numCyclesPower + ":" + hex(salt) + ":" + hex(password);
            synchronized (KEYS) {
                key = KEYS.get(cacheKey);
            }
            if (key == null) {
                key = AES256SHA256Decoder.sha256Password(password, numCyclesPower, salt);
                synchronized (KEYS) {
                    KEYS.put(cacheKey, key);
                }
            }
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new DecryptingStream(in, cipher);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot set up AES decryption", e);
        }
    }

    @Override
    OutputStream encode(OutputStream out, Object options) throws IOException {
        return original.encode(out, options);
    }

    @Override
    byte[] getOptionsAsProperties(Object options) throws IOException {
        return original.getOptionsAsProperties(options);
    }

    @Override
    Object getOptionsFromCoder(Coder coder, InputStream in) throws IOException {
        return original.getOptionsFromCoder(coder, in);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    /** Decrypts AES-CBC in large chunks. */
    private static final class DecryptingStream extends InputStream {
        private final InputStream in;
        private final Cipher cipher;
        private final byte[] raw = new byte[64 * 1024];
        private byte[] plain = new byte[64 * 1024 + 32];
        private int pos;
        private int end;
        private boolean eof;

        DecryptingStream(InputStream in, Cipher cipher) {
            this.in = in;
            this.cipher = cipher;
        }

        private boolean fill() throws IOException {
            while (pos >= end) {
                if (eof) return false;
                int n = in.read(raw, 0, raw.length);
                try {
                    if (n < 0) {
                        eof = true;
                        byte[] last = cipher.doFinal();
                        plain = last.length > plain.length ? last : plain;
                        System.arraycopy(last, 0, plain, 0, last.length);
                        pos = 0;
                        end = last.length;
                    } else if (n > 0) {
                        int need = cipher.getOutputSize(n);
                        if (need > plain.length) plain = new byte[need];
                        end = cipher.update(raw, 0, n, plain, 0);
                        pos = 0;
                    }
                } catch (GeneralSecurityException e) {
                    throw new IOException("AES decryption failed", e);
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return plain[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, end - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
