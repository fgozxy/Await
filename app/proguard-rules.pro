# Gson：保留 Event 模型字段名（JSON 反射序列化需要）
-keep class io.github.fgozxy.await.data.Event { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Gson TypeToken 泛型子类
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
