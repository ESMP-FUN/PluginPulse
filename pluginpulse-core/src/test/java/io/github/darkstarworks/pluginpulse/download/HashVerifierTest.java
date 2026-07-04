package io.github.darkstarworks.pluginpulse.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashVerifierTest {

    @TempDir
    Path dir;

    // Well-known digests of the ASCII string "abc".
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String ABC_SHA512 =
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                    + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";

    private Path abcFile() throws IOException {
        return Files.write(dir.resolve("abc.txt"), "abc".getBytes());
    }

    @Test
    void verifiedWithCorrectHash() throws IOException {
        assertEquals(HashVerifier.Result.VERIFIED,
                HashVerifier.verify(abcFile(), Map.of("sha256", ABC_SHA256)));
    }

    @Test
    void prefersStrongestAlgorithm() throws IOException {
        // sha512 correct, sha256 wrong: sha512 wins → VERIFIED.
        assertEquals(HashVerifier.Result.VERIFIED,
                HashVerifier.verify(abcFile(), Map.of("sha512", ABC_SHA512, "sha256", "00")));
        // sha512 wrong, sha256 correct: sha512 wins → MISMATCH.
        assertEquals(HashVerifier.Result.MISMATCH,
                HashVerifier.verify(abcFile(), Map.of("sha512", "00", "sha256", ABC_SHA256)));
    }

    @Test
    void mismatchAndNoHash() throws IOException {
        assertEquals(HashVerifier.Result.MISMATCH,
                HashVerifier.verify(abcFile(), Map.of("sha256", "0".repeat(64))));
        assertEquals(HashVerifier.Result.NO_HASH, HashVerifier.verify(abcFile(), Map.of()));
    }

    @Test
    void caseInsensitiveComparison() throws IOException {
        assertEquals(HashVerifier.Result.VERIFIED,
                HashVerifier.verify(abcFile(), Map.of("sha256", ABC_SHA256.toUpperCase())));
    }
}
