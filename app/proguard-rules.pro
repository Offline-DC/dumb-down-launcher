# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── OkHttp ────────────────────────────────────────────────────────────────────
# OkHttp uses reflection and the platform's built-in TLS; keep its internals.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**

# ── WorkManager ───────────────────────────────────────────────────────────────
# Workers are instantiated by reflection — WorkManager calls
# `Class.forName(name).getDeclaredConstructor(Context.class, WorkerParameters.class)`
# from its WorkerFactory. Reified PeriodicWorkRequestBuilder<T> keeps the
# class itself, but R8 can still strip the (Context, WorkerParameters)
# constructor because there's no static reference to it. Keep the constructor
# on every ListenableWorker subclass — covers Worker, CoroutineWorker, and
# any future variant we add (UpdateCheckWorker, QuackLocationRefreshWorker,
# QuackFirstQuackWorker, CallLogCleanupWorker).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
# Belt-and-suspenders for the original worker so an aggressive R8 update
# can't strip its body either.
-keep class com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker { *; }

# ── Compose / Kotlin ──────────────────────────────────────────────────────────
# Kotlin coroutines (already handled by default R8 rules but belt-and-suspenders)
-dontwarn kotlinx.coroutines.**

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
