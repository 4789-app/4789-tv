package com.fourseveneightnine.tv.client.profiles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredProfile(
    val id: String,
    val name: String,
    val avatarKey: String,
    val hasPin: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class PinRecord(
    val salt: String,
    val verifier: String,
    val iterations: Int,
    val failedAttempts: Int = 0,
    val lockedUntil: Long? = null,
)

@Serializable
private data class StoredProfileDocument(
    val version: Int = 1,
    val profiles: List<StoredProfile>,
    val selectedProfileId: String,
    val pins: Map<String, PinRecord> = emptyMap(),
    val pendingDeletions: Set<String> = emptySet(),
)

internal interface ProfileEnvelopePersistence {
    fun read(): String?
    fun write(encoded: String?): Boolean
    fun readBackup(): String? = null
    fun writeBackup(encoded: String?): Boolean = true
}

internal interface ProfileEnvelopeCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
}

/**
 * Owns encrypted household-profile metadata and PIN verifiers.
 *
 * The profile database itself is deliberately not managed here. Pairing, add-ons and credentials
 * stay in their existing process-global stores; only [ProfileLibraryManager] switches libraries.
 */
internal class ProfileStore internal constructor(
    private val persistence: ProfileEnvelopePersistence,
    private val cipher: ProfileEnvelopeCipher,
    private val clock: () -> Long,
    private val uuidFactory: () -> UUID,
    private val randomBytes: (Int) -> ByteArray,
) {
    constructor(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        persistence = SharedPreferenceProfilePersistence(context),
        cipher = AndroidKeystoreProfileCipher(),
        clock = clock,
        uuidFactory = UUID::randomUUID,
        randomBytes = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
    )

    private var document: StoredProfileDocument? = null

    @Synchronized
    fun load(): ProfileCatalog = catalog(loadDocument())

    @Synchronized
    fun create(name: String, avatarKey: String, pin: CharArray? = null): HouseholdProfile {
        val current = loadDocument()
        val now = clock()
        val id = generateUniqueId(current)
        val profile = HouseholdProfile(
            id = id,
            name = validateName(name),
            avatarKey = validateAvatarKey(avatarKey),
            hasPin = pin != null && pin.isNotEmpty(),
            createdAt = now,
            updatedAt = now,
        )
        val record = pin?.takeIf(CharArray::isNotEmpty)?.let(::newPinRecord)
        val updated = current.copy(
            profiles = current.profiles + profile.stored(),
            pins = if (record == null) current.pins else current.pins + (id.toString() to record),
        )
        persist(updated)
        return profile
    }

    @Synchronized
    fun update(id: UUID, name: String, avatarKey: String): HouseholdProfile {
        val current = loadDocument()
        val stored = current.profiles.firstOrNull { it.id == id.toString() }
            ?: error("profile_not_found")
        val updatedProfile = stored.copy(
            name = validateName(name),
            avatarKey = validateAvatarKey(avatarKey),
            updatedAt = clock(),
        )
        persist(current.copy(profiles = current.profiles.map { if (it.id == stored.id) updatedProfile else it }))
        return updatedProfile.profile()
    }

    @Synchronized
    fun setPin(id: UUID, pin: CharArray?) {
        val current = loadDocument()
        require(current.profiles.any { it.id == id.toString() }) { "profile_not_found" }
        val record = pin?.takeIf(CharArray::isNotEmpty)?.let(::newPinRecord)
        val updatedPins = if (record == null) current.pins - id.toString() else current.pins + (id.toString() to record)
        persist(
            current.copy(
                profiles = current.profiles.map {
                    if (it.id == id.toString()) {
                        it.copy(hasPin = record != null, updatedAt = clock())
                    } else {
                        it
                    }
                },
                pins = updatedPins,
            ),
        )
    }

    @Synchronized
    fun select(id: UUID) {
        val current = loadDocument()
        require(current.profiles.any { it.id == id.toString() }) { "profile_not_found" }
        if (current.selectedProfileId != id.toString()) {
            persist(current.copy(selectedProfileId = id.toString()))
        }
    }

    @Synchronized
    fun verifyPin(id: UUID, pin: CharArray): PinVerification {
        val current = loadDocument()
        val key = id.toString()
        require(current.profiles.any { it.id == key }) { "profile_not_found" }
        val record = current.pins[key] ?: return PinVerification.NotRequired
        val now = clock()
        if ((record.lockedUntil ?: 0L) > now) return PinVerification.Locked(requireNotNull(record.lockedUntil))

        val salt = decoder.decode(record.salt)
        val expected = decoder.decode(record.verifier)
        val actual = deriveVerifier(pin, salt, record.iterations)
        val verified = MessageDigest.isEqual(expected, actual)
        actual.fill(0)
        if (verified) {
            if (record.failedAttempts != 0 || record.lockedUntil != null) {
                persist(
                    current.copy(
                        pins = current.pins + (key to record.copy(failedAttempts = 0, lockedUntil = null)),
                    ),
                )
            }
            return PinVerification.Verified
        }

        val failures = (record.failedAttempts + 1).coerceAtMost(MAX_FAILED_ATTEMPTS)
        val lockedUntil = if (failures >= MAX_FAILED_ATTEMPTS) now + LOCKOUT_MILLIS else null
        persist(
            current.copy(
                pins = current.pins + (
                    key to record.copy(failedAttempts = failures, lockedUntil = lockedUntil)
                ),
            ),
        )
        return lockedUntil?.let(PinVerification::Locked)
            ?: PinVerification.Rejected(MAX_FAILED_ATTEMPTS - failures)
    }

    @Synchronized
    internal fun beginDelete(id: UUID): Boolean {
        if (id == OwnerProfile.id) return false
        val current = loadDocument()
        if (current.profiles.none { it.id == id.toString() }) return false
        persist(current.copy(pendingDeletions = current.pendingDeletions + id.toString()))
        return true
    }

    @Synchronized
    internal fun finishDelete(id: UUID): Boolean {
        if (id == OwnerProfile.id) return false
        val current = loadDocument()
        if (id.toString() !in current.pendingDeletions) return false
        val remaining = current.profiles.filterNot { it.id == id.toString() }
        val selected = if (current.selectedProfileId == id.toString()) OwnerProfile.id.toString()
        else current.selectedProfileId
        persist(
            current.copy(
                profiles = remaining,
                selectedProfileId = selected,
                pins = current.pins - id.toString(),
                pendingDeletions = current.pendingDeletions - id.toString(),
            ),
        )
        return true
    }

    private fun loadDocument(): StoredProfileDocument {
        document?.let { return it }
        val encoded = persistence.read()
        if (encoded == null) return recoveredOwner("missing")
        runCatching { decodeDocument(encoded) }.getOrNull()?.let { loaded ->
            document = loaded
            return loaded
        }
        persistence.readBackup()?.let { backup ->
            runCatching { decodeDocument(backup) }.getOrNull()?.let { recovered ->
                check(persistence.write(backup)) { "profile_backup_restore_failed" }
                ReceiverDiagnostics.record("profiles.store.recovered", "backup")
                document = recovered
                return recovered
            }
        }
        ReceiverDiagnostics.record("profiles.store.unavailable", "invalid_envelope")
        error("profile_store_unavailable")
    }

    private fun decodeDocument(encoded: String): StoredProfileDocument {
        val plaintext = cipher.decrypt(decoder.decode(encoded))
        return try {
            json.decodeFromString(StoredProfileDocument.serializer(), plaintext.decodeToString()).validated()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun recoveredOwner(reason: String): StoredProfileDocument {
        ReceiverDiagnostics.record("profiles.store.recovered", reason)
        val now = clock()
        val recovered = StoredProfileDocument(
            profiles = listOf(
                StoredProfile(
                    id = OwnerProfile.id.toString(),
                    name = OwnerProfile.name,
                    avatarKey = OwnerProfile.avatarKey,
                    hasPin = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            selectedProfileId = OwnerProfile.id.toString(),
        )
        persist(recovered)
        return recovered
    }

    private fun persist(next: StoredProfileDocument) {
        val validated = next.validated()
        val prior = persistence.read()
        if (prior != null) check(persistence.writeBackup(prior)) { "profile_backup_write_failed" }
        val plaintext = json.encodeToString(validated).encodeToByteArray()
        val encoded = try {
            encoder.encodeToString(cipher.encrypt(plaintext))
        } finally {
            plaintext.fill(0)
        }
        check(persistence.write(encoded)) { "profile_metadata_write_failed" }
        val verified = runCatching {
            val decoded = cipher.decrypt(decoder.decode(requireNotNull(persistence.read())))
            try {
                json.decodeFromString(StoredProfileDocument.serializer(), decoded.decodeToString()) == validated
            } finally {
                decoded.fill(0)
            }
        }.getOrDefault(false)
        if (!verified) {
            persistence.write(prior)
            error("profile_metadata_verify_failed")
        }
        check(persistence.writeBackup(encoded)) { "profile_backup_commit_failed" }
        document = validated
    }

    private fun StoredProfileDocument.validated(): StoredProfileDocument {
        require(version == FORMAT_VERSION) { "unsupported_profile_store_version" }
        require(profiles.isNotEmpty()) { "profiles_empty" }
        val ids = profiles.map { UUID.fromString(it.id) }
        require(ids.map(UUID::toString).size == ids.toSet().size) { "duplicate_profile_id" }
        require(OwnerProfile.id in ids) { "owner_profile_missing" }
        val selectedId = UUID.fromString(selectedProfileId)
        require(selectedId.toString() == selectedProfileId) { "noncanonical_selected_profile_id" }
        require(selectedId in ids) { "selected_profile_missing" }
        profiles.forEach { profile ->
            require(UUID.fromString(profile.id).toString() == profile.id) { "noncanonical_profile_id" }
            validateName(profile.name)
            validateAvatarKey(profile.avatarKey)
            require(profile.createdAt >= 0L && profile.updatedAt >= profile.createdAt) { "invalid_profile_timestamp" }
            require(profile.hasPin == pins.containsKey(profile.id)) { "profile_pin_mismatch" }
        }
        require(pins.keys.all { key -> profiles.any { it.id == key } }) { "orphan_pin" }
        require(pendingDeletions.all { key ->
            profiles.any { it.id == key } && key != OwnerProfile.id.toString()
        }) { "invalid_pending_deletion" }
        pins.values.forEach { pin ->
            require(decoder.decode(pin.salt).size == SALT_BYTES) { "invalid_pin_salt" }
            require(decoder.decode(pin.verifier).size == VERIFIER_BYTES) { "invalid_pin_verifier" }
            require(pin.iterations == PBKDF2_ITERATIONS) { "invalid_pin_iterations" }
            require(pin.failedAttempts in 0..MAX_FAILED_ATTEMPTS) { "invalid_pin_failures" }
            require(pin.lockedUntil == null || pin.failedAttempts == MAX_FAILED_ATTEMPTS) {
                "invalid_pin_lockout"
            }
        }
        return this
    }

    private fun newPinRecord(pin: CharArray): PinRecord {
        require(pin.size in 1..MAX_PIN_CHARS) { "invalid_pin_length" }
        val salt = randomBytes(SALT_BYTES)
        require(salt.size == SALT_BYTES) { "invalid_random_salt" }
        val verifier = deriveVerifier(pin, salt, PBKDF2_ITERATIONS)
        return try {
            PinRecord(
                salt = encoder.encodeToString(salt),
                verifier = encoder.encodeToString(verifier),
                iterations = PBKDF2_ITERATIONS,
            )
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    private fun deriveVerifier(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val copy = pin.copyOf()
        val spec = PBEKeySpec(copy, salt, iterations, VERIFIER_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            copy.fill('\u0000')
        }
    }

    private fun generateUniqueId(current: StoredProfileDocument): UUID {
        val existing = current.profiles.mapTo(HashSet()) { it.id }
        repeat(MAX_UUID_ATTEMPTS) {
            val candidate = uuidFactory()
            if (candidate != OwnerProfile.id && candidate.toString() !in existing) return candidate
        }
        error("profile_id_generation_failed")
    }

    private fun catalog(stored: StoredProfileDocument): ProfileCatalog = ProfileCatalog(
        profiles = stored.profiles.map { it.profile() },
        selectedProfileId = UUID.fromString(stored.selectedProfileId),
    )

    private fun StoredProfile.profile(): HouseholdProfile = HouseholdProfile(
        id = UUID.fromString(id),
        name = name,
        avatarKey = avatarKey,
        hasPin = hasPin,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun HouseholdProfile.stored(): StoredProfile = StoredProfile(
        id = id.toString(),
        name = name,
        avatarKey = avatarKey,
        hasPin = hasPin,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun validateName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME_CHARS) { "invalid_profile_name" }
    }

    private fun validateAvatarKey(value: String): String = value.trim().also {
        require(AVATAR_KEY.matches(it)) { "invalid_avatar_key" }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val PREFERENCE_NAME = "household-profiles-v1"
        const val CIPHERTEXT_KEY = "profiles-ciphertext"
        const val BACKUP_CIPHERTEXT_KEY = "profiles-ciphertext-backup"
        const val KEY_ALIAS = "com.fourseveneightnine.tv.profiles.v1"
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val VERIFIER_BITS = 256
        const val VERIFIER_BYTES = VERIFIER_BITS / Byte.SIZE_BITS
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 30_000L
        const val MAX_PIN_CHARS = 64
        const val MAX_NAME_CHARS = 40
        const val MAX_UUID_ATTEMPTS = 16
        val AVATAR_KEY = Regex("[a-zA-Z0-9_-]{1,40}")
        val json = Json { ignoreUnknownKeys = true }
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }

    private class SharedPreferenceProfilePersistence(context: Context) : ProfileEnvelopePersistence {
        private val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCE_NAME,
            Context.MODE_PRIVATE,
        )

        override fun read(): String? = preferences.getString(CIPHERTEXT_KEY, null)

        override fun write(encoded: String?): Boolean {
            val editor = preferences.edit()
            if (encoded == null) editor.remove(CIPHERTEXT_KEY) else editor.putString(CIPHERTEXT_KEY, encoded)
            return editor.commit()
        }

        override fun readBackup(): String? = preferences.getString(BACKUP_CIPHERTEXT_KEY, null)

        override fun writeBackup(encoded: String?): Boolean {
            val editor = preferences.edit()
            if (encoded == null) editor.remove(BACKUP_CIPHERTEXT_KEY)
            else editor.putString(BACKUP_CIPHERTEXT_KEY, encoded)
            return editor.commit()
        }
    }

    private class AndroidKeystoreProfileCipher : ProfileEnvelopeCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
            return cipher.iv + cryptInChunks(cipher, plaintext)
        }

        override fun decrypt(envelope: ByteArray): ByteArray {
            require(envelope.size > IV_BYTES) { "invalid_profile_envelope" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey(),
                GCMParameterSpec(TAG_BITS, envelope.copyOfRange(0, IV_BYTES)),
            )
            return cryptInChunks(cipher, envelope.copyOfRange(IV_BYTES, envelope.size))
        }

        private fun cryptInChunks(cipher: Cipher, input: ByteArray): ByteArray {
            val output = ByteArrayOutputStream(input.size + TAG_BITS / Byte.SIZE_BITS)
            var offset = 0
            while (offset < input.size) {
                val count = minOf(CIPHER_CHUNK_BYTES, input.size - offset)
                cipher.update(input, offset, count)?.let(output::write)
                offset += count
            }
            cipher.doFinal()?.let(output::write)
            return output.toByteArray()
        }

        private fun existingKey(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("missing_profile_keystore_key")

        private fun existingOrNewKey(): SecretKey = runCatching(::existingKey).getOrElse {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val IV_BYTES = 12
            const val TAG_BITS = 128
            const val CIPHER_CHUNK_BYTES = 16 * 1024
        }
    }
}
