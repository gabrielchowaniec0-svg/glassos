# GlassOS launcher ProGuard rules.
# Keep the AGSL shader holder so reflection-free runtime shaders survive shrinking.
-keep class com.glassos.launcher.** { *; }
