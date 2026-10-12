package dev.keyrelay.launcher

import android.content.Context
import android.os.Build
import dev.keyrelay.core.AdbConnectResult
import dev.keyrelay.core.ShellOutput
import dev.keyrelay.core.ShellResult
import dev.keyrelay.service.classifyConnect
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** ADB client identity of this app; the key pair is what wireless debugging remembers after pairing. */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(context.filesDir, "adb.key")
    private val certFile = File(context.filesDir, "adb.crt")
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        // libadb's default is Long.MAX_VALUE: a dead adbd would hang the connect phase forever.
        setTimeout(ShellOutput.COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            // A key left without its certificate by an older version must not pair with the new certificate.
            keyFile.delete()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = Date()
            val name = X500Name("CN=KeyRelay")
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time), now, Date(now.time + VALIDITY_MS), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            privateKey = pair.private
            certificate = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
            // Certificate first: a key file on disk means its pair is complete.
            writeAtomically(certFile, certificate.encoded)
            writeAtomically(keyFile, privateKey.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "KeyRelay"

    // libadb AdbStream.read never signals end of stream cleanly: after adbd closes it throws "Stream closed." when its
    // queue is empty, and blocks forever when data was still queued at close. So every command echoes a marker.
    /** Runs [cmd]; throws IOException when it does not finish within [ShellOutput.COMMAND_TIMEOUT_MS]. */
    suspend fun exec(cmd: String): ShellResult =
        // Null-then-IOException: callers must not mistake a timeout for their own cancellation.
        withTimeoutOrNull(ShellOutput.COMMAND_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                openStream("shell:" + ShellOutput.wrap(cmd)).use { stream ->
                    ShellOutput.readUntilMarker { buf ->
                        try {
                            stream.read(buf, 0, buf.size)
                        } catch (e: IOException) {
                            // libadb reports an interrupted wait as IOException; rethrow the cause so runInterruptible maps it to cancellation.
                            (e.cause as? InterruptedException)?.let { throw it }
                            if (stream.isClosed) -1 else throw e
                        }
                    }
                }
            }
        } ?: throw IOException("adb command timed out: ${cmd.take(LOG_CMD_CHARS)}")

    /** Drops any earlier session first: one from before wireless debugging went off is dead and must not be reused. */
    internal suspend fun reconnect(context: Context): AdbConnectResult = withContext(Dispatchers.IO) {
        runCatching { disconnect() }
        classifyConnect { autoConnect(context, 2 * ShellOutput.COMMAND_TIMEOUT_MS) }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not replace $file")
    }

    companion object {
        private const val LOG_CMD_CHARS = 40
        private const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(context.applicationContext).also { instance = it } }
    }
}
