# Add project specific ProGuard rules here.
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.flue.launcher.data.model.** { *; }

# LuaJ's JSE artifact bundles a desktop javax.script service provider. Flue uses
# only the VM/compiler directly; Android has no javax.script API and this
# provider is unreachable from the watch-face sandbox.
-dontwarn javax.script.ScriptEngineFactory
