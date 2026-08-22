# Gson：保留 Event 模型字段名（JSON 反射序列化需要）
-keep class io.github.fgozxy.await.data.Event { *; }

# Gson：备份文件的外层结构（导出/导入、WebDAV 备份都用它）
-keep class io.github.fgozxy.await.data.BackupData$Payload { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Gson TypeToken 泛型子类
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 枚举：保留常量名与 values/valueOf（防止 R8 改名破坏序列化）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
