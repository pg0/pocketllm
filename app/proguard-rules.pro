# The native layer resolves these by name at runtime, so R8 must not touch them.
-keep class com.redcoralstudios.pocketllm.llm.LlamaBridge { *; }
-keep interface com.redcoralstudios.pocketllm.llm.LlamaBridge$TokenCallback { *; }
-keep interface com.redcoralstudios.pocketllm.llm.LlamaBridge$ProgressCallback { *; }

# JNI looks up onToken/onProgress on whatever class implements the callbacks,
# including the synthetic lambda classes Kotlin generates for SAM conversion.
-keepclassmembers class * implements com.redcoralstudios.pocketllm.llm.LlamaBridge$TokenCallback {
    public void onToken(java.lang.String);
}
-keepclassmembers class * implements com.redcoralstudios.pocketllm.llm.LlamaBridge$ProgressCallback {
    public boolean onProgress(float);
}
