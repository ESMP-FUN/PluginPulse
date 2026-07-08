package io.github.darkstarworks.pluginpulse.inject;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pluginpulse-inject} — wire PluginPulse into a compiled plugin jar.
 *
 * <pre>
 *   pluginpulse-inject inspect MyPlugin.jar
 *   pluginpulse-inject inject  MyPlugin.jar -o MyPlugin-pulse.jar \
 *       --modrinth my-slug --command-root /myplugin --permission myplugin.admin
 * </pre>
 */
@Command(name = "pluginpulse-inject", mixinStandardHelpOptions = true, version = "pluginpulse-inject",
        description = "Wire PluginPulse into a compiled plugin jar without its source.",
        subcommands = {InjectCli.Inspect.class, InjectCli.Inject.class})
public final class InjectCli implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new InjectCli()).execute(args));
    }

    @Command(name = "inspect", description = "Show detected name/main/version/strategy without modifying the jar.")
    static final class Inspect implements Callable<Integer> {
        @Parameters(index = "0", description = "The plugin jar to inspect.")
        Path jar;

        @Override
        public Integer call() throws Exception {
            if (!Files.isRegularFile(jar)) {
                System.err.println("Not a file: " + jar);
                return 2;
            }
            JarInspector.Inspection insp = JarInspector.inspect(jar);
            Descriptor d = insp.descriptor();
            System.out.println("descriptor : " + d.kind().entry);
            System.out.println("name       : " + d.name());
            System.out.println("version    : " + d.version());
            System.out.println("main       : " + d.main());
            System.out.println("final main : " + insp.finalMain());
            System.out.println("strategy   : " + insp.strategy());
            System.out.println("signed     : " + insp.signed());
            System.out.println("injected   : " + insp.alreadyInjected());
            if (insp.strategy() == Strategy.INSTRUMENT) {
                System.out.println("note       : final main - the in-browser tool refuses this; the CLI instruments it.");
            }
            return 0;
        }
    }

    @Command(name = "inject", description = "Produce a new jar with PluginPulse wired in.")
    static final class Inject implements Callable<Integer> {
        @Parameters(index = "0", description = "The plugin jar to inject.")
        Path input;

        @Option(names = {"-o", "--output"}, description = "Output jar (default: <input>-pulse.jar).")
        Path output;

        @Option(names = "--modrinth", description = "Modrinth project slug.")
        String modrinth;
        @Option(names = "--github", description = "GitHub owner/repo (uses Releases).")
        String github;
        @Option(names = "--github-token", description = "Token for a PRIVATE GitHub repo: a literal PAT "
                + "or a ${ENV_VAR} reference. Needs read-only Contents scope.")
        String githubToken;
        @Option(names = "--hangar", description = "Hangar project slug.")
        String hangar;
        @Option(names = "--permission", description = "Permission gating notices/commands.")
        String permission;
        @Option(names = "--command-root", description = "Root command (e.g. /myplugin); self-registered if free.")
        String commandRoot;
        @Option(names = "--mode", description = "off|check-only|notify|download|auto-stage (default notify).")
        String mode;
        @Option(names = "--contact", description = "User-Agent contact (required by Modrinth's API rules).")
        String contact;
        @Option(names = "--track", description = "Dual-track suffix (e.g. mc26).")
        String track;
        @Option(names = "--check-interval-hours", description = "Hours between checks (default library value).")
        Integer checkIntervalHours;
        @Option(names = "--upgrade", description = "Re-inject a jar that already contains PluginPulse.")
        boolean upgrade;
        @Option(names = "--hot-reload", description = "Enable no-restart installs (needs the hotreload module "
                + "bundled; refused at runtime when unsafe).")
        boolean hotReload;

        @Override
        public Integer call() throws Exception {
            if (!Files.isRegularFile(input)) {
                System.err.println("Not a file: " + input);
                return 2;
            }
            Path out = output != null ? output : defaultOutput(input);
            InjectOptions options = new InjectOptions(modrinth, github, hangar, permission, commandRoot,
                    mode, contact, track, checkIntervalHours, upgrade, githubToken, hotReload);
            try {
                Injector.Result result = new Injector().inject(input, out, options);
                System.out.println("Injected " + result.main() + " via " + result.strategy() + ".");
                System.out.println("  library relocated to : " + result.relocatedPackage());
                if (result.strategy() == Strategy.WRAPPER) {
                    System.out.println("  new main             : " + result.wrapperOrMain());
                }
                if (result.strippedSignature()) {
                    System.out.println("  WARNING: the jar was signed; the signature was stripped "
                            + "(adding classes invalidates it anyway).");
                }
                System.out.println("Wrote " + out);
                System.out.println("Test it on your own server before distributing.");
                return 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Refused: " + e.getMessage());
                return 2;
            }
        }

        private static Path defaultOutput(Path input) {
            String name = input.getFileName().toString();
            String base = name.toLowerCase().endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
            Path parent = input.toAbsolutePath().getParent();
            return parent.resolve(base + "-pulse.jar");
        }
    }
}
