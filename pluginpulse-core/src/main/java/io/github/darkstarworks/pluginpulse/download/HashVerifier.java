package io.github.darkstarworks.pluginpulse.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Verifies a downloaded file against published hashes, preferring the
 * strongest algorithm available (sha512 &gt; sha256 &gt; sha1).
 */
public final class HashVerifier {

    public enum Result { VERIFIED, NO_HASH, MISMATCH }

    private static final String[] PREFERENCE = {"sha512", "sha256", "sha1"};

    private HashVerifier() {
    }

    /**
     * @param hashes algorithm (lowercase) to expected hex digest
     */
    public static Result verify(Path file, Map<String, String> hashes) throws IOException {
        for (String algo : PREFERENCE) {
            String expected = hashes.get(algo);
            if (expected == null || expected.isBlank()) continue;
            String actual = digest(file, algo);
            return actual.equalsIgnoreCase(expected.trim()) ? Result.VERIFIED : Result.MISMATCH;
        }
        return Result.NO_HASH;
    }

    public static String digest(Path file, String algo) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algo.toUpperCase().replace("SHA", "SHA-"));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unsupported hash algorithm: " + algo, e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
