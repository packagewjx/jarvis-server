package jarvis.server.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsUtils {
    fun buildTrustManager(caCertPath: String?): X509TrustManager? {
        if (caCertPath.isNullOrBlank()) {
            return null
        }

        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = Files.newInputStream(Path.of(caCertPath)).use { stream ->
            certFactory.generateCertificate(stream) as X509Certificate
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("jarvis-channel-ca", cert)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }
}
