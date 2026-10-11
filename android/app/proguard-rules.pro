# Please add these rules to your existing keep rules in order to suppress warnings.
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager
-dontwarn android.content.IIntentReceiver$Stub
-dontwarn android.content.IIntentReceiver
-dontwarn android.content.IIntentSender
-dontwarn android.content.pm.IPackageManager
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn javax.annotation.Nullable

# JNI: compositor Wayland + ring input palsu (dari DroidDeck). Nama kelas/method dicari native
# lewat nama (Java_com_droiddeck_...) dan dipanggil balik dari C (onFirstFramePresented, dst).
-keep class com.droiddeck.launcher.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.example.tiny_computer.AudioStream { *; }

# Termux terminal-view/emulator (PTY JNI + refleksi)
-keep class com.termux.** { *; }
-dontwarn com.termux.**

# zstd-jni + commons-compress (ekstraksi driver Turnip .tzst)
-keep class com.github.luben.zstd.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**

# androidsvg: ikon .svg aplikasi di menu Shortcut
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
