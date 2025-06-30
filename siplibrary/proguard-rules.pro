# === Mantener la clase principal de tu API pública ===
-keep class com.eddyslarez.siplibrary.EddysSipLibrary { *; }

# === Mantener los interfaces públicos de eventos ===
-keep interface com.eddyslarez.siplibrary.EddysSipLibrary$SipEventListener { *; }
-keep interface com.eddyslarez.siplibrary.EddysSipLibrary$RegistrationListener { *; }
-keep interface com.eddyslarez.siplibrary.EddysSipLibrary$CallListener { *; }
-keep interface com.eddyslarez.siplibrary.EddysSipLibrary$IncomingCallListener { *; }

# === Mantener clases de datos que el consumidor necesita ===
-keep class com.eddyslarez.siplibrary.EddysSipLibrary$CallInfo { *; }
-keep class com.eddyslarez.siplibrary.EddysSipLibrary$IncomingCallInfo { *; }

# === Mantener enums usados externamente ===
-keep enum com.eddyslarez.siplibrary.EddysSipLibrary$CallDirection { *; }
-keep enum com.eddyslarez.siplibrary.EddysSipLibrary$CallEndReason { *; }

# === Mantener tu excepción personalizada si es parte de la API pública ===
-keep class com.eddyslarez.siplibrary.EddysSipLibrary$SipLibraryException { *; }

# === (Opcional) Mantener logs si los usas externamente ===
-keep class com.eddyslarez.siplibrary.utils.** { *; }
