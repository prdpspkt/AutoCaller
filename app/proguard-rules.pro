# Keep OkHttp / Okio quiet under R8. Nothing app-specific needed for the debug build.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
