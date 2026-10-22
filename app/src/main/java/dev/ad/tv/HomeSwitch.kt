package dev.ad.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ADB client talking to this TV's own adbd over Wireless debugging. Key and certificate live in filesDir. */
class AdbClient private constructor(context: Context) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = android.os.Build.VERSION.SDK_INT
        val keyFile = File(context.filesDir, "adb.key")
        val certFile = File(context.filesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateKey = pair.private
            certificate = selfSigned(pair.public, pair.private)
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "TV Home"

    private fun selfSigned(publicKey: java.security.PublicKey, key: PrivateKey): Certificate {
        val algorithm = "SHA512withRSA"
        val name = X500Name("CN=TV Home")
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 3650L * 86_400_000L)
        val ext = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", ext)
        }
        return X509CertImpl(info).apply { sign(key, algorithm) }
    }

    companion object {
        @Volatile private var instance: AdbClient? = null
        fun get(context: Context): AdbClient = instance ?: synchronized(this) {
            // Pairing needs the platform Conscrypt's hidden exportKeyingMaterial().
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
            instance ?: AdbClient(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Switches the TV between this launcher and the stock Google TV home by disabling / enabling
 * the stock launcher and setup app over ADB. All functions block; call them off the main thread.
 */
object HomeSwitch {
    const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
    private const val STOCK_HOME = "$LAUNCHERX/.home.HomeActivity"

    class WifiOwnedException(val owners: Set<String>) : Exception("Wi-Fi network saved by ${owners.joinToString()}")

    fun isOurs(context: Context): Boolean = !Apps.isInstalled(context, LAUNCHERX)

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isPaired(context: Context) = File(context.filesDir, "adb.paired").exists()

    /** Finds the "Pair device with pairing code" service and pairs with the 6-digit code. */
    fun pair(context: Context, code: String) {
        val (host, port) = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15_000)
            ?: throw IOException(context.getString(R.string.adb_pairing_not_found))
        if (!AdbClient.get(context).pair(host, port, code.trim())) throw IOException(context.getString(R.string.adb_pairing_failed))
        File(context.filesDir, "adb.paired").writeText(System.currentTimeMillis().toString())
    }

    fun useThisLauncher(context: Context, log: (String) -> Unit) = withAdb(context, log) { sh ->
        val pkg = context.packageName
        log("pm grant WRITE_SECURE_SETTINGS")
        sh("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        sh("appops set $pkg GET_USAGE_STATS allow")

        // Disabling an app deletes the Wi-Fi networks it saved; on Google TV that is usually setupwraith.
        val owners = Regex("cname=([\\w.]+)").findAll(sh("dumpsys wifi | grep -o 'cname=[^ ]*'"))
            .map { it.groupValues[1] }.toSet().intersect(setOf(LAUNCHERX, SETUPWRAITH))
        if (owners.isNotEmpty()) throw WifiOwnedException(owners)

        log("set-home-activity")
        sh("cmd package set-home-activity $pkg/${HomeActivity::class.java.name}")
        // Both go together: launcherx off with setupwraith on drops HOME into setup's recovery loop.
        log("disable $SETUPWRAITH")
        sh("pm disable-user --user 0 $SETUPWRAITH")
        log("disable $LAUNCHERX")
        sh("pm disable-user --user 0 $LAUNCHERX")
    }

    fun useStockLauncher(context: Context, log: (String) -> Unit) = withAdb(context, log) { sh ->
        log("enable $LAUNCHERX")
        sh("pm enable $LAUNCHERX")
        log("enable $SETUPWRAITH")
        sh("pm enable $SETUPWRAITH")
        sh("cmd package set-home-activity $STOCK_HOME")
    }

    /** Grants usage access only (for focusing the last app), without touching the home screen. */
    fun grantPermissions(context: Context, log: (String) -> Unit) = withAdb(context, log) { sh ->
        sh("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        sh("appops set ${context.packageName} GET_USAGE_STATS allow")
    }

    private fun withAdb(context: Context, log: (String) -> Unit, block: ((String) -> String) -> Unit) {
        val client = AdbClient.get(context)
        val cr = context.contentResolver
        var turnedOn = false
        if (canWriteSecureSettings(context) && Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 0) {
            log("adb_wifi_enabled=1")
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            turnedOn = true
            Thread.sleep(1500)
        }
        try {
            log("connect")
            val ok = runCatching { client.isConnected || client.connectTls(context, 15_000) }.getOrElse { e ->
                throw IOException(context.getString(R.string.adb_connect_failed) + ": " + e.message, e)
            }
            if (!ok) throw IOException(context.getString(R.string.adb_connect_failed))
            block { cmd -> shell(client, cmd) }
        } finally {
            runCatching { client.disconnect() }
            if (turnedOn) Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
        }
    }

    private fun shell(client: AdbClient, cmd: String): String {
        val stream = client.openStream("shell:$cmd")
        val out = StringBuilder()
        try {
            stream.openInputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        -1 // libadb reports a closed stream as an exception on some Android 14 builds.
                    }
                    if (n < 0) break
                    out.append(String(buf, 0, n))
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString()
    }

    private fun discover(context: Context, type: String, timeoutMs: Long): Pair<String, Int>? {
        val latch = CountDownLatch(1)
        var found: Pair<String, Int>? = null
        val mdns = AdbMdns(context, type) { host: InetAddress?, port: Int ->
            if (host != null && port > 0) {
                found = (host.hostAddress ?: "127.0.0.1") to port
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return found
    }
}
