# =============================================================================
# PluginPulse Companion ProGuard configuration
# Names stay as they are (error reports stay readable, no mapping file) and
# nothing is optimized: the bundled library finds its own classes, fields and
# methods by name, and reaches into server internals by reflection.
# =============================================================================
-dontobfuscate
-dontoptimize

# Plugin entry point, named in plugin.yml.
-keep class io.github.darkstarworks.ppcompanion.** { *; }

# The relocated library: the hot-reload engine is loaded by name, and the update
# state files are read and written by field name.
-keep class io.github.darkstarworks.ppcompanion.pulse.** { *; }

-keepattributes Exceptions, InnerClasses, Signature, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable, LocalVariableTable, LocalVariableTypeTable, Record

# The server provides these.
-dontwarn org.bukkit.**
-dontwarn io.papermc.**
-dontwarn com.destroystokyo.**
-dontwarn net.kyori.**
-dontwarn net.md_5.**
-dontwarn com.google.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jspecify.**
-dontnote org.bukkit.**
-dontnote io.papermc.**
-dontnote jdk.internal.**
