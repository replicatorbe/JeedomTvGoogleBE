# Règles R8 du build release.
# kotlinx.serialization, OkHttp et Compose embarquent déjà leurs règles (consumer rules) ;
# celles-ci les complètent pour les classes @Serializable de l'application.

# --- kotlinx.serialization ------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**

# Sérialiseurs générés par le plugin pour les classes de l'application (corps JSON du contrat).
-keep,includedescriptorclasses class be.jeedomtv.**$$serializer { *; }
-keepclassmembers class be.jeedomtv.** {
    *** Companion;
}
-keepclasseswithmembers class be.jeedomtv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---------------------------------------------------------------------------------------
# Fournisseurs TLS optionnels, absents sur Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Compose --------------------------------------------------------------------------------------
# Rien à ajouter : les bibliothèques Compose et tv-material fournissent leurs règles.
