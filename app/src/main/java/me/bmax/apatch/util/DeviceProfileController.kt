package me.bmax.apatch.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bmax.apatch.apApp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val PRIMARY_USER_ID = 0
private const val BROKER_REQUEST_MAGIC = 0x4B534931
private const val BROKER_RESPONSE_MAGIC = 0x4B534F31
private const val BROKER_VERSION = 1
private const val BROKER_RESPONSE_HEADER_BYTES = 24
private const val BROKER_MAX_REQUEST_BYTES = 1024
private const val BROKER_MAX_OUTPUT_BYTES = 16 * 1024
private const val BROKER_TIMEOUT_MILLIS = 120_000L
private const val BROKER_RECONCILE_TIMEOUT_MILLIS = 5_000L

data class DeviceProfileStatus(
    val configured: Boolean,
    val activeSlot: String?,
    val activeGeneration: Long?,
    val running: Boolean,
)

data class DeviceProfileSlot(
    val name: String,
    val active: Boolean,
)

data class DeviceProfileSnapshot(
    val status: DeviceProfileStatus,
    val slots: List<DeviceProfileSlot>,
)

/** Builds only the fixed profile-service Slot command surface used by the UI. */
object DeviceProfileCommandFactory {
    private val packageNamePattern =
        Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val slotNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun isValidPackageName(value: String): Boolean = packageNamePattern.matches(value)

    fun isValidSlotName(value: String): Boolean = slotNamePattern.matches(value)

    fun status(packageName: String, userId: Int = PRIMARY_USER_ID): List<String> =
        build("status", packageName(packageName), user(userId), "--json")

    fun list(packageName: String, userId: Int = PRIMARY_USER_ID): List<String> =
        build("list", packageName(packageName), user(userId), "--json")

    fun create(
        packageName: String,
        slotName: String,
        captureCurrent: Boolean,
        userId: Int = PRIMARY_USER_ID,
    ): List<String> = build(
        "create",
        packageName(packageName),
        user(userId),
        slotName(slotName),
        *if (captureCurrent) arrayOf("--capture-current") else emptyArray(),
    )

    fun save(packageName: String, userId: Int = PRIMARY_USER_ID): List<String> =
        build("save", packageName(packageName), user(userId))

    fun switch(
        packageName: String,
        slotName: String,
        userId: Int = PRIMARY_USER_ID,
    ): List<String> = build("switch", packageName(packageName), user(userId), slotName(slotName))

    fun stop(packageName: String, userId: Int = PRIMARY_USER_ID): List<String> =
        build("stop", packageName(packageName), user(userId))

    fun delete(
        packageName: String,
        slotName: String,
        userId: Int = PRIMARY_USER_ID,
    ): List<String> = build("delete", packageName(packageName), user(userId), slotName(slotName))

    fun reconcile(): List<String> = listOf("reconcile")

    private fun packageName(value: String): String {
        require(isValidPackageName(value)) { "Invalid Android package name" }
        return value
    }

    private fun slotName(value: String): String {
        require(isValidSlotName(value)) { "Invalid profile slot name" }
        return value
    }

    private fun user(value: Int): String {
        require(value == PRIMARY_USER_ID) { "Only Android user 0 is supported" }
        return value.toString()
    }

    private fun build(vararg arguments: String): List<String> = arguments.toList()
}

object DeviceProfileJsonParser {
    fun parse(
        statusJson: String,
        listJson: String,
        expectedPackageName: String,
    ): DeviceProfileSnapshot {
        val statusDocument = parseDocument(statusJson, "status", expectedPackageName)
        val listDocument = parseDocument(listJson, "list", expectedPackageName)
        if (statusDocument.configured != listDocument.configured ||
            statusDocument.activeSlot != listDocument.activeSlot ||
            statusDocument.activeGeneration != listDocument.activeGeneration ||
            statusDocument.slots.toSet() != listDocument.slots.toSet()
        ) {
            throw JSONException("Profile status and list responses disagree")
        }
        val activeSlot = statusDocument.activeSlot
        return DeviceProfileSnapshot(
            status = DeviceProfileStatus(
                configured = statusDocument.configured,
                activeSlot = activeSlot,
                activeGeneration = statusDocument.activeGeneration,
                running = activeSlot != null,
            ),
            slots = listDocument.slots.sortedBy { it.name.lowercase() },
        )
    }

    private data class ProfileDocument(
        val configured: Boolean,
        val activeSlot: String?,
        val activeGeneration: Long?,
        val slots: List<DeviceProfileSlot>,
    )

    private fun parseDocument(
        json: String,
        command: String,
        expectedPackageName: String,
    ): ProfileDocument {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) throw JSONException("Profile $command command returned no JSON")
        val root = JSONObject(trimmed)
        if (!root.requiredBoolean("ok")) throw JSONException("Profile $command response has ok=false")
        val packageName = root.requiredString("package")
        if (packageName != expectedPackageName) {
            throw JSONException("Profile $command response package does not match the request")
        }
        if (root.requiredLong("userId") != PRIMARY_USER_ID.toLong()) {
            throw JSONException("Profile $command response is not for Android user 0")
        }
        val configured = root.requiredBoolean("configured")
        if (!root.has("active")) throw JSONException("Profile $command response is missing active")
        val activeValue = root.opt("active")
        val active = when (activeValue) {
            JSONObject.NULL -> null
            is JSONObject -> activeValue
            else -> throw JSONException("Profile $command response active must be an object or null")
        }
        val activeSlot = active?.requiredString("slot")
        if (activeSlot != null && !DeviceProfileCommandFactory.isValidSlotName(activeSlot)) {
            throw JSONException("Profile $command response contains an invalid active slot")
        }
        val generation = active?.requiredLong("generation")
        if (generation != null && generation <= 0L) {
            throw JSONException("Profile $command response contains an invalid generation")
        }
        val values = root.opt("slots") as? JSONArray
            ?: throw JSONException("Profile $command response slots must be an array")
        val slots = buildList {
            for (index in 0 until values.length()) {
                val item = values.opt(index) as? JSONObject
                    ?: throw JSONException("Profile $command response slot must be an object")
                val id = item.requiredString("id")
                if (!DeviceProfileCommandFactory.isValidSlotName(id)) {
                    throw JSONException("Profile $command response contains an invalid slot id")
                }
                add(DeviceProfileSlot(id, item.requiredBoolean("active")))
            }
        }
        if (slots.distinctBy { it.name }.size != slots.size) {
            throw JSONException("Profile $command response contains duplicate slot ids")
        }
        val activeSlots = slots.filter { it.active }
        if (activeSlots.size > 1 ||
            (activeSlot == null && activeSlots.isNotEmpty()) ||
            (activeSlot != null && activeSlots.singleOrNull()?.name != activeSlot)
        ) {
            throw JSONException("Profile $command response has inconsistent active state")
        }
        if (!configured && (activeSlot != null || slots.isNotEmpty())) {
            throw JSONException("Unconfigured profile response contains slot state")
        }
        return ProfileDocument(configured, activeSlot, generation, slots)
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean =
        opt(key) as? Boolean
            ?: throw JSONException("Profile response field $key must be a boolean")

    private fun JSONObject.requiredString(key: String): String =
        (opt(key) as? String)?.takeIf { it.isNotEmpty() }
            ?: throw JSONException("Profile response field $key must be a non-empty string")

    private fun JSONObject.requiredLong(key: String): Long {
        return when (val value = opt(key)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw JSONException("Profile response field $key must be an integer")
        }
    }
}

class DeviceProfileController {
    suspend fun refresh(packageName: String): DeviceProfileSnapshot = withContext(Dispatchers.IO) {
        brokerMutex.withLock {
            val status = execute(DeviceProfileCommandFactory.status(packageName))
            val slots = execute(DeviceProfileCommandFactory.list(packageName))
            DeviceProfileJsonParser.parse(status, slots, packageName)
        }
    }

    suspend fun create(packageName: String, slotName: String, captureCurrent: Boolean) =
        mutate(DeviceProfileCommandFactory.create(packageName, slotName, captureCurrent))

    suspend fun save(packageName: String) = mutate(DeviceProfileCommandFactory.save(packageName))

    suspend fun switch(packageName: String, slotName: String) =
        mutate(DeviceProfileCommandFactory.switch(packageName, slotName))

    suspend fun stop(packageName: String) = mutate(DeviceProfileCommandFactory.stop(packageName))

    suspend fun delete(packageName: String, slotName: String) =
        mutate(DeviceProfileCommandFactory.delete(packageName, slotName))

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        brokerMutex.withLock {
            execute(
                DeviceProfileCommandFactory.reconcile(),
                BROKER_RECONCILE_TIMEOUT_MILLIS,
            )
        }
        Unit
    }

    private suspend fun mutate(command: List<String>) = withContext(Dispatchers.IO) {
        brokerMutex.withLock { execute(command) }
        Unit
    }

    private suspend fun execute(
        command: List<String>,
        timeoutMillis: Long = BROKER_TIMEOUT_MILLIS,
    ): String {
        require(command.size == 1 || command.size in 3..5) {
            "invalid broker command size"
        }
        val context = apApp.createDeviceProtectedStorageContext()
        val mailbox = java.io.File(context.filesDir, "ksh-profile-controller")
        check(mailbox.isDirectory || mailbox.mkdirs()) { "cannot create profile broker mailbox" }
        check(!java.nio.file.Files.isSymbolicLink(mailbox.toPath())) { "unsafe profile broker mailbox" }
        check(mailbox.setReadable(false, false) && mailbox.setWritable(false, false))
        check(mailbox.setExecutable(false, false))
        check(mailbox.setReadable(true, true) && mailbox.setWritable(true, true) &&
            mailbox.setExecutable(true, true))
        val requestId = java.security.SecureRandom().nextLong().let { value ->
            if (value == 0L) 1L else value
        }
        val request = encodeRequest(requestId, command)
        val responseFile = java.io.File(mailbox, "response.bin")
        val requestFile = java.io.File(mailbox, "request.bin")
        val temporary = java.io.File(mailbox, ".request.${android.os.Process.myPid()}.$requestId")
        check(!requestFile.exists()) { "another profile broker request is pending" }
        clearRegularMailboxFile(responseFile, "stale broker response")
        java.io.FileOutputStream(responseFile, false).use { stream -> stream.fd.sync() }
        check(responseFile.setReadable(false, false) && responseFile.setWritable(false, false))
        check(responseFile.setReadable(true, true) && responseFile.setWritable(true, true))
        check(!temporary.exists()) { "profile broker request staging file already exists" }
        try {
            java.io.FileOutputStream(temporary).use { stream ->
                stream.write(request)
                stream.fd.sync()
            }
            check(temporary.setReadable(false, false) && temporary.setWritable(false, false))
            check(temporary.setReadable(true, true) && temporary.setWritable(true, true))
            java.nio.file.Files.move(
                temporary.toPath(),
                requestFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (temporary.exists()) {
                check(temporary.delete()) { "cannot clear profile broker request staging file" }
            }
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (responseFile.isFile) {
                    val bytes = runCatching { responseFile.readBytes() }.getOrNull()
                    if (bytes != null && bytes.size >= BROKER_RESPONSE_HEADER_BYTES) {
                        val response = runCatching { decodeResponse(bytes) }.getOrNull()
                        if (response == null) {
                            delay(50L)
                            continue
                        }
                        if (response.requestId == requestId) {
                            clearRegularMailboxFile(responseFile, "broker response")
                            if (response.status != 0) {
                                throw IllegalStateException(
                                    response.output.ifBlank {
                                        "profile service failed with exit code ${response.status}"
                                    }
                                )
                            }
                            return response.output.trimEnd()
                        }
                    }
                }
                delay(50L)
            }
            throw IllegalStateException("profile broker timed out")
        } finally {
            // The broker atomically renames a claimed request to processing.bin.
            // Deleting request.bin here only cancels work that has not been claimed.
            if (requestFile.exists() && !java.nio.file.Files.isSymbolicLink(requestFile.toPath())) {
                requestFile.delete()
            }
        }
    }

    private fun clearRegularMailboxFile(file: java.io.File, description: String) {
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) return
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile) {
            "unsafe $description"
        }
        check(file.delete()) { "cannot clear $description" }
    }

    private data class BrokerResponse(val requestId: Long, val status: Int, val output: String)

    private fun encodeRequest(requestId: Long, command: List<String>): ByteArray {
        val encoded = command.map { argument ->
            argument.toByteArray(Charsets.UTF_8).also { require(it.isNotEmpty() && it.size <= 256) }
        }
        val buffer = java.nio.ByteBuffer
            .allocate(16 + encoded.sumOf { 2 + it.size })
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putInt(BROKER_REQUEST_MAGIC)
        buffer.putShort(BROKER_VERSION.toShort())
        buffer.putShort(encoded.size.toShort())
        buffer.putLong(requestId)
        encoded.forEach { argument ->
            buffer.putShort(argument.size.toShort())
            buffer.put(argument)
        }
        return buffer.array().also { require(it.size <= BROKER_MAX_REQUEST_BYTES) }
    }

    private fun decodeResponse(bytes: ByteArray): BrokerResponse {
        require(bytes.size >= BROKER_RESPONSE_HEADER_BYTES) { "truncated broker response" }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        require(buffer.int == BROKER_RESPONSE_MAGIC) { "invalid broker response" }
        require(buffer.short.toInt() == BROKER_VERSION) { "unsupported broker response" }
        require(buffer.short.toInt() == 0) { "invalid broker response flags" }
        val status = buffer.int
        val length = buffer.int
        val requestId = buffer.long
        require(length in 0..BROKER_MAX_OUTPUT_BYTES &&
            bytes.size == BROKER_RESPONSE_HEADER_BYTES + length) { "invalid broker output length" }
        val output = ByteArray(length)
        buffer.get(output)
        return BrokerResponse(requestId, status, output.toString(Charsets.UTF_8))
    }

    private companion object {
        val brokerMutex = Mutex()
    }
}
