package io.github.darkstarworks.pluginpulse.inject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Emits the static assets the in-browser JS engine consumes:
 * <ul>
 *   <li>{@code wrapper-template.class} — a wrapper generated with placeholder
 *       names ({@code PP__MAIN__PLACEHOLDER}, {@code PP__PULSE__PLACEHOLDER})
 *       that the JS substitutes into via the same constant-pool edit it uses to
 *       relocate the core. Keeping template generation in the verified ASM path
 *       (rather than hand-writing bytes in JS) is what lets the two engines agree.</li>
 * </ul>
 *
 * <p>Run by the Gradle {@code emitWebAssets} task; not part of the CLI.</p>
 */
public final class TemplateEmitter {

    /** Placeholder tokens the JS engine replaces (see docs/engine/constant-pool.js). */
    public static final String MAIN_PLACEHOLDER = "PP__MAIN__PLACEHOLDER";
    public static final String PULSE_PLACEHOLDER = "PP__PULSE__PLACEHOLDER";

    private TemplateEmitter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: TemplateEmitter <output-dir>");
            System.exit(2);
            return;
        }
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        byte[] template = WrapperGenerator.generate(MAIN_PLACEHOLDER, PULSE_PLACEHOLDER);
        Path out = outDir.resolve("wrapper-template.class");
        Files.write(out, template);
        System.out.println("Wrote " + out + " (" + template.length + " bytes)");
    }
}
