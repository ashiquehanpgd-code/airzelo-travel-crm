# AirZelo: keep the app and the engine whole. The engine reads its schema with Class.getResourceAsStream("schema.json"),
# which resolves against the package name, and it is small; shrinking is for the extended icon set.
-keep class com.airzelo.** { *; }
-keepnames class com.airzelo.** { *; }
