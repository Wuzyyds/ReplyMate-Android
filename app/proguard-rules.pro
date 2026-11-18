# 无第三方 SDK，仅保留 OkHttp / kotlinx.serialization 基本规则
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }

# ---- R8 开启后必需的保留规则 ----

# kotlinx.serialization：反序列化依赖伴生对象与序列化器，混淆后会解析失败
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-keep,includedescriptorclasses class com.wzy.replymate.**$$serializer { *; }
-keepclassmembers class com.wzy.replymate.** { *** Companion; }
-keepclasseswithmembers class com.wzy.replymate.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room：实体字段名参与 SQL 映射，不能改
-keep class com.wzy.replymate.data.db.** { *; }

# 无障碍服务与悬浮服务由系统按类名反射实例化
-keep class com.wzy.replymate.service.ReplyAccessibilityService { *; }
-keep class com.wzy.replymate.service.OverlayService { *; }
-dontwarn com.google.mlkit.**

