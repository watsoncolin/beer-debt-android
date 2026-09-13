# kotlinx.serialization: keep serializers for the ledger models.
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class me.colinwatson.beerdebt.**$$serializer { *; }
-keepclassmembers class me.colinwatson.beerdebt.** { *** Companion; }
-keepclasseswithmembers class me.colinwatson.beerdebt.** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager's Room database is instantiated by name with its no-arg
# constructor. R8 full mode (the AGP default) dropped that constructor on the
# first release build and the app crashed at startup on every phone
# (NoSuchMethodException: WorkDatabase_Impl.<init>). Keep every Room database.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
