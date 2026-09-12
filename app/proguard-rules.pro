# kotlinx.serialization: keep serializers for the ledger models.
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class me.colinwatson.beerdebt.**$$serializer { *; }
-keepclassmembers class me.colinwatson.beerdebt.** { *** Companion; }
-keepclasseswithmembers class me.colinwatson.beerdebt.** { kotlinx.serialization.KSerializer serializer(...); }
