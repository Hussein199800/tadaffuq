# OkHttp/Okio تأتي بقواعدها. Room وHilt كذلك.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# JSch يحمّل خوارزمياته بالاسم عبر الانعكاس
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
# libtorrent4j: واجهات JNI تُستدعى من الشيفرة الأصلية بالاسم
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { *; }
