# Anipull 1.9 stable — R8 / ProGuard rules (conservative).
#
# The app has NO reflection-heavy libraries: no Gson/Moshi/Coil, manual
# org.json parsing, SharedPreferences, Jetpack Compose, Haze 1.3.1,
# kotlinx-coroutines. Compose, Haze and the AndroidX libraries ship their
# own consumer ProGuard rules, and AGP's default rules keep everything the
# manifest reaches (activities, receivers, providers). The keeps below are
# only the app's own entry points, named explicitly so a future R8 change
# cannot silently strip them. No blanket -dontobfuscate, no whole-package
# -keep of com.js.anipull.**

# App entry point, reached from AndroidManifest (.MainActivity).
-keep class com.js.anipull.MainActivity { *; }

# BroadcastReceiver reached from notification actions via PendingIntent
# (Notifications.kt: DownloadActionReceiver, manifest .DownloadActionReceiver).
-keep class com.js.anipull.DownloadActionReceiver { *; }

# The update APK is served by androidx.core.content.FileProvider
# (manifest provider, library class — kept by the library's own rules).
# No app-side FileProvider subclass exists, so no keep is needed here.

# Keep the crash-log writer reachable: CrashLogs is installed from
# MainActivity.onCreate via a direct call, so no keep is needed either.
# (Listed here only so a future refactor that reaches it by name knows
# where the rule belongs.)
