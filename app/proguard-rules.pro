# Defaults are enough: Compose, AndroidX and Play services ship consumer rules.
-dontwarn org.json.**

# JavaMail (IMAP/POP3/SMTP) loads providers and handlers by name
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn org.apache.harmony.awt.**
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
