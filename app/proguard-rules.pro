# BouncyCastle: we only use the lightweight X25519 / XSalsa20 / Poly1305 classes directly.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.engines.XSalsa20Engine { *; }
-keep class org.bouncycastle.crypto.macs.Poly1305 { *; }
-keep class org.bouncycastle.math.ec.rfc7748.X25519 { *; }
