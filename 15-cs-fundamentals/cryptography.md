# Cryptography Fundamentals

[← Back to master index](../README.md)

Cryptography is the engineering discipline of protecting data confidentiality, integrity, and authenticity using mathematical primitives. This guide covers the building blocks every backend engineer should understand — hashing, password storage, symmetric and asymmetric encryption, key exchange, signatures, MACs, certificates, TLS, and randomness — with a practical Java lens. All content is current to 2026, reflecting modern recommendations (Argon2id, AES-GCM, TLS 1.3, ECC curves, post-quantum awareness).

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between encoding, encryption, and hashing?

These three are constantly confused in interviews, and conflating them is a red flag. They solve different problems:

```
ENCODING   — reversible, NO key. Goal: data representation/transport.
             Base64, URL-encoding, hex. Anyone can decode. NOT security.

ENCRYPTION — reversible, WITH a key. Goal: confidentiality.
             AES, RSA. Only key-holders can recover plaintext.

HASHING    — one-way, NO key (or a salt). Goal: integrity / fingerprinting.
             SHA-256, Argon2. Cannot be reversed to the original.
```

- **Encoding** transforms data into another format for compatibility (e.g., Base64 to put binary in JSON or a URL). It provides *zero* security — Base64 is trivially decoded.
- **Encryption** is reversible *only with the correct key*. It protects confidentiality.
- **Hashing** is a deterministic one-way function. You cannot get the input back from the output. Used for integrity checks, deduplication, and password storage (with salting).

A common interview trap: "We Base64-encode the password before storing it." That is not security — it is obfuscation at best.

### Q2. [Theory] What properties make a cryptographic hash function "good"?

A cryptographic hash function `H(m)` maps an arbitrary-length input to a fixed-length output (the digest). The properties that matter:

1. **Deterministic** — same input always yields the same output.
2. **Fast to compute** — for general-purpose hashing (NOT for passwords, where you want it slow).
3. **Pre-image resistance** — given a digest `h`, it is infeasible to find any `m` such that `H(m) = h`. (One-wayness.)
4. **Second pre-image resistance** — given `m1`, it is infeasible to find a different `m2` with `H(m1) = H(m2)`.
5. **Collision resistance** — it is infeasible to find *any* two distinct inputs that hash to the same value.
6. **Avalanche effect** — flipping a single input bit flips ~50% of output bits, so outputs look random and unrelated.

MD5 and SHA-1 are broken on collision resistance and must not be used for security. SHA-256/SHA-3 currently hold all properties.

### Q3. [Theory] What is the avalanche effect and why does it matter?

The avalanche effect means a tiny change in the input (even one bit) produces a drastically different output — statistically, about half the output bits change. This is what makes a hash "look random."

```
SHA-256("hello")  = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
SHA-256("hellp")  = c8b5083d... (completely different — single char changed)
```

Why it matters: without avalanche, an attacker could observe correlations between similar inputs and outputs and work backward or build shortcuts. It also ensures that hash-based partitioning and deduplication distribute uniformly. A good cipher exhibits the same property for the same reason.

### Q4. [Theory] Name the SHA-2 and SHA-3 families and explain how they differ.

- **SHA-2** (2001) is a family: SHA-224, SHA-256, SHA-384, SHA-512 (the number is the digest length in bits). It uses a **Merkle–Damgård** construction with the Davies–Meyer compression function. SHA-256 is the workhorse — used in TLS, Bitcoin, certificates, JWT signatures.
- **SHA-3** (2015, Keccak) is built on a completely different design called a **sponge construction**. It is *not* a patch for SHA-2 — SHA-2 is still secure. SHA-3 exists as a structurally independent backup so that a hypothetical break of Merkle–Damgård would not break everything.

A practical difference: SHA-2 (specifically SHA-256/512) is vulnerable to **length-extension attacks**, while SHA-3 and SHA-512/256 are not. This is why HMAC exists (see later) and why you should not naively use `H(secret || message)` as a MAC.

### Q5. [Practical] How do you compute a SHA-256 hash in Java?

Use `java.security.MessageDigest`. Always specify the charset explicitly.

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public static String sha256(String input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash); // Java 17+ hex encoding
}

// sha256("hello") -> "2cf24dba5fb0a30e..."
```

Notes:
- `MessageDigest` is **not thread-safe** — create a new instance per use or per thread.
- Use `HexFormat` (Java 17+) or Base64 to render the raw bytes; don't `new String(hash)`, which mangles non-printable bytes.
- For comparing digests against a secret value, use `MessageDigest.isEqual(a, b)` which is constant-time.

### Q6. [Theory] What is a salt and why must passwords be salted?

A **salt** is a unique, random value generated per password and stored alongside the hash. You hash `salt || password` instead of just `password`.

Without salt, identical passwords produce identical hashes, which enables:
- **Rainbow table attacks** — precomputed tables mapping common hashes back to passwords.
- **Cross-account correlation** — you can see which users share a password.

```
No salt:   H("password123") -> always same digest -> rainbow table hits
With salt: H("a8f3..." + "password123") -> unique per user -> tables useless
```

The salt does not need to be secret — it needs to be **unique and random** (use a CSPRNG, typically 16 bytes). Modern password hashes (bcrypt, Argon2) generate and embed the salt automatically in their output string.

### Q7. [Theory] Why shouldn't you use SHA-256 directly to store passwords?

SHA-256 is designed to be **fast** — that is exactly wrong for passwords. An attacker with a leaked database and a GPU can compute billions of SHA-256 hashes per second, brute-forcing weak passwords almost instantly.

Password hashing needs to be **deliberately slow and memory-hard**:
- **Slow** (high iteration count) so each guess costs real time.
- **Memory-hard** (Argon2, scrypt) so attackers can't cheaply parallelize on GPUs/ASICs.

Use a purpose-built password hash: **Argon2id** (first choice in 2026), **scrypt**, **bcrypt**, or **PBKDF2** (when FIPS compliance forces it). These bundle salting, configurable cost, and resistance to hardware acceleration.

### Q8. [Theory] Compare bcrypt, scrypt, PBKDF2, and Argon2.

| Algorithm | Year | Tunable cost | Memory-hard | Notes |
|-----------|------|-------------|-------------|-------|
| PBKDF2 | 2000 | iterations | No | FIPS-approved; weakest vs GPUs; OWASP says ≥600k iters (HMAC-SHA256) |
| bcrypt | 1999 | cost factor | Slightly (4KB) | Battle-tested; 72-byte password truncation gotcha |
| scrypt | 2009 | CPU + memory | Yes | Memory-hard; used in crypto wallets |
| Argon2 | 2015 | time + memory + parallelism | Yes | PHC winner; **Argon2id** is the modern default |

**Recommendation (2026):** Argon2id is the first choice. If unavailable or constrained by compliance, scrypt or bcrypt are fine. PBKDF2 only when FIPS 140 mandates it. Argon2 has three variants: Argon2d (GPU-resistant, side-channel risk), Argon2i (side-channel resistant), and **Argon2id** (hybrid — recommended for password storage).

### Q9. [Practical] How would you hash a password with bcrypt in a Spring application?

Spring Security ships `BCryptPasswordEncoder`, but the modern, future-proof choice is `DelegatingPasswordEncoder`, which stores an algorithm prefix so you can migrate later.

```java
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

// On signup — salt is generated internally and embedded in the output
String stored = encoder.encode("S3cret!");
// -> "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMye..."  (algorithm tagged)

// On login — never decrypt; you re-hash and compare
boolean ok = encoder.matches("S3cret!", stored);
```

For Argon2 explicitly:

```java
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

// (saltLength, hashLength, parallelism, memoryKb, iterations)
PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
```

Key points: the encoder generates the salt, embeds parameters in the string, and `matches()` is constant-time. The `{bcrypt}` / `{argon2}` prefix lets you upgrade hashes transparently on next login.

### Q10. [Theory] What is the difference between symmetric and asymmetric encryption?

- **Symmetric**: one shared secret key encrypts and decrypts. Fast (AES does GB/s with hardware acceleration). Problem: how do both parties get the same key securely? Examples: AES, ChaCha20.
- **Asymmetric** (public-key): a key *pair* — a public key encrypts (or verifies) and a private key decrypts (or signs). Solves key distribution because the public key can be shared openly. Much slower; works on small data. Examples: RSA, ECC.

```
Symmetric:   Alice --[AES key K]-- Bob       (both hold K, K must stay secret)
Asymmetric:  Alice --(Bob's PUBLIC key)--> Bob decrypts with PRIVATE key
```

In practice they are combined (**hybrid encryption**): use slow asymmetric crypto to exchange/agree on a symmetric key, then use fast symmetric crypto for the bulk data. TLS works exactly this way.

### Q11. [Theory] What is AES and what key sizes does it support?

**AES** (Advanced Encryption Standard) is the dominant symmetric **block cipher**, standardized by NIST in 2001 (originally Rijndael). It operates on **128-bit (16-byte) blocks** and supports three key sizes:

- **AES-128** — 128-bit key, 10 rounds.
- **AES-192** — 192-bit key, 12 rounds.
- **AES-256** — 256-bit key, 14 rounds.

AES-128 is secure for virtually all uses; AES-256 is chosen for higher security margins and is mandated in some compliance regimes (and gives more headroom against future quantum attacks via Grover's algorithm, which roughly halves effective key strength). Modern CPUs have **AES-NI** instructions making it extremely fast. AES has no known practical break.

### Q12. [Theory] What is the difference between a block cipher and a stream cipher?

- **Block cipher** encrypts fixed-size blocks (AES = 16 bytes). To handle arbitrary-length data it needs a **mode of operation** (CBC, GCM, etc.) and often padding.
- **Stream cipher** encrypts data one byte/bit at a time by XORing it with a pseudo-random keystream generated from the key + nonce. No padding needed. Examples: ChaCha20, RC4 (broken, do not use).

```
Block:  plaintext -> [16-byte block][16-byte block]... -> cipher per block + mode
Stream: plaintext XOR keystream(key, nonce) = ciphertext  (same length)
```

Interestingly, a block cipher in counter (CTR) mode *becomes* a stream cipher. AES-GCM and ChaCha20-Poly1305 are the two go-to authenticated stream-style constructions in TLS 1.3.

### Q13. [Theory] Why is ECB mode insecure? What should you use instead?

**ECB (Electronic CodeBook)** encrypts each block independently with the same key. Identical plaintext blocks produce identical ciphertext blocks — so structure in the plaintext leaks straight through.

```
The infamous "ECB penguin": encrypting a bitmap with ECB still
shows the penguin's outline because repeated pixel blocks map to
repeated ciphertext blocks. Patterns survive encryption.
```

ECB provides no semantic security and no integrity. **Never use ECB.** Use an authenticated mode — **AES-GCM** (preferred) — which provides both confidentiality and integrity, or CBC with a random IV plus a separate MAC if GCM is unavailable.

### Q14. [Theory] What is an IV/nonce and why must it be unique?

An **IV (Initialization Vector)** or **nonce** ("number used once") randomizes encryption so that encrypting the same plaintext twice yields different ciphertexts. It is mixed into the first block / keystream.

- For **CBC**, the IV must be **random and unpredictable** (16 bytes for AES). It is not secret — you prepend it to the ciphertext.
- For **CTR/GCM**, the value must be **unique per key** (never reused). Reusing a GCM nonce with the same key is catastrophic: it leaks the XOR of plaintexts and, worse, can leak the authentication key, letting an attacker forge messages.

Rule of thumb: generate the IV/nonce from a CSPRNG and store it alongside the ciphertext. Never hard-code it, never reuse it.

### Q15. [Practical] Show how to encrypt and decrypt with AES-GCM in Java.

AES-GCM is authenticated encryption — it gives confidentiality *and* integrity in one operation.

```java
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

static final int GCM_TAG_BITS = 128;   // 16-byte auth tag
static final int IV_BYTES = 12;        // 96-bit nonce, recommended for GCM

public static byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
    byte[] iv = new byte[IV_BYTES];
    SecureRandom.getInstanceStrong().nextBytes(iv);   // unique nonce

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
    byte[] ct = cipher.doFinal(plaintext);

    // Prepend IV so the decryptor can read it (IV is not secret)
    byte[] out = new byte[IV_BYTES + ct.length];
    System.arraycopy(iv, 0, out, 0, IV_BYTES);
    System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
    return out;
}

public static byte[] decrypt(byte[] blob, SecretKey key) throws Exception {
    byte[] iv = java.util.Arrays.copyOfRange(blob, 0, IV_BYTES);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
    // doFinal throws AEADBadTagException if tampered — integrity is enforced
    return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
}
```

If the ciphertext or tag is modified, `doFinal` throws `AEADBadTagException` — you get tamper detection for free. Generate a fresh IV every encryption.

### Q16. [Practical] How do you generate a secure AES key in Java?

Use `KeyGenerator` (random key) or `SecretKeyFactory` with a KDF (key derived from a password). Never use `String.getBytes()` as a key.

```java
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

// Random 256-bit key
KeyGenerator kg = KeyGenerator.getInstance("AES");
kg.init(256);
SecretKey key = kg.generateKey();

// Derive a key from a password (PBKDF2) — for password-based encryption
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;

SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
PBEKeySpec spec = new PBEKeySpec(password, salt, 600_000, 256); // 600k iters
SecretKey derived = new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
```

Distinguish the two cases: a **data-encryption key** should be random (`KeyGenerator`); a key derived from a human **password** must go through a slow KDF (PBKDF2/Argon2) with a salt.

## 🟡 Intermediate (3–7 yrs)

### Q17. [Theory] How does RSA work at a conceptual level, and what key size should you use?

RSA relies on the difficulty of **factoring the product of two large primes**.

```
1. Pick two large primes p, q. Compute n = p*q (the modulus).
2. Compute φ(n) = (p-1)(q-1). Choose public exponent e (commonly 65537).
3. Compute private exponent d = e⁻¹ mod φ(n).
   Public key = (n, e)    Private key = (n, d)
4. Encrypt: c = mᵉ mod n      Decrypt: m = cᵈ mod n
```

Security rests on the fact that knowing `n` and `e` doesn't reveal `d` without factoring `n`.

**Key sizes (2026):** 2048-bit is the practical minimum and still considered secure; **3072-bit** gives ~128-bit security and is recommended for long-lived keys; 4096-bit for very long-term. RSA-1024 is broken/deprecated. Note RSA is being eclipsed by ECC because ECC gives equivalent security with far smaller keys.

### Q18. [Theory] What is ECC and why is it preferred over RSA?

**Elliptic Curve Cryptography** bases its security on the **elliptic-curve discrete logarithm problem**, which is much harder per bit than factoring. The payoff: dramatically smaller keys for the same security level.

| Security level | RSA key | ECC key |
|---------------|---------|---------|
| 128-bit | 3072 bits | 256 bits (P-256, Curve25519) |
| 192-bit | 7680 bits | 384 bits (P-384) |
| 256-bit | 15360 bits | 521 bits (P-521) |

Smaller keys mean faster operations, less bandwidth, lower power — critical for mobile, IoT, and high-volume TLS. Common curves: **NIST P-256/P-384** and **Curve25519** (X25519 for key exchange, Ed25519 for signatures), which is widely favored for its simplicity and resistance to implementation mistakes. TLS 1.3 prefers X25519.

### Q19. [Theory] Explain the Diffie-Hellman key exchange. What problem does it solve?

Diffie-Hellman (DH) lets two parties establish a **shared secret over a public channel** without ever transmitting the secret itself — solving the symmetric key-distribution problem.

```
Public params: prime p, generator g
Alice: picks secret a, sends A = gᵃ mod p
Bob:   picks secret b, sends B = gᵇ mod p

Alice computes: Bᵃ = (gᵇ)ᵃ = gᵃᵇ mod p
Bob computes:   Aᵇ = (gᵃ)ᵇ = gᵃᵇ mod p
                       ^ both get the same shared secret gᵃᵇ
```

An eavesdropper sees `g, p, A, B` but cannot compute `gᵃᵇ` without solving the discrete log problem. **ECDH** is the elliptic-curve variant — same idea, smaller/faster. Note: plain DH provides no authentication, so it's vulnerable to man-in-the-middle unless combined with signatures/certificates.

### Q20. [Theory] What is forward secrecy (PFS) and how is it achieved?

**Perfect Forward Secrecy** means that compromising a server's long-term private key does **not** let an attacker decrypt *past* recorded sessions.

It's achieved using **ephemeral** key exchange — **DHE** or **ECDHE** — where a fresh, throwaway DH key pair is generated per session and discarded afterward. The long-term key (the certificate's private key) is used only to *authenticate* the exchange (sign it), not to derive the session secret.

```
Without PFS (old RSA key exchange):
   client encrypts session key with server's RSA public key
   -> steal RSA private key later -> decrypt ALL recorded traffic

With PFS (ECDHE):
   per-session ephemeral keys -> stealing long-term key reveals nothing
   about past sessions because ephemeral secrets are gone.
```

TLS 1.3 **mandates** forward secrecy — static RSA key exchange was removed entirely.

### Q21. [Theory] What is a digital signature and what guarantees does it provide?

A digital signature is created by **signing a hash of the message with a private key**; anyone can verify it with the corresponding public key.

```
Sign:   signature = Encrypt_privateKey( H(message) )
Verify: H(message) == Decrypt_publicKey( signature ) ?
```

Guarantees:
1. **Authenticity** — only the private-key holder could have produced it.
2. **Integrity** — any change to the message changes its hash, breaking verification.
3. **Non-repudiation** — the signer cannot later deny signing (since only they hold the private key).

Note the direction is opposite to encryption: you sign with the *private* key and verify with the *public* key. Algorithms: RSA-PSS, ECDSA, EdDSA (Ed25519). We sign the hash, not the whole message, for efficiency.

### Q22. [Theory] What's the difference between a MAC and a digital signature?

Both protect integrity and authenticity, but with different key models:

| | MAC (e.g., HMAC) | Digital signature (e.g., ECDSA) |
|--|------------------|--------------------------------|
| Keys | One **shared secret** | **Key pair** (private/public) |
| Speed | Fast (symmetric) | Slow (asymmetric) |
| Non-repudiation | **No** — both parties share the key, either could have made it | **Yes** — only signer holds private key |
| Use case | API request integrity, cookies, session tokens | Certificates, software signing, contracts |

Because a MAC uses a shared key, the verifier could also have produced the tag — so it can't prove *which* party did. Signatures give non-repudiation precisely because verification doesn't require the signing key.

### Q23. [Theory] What is HMAC and why not just use H(secret || message)?

**HMAC** (Hash-based MAC) is a construction that turns a hash function into a keyed MAC:

```
HMAC(K, m) = H( (K ⊕ opad) || H( (K ⊕ ipad) || m ) )
```

The naive `H(secret || message)` is **broken for Merkle–Damgård hashes (MD5, SHA-1, SHA-2)** due to **length-extension attacks**: knowing `H(secret || m)` and the length of secret, an attacker can compute `H(secret || m || padding || extra)` *without knowing the secret* — forging a valid MAC for an extended message.

HMAC's nested two-pass structure defeats length extension. Use `HMAC-SHA256` for new systems. (With SHA-3, the prefix-MAC issue goes away, but HMAC remains the interoperable standard.)

### Q24. [Practical] Implement HMAC-SHA256 verification in Java, safely.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public static byte[] hmacSha256(byte[] key, byte[] message) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(message);
}

public static boolean verify(byte[] key, byte[] message, byte[] received) throws Exception {
    byte[] expected = hmacSha256(key, message);
    // CONSTANT-TIME comparison — prevents timing attacks
    return MessageDigest.isEqual(expected, received);
}
```

The critical detail: **never compare MACs with `Arrays.equals` or `==`**. A naive comparison returns early on the first mismatching byte, leaking timing information that lets an attacker forge a tag byte by byte. `MessageDigest.isEqual` is constant-time (compares all bytes regardless).

### Q25. [Theory] What is an X.509 certificate and what does it contain?

An **X.509 certificate** binds a public key to an identity (e.g., a domain name), vouched for by a Certificate Authority's signature. Key fields:

```
- Subject:            who the cert is for (CN / SAN, e.g. example.com)
- Subject Public Key: the public key being certified
- Issuer:             the CA that signed it
- Validity:           notBefore / notAfter dates
- Serial Number
- Signature:          CA's signature over the cert (proves authenticity)
- Extensions:         SAN (Subject Alternative Names), Key Usage, etc.
```

Modern browsers ignore the legacy `CN` and require the **SAN** (Subject Alternative Name) field for hostname matching. The certificate itself is public; its security comes from the CA's signature, which clients verify by chaining up to a trusted root.

### Q26. [Theory] Explain the certificate chain of trust and how PKI works.

PKI (Public Key Infrastructure) establishes trust through a **hierarchy** rooted in a small set of pre-trusted CAs.

```
Root CA (self-signed, in OS/browser trust store, offline)
   │ signs
   ▼
Intermediate CA (online, issues end-entity certs)
   │ signs
   ▼
Leaf / End-entity cert (example.com)
```

When you connect to `example.com`, the server presents the leaf + intermediates. The client:
1. Verifies the leaf was signed by the intermediate.
2. Verifies the intermediate was signed by a root.
3. Checks the root is in its **trust store**.
4. Validates dates, hostname (SAN), and revocation.

Roots are kept offline and self-signed; intermediates do the day-to-day issuing so a compromised intermediate can be revoked without nuking the root. This is the foundation of HTTPS trust.

### Q27. [Theory] How are revoked certificates handled (CRL vs OCSP vs stapling)?

A certificate may need to be revoked before expiry (key compromise, mis-issuance). Mechanisms:

- **CRL (Certificate Revocation List)** — the CA publishes a signed list of revoked serial numbers. Problem: lists grow huge, clients cache them, freshness lags.
- **OCSP (Online Certificate Status Protocol)** — the client asks the CA's responder "is serial X still valid?" in real time. Problem: latency and a privacy leak (the CA learns which sites you visit), plus availability concerns.
- **OCSP Stapling** — the *server* periodically fetches a signed, time-stamped OCSP response and "staples" it to the TLS handshake. The client gets fresh revocation status with no extra round trip and no privacy leak. This is the modern preferred approach.

There's also **short-lived certificates** (e.g., 90-day or shorter via ACME/Let's Encrypt) which reduce reliance on revocation altogether — the 2026 trend is toward ever-shorter validity.

### Q28. [Theory] Walk through the TLS 1.3 handshake.

TLS 1.3 streamlined the handshake to **one round trip (1-RTT)** and mandates forward secrecy and AEAD ciphers.

```
Client                                        Server
  │  ClientHello                                │
  │  - supported cipher suites                  │
  │  - key_share (ECDHE public, e.g. X25519)    │
  │  - supported_groups, signature_algs         │
  │ ──────────────────────────────────────────▶ │
  │                            ServerHello       │
  │   - selected cipher suite                    │
  │   - key_share (server ECDHE public)          │
  │   {EncryptedExtensions, Certificate,         │
  │    CertificateVerify (signs handshake),      │
  │    Finished}                                 │
  │ ◀────────────────────────────────────────── │
  │  {Finished}                                  │
  │  [Application Data] ───────────────────────▶ │
```

After both `key_share`s are exchanged, each side derives the shared secret via ECDHE and a key schedule (HKDF). The server proves its identity by signing the handshake transcript with its certificate's private key (`CertificateVerify`). Improvements over TLS 1.2: 1-RTT (vs 2), removed RSA key exchange / static DH / CBC / RC4 / weak hashes, mandatory PFS, optional **0-RTT** resumption (with replay caveats).

### Q29. [Theory] What is a CSPRNG and why can't you use Math.random() or Random?

A **CSPRNG** (Cryptographically Secure Pseudo-Random Number Generator) produces output that is computationally indistinguishable from true randomness and is **unpredictable** even if an attacker sees previous outputs.

- `java.util.Random` and `Math.random()` use a **linear congruential generator** with a 48-bit seed. Given a couple of outputs, an attacker can recover the seed and predict all future values. **Never** use them for keys, tokens, IVs, salts, or session IDs.
- Use **`java.security.SecureRandom`** for anything security-sensitive.

```java
SecureRandom rng = SecureRandom.getInstanceStrong(); // OS entropy source
byte[] token = new byte[32];
rng.nextBytes(token); // 256-bit unpredictable token
```

The difference is unpredictability: a non-crypto PRNG is great for simulations but fatal for secrets.

### Q30. [Practical] Generate a secure random token (e.g., session ID / API key) in Java.

```java
import java.security.SecureRandom;
import java.util.Base64;

private static final SecureRandom RNG = new SecureRandom();

public static String secureToken(int numBytes) {
    byte[] buf = new byte[numBytes];           // 32 bytes = 256 bits
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
}
// secureToken(32) -> "Xq3...url-safe 43-char token"
```

Best practices: ≥128 bits of entropy (16 bytes) for tokens, 256 bits for keys. Use the URL-safe Base64 encoder for tokens that go in URLs/cookies. Reuse a single `SecureRandom` instance (it's thread-safe and seeding is expensive). On Linux, `SecureRandom` draws from `/dev/urandom` by default.

### Q31. [Practical] You need to encrypt data at rest in a database. What approach do you take?

A layered approach using **envelope encryption** is the standard:

```
                ┌─────────────┐
  Master Key →  │  KMS / HSM  │  (never leaves; AWS KMS, GCP KMS, Vault)
  (KEK)         └─────┬───────┘
                      │ wraps/unwraps
                      ▼
              Data Encryption Key (DEK)  ── encrypts ──▶ row/column data (AES-GCM)
              (stored encrypted alongside ciphertext)
```

1. Generate a per-record or per-table **DEK** with `KeyGenerator`.
2. Encrypt data with **AES-256-GCM** using the DEK.
3. Encrypt (wrap) the DEK with a **KEK** held in a KMS/HSM — the KEK never leaves the boundary.
4. Store the wrapped DEK + IV + ciphertext together.

Benefits: rotating the KEK only requires re-wrapping DEKs (cheap), not re-encrypting all data; the plaintext master key never touches your app. Also consider column-level vs full-disk (TDE) depending on threat model, and keep encryption transparent to queries where possible.

### Q32. [Practical] How do you securely store and use secrets (API keys, DB passwords) in an application?

Never hard-code secrets or commit them to source control. The hierarchy of good options:

1. **Secrets manager** — AWS Secrets Manager, GCP Secret Manager, HashiCorp Vault. Secrets are encrypted at rest, access-controlled via IAM, audited, and support automatic rotation. Best choice.
2. **Environment variables / injected files** — acceptable for containers when fed from a secrets manager (e.g., via a CSI driver), but env vars can leak in logs, crash dumps, and child processes.
3. **Encrypted config** — e.g., Spring Cloud Config with encryption, Jasypt. Better than plaintext but you still must protect the decryption key.

```java
// Pull from a secrets manager at startup, not from a properties file
String dbPassword = secretsClient.getSecretValue("prod/db/password");
```

Additional rules: scan repos for committed secrets (gitleaks/trufflehog), rotate on exposure, use short-lived dynamic credentials where possible, and grant least privilege.

## 🟠 Advanced (8–12 yrs)

### Q33. [Theory] Explain the padding oracle attack and how to prevent it.

A **padding oracle** attack defeats CBC-mode encryption when the system reveals (directly or via timing/error differences) whether a decrypted ciphertext has **valid PKCS#7 padding**.

```
CBC decryption: Pₙ = Decrypt(Cₙ) ⊕ Cₙ₋₁
Attacker controls Cₙ₋₁ byte by byte. By tweaking it and observing
"padding valid / invalid", they recover the intermediate Decrypt(Cₙ),
then the plaintext — WITHOUT the key. ~256 tries per byte.
```

The classic enabler was an app returning different errors for "bad padding" vs "bad MAC" (POODLE, Lucky13 were related). **Prevention:**

1. Use **authenticated encryption (AES-GCM)** so the tag is checked *before* any padding logic — invalid ciphertext is rejected uniformly.
2. If stuck with CBC, follow **encrypt-then-MAC**: verify the MAC first in constant time; only decrypt if it passes. Never leak distinct error states.
3. Return identical error responses and timing regardless of the failure reason.

The deeper lesson: **MAC-then-encrypt** and unauthenticated CBC are dangerous; prefer AEAD.

### Q34. [Theory] What are replay attacks and downgrade attacks, and how do you defend against them?

**Replay attack** — an attacker captures a valid message/request and re-sends it later to repeat an action (e.g., re-submitting a "transfer $100" request). Defenses:
- **Nonces** the server tracks and rejects on reuse.
- **Timestamps** with a short validity window (plus clock-skew tolerance).
- **Monotonic counters / sequence numbers**.
- For tokens: short TTLs and one-time-use semantics.
- TLS 1.3 **0-RTT** data is replayable by design — only use it for idempotent requests.

**Downgrade attack** — a MITM forces the parties to negotiate a weaker/older protocol or cipher (e.g., TLS 1.3 → SSLv3 as in POODLE; FREAK/Logjam forced export-grade crypto). Defenses:
- **Disable old protocols/ciphers** entirely (no SSLv3, TLS 1.0/1.1).
- TLS 1.3 includes **downgrade-protection sentinels** in the server random.
- **HSTS** forces HTTPS and prevents protocol stripping to HTTP.
- Signed handshake transcripts so tampering with negotiation is detected.

### Q35. [Theory] How does authenticated encryption (AEAD) work, and what is AAD?

**AEAD** (Authenticated Encryption with Associated Data) combines confidentiality and integrity in a single primitive, eliminating the error-prone job of bolting a MAC onto a cipher yourself. AES-GCM and ChaCha20-Poly1305 are the dominant AEAD constructions.

```
AEAD.encrypt(key, nonce, plaintext, AAD) -> (ciphertext, authTag)
AEAD.decrypt(key, nonce, ciphertext, authTag, AAD)
   -> plaintext  OR  failure (if tag invalid)
```

**AAD (Associated Data)** is data that is **authenticated but not encrypted** — covered by the tag but sent in the clear. Use it for headers/metadata that must travel in plaintext but must not be tampered with: e.g., a message's version, routing headers, the record sequence number in TLS, or a key/version ID. If an attacker alters the AAD, tag verification fails.

This is why "encrypt-then-MAC done right" is just AEAD — use it instead of hand-rolling.

### Q36. [Practical] Design a key rotation strategy for a system encrypting millions of records.

Goals: rotate keys without downtime and without re-encrypting everything at once.

```
Key versioning + envelope encryption:

record = { keyVersion, wrappedDEK, iv, ciphertext }

1. Each record tags WHICH key (KEK version) wrapped its DEK.
2. Introduce KEK v2 in the KMS; mark it the new default for writes.
3. New writes use v2; old records keep v1 — both decrypt fine because
   the keyVersion tells you which KEK to unwrap with.
4. Lazy re-wrap: on read/update, re-wrap the DEK under v2.
   Optionally run a background migration for cold data.
5. Once no record references v1, disable then destroy v1.
```

Key principles:
- **Rotate the KEK, not every DEK** — re-wrapping a small DEK is cheap; re-encrypting petabytes is not (envelope encryption is what makes this feasible).
- **Version every ciphertext** so multiple key generations coexist.
- **Automate rotation** on a schedule (e.g., yearly) and **emergency rotation** on suspected compromise.
- Keep old KEKs available (disabled-for-encrypt, enabled-for-decrypt) until migration completes, then destroy.
- Audit and alarm on key usage.

### Q37. [Practical] You must verify a JWT's signature. Walk through what you check and a code sketch.

A JWT has three Base64URL parts: `header.payload.signature`. Verification must check **both** the signature and the claims — and avoid classic pitfalls.

```java
// Using a vetted library (e.g., jjwt / nimbus). Never parse/verify by hand.
Jws<Claims> jws = Jwts.parser()
    .verifyWith(publicKey)                 // RS256/ES256 public key, or HMAC key
    .requireIssuer("https://auth.example") // pin issuer
    .requireAudience("my-api")             // pin audience
    .clockSkewSeconds(30)
    .build()
    .parseSignedClaims(token);             // throws if signature/exp invalid
```

Checks, in order:
1. **Signature** valid against the expected key — and **pin the algorithm**. The infamous attack is the `alg: none` bypass and the RS256→HS256 confusion (attacker signs with the public key as an HMAC secret). Configure the library to accept *only* the algorithm you expect.
2. **`exp`** (not expired) and **`nbf`/`iat`** within skew.
3. **`iss`** and **`aud`** match your service.
4. For key rotation, fetch the right key by `kid` from a **JWKS** endpoint (and cache it).

Also: don't put secrets in the payload (it's only Base64, not encrypted — use JWE if you need confidentiality), and keep tokens short-lived.

### Q38. [Theory] What is the post-quantum cryptography threat, and what's the state of mitigation in 2026?

Large-scale quantum computers would break today's public-key crypto:
- **Shor's algorithm** efficiently factors integers and solves discrete logs — breaking **RSA, DH, and ECC** entirely.
- **Grover's algorithm** quadratically speeds up brute force — halving symmetric/hash security, so AES-256 → ~128-bit effective (still safe), and you'd prefer 256-bit keys and SHA-384+.

The realistic near-term threat is **"harvest now, decrypt later"**: adversaries record encrypted traffic today to decrypt once quantum computers mature.

State in 2026: NIST finalized post-quantum standards in 2024 — **ML-KEM (Kyber)** for key encapsulation (FIPS 203), **ML-DSA (Dilithium)** (FIPS 204) and **SLH-DSA (SPHINCS+)** (FIPS 205) for signatures. The industry is deploying **hybrid** key exchange (e.g., **X25519 + ML-KEM-768** in TLS, already shipping in major browsers and cloud providers) so you keep classical security while adding PQC. Symmetric crypto and hashes need only larger parameters, not replacement.

### Q39. [Theory] Compare AES-GCM and ChaCha20-Poly1305. When would you choose each?

Both are modern AEAD ciphers used in TLS 1.3.

| | AES-256-GCM | ChaCha20-Poly1305 |
|--|-------------|-------------------|
| Type | Block cipher (CTR) + GCM auth | Stream cipher + Poly1305 auth |
| Hardware accel | Very fast **with AES-NI** | Fast in **pure software** |
| Without AES-NI | Slow and risks cache-timing side channels | Consistent, constant-time by design |
| Nonce reuse | Catastrophic (forgery + key leak) | Catastrophic too, but less brittle |

**Choose AES-GCM** when you have hardware AES (modern servers, most x86/ARM) — it's the fastest. **Choose ChaCha20-Poly1305** on devices without AES-NI (older/low-power mobile, embedded) where software AES would be slow and side-channel-prone. This is exactly why TLS 1.3 servers often prioritize ChaCha20 for mobile clients and AES-GCM for desktops. ChaCha20 is also simpler to implement in constant time.

### Q40. [Behavioral] Describe a time you found or fixed a cryptographic vulnerability or weakness. How did you handle it?

(Guidance for answering with a STAR structure.)

**Situation/Task:** Frame a concrete weakness — e.g., "During a security review I found we were storing passwords with unsalted SHA-1," or "an internal service used AES-ECB for PII," or "we shipped `Math.random()` for password-reset tokens."

**Action:** Emphasize a measured, responsible process:
- Confirm and quantify the risk (what data, how exploitable) before raising alarms.
- Avoid blame; bring a fix, not just a problem.
- Choose a correct remediation (e.g., migrate to Argon2id via `DelegatingPasswordEncoder` so hashes upgrade on next login; switch to AES-GCM; rotate any exposed secrets/keys).
- Coordinate disclosure and a rollout plan (feature flag, backfill, monitoring).
- Add guardrails so it can't recur — linters, code-review checklists, a crypto wrapper library, dependency scanning.

**Result:** Quantify ("migrated 2M password hashes transparently with zero forced resets") and note the lasting process improvement. Strong answers show judgment about *severity and communication*, not just the technical fix.

### Q41. [Practical] How do you mitigate timing side-channel attacks in cryptographic code?

A **timing side channel** leaks secret-dependent information through how long an operation takes. Examples: byte-by-byte string comparison of MACs/tokens, branch on secret data, table lookups indexed by secret (cache-timing in software AES), early-exit padding checks.

Mitigations:

```java
// BAD: returns early, leaks position of first mismatch
boolean bad = token.equals(stored);

// GOOD: constant-time, examines every byte
boolean good = MessageDigest.isEqual(tokenBytes, storedBytes);
```

General principles:
- **Constant-time comparisons** for secrets (`MessageDigest.isEqual`, `Arrays.equals` is NOT guaranteed constant-time for this).
- **No secret-dependent branches or memory indices** — control flow and memory access patterns must not depend on secret values.
- Prefer **AEAD** so verification is uniform; return **uniform error responses and timing**.
- Use **hardware AES (AES-NI)** to avoid software table lookups.
- Lean on **vetted libraries** (the JCA, BouncyCastle, libsodium) rather than hand-rolling — they've addressed these.
- Consider adding jitter only as defense-in-depth, not a primary fix.

### Q42. [Theory] What is certificate pinning, and what are its trade-offs?

**Certificate (or public-key) pinning** hard-codes the expected server certificate or public key in the client, so the client rejects any cert — even a valid CA-issued one — that doesn't match. It defends against a **compromised or rogue CA** issuing a fraudulent cert for your domain (a real MITM vector).

```
Normal TLS:  trust ANY cert chaining to a trusted root.
Pinning:     additionally require the cert/key to match a pinned value.
```

Trade-offs / risks:
- **Operational fragility:** if you rotate keys/certs without updating pins, you brick clients ("pinning suicide"). Always pin a **backup key** too.
- Hard to fix once shipped (especially mobile apps with slow update cycles).
- HTTP Public Key Pinning (HPKP) for browsers was **deprecated** for this reason.

Best practices (2026): pin to an **intermediate or your own key**, not a leaf; always include backup pins; use it primarily in **mobile apps** for high-value APIs; combine with Certificate Transparency monitoring rather than relying on pinning alone.

### Q43. [Theory] What is Certificate Transparency and what problem does it solve?

**Certificate Transparency (CT)** is a system of public, append-only, cryptographically verifiable **logs** of every certificate a CA issues. It addresses the problem that a misbehaving or compromised CA could **silently issue a fraudulent certificate** for your domain without you ever knowing.

How it works:
- CAs submit issued certs to CT logs, receiving a **Signed Certificate Timestamp (SCT)**.
- Browsers (Chrome, Safari) **require valid SCTs** to trust a cert.
- The logs are public and monitored, so domain owners can **detect** unauthorized certificates issued for their domains (via monitoring services / crt.sh).

```
CA issues cert -> submits to CT logs -> gets SCT -> server staples SCT
Domain owner monitors logs -> spots a cert they didn't request -> revokes/alerts
```

CT shifts the model from "prevent" to "detect and respond" — you can't stop a rogue issuance instantly, but you *will* see it. It's now effectively mandatory for publicly trusted certs.

## 🔴 Expert (15+ yrs)

### Q44. [Theory] How would you architect cryptographic key management for a large multi-tenant SaaS?

A robust design separates concerns and minimizes the blast radius of any single compromise:

```
                ┌────────────────────────────┐
                │   HSM / Cloud KMS (root)    │  root KEKs, FIPS 140-3
                └──────────────┬──────────────┘
                               │ wraps
              ┌────────────────┼────────────────┐
        Tenant-A KEK      Tenant-B KEK      Tenant-C KEK   (per-tenant isolation)
              │                                  │
        wraps DEKs                          wraps DEKs
              ▼                                  ▼
        AES-GCM data                        AES-GCM data
```

Principles:
- **Per-tenant key isolation** so one tenant's compromise (or legal hold / data deletion) doesn't touch others; supports "crypto-shredding" (delete a tenant's key to render their data unrecoverable).
- **Envelope encryption** with KEK→DEK hierarchy; master keys live in an **HSM/KMS** and never leave.
- **Key versioning** and automated **rotation** with lazy re-wrap.
- **Separation of duties**: key admins ≠ data admins; dual control / quorum for root-key operations.
- **Audit logging** of every key use (who, when, which key) for compliance (SOC 2, PCI, FIPS).
- **Regional residency / BYOK / HYOK** options for customers with sovereignty requirements.
- Define **RTO/RPO for keys** — losing a KEK loses the data, so KMS durability and recovery are first-class.

### Q45. [Theory] How do you balance FIPS 140 compliance with modern cryptographic best practices?

FIPS 140-2/140-3 validates *cryptographic modules*, and many regulated environments (government, healthcare, finance) **require** it. The tension: the FIPS-approved algorithm list and validated module list often **lag** behind the cutting edge.

Trade-offs and approach:
- **Algorithm constraints:** FIPS may force PBKDF2 instead of Argon2id for password hashing, or restrict you to NIST curves (P-256/384) rather than Curve25519/Ed25519 (though Ed25519 is now FIPS-approved as of recent updates). You comply, then maximize within the allowed set (e.g., PBKDF2 with very high iteration counts).
- **Validated modules only:** you must use a *validated* build (e.g., BouncyCastle FIPS, OpenSSL FIPS provider, the JDK in FIPS mode), and you can't just upgrade to the newest crypto until it's validated — validation takes time and money.
- **Operational rigor:** FIPS mode disables non-approved algorithms entirely (e.g., MD5), which can break dependencies — test thoroughly.
- **Post-quantum:** NIST PQC standards (ML-KEM/ML-DSA) being FIPS standards actually *aligns* compliance with the modern frontier.

The engineering judgment: treat FIPS as a constraint to design within, document where it forces a sub-optimal-but-approved choice, and isolate crypto behind an abstraction so you can swap validated implementations.

### Q46. [Theory] Explain threshold cryptography / secret sharing and a use case.

**Secret sharing** (Shamir's Secret Sharing, SSS) splits a secret `S` into `n` shares such that any `k` of them reconstruct `S`, but `k-1` shares reveal **nothing**.

```
Shamir's scheme: build a degree-(k-1) polynomial f(x) with f(0)=S,
random other coefficients. Shares are points (i, f(i)).
Any k points uniquely interpolate the polynomial -> recover f(0)=S.
Fewer than k points leave S information-theoretically hidden.
```

**Threshold cryptography** generalizes this so the secret key never has to be *reconstructed* at all — `k` of `n` parties cooperate to produce a signature/decryption, and no single party ever holds the full key (threshold signatures, MPC).

Use cases:
- **Root key protection / quorum:** a KMS or root CA key split among officers; any 3 of 5 must combine to perform a sensitive operation (no single point of compromise or insider abuse).
- **Crypto custody / wallets:** threshold signatures (TSS/MPC) so no one device holds the full signing key.
- **Disaster recovery:** master key recovery requiring a quorum.

The win: eliminate single points of failure/compromise and enforce multi-party control without a single reconstructed secret.

### Q47. [Behavioral] You discover production has been using a weak/broken cipher for years. How do you lead the response?

(Guidance — this probes incident leadership, not just crypto knowledge.)

**Assess and contain first.** Quantify exposure: what data, which systems, is it exploitable in practice, is there evidence of compromise. Avoid both panic and minimization. Loop in security/legal/leadership early if there's potential breach or regulatory (GDPR/PCI/HIPAA) reporting duty.

**Prioritize by risk.** A broken cipher for data-at-rest with no external exposure is different from a broken TLS cipher on a public endpoint. Triage accordingly.

**Plan remediation without breaking prod.**
- Introduce the strong primitive alongside the old (versioned ciphertext, `DelegatingPasswordEncoder`-style upgrade-on-use).
- Rotate any keys/secrets that may be exposed.
- Backfill/migrate data on a schedule; monitor.
- Decommission the weak path once nothing depends on it.

**Communicate honestly** — to leadership with risk and timeline, to customers if disclosure is warranted, and a blameless internal post-mortem.

**Prevent recurrence:** crypto-agility abstraction, automated scanning (deps + algorithms), code-review gates, and a documented cryptographic standard for the org. The signal you want to send: calm, methodical, transparent, and focused on systemic fixes — not heroics or cover-ups.

### Q48. [Theory] What does it mean for a system to be "crypto-agile," and how do you design for it?

**Crypto-agility** is the ability to swap cryptographic algorithms, key sizes, or providers **without re-architecting** the system — essential because algorithms get deprecated (SHA-1, RSA-1024) and new ones arrive (post-quantum).

Design principles:

```
Tag everything with an algorithm/version identifier:
   ciphertext  = { algoId, keyVersion, iv, data, tag }
   passwordHash = "{argon2id}$..."   (prefix tells you the algorithm)
   token        = carries a kid -> resolve key + alg from JWKS
```

- **Abstract crypto behind an interface** (an `Encryptor` / `Signer` service), never call primitives inline across the codebase.
- **Self-describing data:** persist the algorithm + key version *with* every ciphertext/hash so old and new coexist and you know how to decrypt legacy data.
- **Negotiated protocols:** like TLS, support multiple suites and prefer the strongest mutually supported one; allow disabling weak ones centrally.
- **Upgrade-on-use:** re-encrypt/re-hash lazily when data is touched (e.g., re-hash password on login).
- **Hybrid modes** for transitions (classical + PQC during the migration window).
- **Inventory:** know everywhere crypto is used (a "cryptographic bill of materials") so you can assess and respond to a new break quickly.

The 2026 driver is the post-quantum migration: organizations that built crypto-agility can roll out ML-KEM/ML-DSA incrementally; those that hard-coded RSA everywhere face painful rewrites.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q49. [Theory] What is the internal structure of a hash digest — how does SHA-256 process a message block by block?

SHA-256 is built on the **Merkle–Damgård** construction. The message is not hashed all at once; it is broken into fixed-size blocks and folded into a running state.

```
1. PADDING: append a single '1' bit, then '0' bits, then the 64-bit
   message length, so the total is a multiple of 512 bits.
2. PARSE: split into 512-bit (64-byte) blocks M(1)…M(N).
3. INIT: H = eight 32-bit constants (fractional parts of sqrt of first 8 primes).
4. COMPRESS: for each block, run the compression function
        H(i) = compress(H(i-1), M(i))
   which expands the 16 words to 64, then does 64 rounds of mixing
   (Ch, Maj, Σ0, Σ1 functions + round constants Kt).
5. OUTPUT: concatenate the final eight 32-bit words = 256-bit digest.
```

The key internal idea is the **compression function**: it takes the previous chaining value plus the next block and produces a new chaining value of the same size. This chaining is what lets an arbitrary-length input collapse to a fixed output, and it is also exactly the structural property that enables **length-extension attacks** (the final digest *is* the internal state, so an attacker who knows it can keep compressing more blocks). SHA-3's sponge avoids this because it discards part of the state ("capacity") on output.

#### Q50. [Theory] Why does padding in a hash function include the message length (Merkle–Damgård strengthening)?

Appending the original message length to the padding is called **Merkle–Damgård strengthening**, and it is essential for collision resistance. Without it, two different messages that happen to differ only in how their padding bits line up could be made to collide more easily.

Concretely, encoding the length ensures that two messages of *different* lengths can never share the same final padded block sequence. The length field "seals" the input so an attacker cannot exploit ambiguity in where a message ends. It also caps the maximum input size (SHA-256 uses a 64-bit length field, so up to 2^64 − 1 bits). This is a small but load-bearing detail: the security proof that the compression function's collision resistance lifts to the full hash relies on this length encoding.

#### Q51. [Theory] What actually happens inside one AES round, and why are there multiple rounds?

Each AES round (except the last) applies four invertible transformations to the 4×4 byte **state matrix**:

```
1. SubBytes   — each byte replaced via the S-box (nonlinear substitution).
                The S-box = multiplicative inverse in GF(2^8) + affine map.
2. ShiftRows  — rows are cyclically shifted (0,1,2,3 bytes) → diffusion across columns.
3. MixColumns — each column multiplied by a fixed matrix in GF(2^8) → diffusion within columns.
4. AddRoundKey — XOR the state with the round's subkey (from key schedule).
```

The last round omits MixColumns (a design choice that makes encryption/decryption symmetric in structure). Why multiple rounds? **Confusion and diffusion** (Shannon's principles) accumulate: SubBytes provides confusion (nonlinearity), ShiftRows + MixColumns provide diffusion (one input byte affects all output bytes within a few rounds). A single round is trivially breakable; 10/12/14 rounds give a large security margin against differential and linear cryptanalysis. The number of rounds scales with key size because larger keys need more mixing to fully diffuse.

#### Q52. [Theory] What is the AES key schedule and why does it matter?

The **key schedule** (key expansion) derives the per-round subkeys from the original cipher key. AES-128 expands a 128-bit key into 11 round keys (one per round plus the initial AddRoundKey); AES-256 expands into 15.

```
- Words of the key are processed; every Nk words a transformation applies:
    RotWord (rotate bytes), SubWord (S-box each byte), XOR with Rcon (round constant).
- Each new word = previous word XOR a transformed earlier word.
```

It matters for two reasons. First, **distinctness**: each round uses a different subkey, so identical rounds don't simply repeat — this defeats slide attacks. Second, it's a known **weak point historically**: the AES-256 key schedule has less diffusion than AES-128's, which is why related-key attacks (academic, not practical) are slightly stronger against AES-256. For real systems this is irrelevant because related-key attacks require an attacker who can force encryption under chosen key relationships, which a sane protocol never permits.

#### Q53. [Practical] Show how to inspect the actual structure of a Base64URL-encoded JWT in Java without a library, and explain why this is not verification.

```java
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public static void inspectJwt(String token) {
    String[] parts = token.split("\\.");
    if (parts.length != 3) throw new IllegalArgumentException("not a JWS");

    Base64.Decoder dec = Base64.getUrlDecoder();
    String header  = new String(dec.decode(parts[0]), StandardCharsets.UTF_8);
    String payload = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);
    // parts[2] is the signature bytes — meaningless until verified

    System.out.println("Header : " + header);   // {"alg":"RS256","kid":"..."}
    System.out.println("Payload: " + payload);  // {"sub":"...","exp":...}
    // NOTE: we have NOT checked the signature. Anyone can forge these two parts.
}
```

This decodes the JWT but provides **zero security**. Base64URL is an encoding, not encryption or authentication — the payload is fully readable and fully forgeable. The entire trust of a JWT lives in the **third segment** (the signature) verified against the issuer's key with a pinned algorithm. The lesson: never make an authorization decision from a decoded-but-unverified JWT. Always run it through a vetted parser (`verifyWith(key)`) that checks the signature, `exp`, `iss`, and `aud`.

#### Q54. [Theory] What is constant-time comparison doing at the byte/instruction level, and why is `MessageDigest.isEqual` safe?

A naive comparison short-circuits:

```
for each byte:  if a[i] != b[i] return false   // exits early!
```

The early return means the *time taken* correlates with how many leading bytes matched. An attacker submitting guessed tokens can measure response latency and recover the secret one byte at a time (~256 attempts per byte instead of 256^n).

A constant-time comparison instead accumulates differences without branching:

```java
// Conceptually what MessageDigest.isEqual does (modern JDK):
int result = (a.length == b.length) ? 0 : 1;
int len = Math.min(a.length, b.length);
for (int i = 0; i < len; i++) {
    result |= a[i] ^ b[i];   // XOR is 0 only if bytes match; OR accumulates
}
return result == 0;          // examined EVERY byte, no early exit, no data-dependent branch
```

The crucial properties: it touches all bytes regardless of where the first mismatch is, and the control flow does not depend on secret data. Modern OpenJDK's `MessageDigest.isEqual` is implemented to be length-independent and constant-time per byte. Note `Arrays.equals` is *not* contractually constant-time and must not be used for secret comparison.

#### Q55. [Theory] Why is a 96-bit (12-byte) nonce specifically recommended for AES-GCM?

GCM internally builds its counter from the nonce. When you supply exactly **96 bits**, GCM uses it directly as the leading bits of the initial counter block (with the low 32 bits set to the block counter). Any other length forces GCM to **hash the nonce through GHASH** to derive the 128-bit counter, which is slower and, more importantly, introduces a small extra collision surface.

```
12-byte nonce: J0 = nonce || 0x00000001   (direct, fast, recommended)
other length:  J0 = GHASH(nonce)          (extra processing, larger collision risk)
```

The deeper reason it matters: GCM's security degrades sharply if a nonce ever repeats under the same key. With a 96-bit random nonce, the birthday bound means you should keep encryptions under one key well below ~2^32 messages to keep collision probability negligible. For very high volumes, use a **deterministic counter nonce** (e.g., a per-message incrementing value) or rotate keys — never rely on randomness alone at scale. This is why TLS and most libraries standardize on 12-byte nonces.

#### Q56. [Practical] Demonstrate the catastrophic effect of GCM nonce reuse conceptually with code comments.

```java
// DO NOT DO THIS. Illustration of why nonce reuse is fatal in GCM.
byte[] iv = fixedIv();                // BUG: same IV reused
Cipher c = Cipher.getInstance("AES/GCM/NoPadding");

c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
byte[] ct1 = c.doFinal(plaintext1);   // keystream Ks XOR plaintext1

c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
byte[] ct2 = c.doFinal(plaintext2);   // SAME keystream Ks XOR plaintext2

// Attacker computes:  ct1 XOR ct2  ==  plaintext1 XOR plaintext2
//   -> the keystream cancels out, leaking the XOR of plaintexts.
// WORSE for GCM: nonce reuse also lets an attacker recover the GHASH
//   authentication subkey H, enabling FORGERY of arbitrary messages.
```

For a stream-style cipher, reusing the keystream means `C1 XOR C2 = P1 XOR P2`, which leaks plaintext relationships and often the plaintexts themselves (crib-dragging). GCM is uniquely brittle because nonce reuse additionally compromises the **authentication key H** (a fixed function of the cipher key), so the attacker can forge valid tags — total break of both confidentiality and integrity. The fix is a fresh CSPRNG nonce (or a guaranteed-unique counter) every single encryption, and consider **AES-GCM-SIV** which is nonce-misuse resistant.

#### Q57. [Theory] How does HKDF work internally (extract-then-expand), and why two phases?

**HKDF** (HMAC-based Key Derivation Function, RFC 5869) is the standard way to turn a high-entropy-but-not-uniform secret (like an ECDH shared secret) into one or more uniformly random keys. It has two distinct phases:

```
1. EXTRACT:  PRK = HMAC(salt, IKM)
   "Concentrate" the input keying material into a fixed-length
   pseudorandom key PRK. Removes structure/bias from the raw secret.

2. EXPAND:   OKM = HMAC(PRK, info || counter), chained, sliced to length
   "Stretch" PRK into as many bytes of output key material as needed,
   bound to a context string 'info' (domain separation).
```

Why two phases? The raw ECDH output is uniformly random *as a group element* but not as a uniform byte string — extract fixes that. Expand then lets you derive **multiple independent keys** (e.g., separate client/server, encryption/MAC keys) from one shared secret, each tied to a distinct `info` label so they're cryptographically separated. TLS 1.3's entire key schedule is built on HKDF-Expand-Label. The `salt` (extract) and `info` (expand) give you both randomness extraction and domain separation in one clean primitive.

### 🟡 — extended

#### Q58. [Theory] Explain the math of why RSA encryption and decryption are inverses (Euler's theorem).

RSA's correctness rests on **Euler's theorem**: for `m` coprime to `n`, `m^φ(n) ≡ 1 (mod n)`.

```
Setup:  n = p·q,  φ(n) = (p-1)(q-1),  e·d ≡ 1 (mod φ(n))
So:     e·d = 1 + k·φ(n)  for some integer k.

Decrypt(Encrypt(m)) = (m^e)^d = m^(e·d) = m^(1 + k·φ(n))
                    = m · (m^φ(n))^k
                    ≡ m · 1^k                (by Euler's theorem)
                    ≡ m   (mod n)
```

So raising to the `e`-th then `d`-th power returns the original message modulo `n`. (The edge case where `m` shares a factor with `n` is handled by the Chinese Remainder Theorem, and in practice proper padding plus the astronomically low chance of hitting `p` or `q` makes it a non-issue.) The security gap is that computing `d` requires `φ(n)`, which requires factoring `n` into `p·q` — believed hard for classical computers. This is also why textbook RSA without padding (OAEP/PSS) is insecure: it's deterministic and malleable.

#### Q59. [Theory] Why is "textbook RSA" insecure, and what do OAEP and PSS add?

Raw "textbook" RSA — `c = m^e mod n` — has multiple fatal flaws:

- **Deterministic:** same plaintext → same ciphertext, so an attacker can detect repeats and brute-force small message spaces (e.g., "yes"/"no", a credit-card number).
- **Malleable:** `(m^e)·(2^e) = (2m)^e`, so an attacker can multiply the plaintext by a constant without the key.
- **Small-message / low-exponent attacks:** small `m` with `e=3` may satisfy `m^3 < n`, making it recoverable by a plain cube root.

**OAEP** (Optimal Asymmetric Encryption Padding) fixes encryption: it injects randomness and a mask-generation function so encryption is **randomized** and non-malleable, and it's provably secure under chosen-ciphertext attack assumptions. **PSS** (Probabilistic Signature Scheme) does the analogous job for **signatures**, adding a random salt so two signatures of the same message differ and the scheme has a tight security proof. Rule: never use `RSA/ECB/NoPadding`; use `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` for encryption and `RSASSA-PSS` for signatures. (Even better in 2026: prefer ECDH/ECDSA or hybrid PQC.)

#### Q60. [Theory] What is the difference between ECDSA and EdDSA, and why is Ed25519 considered safer to implement?

Both are elliptic-curve signature schemes, but they differ in critical engineering details:

| | ECDSA | EdDSA (Ed25519) |
|--|-------|------------------|
| Nonce (k) | Requires a fresh **random** k per signature | **Deterministic** — k = hash(private key, message) |
| Curve | NIST P-256 etc. (Weierstrass) | Edwards curve (Curve25519) |
| Failure mode | Reused/biased k **leaks the private key** | No per-signature randomness to get wrong |
| Side channels | Needs care (variable-time scalar mult) | Designed for constant-time implementation |

The headline difference is the **nonce**. ECDSA's security collapses if the per-signature random value `k` is ever repeated or even slightly biased — this is exactly how the **Sony PS3** signing key and several Bitcoin wallets were compromised (reused k → solve two linear equations → recover the private key). EdDSA removes this footgun by deriving `k` deterministically from the key and message, so there is no RNG to fail. Combined with Edwards-curve formulas that have no exceptional cases and are naturally constant-time, Ed25519 is much harder to implement insecurely. This is why TLS 1.3, SSH, and modern signing prefer it.

#### Q61. [Theory] How does the TLS 1.3 key schedule derive its many keys from one shared secret?

TLS 1.3 runs a chained **HKDF** key schedule that progressively mixes in secrets and produces a distinct key for each purpose. Conceptually:

```
0 (or PSK) ──HKDF-Extract──▶ Early Secret
                               │ Derive-Secret
(EC)DHE shared secret ──Extract──▶ Handshake Secret
                               │  ├─▶ client_handshake_traffic_secret
                               │  └─▶ server_handshake_traffic_secret
0 ──────────────────────Extract──▶ Master Secret
                               │  ├─▶ client_application_traffic_secret
                               │  ├─▶ server_application_traffic_secret
                               │  ├─▶ exporter_master_secret
                               │  └─▶ resumption_master_secret
```

Each `Derive-Secret` is `HKDF-Expand-Label(secret, label, transcript_hash)` — so every derived key is bound to both the secret *and* the handshake transcript so far. That transcript binding is what makes the handshake tamper-evident: if an attacker alters any handshake message, the derived keys diverge and the `Finished` MAC check fails. Separate handshake vs application secrets mean that even if handshake keys leak, application data has independent keys; and `resumption_master_secret` enables session tickets / 0-RTT without exposing the main secrets. This layered, transcript-bound derivation is a model of crypto-engineering done right.

#### Q62. [Practical] Implement HKDF-Expand manually in Java using HMAC, and explain each step.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

// RFC 5869 HKDF-Expand. PRK is the pseudorandom key from the Extract step.
public static byte[] hkdfExpand(byte[] prk, byte[] info, int outLen) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    int hashLen = 32;                                   // SHA-256 output size
    int n = (int) Math.ceil((double) outLen / hashLen); // number of T(i) blocks
    if (n > 255) throw new IllegalArgumentException("outLen too large");

    byte[] okm = new byte[n * hashLen];
    byte[] prev = new byte[0];                          // T(0) = empty
    for (int i = 1; i <= n; i++) {
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(prev);                               // T(i-1)
        mac.update(info);                               // context binding
        mac.update((byte) i);                           // 1-based counter
        prev = mac.doFinal();                           // T(i)
        System.arraycopy(prev, 0, okm, (i - 1) * hashLen, hashLen);
    }
    return Arrays.copyOf(okm, outLen);                  // truncate to requested length
}
```

Step by step: each output block `T(i) = HMAC(PRK, T(i-1) || info || i)`. Chaining `T(i-1)` into `T(i)` makes the stream pseudorandom and irreversible; folding in `info` binds the output to a context label (domain separation); the single-byte counter caps you at 255 blocks (255·32 = 8160 bytes max). You concatenate the `T(i)` blocks and truncate to the requested length. The Extract step (omitted here) would be `PRK = HMAC(salt, IKM)`. In production use a library's HKDF — this is for understanding the internals.

#### Q63. [Theory] What is the birthday bound, and how does it determine safe usage limits for nonces, tags, and hashes?

The **birthday paradox** says collisions in a space of size `2^n` become likely after only about `2^(n/2)` random samples — far fewer than intuition suggests. This single fact governs many crypto limits:

```
- Hash collision resistance: a 256-bit hash gives ~128-bit collision
  resistance (you expect a collision after ~2^128 hashes), which is why
  SHA-256 targets 128-bit collision security even though preimage is 256-bit.
- Random 96-bit GCM nonces: collisions become non-negligible near 2^48
  messages → keep usage well under 2^32 per key for safety margin.
- 128-bit auth tag: forgery/collision concerns scale with 2^64.
- 64-bit block ciphers (3DES): only ~2^32 blocks before block collisions
  leak data (the Sweet32 attack) — a key reason 3DES was retired.
```

The practical takeaway: the security level of a primitive against collision-type attacks is **half** its output size in bits. This is why you size hashes and tags at twice the security level you want, why random IVs for 64-bit-block ciphers are dangerous, and why high-volume systems must rotate keys or use counter-based nonces before approaching the birthday bound. Sizing decisions that ignore the birthday bound are a classic, subtle vulnerability.

#### Q64. [Theory] How does Poly1305 authenticate data, and how does it pair with ChaCha20?

**Poly1305** is a one-time **MAC** based on evaluating a polynomial modulo the prime `2^130 − 5`. The message is split into 16-byte chunks treated as coefficients; the MAC is `(c1·r^q + c2·r^(q-1) + … + cq·r) mod (2^130−5) + s mod 2^128`, where `(r, s)` is a one-time key.

```
- r, s are derived freshly per message (here from ChaCha20's keystream
  using the key + nonce), so the (r,s) pair is never reused.
- Evaluating the polynomial at the secret point r and adding s
  produces a 16-byte tag an attacker can't forge without (r,s).
```

The pairing in **ChaCha20-Poly1305** (RFC 8439) works like this: ChaCha20 generates a keystream from `(key, nonce)`; its first block produces the one-time Poly1305 `(r, s)` key, and the rest encrypts the plaintext. Poly1305 then authenticates the ciphertext plus any AAD. The "one-time" requirement is why the `(r, s)` must be unique per message — exactly why **nonce reuse breaks Poly1305 too** (reused keystream → reused MAC key → forgery). The beauty is that it's fast in pure software, constant-time by construction (no S-box table lookups), and needs no hardware acceleration — making it ideal for mobile and embedded devices.

#### Q65. [Practical] How do you derive multiple distinct keys from a single master secret correctly, and what is the pitfall of doing it wrong?

```java
// CORRECT: domain-separated derivation with distinct 'info' labels.
byte[] prk = hkdfExtract(salt, masterSecret);
byte[] encKey  = hkdfExpand(prk, "app-v1 encryption".getBytes(UTF_8), 32);
byte[] macKey  = hkdfExpand(prk, "app-v1 mac".getBytes(UTF_8),        32);
byte[] cookieKey = hkdfExpand(prk, "app-v1 cookie".getBytes(UTF_8),   32);
// Each key is cryptographically independent because the label differs.
```

The pitfall is **reusing one key for multiple purposes** or deriving keys without domain separation:

```java
// WRONG: same key for encryption AND MAC, or naive slicing without labels.
byte[] key = masterSecret;
// using 'key' for both AES-GCM and HMAC can create cross-protocol
// interactions; truncating the same secret for two uses with no
// context label means an attack on one usage can leak the other.
```

The rule is **key separation**: a key should be used for exactly one algorithm and purpose. Derive each via a KDF with a unique `info`/label so that compromise or cryptanalysis of one usage cannot help attack another. This also future-proofs rotation — you can rotate the encryption key without touching the cookie-signing key. Reusing a single key across encryption, MAC, and signing is a recurring real-world vulnerability (it enables, e.g., the RS256↔HS256 JWT confusion family of bugs).

### 🟠 — extended

#### Q66. [Theory] Explain GHASH and how the GCM authentication tag is actually computed.

GCM = **CTR mode for encryption + GHASH for authentication**. GHASH is a polynomial hash over the binary field `GF(2^128)`.

```
1. H = AES_encrypt(key, 0^128)          // the "hash subkey" (depends only on key)
2. Treat AAD and ciphertext as 128-bit blocks A1..Am, C1..Cn.
3. GHASH accumulates:  X(i) = (X(i-1) XOR block_i) · H   in GF(2^128)
   over AAD blocks, then ciphertext blocks, then a length block (len(AAD)||len(C)).
4. Tag = GHASH_result XOR AES_encrypt(key, J0)   // J0 = initial counter from nonce
```

The authentication is essentially evaluating a polynomial in `H` whose coefficients are the AAD and ciphertext blocks, then masking with an encrypted counter block. Two security-critical facts fall out of this structure: (1) `H` depends **only on the key**, not the nonce, which is why **nonce reuse leaks enough to recover H and forge tags** — once you know `H`, you control the polynomial; (2) GHASH is **not** a cryptographic hash by itself — it's a universal hash that's only secure because the result is encrypted with a per-message counter value. Understanding GHASH explains both why GCM is fast (field multiplication is cheap and parallelizable with PCLMULQDQ) and why it's so unforgiving of nonce reuse.

#### Q67. [Theory] What is AES-GCM-SIV and what problem does it solve over plain GCM?

**AES-GCM-SIV** (RFC 8452) is a **nonce-misuse-resistant** AEAD. Plain GCM catastrophically fails on nonce reuse (keystream reuse + authentication-key recovery → full break). GCM-SIV degrades gracefully instead.

```
Key idea: the IV/counter is derived from a synthetic value (SIV)
computed by a keyed hash (POLYVAL) over the plaintext + AAD + nonce.
   tag/IV = MAC(plaintext, AAD, nonce)   then encrypt with CTR using that.
```

Because the encryption counter is a function of the **plaintext itself**, reusing a nonce with *different* plaintexts still produces different keystreams. The only leakage under nonce reuse is the fact that two identical (plaintext, AAD, nonce) triples produce identical ciphertexts — i.e., you leak equality, nothing more. You never lose the authentication key or expose plaintext XORs. The trade-off: GCM-SIV is **two-pass** (it must process the plaintext to compute the synthetic IV before encrypting), so it's slightly slower and not streamable. Use it when you cannot guarantee unique nonces — distributed systems generating nonces independently, stateless services, or backup/restore scenarios where counters reset. It's the pragmatic safety net for the most common catastrophic GCM mistake.

#### Q68. [Theory] Walk through the Bleichenbacher / ROBOT attack on RSA PKCS#1 v1.5 and the modern defense.

**Bleichenbacher's attack (1998)** is an adaptive chosen-ciphertext attack against **RSA PKCS#1 v1.5** encryption padding. It's a padding oracle in the asymmetric world.

```
- The server decrypts and checks if the result has valid PKCS#1 v1.5
  padding (starts with 0x00 0x02 ...). If it reveals "valid"/"invalid",
  that's an ORACLE.
- The attacker multiplies the target ciphertext c by s^e (using RSA's
  multiplicative homomorphism), submits c·s^e, and learns whether the
  decryption of s·m falls in the "valid padding" range.
- Each query narrows the interval containing m. With enough queries
  (~thousands to millions) the attacker recovers the plaintext —
  e.g., the TLS pre-master secret — WITHOUT the private key.
```

**ROBOT (2017)** showed many TLS stacks were *still* vulnerable nearly 20 years later because the oracle leaked through subtle timing or error differences. Defenses:

1. **Eliminate the oracle:** make decryption behavior **identical** whether or not padding is valid — generate a random pre-master secret and proceed with it on padding failure, so the handshake fails later, uniformly, with no distinguishable signal.
2. **Use RSA-OAEP** instead of PKCS#1 v1.5 for new encryption.
3. **Prefer (EC)DHE key exchange** — TLS 1.3 removed RSA key exchange entirely, killing this attack class for the handshake.

The meta-lesson mirrors the symmetric padding oracle (Q33): any observable difference between "decryption failed for reason A" vs "reason B" is a potential oracle. AEAD and removing RSA key transport are the structural fixes.

#### Q68b notwithstanding — continuing numbering:

#### Q69. [Practical] Design a constant-time, oracle-free decryption path. What must be invariant?

```java
// Goal: an attacker observing ANY behavior cannot distinguish
//       "wrong key", "bad padding", "bad MAC", or "tampered" cases.
public byte[] safeDecrypt(byte[] blob, SecretKey key) {
    try {
        // 1. AEAD verifies the tag BEFORE returning any plaintext.
        //    A single AEADBadTagException covers all tamper cases.
        byte[] iv = Arrays.copyOfRange(blob, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return c.doFinal(blob, 12, blob.length - 12);
    } catch (Exception e) {
        // 2. UNIFORM failure: same exception type, same message,
        //    same log level, same response code/time for every failure.
        throw new SecurityException("decryption failed");
    }
}
```

What must be invariant across success-vs-failure and across different failure causes:

- **Error responses** — one generic error; never "bad MAC" vs "bad padding" vs "unknown key id".
- **Timing** — the failure path should take indistinguishable time; prefer AEAD where the tag check is the single gate, and avoid secret-dependent branching.
- **Side effects / logs** — don't log at different verbosity for different failures (logs can be a timing/observability oracle).
- **Memory access patterns** — no secret-dependent table lookups (use AES-NI).

The architectural principle: **collapse all failure modes into one indistinguishable outcome**. Padding oracles (Vaudenay/Lucky13), Bleichenbacher/ROBOT, and MAC-comparison timing attacks all exploit a *difference* the system unintentionally exposes. AEAD plus uniform error handling removes the difference at the source, which is far more robust than trying to equalize timing after the fact.

#### Q70. [Theory] How does a Hardware Security Module (HSM) protect keys, and what does "keys never leave the HSM" actually mean?

An **HSM** is a tamper-resistant hardware device that generates, stores, and uses cryptographic keys inside a hardened boundary. "Keys never leave" means the **private/secret key material is never exposed in plaintext outside the HSM's secure boundary** — not to the application, not to the OS, not to memory dumps.

```
App  ──"sign this digest with key #7"──▶  HSM
App  ◀──────────── signature ──────────   HSM   (key #7 never crosses the wire)
```

Mechanisms:
- **Operations, not keys, cross the boundary:** you send data *in* and get results *out*; the key stays inside. The app holds only a handle/label.
- **Tamper resistance/response:** physical intrusion (drilling, temperature, voltage glitching) triggers **zeroization** — the device wipes its keys.
- **FIPS 140-3 Level 3/4** validation certifies these physical and logical protections.
- **Access control & audit:** authenticated, role-separated, logged operations; often quorum (M-of-N) for sensitive actions.
- **Key wrapping:** if a key must be backed up/exported, it leaves only **wrapped** (encrypted) under another HSM-resident key.

The security benefit: even a fully compromised application server cannot exfiltrate the key — the attacker can *use* it while they have access (which is why rate limiting, monitoring, and revocation still matter) but cannot *steal* it for offline use. This is the root-of-trust anchor for CAs, payment systems (PCI requires HSMs), and cloud KMS backends.

#### Q71. [Theory] What is a length-extension attack, step by step, and which constructions resist it?

A **length-extension attack** exploits Merkle–Damgård hashes (MD5, SHA-1, SHA-256, SHA-512) used naively as `MAC = H(secret || message)`.

```
Attacker knows:  H(secret || message)  and  len(secret || message)
                 (but NOT secret itself)

Because the digest IS the internal chaining state after processing
the input, the attacker can:
1. Set the hash's internal state = the known digest.
2. Append the original padding (which they can compute from the length)
   plus arbitrary 'extension' bytes.
3. Continue the compression function to get
      H(secret || message || padding || extension)
   — a VALID MAC for an extended message, with no knowledge of secret.
```

This lets an attacker forge `(message || extra, valid_mac)` — e.g., appending `&admin=true` to a signed request. Resistant constructions:

- **HMAC** — its nested `H((K⊕opad) || H((K⊕ipad) || m))` structure means the output is *not* the raw internal state, so you can't extend it.
- **SHA-3 (Keccak)** — the sponge's capacity bits are never output, so the full state is hidden.
- **Truncated hashes** like **SHA-512/256** — truncation discards part of the state, breaking the attack.
- **BLAKE2/BLAKE3** — designed with keyed modes immune to extension.

The practical rule: never roll your own `H(secret || msg)` MAC over a Merkle–Damgård hash — use HMAC or a built-in keyed hash / AEAD.

#### Q72. [Practical] How do you implement crypto-agility for stored ciphertext so old and new algorithms coexist? Show the envelope format.

```java
// Self-describing ciphertext envelope: the algorithm + key version
// travel WITH the data so any reader knows how to decrypt it.
record CryptoEnvelope(byte algoId, int keyVersion, byte[] iv, byte[] ciphertext) {

    private static final byte ALG_AES256_GCM    = 1;
    private static final byte ALG_CHACHA20_POLY = 2;
    private static final byte ALG_AES256_GCM_SIV = 3;   // added later, no migration needed

    byte[] serialize() {
        var buf = java.nio.ByteBuffer.allocate(1 + 4 + 1 + iv.length + ciphertext.length);
        buf.put(algoId).putInt(keyVersion).put((byte) iv.length).put(iv).put(ciphertext);
        return buf.array();
    }

    static byte[] decrypt(byte[] blob, KeyProvider keys) throws Exception {
        var buf = java.nio.ByteBuffer.wrap(blob);
        byte algoId = buf.get();
        int keyVersion = buf.getInt();
        byte[] iv = new byte[buf.get()]; buf.get(iv);
        byte[] ct = new byte[buf.remaining()]; buf.get(ct);

        SecretKey key = keys.forVersion(keyVersion);     // resolve by version
        String transform = switch (algoId) {             // resolve by algorithm
            case ALG_AES256_GCM, ALG_AES256_GCM_SIV -> "AES/GCM/NoPadding";
            case ALG_CHACHA20_POLY -> "ChaCha20-Poly1305";
            default -> throw new IllegalStateException("unknown algoId " + algoId);
        };
        Cipher c = Cipher.getInstance(transform);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }
}
```

The design principles: **tag every ciphertext with `algoId` + `keyVersion`** so decryption is self-describing; **always write with the current default** but **read any supported version**; **add a new algorithm by adding a case**, not by migrating existing data; and **deprecate old algorithms by refusing to write them** while still being able to read until a background re-encryption job retires them. This is exactly how you'd stage a **classical → hybrid PQC** migration: add an `ALG_X25519_MLKEM768` envelope type, switch new writes to it, and lazily upgrade old records — no flag day.

### 🔴 — extended

#### Q73. [Theory] Explain how a fault-injection or glitch attack can extract an RSA private key from CRT-RSA, and the countermeasure.

**CRT-RSA** speeds up RSA signing ~4x by computing modulo `p` and `q` separately (Chinese Remainder Theorem) instead of modulo `n`:

```
s_p = m^(d mod p-1) mod p
s_q = m^(d mod q-1) mod q
s   = CRT_combine(s_p, s_q)   // recombine into the full signature
```

The **Boneh–DeMillo–Lipton fault attack** (a.k.a. the "Bellcore attack"): if an attacker induces a **hardware fault** (voltage glitch, clock glitch, laser, EM pulse) during *one* of the two branches — say `s_q` is corrupted to `s_q'` but `s_p` is correct — then the faulty signature `s'` satisfies `s'^e ≡ m (mod p)` but `s'^e ≢ m (mod q)`.

```
gcd(s'^e − m, n) = p     ← factors n instantly!
```

A single faulty signature reveals `p`, factoring `n` and exposing the entire private key. Countermeasures:

- **Verify before release:** after signing, recompute `s^e mod n` and check it equals `m`; if not, suppress the output (don't emit a faulty signature).
- **Redundant computation / consistency checks** in both CRT branches.
- **Hardware countermeasures:** glitch detectors, sensors, shielding (this is why secure elements and smartcards harden against fault injection).

The broad lesson for staff-level work: cryptographic security depends not just on the math but on the **integrity of the computation**. Physical and fault attacks bypass the math entirely, which is why high-assurance signing happens in HSMs/secure elements with fault detection, and why you always verify a signature before releasing it.

#### Q74. [Theory] How do lattice-based KEMs like ML-KEM (Kyber) work conceptually, and why are they quantum-resistant?

**ML-KEM** (FIPS 203, derived from CRYSTALS-Kyber) is a key-encapsulation mechanism whose security rests on the **Module Learning With Errors (MLWE)** problem rather than factoring or discrete log.

```
Core hard problem (LWE):  given (A, b = A·s + e),  recover the secret s,
   where A is a public matrix, s is a small secret vector, and e is small
   random "noise". The noise makes solving the linear system intractable.

KEM sketch:
  KeyGen:   public key (A, b=A·s+e),  secret key s
  Encaps:   pick small r, compute u = Aᵀ·r + e1,  v = bᵀ·r + e2 + encode(K)
            send (u, v); shared secret derived from K
  Decaps:   K ≈ v − sᵀ·u   (the noise terms nearly cancel; rounding recovers K)
```

It's quantum-resistant because **Shor's algorithm doesn't apply** — Shor breaks the *hidden subgroup / period-finding* structure behind factoring and discrete log, but lattice problems (LWE, SVP/CVP) have no known efficient quantum algorithm; the best quantum speedups are modest (Grover-like), not exponential. Practical engineering points for 2026:

- ML-KEM is used for **key establishment**, paired with a classical exchange in **hybrid** mode (`X25519 + ML-KEM-768`) so a flaw in either still leaves you protected.
- It has **larger public keys/ciphertexts** (~1KB+) than ECC — a bandwidth cost driving handshake-size optimizations.
- It's an **IND-CCA2 KEM** (via the Fujisaki–Okamoto transform), so it derives a symmetric key safely; you then run normal AEAD.
- Signatures use a sibling, **ML-DSA (Dilithium)**, on similar lattice assumptions.

The strategic point: lattice crypto is the leading post-quantum family precisely because it has decades of cryptanalysis, reasonable performance, and resists the quantum algorithm (Shor) that demolishes RSA/ECC.

#### Q75. [Theory] What is a chosen-ciphertext attack (CCA) and what does IND-CCA2 security guarantee?

Cryptographic security is defined via **games** between a challenger and an adversary. The security goals form a hierarchy:

```
IND-CPA  (chosen-plaintext):  attacker can encrypt anything, must not
         distinguish encryptions of two chosen messages.
IND-CCA1 (lunchtime):         + a decryption oracle BEFORE the challenge.
IND-CCA2 (adaptive):          + a decryption oracle BEFORE AND AFTER the
         challenge (on any ciphertext except the challenge itself).
```

**IND-CCA2** ("indistinguishability under adaptive chosen-ciphertext attack") is the gold standard. The guarantee: even an attacker who can get **arbitrary other ciphertexts decrypted** — adaptively, choosing them based on what they've seen, including after receiving the challenge ciphertext — still cannot learn anything about the challenge plaintext, not even a single bit.

Why it's the right bar in practice: real systems *are* decryption oracles (a TLS server decrypts whatever you send; a service decrypts tokens). Bleichenbacher (Q68) and padding oracles (Q33) are exactly failures of CCA security — the system acted as a decryption/validity oracle. **AEAD schemes (AES-GCM, ChaCha20-Poly1305) and RSA-OAEP are designed to be IND-CCA2**, which is why they reject any ciphertext that wasn't legitimately produced (the tag check). When evaluating a construction, asking "is this IND-CCA2?" is the rigorous version of "can an attacker abuse it as an oracle?" — and the answer must be yes for anything handling adversary-supplied ciphertext.

#### Q76. [Theory] Explain a cache-timing attack on software AES and why AES-NI / bitslicing defends against it.

Naive software AES implements the S-box and MixColumns via **precomputed lookup tables** (T-tables) in memory. The problem: which table entries you access depends on the **secret key and data**.

```
- T-table lookup:  index = state_byte (secret-dependent)
- A cache line holds several table entries. Whether index falls in a
  cached vs uncached line affects access TIME.
- An attacker sharing the cache (co-resident VM, hyperthread, or via
  Flush+Reload / Prime+Probe) measures which cache lines were touched
  -> infers the secret-dependent indices -> recovers key bytes.
```

This is a **microarchitectural side channel** — the math is untouched, but the *memory access pattern* leaks the key. Documented attacks recovered AES keys across VMs in cloud environments. Defenses:

- **AES-NI** (hardware AES instructions): the round operations execute in dedicated CPU circuitry with **no data-dependent memory lookups and constant time**, eliminating the cache channel entirely. This is the primary defense and why hardware AES is a security feature, not just a speed one.
- **Bitslicing / constant-time software AES:** compute the S-box with bitwise logic instead of table lookups, so there are no secret-dependent memory accesses. Slower but constant-time on CPUs without AES-NI.
- **ChaCha20** as an alternative: it's an ARX (add-rotate-xor) design with **no lookup tables**, so it's naturally constant-time in software — which is exactly why TLS 1.3 offers it for AES-NI-less devices.

The staff-level insight: "constant-time" must include **memory access patterns**, not just branch-free control flow. Table-driven crypto is a side-channel liability; prefer hardware instructions or table-free designs.

#### Q77. [Theory] How does Certificate Transparency's Merkle tree provide cryptographic proof of inclusion and consistency?

CT logs are **append-only Merkle hash trees**, and the Merkle structure is what makes the log *verifiable* rather than merely *trusted*.

```
A Merkle tree hashes leaves (certs) pairwise up to a single root hash (STH):
        Root (Signed Tree Head)
       /            \
     H(0,1)        H(2,3)
    /    \         /    \
  H(c0) H(c1)   H(c2)  H(c3)
```

Two proofs fall out of this structure, both `O(log n)` in size:

- **Inclusion proof (audit path):** to prove cert `c2` is in a log of `n` entries, the log provides the sibling hashes along the path from `c2`'s leaf to the root (`H(c3)` and `H(0,1)`). The verifier recomputes the root and checks it matches the signed STH. This proves the cert is genuinely logged — backing the **SCT** the browser requires.
- **Consistency proof:** given an old STH (tree size `m`) and a new STH (size `n > m`), the log provides hashes proving the new tree is a strict **append-only superset** of the old one — nothing was deleted or modified, only added.

Why this matters: a malicious log operator can't **equivocate** (show different roots to different parties) or **retroactively edit** history without detection, because auditors/monitors cross-check STHs (gossip protocols) and consistency proofs. CT thus achieves **detect-not-prevent** integrity at internet scale: any mis-issued certificate becomes a permanent, publicly provable entry. The same Merkle-log pattern underpins transparency systems beyond certs (binary transparency, key transparency like in messaging apps).

#### Q78. [Practical] Architect a verifiable, tamper-evident audit log for a security-sensitive system. What cryptography do you use?

The goal is an append-only log where any tampering (deletion, modification, reordering) of past entries is **cryptographically detectable**, even by an insider with write access.

```
Hash-chaining (blockchain-of-records, no consensus needed):
  entry_i = { data_i, timestamp_i, prevHash = H(entry_(i-1)) }
  H(entry_i) chains every record to its predecessor.
  Tampering with entry_k breaks H for k and every entry after it.
```

A robust design layers several primitives:

- **Hash chain** links each entry to the previous one, so altering any record invalidates all subsequent hashes (like Q77's Merkle consistency, applied to logs).
- **Periodic Merkle tree + signed checkpoint:** batch entries into a Merkle tree, sign the root with an **HSM-held key**, and publish/anchor the signed root somewhere the operator can't quietly rewrite (a second system, a notary, even a public blockchain or CT-style log). This gives `O(log n)` inclusion proofs and prevents the operator from rewriting both the log and its own checkpoints.
- **HMAC or signatures per entry** to bind authorship; signatures give **non-repudiation** (insider can't deny writing an entry).
- **Forward-secure / append-only keys** so that compromising the current key doesn't let an attacker forge **past** entries (e.g., evolve the MAC key with a one-way function each period and delete the old one).
- **External anchoring:** periodically publish the signed root hash externally so even a full-system compromise can't rewrite already-anchored history.

The threat model that drives the design is the **malicious insider/administrator**: ordinary logging trusts whoever controls the storage. Cryptographic audit logs remove that trust — the math, plus an externally anchored signed checkpoint and forward-secure keys, make undetected retroactive tampering infeasible. This pattern underlies key transparency, certificate transparency, and tamper-evident financial/compliance ledgers.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q79. [Practical] Your AES-GCM decryption suddenly throws `AEADBadTagException` in production for some records but not others. How do you diagnose it?

`AEADBadTagException` means the authentication tag did not verify — the ciphertext, IV, AAD, or key the decryptor used does not match what was used to encrypt. It is *not* random corruption most of the time; it is almost always an input mismatch. Work through the candidates systematically:

```
Tag fails when ANY of these differ between encrypt and decrypt:
  1. KEY        — wrong key version / rotated key / wrong tenant key
  2. IV/NONCE   — truncated, re-ordered, or not stored/parsed correctly
  3. AAD        — associated data differs (e.g., a header value changed)
  4. CIPHERTEXT — bytes mangled in transit/storage
  5. TAG length — encrypted with 128-bit tag, decrypting with 96-bit spec
```

Concrete diagnostic steps:
- **Correlate the failures.** Do they all share a time window (a deploy / key rotation), a tenant, or a region? "Some records fail" after a deploy screams *key version mismatch* or a serialization change.
- **Check the envelope parsing.** A classic bug: storing `IV || ciphertext||tag` but slicing the IV with the wrong offset, or a charset/encoding round-trip (Base64 vs hex) corrupting bytes. Print lengths: `iv.length` must be exactly 12, the remaining length must equal `plaintext + 16` (tag).
- **Check AAD.** If you pass AAD (e.g., a record ID or version header) and that value changed or is computed differently on read, the tag fails even though key/IV/ciphertext are fine.
- **Check storage.** A `VARCHAR`/`TEXT` column instead of `BYTEA`/`BLOB`, or a non-binary-safe transport, silently mangles high bytes. Store ciphertext as binary or Base64, never as a raw string.

The mental model: GCM is doing its job — it is *refusing to return tampered or mismatched plaintext*. The bug is almost never in AES; it is in how you stored/retrieved the IV, key, or AAD.

#### Q80. [Practical] A password reset works locally but every user's `matches()` returns false in production. What are the likely causes?

When `encoder.matches(raw, stored)` returns false universally, the stored hash and the verification path disagree. Likely root causes, most common first:

1. **The stored hash got mangled by the database column.** A bcrypt hash is 60 chars; if the column is `VARCHAR(50)` it is silently truncated, so it can never match. Argon2/PBKDF2 strings are longer still. Check the column width and that nothing trimmed/trailing-padded it.
2. **Double-encoding.** Some code path hashes an already-hashed value (e.g., a service hashes, then a framework hashes again on save), so the stored value is `H(H(password))` but login only computes `H(password)`.
3. **Different encoder configuration / algorithm prefix.** If you stored `{bcrypt}...` but configured a bare `Argon2PasswordEncoder` (no delegating decoder), `matches` can't route to the right algorithm. Use `DelegatingPasswordEncoder` consistently so the `{id}` prefix selects the verifier.
4. **Charset mismatch.** Hashing `password.getBytes()` with the platform default charset on one machine (UTF-8) and another (e.g., Windows-1252) yields different bytes for non-ASCII passwords. Always pin `StandardCharsets.UTF_8`.
5. **Whitespace / normalization.** A trailing newline from reading the password out of a file/env, or Unicode normalization differences (NFC vs NFD) for accented characters.

```java
// Reproduce deterministically to isolate the layer:
String stored = userRepo.findByEmail(email).getPasswordHash();
System.out.println("stored len = " + stored.length() + " prefix=" + stored.substring(0, 8));
System.out.println("matches = " + encoder.matches(rawFromRequest, stored));
```

The fastest triage is to log the stored hash length and prefix (never the raw password): a truncated or unprefixed hash points straight at the column/config problem.

#### Q81. [Practical] How do you encrypt a large file (multiple GB) that does not fit in memory, with integrity protection?

You cannot `doFinal()` a multi-GB byte array — you will OOM, and a single GCM tag over a huge stream means you can't detect tampering until the very end (and can't safely process plaintext before verifying). The standard approaches:

```java
// Streaming AES-GCM with CipherInputStream/CipherOutputStream.
// Encrypts in chunks; the GCM tag is written at the end by close().
public static void encryptFile(Path in, Path out, SecretKey key) throws Exception {
    byte[] iv = new byte[12];
    SecureRandom.getInstanceStrong().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));

    try (OutputStream fileOut = Files.newOutputStream(out)) {
        fileOut.write(iv);                              // prepend IV (not secret)
        try (CipherOutputStream cos = new CipherOutputStream(fileOut, cipher);
             InputStream fileIn = Files.newInputStream(in)) {
            byte[] buf = new byte[1 << 16];             // 64KB chunks
            int n;
            while ((n = fileIn.read(buf)) != -1) cos.write(buf, 0, n);
        } // close() flushes the final block AND the auth tag
    }
}
```

Two important caveats:
- **The single-tag problem:** with one tag for the whole file, you can't trust *any* plaintext until the entire file is processed and the tag checks. For very large files, use a **chunked / framed** scheme: split into fixed-size frames, encrypt each as its own AEAD message with a per-frame nonce derived from a base nonce + frame counter, and include the frame index as AAD to prevent reordering/truncation. Libraries like **Tink's Streaming AEAD** and the **age** format implement exactly this.
- **`CipherInputStream` swallows the tag exception** in some older JDKs — it can return EOF instead of throwing on a bad tag. Prefer `CipherOutputStream` for encryption and verify behavior, or use a vetted streaming-AEAD library (Tink) rather than rolling your own framing.

The rule: for bulk data, frame it, authenticate each frame, and bind frame order — don't trust a half-decrypted stream.

#### Q82. [Coding] Write a Java method that hashes a file's contents with SHA-256 in a streaming, memory-efficient way.

```java
import java.security.MessageDigest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

public static String sha256File(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(path)) {
        byte[] buf = new byte[1 << 16];          // 64KB buffer — constant memory
        int read;
        while ((read = in.read(buf)) != -1) {
            digest.update(buf, 0, read);         // feed chunks incrementally
        }
    }
    return HexFormat.of().formatHex(digest.digest());
}
```

The key idea is `MessageDigest.update()` accepts data incrementally, so you never hold the whole file in memory — only a fixed 64KB buffer. `digest()` finalizes and returns the 32-byte hash, which `HexFormat` (Java 17+) renders as a 64-char hex string. This is exactly how checksums for large downloads/artifacts are computed. The same streaming pattern works for `Mac` (HMAC) by calling `mac.update(buf, 0, read)` in the loop. Always read in a buffer; never `digest.digest(Files.readAllBytes(path))` for large files.

#### Q83. [Practical] A teammate proposes storing user emails encrypted but still wants to look users up by exact email. How do you support that?

Standard randomized encryption (AES-GCM with a fresh IV) produces a *different* ciphertext every time, so you cannot `WHERE email_encrypted = ?` — the bytes won't match. You need a **deterministic, searchable** value separate from the confidential storage. The clean pattern is a **blind index**:

```
Store TWO columns:
  email_ciphertext  = AES-GCM(email)            // randomized, for confidentiality
  email_index       = HMAC(indexKey, normalize(email))  // deterministic, for lookup
```

- **Normalize first** (lowercase, trim) so `Alice@X.com` and `alice@x.com` map to the same index.
- **Use a keyed HMAC, not a plain hash.** A plain `SHA-256(email)` is offline-guessable (the email space is small and enumerable), so an attacker with the database could brute-force which rows hold which emails. HMAC with a secret `indexKey` (kept in KMS, separate from the encryption key) blocks offline correlation unless the key leaks.
- **Lookup:** compute `HMAC(indexKey, normalize(input))` in the app and query `WHERE email_index = ?`. You get O(1) exact-match lookups while the actual email stays encrypted.

```java
String index = HexFormat.of().formatHex(hmacSha256(indexKey, email.trim().toLowerCase().getBytes(UTF_8)));
// SELECT * FROM users WHERE email_index = :index
```

Trade-offs to call out: this supports **exact match only** — no `LIKE`, range, or sorting (those need order-preserving or fully-homomorphic schemes with much weaker security or heavy cost). Deterministic indexing also leaks **equality** (which rows share a value), which is usually fine for unique emails but leaks frequency for low-cardinality fields. For those, consider not indexing, or accept the leak knowingly.

#### Q84. [Theory] An endpoint compares an API key with `apiKey.equals(stored)`. Walk a junior engineer through why that is a vulnerability and the fix.

The vulnerability is a **timing side channel**. `String.equals` compares character by character and **returns as soon as it finds a mismatch**. That means a key whose first character is correct takes *measurably* longer to reject than one whose first character is wrong. An attacker who can time responses can recover the key one character at a time — turning an infeasible `62^n` brute force into a feasible `62 × n`.

```java
// VULNERABLE: early-exit comparison leaks how many leading chars matched
if (providedKey.equals(storedKey)) { ... }

// FIXED: constant-time comparison examines every byte regardless
boolean ok = MessageDigest.isEqual(
        providedKey.getBytes(StandardCharsets.UTF_8),
        storedKey.getBytes(StandardCharsets.UTF_8));
```

Two extra refinements I'd teach:
- **Hash the key before comparison** so you compare fixed-length digests, which also avoids leaking the key *length* through timing and means a database leak exposes only hashes: compare `HMAC(serverKey, provided)` against a stored HMAC.
- **Don't store raw API keys at all** — store a hash (they're high-entropy, so a fast hash like SHA-256 is acceptable here, unlike passwords), and look them up by a prefix/index, then constant-time compare the secret part.

The general principle: any time you compare a secret, the comparison time must not depend on *how much* of the secret matched.

### 🟡 — extended

#### Q85. [Practical] You're integrating with a partner whose API requires HMAC-signed requests with a timestamp. Design the request signing and the server-side verification.

This is a standard request-authentication scheme (the shape AWS SigV4, Stripe webhooks, etc. use). The signature proves the request came from a holder of the shared secret and (with the timestamp) limits replay.

```java
// CLIENT: build a canonical string and sign it.
String canonical = method + "\n" + path + "\n" + timestamp + "\n" + sha256Hex(body);
String signature = HexFormat.of().formatHex(hmacSha256(secret, canonical.getBytes(UTF_8)));
// Send headers: X-Timestamp, X-Signature
```

```java
// SERVER: recompute and verify in constant time, then check freshness.
public boolean verify(HttpRequest req, byte[] secret) throws Exception {
    long ts = Long.parseLong(req.header("X-Timestamp"));
    if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) return false; // 5-min window
    String canonical = req.method() + "\n" + req.path() + "\n" + ts + "\n" + sha256Hex(req.body());
    byte[] expected = hmacSha256(secret, canonical.getBytes(UTF_8));
    byte[] provided = HexFormat.of().parseHex(req.header("X-Signature"));
    return MessageDigest.isEqual(expected, provided);   // constant-time
}
```

Design points that matter in review:
- **Canonicalization must be deterministic and agreed.** Both sides must build the exact same string — same field order, same casing, same handling of trailing slashes and query-param ordering. Most signature bugs are canonicalization mismatches, not crypto bugs.
- **Sign a hash of the body**, not the raw body, so large payloads don't bloat the signing string, and bind method + path so a signature can't be replayed against a different endpoint.
- **Timestamp + short window** stops indefinite replay; add a server-side **nonce cache** within the window if you need exactly-once.
- **Constant-time signature comparison** (`MessageDigest.isEqual`).
- **Reject missing/empty signatures explicitly** — don't let a null signature pass an early-return.

#### Q86. [Practical] Your service's TLS handshake fails with `PKIX path building failed: unable to find valid certification path to requested target`. How do you fix it?

This Java error means the JVM's trust store does not contain a CA that chains to the server's certificate — i.e., trust, not connectivity. The systematic fix:

1. **Inspect the actual chain the server presents:**
   ```
   openssl s_client -connect host:443 -showcerts
   ```
   This shows the leaf and any intermediates. The single most common cause is a **missing intermediate certificate**: the server only sends the leaf, and the client can't bridge leaf → root. Fix it on the *server* by configuring the full chain (leaf + intermediates), not by hacking the client.
2. **If it's a private/internal CA**, the JVM simply doesn't trust it. Import the CA (not the leaf) into a trust store:
   ```
   keytool -importcert -alias internal-ca -file ca.crt -keystore truststore.jks
   ```
   and point the JVM at it with `-Djavax.net.ssl.trustStore=...`. Trust the **CA**, never pin the leaf in the trust store (it breaks on renewal).
3. **Debug what the JVM is actually doing:** `-Djavax.net.debug=ssl:handshake` prints the chain validation. Check the cert's validity dates and that the **SAN** matches the hostname (a `No subject alternative names matching ...` error is a different but related failure).
4. **Don't "fix" it by disabling verification.** Trust-all `TrustManager`s or `setHostnameVerifier((h,s)->true)` turn TLS into plaintext-against-MITM. That's the cardinal sin this error tempts people into.

The decision tree: missing intermediate → fix the server's chain; private CA → import the CA into the trust store; expired/wrong-host cert → reissue the cert. Never bypass validation.

#### Q87. [Coding] Implement envelope encryption end to end in Java: generate a DEK, encrypt data with it, then wrap the DEK with a KEK.

```java
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;

public class Envelope {
    record Sealed(byte[] wrappedDek, byte[] dekIv, byte[] dataIv, byte[] ciphertext) {}

    // KEK would normally live in a KMS/HSM; here it's a SecretKey for illustration.
    public static Sealed seal(byte[] plaintext, SecretKey kek) throws Exception {
        // 1. Generate a fresh per-message Data Encryption Key (DEK).
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey dek = kg.generateKey();

        // 2. Encrypt the data with the DEK (AES-GCM).
        byte[] dataIv = randomIv();
        Cipher dataCipher = Cipher.getInstance("AES/GCM/NoPadding");
        dataCipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(128, dataIv));
        byte[] ciphertext = dataCipher.doFinal(plaintext);

        // 3. Wrap (encrypt) the DEK with the KEK — in real life this is a KMS Encrypt call.
        byte[] dekIv = randomIv();
        Cipher wrapCipher = Cipher.getInstance("AES/GCM/NoPadding");
        wrapCipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(128, dekIv));
        byte[] wrappedDek = wrapCipher.doFinal(dek.getEncoded());

        return new Sealed(wrappedDek, dekIv, dataIv, ciphertext);
    }

    public static byte[] open(Sealed s, SecretKey kek) throws Exception {
        // 1. Unwrap the DEK with the KEK (KMS Decrypt).
        Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
        unwrap.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, s.dekIv()));
        SecretKey dek = new SecretKeySpec(unwrap.doFinal(s.wrappedDek()), "AES");

        // 2. Decrypt the data with the recovered DEK.
        Cipher dataCipher = Cipher.getInstance("AES/GCM/NoPadding");
        dataCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, s.dataIv()));
        return dataCipher.doFinal(s.ciphertext());
    }

    private static byte[] randomIv() throws Exception {
        byte[] iv = new byte[12];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        return iv;
    }
}
```

What this demonstrates: the **DEK** does the bulk data encryption (fast symmetric AES-GCM); the **KEK** only ever encrypts the small DEK. In production, steps 3 (`wrap`) and unwrap are calls to `kms.encrypt(dek)` / `kms.decrypt(wrappedDek)` so the KEK plaintext never enters your process. To rotate, you re-wrap the DEK under the new KEK without touching the (possibly huge) ciphertext — which is the whole reason envelope encryption exists.

#### Q88. [Practical] After enabling FIPS mode, your application crashes with "no such algorithm" errors. How do you triage and resolve this?

FIPS mode disables every non-approved algorithm in the provider, so any code path using a forbidden primitive throws `NoSuchAlgorithmException` (or a provider exception). Triage:

1. **Enumerate what broke.** The error names the algorithm. Common casualties: **MD5** (often used innocuously for non-security checksums/ETags), **plain DES/RC4**, **`SecureRandom` with a non-approved source**, and sometimes **bcrypt/Argon2** (not FIPS-approved — only **PBKDF2** is), and **Ed25519/X25519** on older validated builds.
2. **Separate security vs incidental uses.** MD5 used as a content fingerprint for caching is not a security control — but FIPS mode bans it wholesale. You either switch those to SHA-256 or route them through a non-FIPS provider explicitly if policy allows a documented exception.
3. **Use a validated provider correctly.** Ensure you're on **BouncyCastle FIPS (bc-fips)** or the **JDK in FIPS mode** and that the provider order is set so the FIPS provider is selected. Check `Security.getProviders()`.
4. **Swap to approved algorithms:** PBKDF2-HMAC-SHA256 (high iterations) for password hashing, NIST P-256/P-384 for ECC if your validated build predates Ed25519 approval, AES-GCM (approved) for encryption, SHA-2/SHA-3 for hashing.
5. **Test the whole dependency tree.** Third-party libraries often call MD5/bcrypt internally; FIPS mode surfaces them. Run integration tests under FIPS, not just unit tests.

The judgment to articulate: comply with the approved-algorithm list, replace incidental MD5 with SHA-256, isolate crypto behind an abstraction so swapping providers is localized, and document any place where FIPS forces a less-modern-but-approved choice (e.g., PBKDF2 instead of Argon2id).

#### Q89. [Coding] Write a constant-time check that a received webhook signature matches, handling hex decoding and length safely.

```java
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;

public static boolean verifyWebhook(byte[] secret, byte[] payload, String signatureHeader) {
    try {
        // 1. Decode the provided signature defensively (malformed hex -> reject, don't throw to caller).
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader);
        } catch (IllegalArgumentException badHex) {
            return false;
        }
        // 2. Compute the expected HMAC.
        byte[] expected = hmacSha256(secret, payload);

        // 3. Constant-time compare. isEqual handles unequal lengths without early data-dependent exit.
        return MessageDigest.isEqual(expected, provided);
    } catch (Exception e) {
        return false;   // uniform failure — never leak WHY it failed
    }
}

private static byte[] hmacSha256(byte[] key, byte[] msg) throws Exception {
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(msg);
}
```

Subtle points reviewers look for: the signature is computed over the **raw bytes** of the payload exactly as received (re-serializing the JSON would change whitespace/key order and break the signature — a top cause of webhook verification failures); malformed hex is caught and turned into a clean `false` rather than a thrown exception that might be handled differently (an oracle); and the comparison is `MessageDigest.isEqual`, not `Arrays.equals` or `String.equals`. Many providers (Stripe, GitHub) also include a timestamp in the signed string — add a freshness check to stop replay.

#### Q90. [Practical] A code review shows `new SecureRandom(seed)` where `seed` is a fixed value. Why is this dangerous, and what's the correct usage?

Passing a **fixed seed** to `SecureRandom` makes its output **deterministic** — the entire point of a CSPRNG (unpredictability) is destroyed. Anyone who knows or guesses the seed reproduces every "random" key, token, IV, or salt the generator ever produces.

```java
// DANGEROUS: deterministic — same seed => same "random" stream every run
SecureRandom bad = new SecureRandom("hardcoded-seed".getBytes());

// Also subtly risky on some platforms:
SecureRandom maybe = new SecureRandom();   // self-seeds from OS entropy (fine on modern JDKs)

// CORRECT: explicitly request a strong, OS-backed instance
SecureRandom good = SecureRandom.getInstanceStrong();
byte[] key = new byte[32];
good.nextBytes(key);
```

Key clarifications:
- `new SecureRandom()` **without** a seed is fine — it self-seeds from the OS entropy source. The danger is *supplying* a fixed/low-entropy seed, or worse, seeding it from `System.currentTimeMillis()` (trivially guessable).
- Don't call `setSeed(fixedValue)` either — on some implementations `setSeed` *replaces* entropy rather than adding to it.
- `getInstanceStrong()` maps to a blocking, high-quality source (e.g., `NativePRNGBlocking` / `/dev/random`-style) suitable for long-lived keys. For high-volume token generation, a single shared `new SecureRandom()` is fine and non-blocking; reseeding is automatic.

The reason this matters so much: a deterministic CSPRNG has caused real breaches — the Debian OpenSSL bug (2008) reduced the seed space so badly that all generated keys could be enumerated.

#### Q91. [Practical] You need to rotate the HMAC key used to sign session cookies without logging everyone out. How?

The problem: if you swap the signing key atomically, every existing cookie (signed with the old key) instantly fails verification and all users are logged out. The fix is to **verify against multiple keys during a transition window** while signing only with the new one.

```java
// Maintain a small ordered set of keys: the current signing key + recent previous keys.
class CookieSigner {
    private final SecretKey signingKey;            // current — used to SIGN
    private final List<SecretKey> verifyKeys;      // current + previous — used to VERIFY

    String sign(String payload) { return payload + "." + hmacHex(signingKey, payload); }

    boolean verify(String payload, String mac) {
        byte[] given = HexFormat.of().parseHex(mac);
        for (SecretKey k : verifyKeys) {            // try current first, then olds
            if (MessageDigest.isEqual(hmac(k, payload), given)) return true;
        }
        return false;
    }
}
```

Rollout sequence:
1. Add the **new key** to the verify set (deploy everywhere) — but keep signing with the old key. Now both keys are *accepted*.
2. Flip the **signing** key to the new one. New cookies use the new key; old cookies still verify because the old key is still in the verify set.
3. After a full session-lifetime has elapsed (so all old cookies expired or were re-issued), **remove the old key** from the verify set.

This "accept old + new, then drop old" pattern is the general key-rotation recipe for any verification (JWT signing keys via `kid` + JWKS, webhook secrets, etc.). The same staged approach prevents lockout in every signature-rotation scenario. Optionally, when you successfully verify with an old key, **re-issue** the cookie signed with the new key so migration completes faster.

#### Q92. [Coding] Implement PBKDF2 password hashing and verification in Java, storing parameters in the hash string.

```java
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;

public class Pbkdf2 {
    private static final int ITERATIONS = 600_000;   // OWASP 2026 floor for HMAC-SHA256
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS   = 256;

    public static String hash(char[] password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS);
        // Self-describing format: algo$iterations$salt$hash
        return "pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    public static boolean verify(char[] password, String stored) throws Exception {
        String[] p = stored.split("\\$");
        int iterations = Integer.parseInt(p[1]);
        byte[] salt    = Base64.getDecoder().decode(p[2]);
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password, salt, iterations);
        return MessageDigest.isEqual(expected, actual);   // constant-time
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iter) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iter, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();   // zero the password material when done
        }
    }
}
```

Design notes: the **salt and iteration count are stored *in* the hash string**, so you can later raise iterations without breaking old hashes (verify with the stored count, then re-hash on next login at the new count). The password is a `char[]` (not `String`) so it can be **zeroed** with `clearPassword()` — `String` is immutable and lingers in the heap until GC. Verification is constant-time via `MessageDigest.isEqual`. In greenfield code prefer Argon2id; PBKDF2 here is the right answer when FIPS compliance is required.

#### Q93. [Practical] Logs show occasional `IllegalBlockSizeException: Input length must be multiple of 16` during decryption. What went wrong and how do you fix it?

That exception comes from a **block cipher in a padding-less or CBC mode** (`AES/CBC/NoPadding` or `AES/ECB/NoPadding`) receiving ciphertext whose length isn't a multiple of the 16-byte block. The realistic root causes:

1. **Truncated or corrupted ciphertext.** The stored/transmitted bytes were cut off — e.g., a `VARCHAR` column truncating binary data, or a transport that mangled trailing bytes. The decryptor gets, say, 30 bytes instead of 32.
2. **Encoding round-trip damage.** The ciphertext was Base64/hex encoded on write but decoded with the wrong scheme on read (URL-safe vs standard Base64, missing padding), yielding a wrong-length byte array.
3. **IV mixed into the length accounting incorrectly** — e.g., the code stored `IV || ciphertext` but on read forgot to strip the IV, so the remaining length is off by 16.
4. **Mode mismatch:** encrypting with `AES/GCM` (no block-size requirement) but decrypting with `AES/CBC/NoPadding`, or vice versa.

Fixes:
- **Move to `AES/GCM/NoPadding`** (a stream-style mode) which has *no* block-multiple requirement and adds integrity — most of these bugs disappear and tampering is caught with `AEADBadTagException` instead of a confusing length error.
- If you must use CBC, use **`AES/CBC/PKCS5Padding`** so arbitrary-length plaintext is padded to a block multiple on encrypt and stripped on decrypt.
- **Store ciphertext as binary** (`BYTEA`/`BLOB`) or as canonical Base64, and verify the byte length on read equals what you wrote.

The deeper takeaway: a block-size exception on decrypt almost always means the *bytes are not the bytes you encrypted* (truncation/encoding), not a crypto-algorithm problem — and switching to authenticated GCM both removes the constraint and turns silent corruption into an explicit failure.

### 🟠 — extended

#### Q94. [Practical] Design a token-based password reset flow. What does the token contain, how is it stored, and how do you prevent abuse?

A reset link emails the user a token that, when presented, authorizes a password change. The security requirements: the token must be **unguessable, single-use, short-lived, and bound to the account**, and a database leak must not let an attacker mint resets.

```
1. Generate: token = SecureRandom 32 bytes -> URL-safe Base64 (256-bit entropy).
2. Store ONLY a hash of it:  db row { userId, sha256(token), expiresAt, usedAt=null }.
3. Email the raw token in the link; never store the raw token.
4. On submit: hash the presented token, look it up, check not expired / not used,
   then set the new password and mark usedAt (single use).
```

```java
String raw = secureToken(32);                  // emailed to user
String stored = sha256Hex(raw);                // stored in DB
// verify:
var row = resetRepo.findByTokenHash(sha256Hex(presented));
if (row == null || row.used() || row.expired()) return reject();  // uniform error
```

Anti-abuse and pitfalls to call out:
- **Hash the token at rest** (it's high-entropy, so a fast SHA-256 is fine — unlike a password). A leaked reset table then yields nothing usable.
- **Single-use + short TTL** (e.g., 15–60 min). Invalidate on use *and* invalidate all outstanding reset tokens once a password actually changes.
- **Don't leak account existence.** "If an account exists, we sent an email" — return the same response whether or not the email is registered, to avoid user enumeration.
- **Rate-limit** reset requests per account/IP to prevent inbox flooding and token-guessing.
- **Bind and re-authenticate sensitive follow-ups** — after reset, invalidate existing sessions so a thief who triggered the reset is logged out everywhere.
- **Invalidate on email change** and require the *current* session or token, not just the new password.

#### Q95. [Practical] You must migrate millions of password hashes from unsalted SHA-1 to Argon2id without forcing every user to reset. What's your strategy?

You can't recover the original passwords from SHA-1, so you can't directly compute Argon2id of the password offline. The standard trick is **layered (nested) hashing now, transparent upgrade on login**.

```
Immediate (offline, no user interaction):
   newHash = Argon2id( SHA-1(password) )        // wrap the existing digest
   Mark the row as algo = "argon2id-over-sha1".

On next successful login (you finally see the plaintext password):
   1. Verify by computing Argon2id(SHA-1(input)) against stored.
   2. If it matches, RE-HASH with plain Argon2id(input) and store algo="argon2id".
```

Why this works and what to watch:
- **The wrap closes the immediate exposure.** The moment you wrap every SHA-1 digest in Argon2id, the fast-brute-forceable SHA-1 layer is no longer the outer (attackable) layer — an attacker with the leaked DB now faces Argon2id's cost, even before any user logs in.
- **`DelegatingPasswordEncoder`-style algorithm tags** let both formats coexist; the `{algo}` prefix routes each row to the right verifier.
- **Upgrade-on-use** transparently migrates active users to clean Argon2id with no reset.
- **Long-tail dormant accounts** stay in the wrapped form indefinitely, which is safe; optionally expire accounts that never log in.
- **Communicate and monitor:** track the percentage migrated, and after a long window consider forcing resets for the dormant remainder if policy requires a single clean algorithm.

This is the canonical "you inherited bad password storage" answer: wrap to stop the bleeding immediately, then upgrade-on-login to converge — never a mass forced reset if you can avoid it.

#### Q96. [Coding] Implement a tamper-evident hash chain for an audit log in Java, and a verifier that detects modification.

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HashChain {
    record Entry(long seq, String data, String prevHash, String hash) {}

    private final List<Entry> log = new ArrayList<>();
    private static final String GENESIS = "0".repeat(64);

    public Entry append(String data) throws Exception {
        long seq = log.size();
        String prev = log.isEmpty() ? GENESIS : log.get(log.size() - 1).hash();
        String hash = sha256(seq + "|" + data + "|" + prev);   // bind seq+data+prev
        Entry e = new Entry(seq, data, prev, hash);
        log.add(e);
        return e;
    }

    // Returns the index of the first tampered entry, or -1 if the chain is intact.
    public int verify() throws Exception {
        String prev = GENESIS;
        for (int i = 0; i < log.size(); i++) {
            Entry e = log.get(i);
            if (!e.prevHash().equals(prev)) return i;                 // chain link broken
            String recomputed = sha256(e.seq() + "|" + e.data() + "|" + e.prevHash());
            if (!e.hash().equals(recomputed)) return i;               // entry content altered
            prev = e.hash();
        }
        return -1;
    }

    private static String sha256(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }
}
```

Each entry's hash covers its sequence number, its data, and the **previous entry's hash**, so any modification to entry *k* changes `hash(k)`, which breaks `prevHash` of entry *k+1*, cascading to the end — the verifier catches it at the first altered record. To make it robust against an insider who can rewrite the whole table (and recompute all hashes), you must **anchor** it: periodically sign the latest hash with an HSM-held key and publish that signed checkpoint somewhere the operator can't silently rewrite (Q78). The chain alone proves *internal* consistency; the external signed anchor proves *no wholesale rewrite*.

#### Q97. [Practical] A pen-test reports your JWTs are vulnerable to the `alg:none` and RS256→HS256 confusion attacks. Explain both and the fixes.

Both attacks exploit a verifier that trusts the **`alg` field in the attacker-controlled token header** to decide how to verify.

```
alg:none:
   Attacker sets header {"alg":"none"}, supplies an empty signature.
   A naive library "verifies" by checking... nothing -> token accepted.

RS256 -> HS256 confusion:
   Server expects RS256 (verify with RSA public key).
   Attacker changes header to {"alg":"HS256"} and signs the token using
   the RSA PUBLIC key (which is, well, public) AS THE HMAC SECRET.
   A verifier that reads alg from the token will HMAC-verify with the
   public key it has on hand -> forged token accepted.
```

Fixes:
1. **Pin the algorithm in code; never read it from the token.** Configure the parser to accept *only* RS256 (or only ES256). The verifier's algorithm must be a server-side constant, not negotiated by the token.
   ```java
   Jws<Claims> jws = Jwts.parser()
       .verifyWith(rsaPublicKey)
       .sig().add(SignatureAlgorithms.RS256).and()  // only RS256 accepted
       .build()
       .parseSignedClaims(token);
   ```
2. **Reject `alg:none` outright** — never allow unsigned tokens in a security context.
3. **Separate key types by usage.** An RSA verification key must never be usable as an HMAC key; a library that keys verification by algorithm type prevents the confusion structurally.
4. **Use a maintained library** — modern jjwt/Nimbus default to rejecting `none` and require you to declare expected algorithms.

The root cause is "the token tells you how to check the token." The structural fix is that the **server decides the algorithm and key**, full stop.

#### Q98. [Practical] How would you detect and respond to a suspected private-key compromise for your TLS certificate?

A compromised private key means an attacker can impersonate your domain and decrypt traffic (for non-PFS sessions). This is an incident; act on contain → rotate → revoke → investigate.

```
1. CONTAIN:  assume the key is fully exposed. Identify everywhere it's deployed
             (load balancers, CDNs, services) — your crypto inventory pays off here.
2. ROTATE:   generate a NEW key pair (don't reuse), get a new certificate issued,
             deploy it across all endpoints.
3. REVOKE:   revoke the OLD certificate via the CA (so CRL/OCSP marks it invalid).
4. INVALIDATE downstream artifacts that trusted the key.
```

Detection signals and response details:
- **Detect** via Certificate Transparency monitoring (an unexpected cert for your domain in CT logs = possible mis-issuance/compromise), IDS alerts, leaked-secret scanners hitting your key in a repo/paste, or anomalous traffic.
- **Forward secrecy limits the blow:** because TLS 1.3 mandates ECDHE, a stolen long-term key does **not** decrypt past recorded sessions — it only enables *future* impersonation until revoked. This is exactly why PFS matters operationally.
- **Revocation realities:** OCSP/CRL propagation is imperfect and some clients soft-fail. **Short-lived certificates** (ACME, 90-day or shorter) reduce the window structurally — the 2026 best practice is short validity + automation so a compromise self-heals quickly.
- **Post-incident:** rotate any other secrets that shared the compromised host, run a blameless post-mortem, store keys in an HSM/KMS going forward so the raw key can't be exfiltrated again, and add CT monitoring if it wasn't already in place.

#### Q99. [Coding] Show how to verify a digital signature (RSA-PSS) over a file in Java, including loading the public key.

```java
import java.security.*;
import java.security.spec.*;
import java.nio.file.*;
import java.util.Base64;

public class VerifySignature {

    // Load an X.509-encoded RSA public key (e.g., from a .der/.pem-decoded file).
    public static PublicKey loadRsaPublicKey(byte[] x509Der) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(new X509EncodedKeySpec(x509Der));
    }

    public static boolean verify(Path file, byte[] signatureBytes, PublicKey pub) throws Exception {
        Signature sig = Signature.getInstance("RSASSA-PSS");
        sig.setParameter(new PSSParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));   // PSS params must match signer
        sig.initVerify(pub);

        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) sig.update(buf, 0, n);    // stream large files
        }
        return sig.verify(signatureBytes);   // true iff signature is authentic & file untampered
    }
}
```

Points worth raising: **RSA-PSS** (`RSASSA-PSS`) is the modern, randomized RSA signature scheme — prefer it over the legacy `SHA256withRSA` (PKCS#1 v1.5). The **PSS parameters (hash, MGF, salt length) must match what the signer used**, or verification fails even on a valid signature — a frequent integration bug. The file is streamed through `Signature.update()` so it works for large artifacts without loading them into memory. `verify()` returns a boolean rather than throwing on mismatch, so handle `false` as "untrusted, reject." Loading uses `X509EncodedKeySpec` for the standard public-key encoding. For new systems, **Ed25519** (`Signature.getInstance("Ed25519")`) is even better — no parameters to misconfigure.

#### Q100. [Practical] Your encryption throughput is far below the hardware's capability. How do you profile and fix crypto performance?

Crypto rarely *should* be your bottleneck on modern hardware (AES-NI does many GB/s), so slow throughput usually points at a misuse pattern. Profile, then attack the common culprits:

```
Common throughput killers:
  1. Re-creating Cipher/MessageDigest/KeyGenerator per call (expensive init).
  2. Using a slow mode/algorithm without hardware accel (software AES, or
     RSA on bulk data instead of symmetric).
  3. Blocking SecureRandom (getInstanceStrong / /dev/random) per operation.
  4. Tiny I/O buffers (per-byte update calls) instead of large chunks.
  5. Lock contention on a shared non-thread-safe object.
```

Fixes and how to verify:
- **Reuse and pool objects where safe.** `Cipher` is not thread-safe, so pool per-thread instances (`ThreadLocal<Cipher>`) rather than `Cipher.getInstance` on every request; `SecureRandom` *is* thread-safe — share one instance.
- **Confirm AES-NI is active.** On the JVM, the HotSpot intrinsics for AES/GHASH should kick in automatically on supported CPUs; verify with `-XX:+PrintIntrinsics` or that the `_aescrypt_*` intrinsics are present. In containers/VMs make sure the CPU feature is passed through.
- **Use symmetric crypto for bulk data**; never RSA-encrypt large payloads directly (that's what envelope encryption is for).
- **Avoid `getInstanceStrong()` in hot paths** — a plain shared `SecureRandom` self-seeds once and is non-blocking; reserve the blocking strong source for long-lived key generation.
- **Batch I/O** into 32–64KB chunks fed to `update()`; per-byte calls dominate with overhead.
- **Pick ChaCha20-Poly1305** on hardware *without* AES-NI, where software AES is both slow and side-channel-prone.

Measure with a JMH microbenchmark isolating the crypto call, compare to the theoretical AES-NI rate, and the gap usually points straight at object re-creation or a blocking RNG.

### 🔴 — extended

#### Q101. [Practical] Architect a multi-region, low-latency encryption setup where data is encrypted in one region and decrypted in another. What are the key-distribution challenges?

The core tension: you want **fast, local** encryption/decryption in every region, but a **single source of truth** for keys, without shipping plaintext key material across regions insecurely.

```
Pattern: regional KMS replicas + envelope encryption.
  - A multi-region KEK (e.g., AWS KMS multi-region key, or replicated key
    material in each region's KMS/HSM) so any region can wrap/unwrap DEKs locally.
  - Data is encrypted with a DEK (local, fast AES-GCM); the DEK is wrapped
    by the regional KEK replica. The wrapped DEK travels WITH the data.
  - Any region decrypts by asking its LOCAL KMS to unwrap the DEK — no
    cross-region call on the hot path.
```

Challenges and how to address them:
- **Key replication vs latency:** unwrapping a DEK per request via a remote KMS adds cross-region RTT. Solve with **local KMS replicas** and **DEK caching** (cache the *plaintext* DEK briefly in memory with a short TTL, never on disk) so most operations are local.
- **Consistency on rotation:** when you rotate the KEK, all regions must converge on the new version. Use **versioned keys** so a record wrapped under v3 in region A still unwraps in region B that also has v3; never destroy an old KEK version until every region has migrated.
- **Data residency / sovereignty:** some data legally cannot leave a region. Use **per-region keys** and keep both data and its keys in-region; replicate only metadata. Crypto-shredding (delete the regional key) then enforces regional deletion.
- **Blast radius:** a per-region or per-tenant KEK means a compromise in one region doesn't expose another. Separate trust boundaries per region.
- **Clock/version skew:** propagate key metadata (which versions exist, which is default-for-write) via a consistent control plane so a region doesn't write under a key another region can't yet read.

The crisp summary: **wrapped DEKs travel with the data, KEKs are replicated (or per-region) in each region's KMS, plaintext DEKs are cached briefly and locally, and everything is versioned** so rotation and multi-region reads coexist without cross-region hot-path calls.

#### Q102. [Practical] Lead the design of a "harvest now, decrypt later" mitigation for your most sensitive long-lived data and traffic. What concrete steps in 2026?

"Harvest now, decrypt later" (HNDL) means adversaries record your encrypted traffic/data today and decrypt it once a cryptographically relevant quantum computer exists. The data most at risk is anything whose **confidentiality must outlast the quantum timeline** (decades-long secrets: health records, state secrets, long-term IP, root keys). Concrete 2026 program:

```
Prioritize by SHELF LIFE, not just sensitivity:
   risk = (years the data must stay secret) vs (years until quantum threat)
   Long-lived + currently RSA/ECC-protected = migrate first.
```

1. **Inventory (cryptographic bill of materials).** Catalog where RSA/ECC/DH protect long-lived secrets — in transit (TLS), at rest, in signatures, in key exchange. You can't migrate what you can't find; this is why crypto-agility (Q48) is the prerequisite.
2. **Deploy hybrid key exchange now for traffic.** Turn on **X25519 + ML-KEM-768** hybrid in TLS where supported (major browsers, load balancers, and cloud providers ship it in 2026). Hybrid means an attacker must break *both* the classical and the PQC part — so you keep classical assurance while gaining quantum resistance against HNDL on recorded traffic.
3. **Re-encrypt long-lived data at rest under PQC-protected key wrapping.** Move KEK wrapping toward ML-KEM-based KEMs (or hybrid) for data that must remain secret beyond the threat horizon; symmetric data encryption (AES-256-GCM) is already quantum-safe — just ensure 256-bit keys.
4. **Upsize symmetric/hash parameters.** AES-256 (Grover only halves it → ~128-bit effective, still safe) and SHA-384/512; avoid AES-128 for long-lived secrets.
5. **Signatures: plan but don't panic.** Signatures aren't HNDL-vulnerable (a forged signature in the future doesn't retroactively break a past authentication), so migrate signing (ML-DSA/SLH-DSA) on a slower track than confidentiality — but do it for anything verifying long-lived artifacts (firmware, code signing) whose trust must persist.
6. **Crypto-agility everywhere** so you can swap parameters as standards firm up, and **monitor NIST/industry** guidance.

The leadership message: this is a *prioritized, multi-year, hybrid* migration driven by data shelf-life — start with recorded-traffic confidentiality (hybrid TLS) and long-lived at-rest secrets, because those are the only things an HNDL adversary can exploit.

#### Q103. [Theory] A vendor offers "unbreakable" encryption using a proprietary, secret algorithm. As the security lead, how do you evaluate this claim?

This should trigger immediate skepticism — it violates **Kerckhoffs's principle**: a cryptosystem must be secure even if everything about it *except the key* is public. "Security through obscurity" of the algorithm itself is a red flag, not a feature.

How I'd evaluate and respond:
- **Demand the algorithm be public and standard.** Real-world trust in AES, SHA-2, ChaCha20, and the PQC standards comes from *decades of public cryptanalysis* by adversarial experts. A secret algorithm has had none — "no one has broken it" really means "no one qualified has been allowed to try." History is littered with proprietary ciphers (A5/1, KeeLoq, MIFARE Crypto-1, many DRM schemes) that were trivially broken once reverse-engineered.
- **"Unbreakable" is a categorical red flag.** No serious cryptographer claims a system is unbreakable (the lone exception, the one-time pad, has crippling key-distribution requirements and is almost never what these vendors mean). Modern crypto is about *computational infeasibility under stated assumptions*, with explicit security proofs — not absolutes.
- **Ask for the artifacts of legitimacy:** peer-reviewed publication, a security proof/reduction to a known-hard problem, independent third-party audits, standardization (NIST/IETF), and **FIPS 140-3 validation** of the implementation. Absence of all of these is disqualifying.
- **Insist on key management, not just the cipher.** Even a sound algorithm fails with bad key handling; a vendor hand-waving the algorithm usually hand-waves this too.

The recommendation I'd give leadership: **reject it** and use standardized, publicly vetted, validated primitives. The value of an algorithm is proportional to how hard qualified adversaries have *publicly tried and failed* to break it — secrecy of the algorithm subtracts from trust rather than adding to it.

#### Q104. [Coding] Implement Shamir's Secret Sharing (split and reconstruct) in Java for splitting a master key into N shares with threshold K.

```java
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

public class Shamir {
    // A 257-bit prime > any 256-bit secret; all arithmetic is mod PRIME.
    private static final BigInteger PRIME = BigInteger.TWO.pow(257).subtract(BigInteger.valueOf(93));
    private static final SecureRandom RNG = new SecureRandom();

    // Split secret into n shares; any k reconstruct it. Returns map x -> y.
    public static Map<Integer, BigInteger> split(BigInteger secret, int n, int k) {
        if (secret.compareTo(PRIME) >= 0) throw new IllegalArgumentException("secret too large");
        // Random degree-(k-1) polynomial with f(0) = secret.
        BigInteger[] coeff = new BigInteger[k];
        coeff[0] = secret;
        for (int i = 1; i < k; i++) coeff[i] = new BigInteger(256, RNG).mod(PRIME);

        Map<Integer, BigInteger> shares = new LinkedHashMap<>();
        for (int x = 1; x <= n; x++) {                 // evaluate f(x) for x = 1..n
            BigInteger y = BigInteger.ZERO, xb = BigInteger.valueOf(x), pow = BigInteger.ONE;
            for (int i = 0; i < k; i++) {
                y = y.add(coeff[i].multiply(pow)).mod(PRIME);
                pow = pow.multiply(xb).mod(PRIME);
            }
            shares.put(x, y);
        }
        return shares;
    }

    // Reconstruct f(0) via Lagrange interpolation over any k shares.
    public static BigInteger reconstruct(Map<Integer, BigInteger> shares) {
        BigInteger secret = BigInteger.ZERO;
        List<Integer> xs = new ArrayList<>(shares.keySet());
        for (int i = 0; i < xs.size(); i++) {
            BigInteger xi = BigInteger.valueOf(xs.get(i));
            BigInteger num = BigInteger.ONE, den = BigInteger.ONE;
            for (int j = 0; j < xs.size(); j++) {
                if (i == j) continue;
                BigInteger xj = BigInteger.valueOf(xs.get(j));
                num = num.multiply(xj.negate()).mod(PRIME);            // (0 - xj)
                den = den.multiply(xi.subtract(xj)).mod(PRIME);        // (xi - xj)
            }
            BigInteger lagrange = num.multiply(den.modInverse(PRIME)).mod(PRIME);
            secret = secret.add(shares.get(xs.get(i)).multiply(lagrange)).mod(PRIME);
        }
        return secret.mod(PRIME);
    }
}
```

The scheme builds a random degree-`(k-1)` polynomial whose constant term `f(0)` is the secret; each share is a point `(x, f(x))`. Any `k` points uniquely determine the polynomial (Lagrange interpolation recovers `f(0)`), while `k-1` points leave the secret information-theoretically hidden — every possible secret is equally consistent with `k-1` shares. All arithmetic is in a prime field so the math is exact and the interpolation has a modular inverse. Real use: splitting a KMS root key or recovery key among officers so any `k` of `n` can reconstruct it (Q46), eliminating a single point of compromise. In production use a vetted library (the modular inverse, field size, and constant-time concerns are easy to get subtly wrong).

#### Q105. [Theory] Your org wants threshold signatures (no single machine ever holds the full signing key) for a high-value signing service. Compare Shamir-based reconstruction vs MPC/TSS and recommend.

Both aim to remove the single-point-of-compromise of a monolithic signing key, but they differ in whether the full key ever **materializes**:

```
Shamir + reconstruct-to-sign:
   shares held by N parties -> at sign time, K shares are COMBINED on one
   machine to rebuild the full key -> sign -> discard.
   ⚠ The full key EXISTS in memory on that machine at signing time.

MPC / Threshold Signature Scheme (TSS):
   K parties run a multi-party protocol that PRODUCES a valid signature
   WITHOUT ever assembling the full private key anywhere. Each party holds
   a key share for the lifetime of the key; signing is a joint computation.
```

Comparison:
- **Exposure window:** Shamir reconstruction creates a moment where the complete key sits on one host — a juicy target (memory scraping, a compromised signer). TSS never reconstructs the key, so there is *no* single machine to compromise for the full key at any time. This is the decisive security difference.
- **Operational complexity:** Shamir is simple and well-understood; the hard part is the reconstruct step's exposure. TSS protocols (e.g., GG18/GG20-style ECDSA, FROST for Schnorr/EdDSA) are more complex, require interactive rounds, and demand vetted, audited implementations — subtle protocol bugs have caused real losses.
- **Compatibility:** TSS produces a *normal* signature verifiable by standard ECDSA/EdDSA verifiers — no verifier changes needed. Both approaches yield standard signatures.
- **Performance:** TSS adds network rounds between parties; Shamir's overhead is just reconstruction.

**Recommendation:** for a *high-value* signing service, use **MPC/TSS** (e.g., FROST for Ed25519/Schnorr, or a vetted threshold-ECDSA library), so the full key never exists on any single machine — pair it with HSM-backed shares and M-of-N quorum policy. Reserve Shamir for **cold recovery** of a key that is otherwise stored in an HSM (split the recovery secret among officers, reconstruct only in a controlled disaster-recovery ceremony), not for the live signing hot path. In short: TSS for online signing, Shamir for offline key recovery.

#### Q106. [Behavioral] You inherit a service where the previous team rolled their own encryption (custom XOR-based scheme). The team is proud of it. How do you handle replacing it?

This is as much a people problem as a crypto problem — the technical answer is obvious (replace custom XOR with vetted AEAD), but mishandling the human side gets you stonewalled. I'd approach it as:

- **Lead with evidence, not contempt.** A custom XOR scheme is almost certainly insecure (key reuse leaks plaintext via XOR cancellation, no integrity so it's trivially malleable, likely no authentication). Rather than "this is amateur," I'd *demonstrate* a concrete break — show how two ciphertexts under the same keystream reveal `P1 XOR P2`, or forge a message by flipping bits — so the risk is undeniable and impersonal. Make the *system* the subject, not the people.
- **Acknowledge the constraints they worked under.** They may have built it before a library was available, or for a reason (size, a weird platform). Understanding *why* it exists earns the credibility to change it and may surface a real constraint I need to honor.
- **Frame it as an upgrade, not an indictment.** "We can get confidentiality *and* integrity, plus FIPS validation and crypto-agility, by moving to AES-GCM — and we can do it without a flag day." Tie it to a benefit the team cares about (fewer incidents, compliance unblocked).
- **Migrate safely and incrementally.** Introduce a versioned envelope (Q72) so old and new coexist, write new data under AES-GCM, lazily re-encrypt old data, and keep the change reviewable. Bring the team along by having them help build the abstraction.
- **Prevent recurrence systemically:** a shared crypto wrapper library, a "no hand-rolled crypto" standard, and code-review gates — so it's an institutional norm, not a personal critique.

The signal I want to send: I respect the team and their work, I'm bringing a safer path (not just criticism), and I'm fixing the system so the next person inherits something better. Replacing hand-rolled crypto is non-negotiable on the merits — but *how* you do it determines whether you get cooperation or a turf war.

#### Q107. [Coding] Implement an AEAD envelope with Associated Data (AAD) binding a record's ID and version, and show why AAD prevents a swap attack.

```java
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

public class AadEnvelope {

    public static byte[] encrypt(byte[] plaintext, SecretKey key,
                                 long recordId, int version) throws Exception {
        byte[] iv = new byte[12];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(aad(recordId, version));      // bind context: authenticated, NOT encrypted
        byte[] ct = c.doFinal(plaintext);
        return ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array();
    }

    public static byte[] decrypt(byte[] blob, SecretKey key,
                                 long recordId, int version) throws Exception {
        byte[] iv = java.util.Arrays.copyOfRange(blob, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(aad(recordId, version));      // MUST match the AAD used at encrypt time
        // If the row was moved to a different recordId/version, the tag check FAILS here.
        return c.doFinal(blob, 12, blob.length - 12);
    }

    private static byte[] aad(long recordId, int version) {
        return ByteBuffer.allocate(12).putLong(recordId).putInt(version).array();
    }
}
```

Why AAD matters — the **swap attack**: imagine an attacker with database write access cannot decrypt or forge ciphertext (AES-GCM stops that), but they *can* copy row 5's `ciphertext` blob into row 9. Without AAD, row 9 now decrypts to row 5's plaintext — the attacker moved a secret (say, a high-balance record, or an admin flag) onto a different account without ever breaking the cipher. **Binding the record ID and version as AAD** defeats this: the tag is computed over `(ciphertext, recordId, version)`, so decrypting under a *different* `recordId` makes the tag verification fail with `AEADBadTagException`. AAD authenticates context that must travel in plaintext (you need the ID to find the row) but must not be tampered with or repositioned. The general principle: **bind ciphertext to its context** (row key, version, tenant, purpose) via AAD so it can't be replayed, swapped, or confused across records.

#### Q108. [Practical] You must prove to an auditor that a specific document existed at a specific time and hasn't changed since. Design the scheme.

This is **secure timestamping with tamper-evidence** — proving *existence at time T* (the document existed no later than T) and *integrity since* (it hasn't changed). You don't need to reveal the document to prove this, only its hash.

```
1. Compute H = SHA-256(document).               // fingerprint, reveals nothing
2. Get a trusted timestamp over H:
     - RFC 3161 Time-Stamping Authority (TSA): submit H, receive a signed
       timestamp token = TSA_sign(H, time T). The TSA's signature + trusted
       clock binds H to T.
   AND / OR
     - Anchor H in a public append-only log (Certificate-Transparency-style
       Merkle log, or a public blockchain transaction) whose inclusion is
       independently verifiable and hard to backdate.
3. Store { document, H, timestamp_token } and the inclusion proof.
```

Verifying later (what you show the auditor):
- **Integrity:** re-hash the document; if it equals the stored `H`, it is byte-for-byte unchanged. Any modification changes `H`, breaking the link to the timestamp.
- **Existence-at-T:** verify the **TSA's signature** over `(H, T)` using the TSA's certificate chain (RFC 3161), and/or verify the **Merkle inclusion proof** against a published signed tree head whose timestamp predates your claim. A public-log anchor is convincing because the operator can't retroactively insert an entry without breaking consistency proofs (Q77).

Design points to raise: hashing first means you **never expose the confidential document** to the TSA or the public log — only its digest. Using a **standards-based TSA (RFC 3161)** gives a legally recognized, signed timestamp; **anchoring in a public Merkle/blockchain log** adds independence from any single trusted party (defense against a colluding TSA). For long-term proofs (decades), plan for **algorithm agility** — re-timestamp under a stronger hash before SHA-256 weakens, so the proof chain stays valid. Combine TSA + public anchor for the strongest "existed at T, unchanged since" guarantee an auditor will accept.

## ✅ Key Takeaways

- **Encoding ≠ encryption ≠ hashing** — know which property (representation, confidentiality, integrity) each provides.
- **Never store passwords with fast hashes.** Use Argon2id (or scrypt/bcrypt/PBKDF2) with per-password salts; let the library handle it.
- **Prefer AEAD** (AES-GCM, ChaCha20-Poly1305) over unauthenticated modes; never use ECB; never reuse a nonce/IV under the same key.
- **Sign with the private key, verify with the public key.** Use HMAC for shared-secret integrity; signatures for non-repudiation.
- **TLS 1.3** gives 1-RTT handshakes and mandatory forward secrecy via ECDHE; trust flows through X.509 chains and PKI.
- **Use `SecureRandom`, never `Random`/`Math.random()`** for keys, tokens, IVs, and salts.
- **Envelope encryption + KMS/HSM** is the standard for data-at-rest and key management; design for **key rotation** and **crypto-agility** from day one.
- **Compare secrets in constant time** and prefer vetted libraries over hand-rolled crypto.
- **Post-quantum is here:** NIST's ML-KEM/ML-DSA are standardized; hybrid key exchange is already deploying. Plan for "harvest now, decrypt later."

## ⚠️ Common Pitfalls

- Base64-encoding (or "hashing" with MD5/SHA-1) passwords and calling it secure.
- Using plain SHA-256 for passwords — too fast, brute-forceable on GPUs.
- Reusing an IV/nonce with AES-GCM (leaks plaintext XOR and can leak the auth key → forgery).
- Using AES-ECB and leaking plaintext structure ("ECB penguin").
- Comparing MACs/tokens with `equals`/`==` (timing side channel) instead of `MessageDigest.isEqual`.
- Rolling your own crypto or your own `H(secret || msg)` MAC (length-extension) instead of HMAC/AEAD.
- Trusting JWTs without pinning the algorithm (`alg:none`, RS256→HS256 confusion).
- Hard-coding keys/secrets in source or config; committing them to version control.
- No key rotation plan, or hard-coding a single algorithm everywhere (no crypto-agility).
- Certificate pinning without a backup pin (bricks clients on rotation).
- Treating client-side checks as security, or assuming TLS alone protects data at rest.

## 📚 Further Reading

- NIST FIPS 197 (AES), FIPS 180-4 (SHA-2), FIPS 202 (SHA-3), FIPS 203/204/205 (post-quantum ML-KEM/ML-DSA/SLH-DSA).
- NIST SP 800-57 (Key Management) and SP 800-38D (GCM).
- RFC 8446 — TLS 1.3.
- RFC 9106 — Argon2; RFC 2104 — HMAC; RFC 5280 — X.509 / PKI.
- OWASP Cheat Sheets: Password Storage, Cryptographic Storage, Transport Layer Protection.
- "Cryptography Engineering" — Ferguson, Schneier, Kohno.
- "Serious Cryptography" (2nd ed.) — Jean-Philippe Aumasson.
- Java Cryptography Architecture (JCA) Reference Guide; BouncyCastle / BC-FIPS documentation.
