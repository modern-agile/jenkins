package metanectar.agent;

import hudson.model.Hudson;
import hudson.model.Hudson.MasterComputer;
import hudson.model.UsageStatistics.CombinedCipherInputStream;
import hudson.model.UsageStatistics.CombinedCipherOutputStream;
import hudson.remoting.Channel;
import hudson.util.IOException2;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import javax.crypto.KeyAgreement;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The Jenkins/Nectar protocol that establishes the channel with MetaNectar.
 *
 *
 * <h2>Security Architecture</h2>
 * <p>
 * This protocol involves two parties proving to each other who they are and establish
 * secure remoting channel, over an insecure transport. The protocol is symmetric.
 *
 * <p>
 * The "who they are" part hinges on an RSA key pair, much like how it's used in SSH.
 * The other side of the communication knows you are you via your public key.
 *
 * <p>
 * While we could have just gotten away with a bare public key, we use X509 certificate.
 * We are just using it to carry around a public key, and so it merely needs to be self-signed,
 * but use of the existing public key infrastructure can make the authorization easier down the road
 * (that is, an administrator needs to acknowledge that it approves of the given public key,
 * and doing so is easier if you use a certificate and especially if it has a trust chain.)
 *
 * <p>
 * The authentication is done by each side providing a signature on a random session ID.
 * For this to work, a random session ID needs to be generated by a coin-toss. So for that,
 * we do Diffie-Hellman key exchange.
 *
 * <p>
 * The protocol also exchanges the certificate, and each side will verify the signature,
 * check if it's willing to talk to the owner of the validated public key, then proceed.
 *
 * @author Paul Sandoz
 * @author Kohsuke Kawaguchi
 */
public abstract class MetaNectarAgentProtocol implements AgentProtocol {
    /**
     * Certificate that shows the identity of this client.
     */
    private final X509Certificate identity;
    /**
     * Private key that pairs up with the {@link #identity}
     */
    private final RSAPrivateKey privateKey;

    private final Listener listener;

    /**
     * Receives the progress updates of the protocol and makes key decisions.
     */
    public abstract static class Listener {
        /**
         * Returns our HTTP URL.
         *
         * @see Hudson#getRootUrl()
         */
        public abstract URL getOurURL() throws IOException;

        /**
         * When the hand-shake was completed and we authenticated the identity of the peer,
         * this method is called to determine if we are willing to establish a communication channel.
         *
         * <p>
         * Return normally from this method to proceed.
         *
         * @param address
         *      The HTTP URL of the peer. Useful for showing diagnostic messages.
         * @param identity
         *      Verified identity of the peer.
         *
         * @throws GracefulConnectionRefusalException
         *      To refuse a connection gracefully in such a way that the other side will learn the reason.
         */
        public abstract void onConnectingTo(URL address, X509Certificate identity) throws GeneralSecurityException, IOException;

        /**
         * When a communication channel is fully established.
         */
        public abstract void onConnectedTo(Channel channel, X509Certificate identity) throws IOException;
    }

    public static class Inbound extends MetaNectarAgentProtocol implements AgentProtocol.Inbound {
        public Inbound(X509Certificate identity, RSAPrivateKey privateKey, Listener listener) {
            super(identity, privateKey, listener);
        }

        protected KeyAgreement diffieHellman(Connection connection) throws IOException, GeneralSecurityException {
            return connection.diffieHellman(false);
        }
    }

    public static class Outbound extends MetaNectarAgentProtocol implements AgentProtocol.Outbound {
        public Outbound(X509Certificate identity, RSAPrivateKey privateKey, Listener listener) {
            super(identity, privateKey, listener);
        }

        protected KeyAgreement diffieHellman(Connection connection) throws IOException, GeneralSecurityException {
            return connection.diffieHellman(true);
        }
    }

    /**
     * Indicates that we are refusing to proceed with this connection.
     * The message provided will be passed to the other end so that they can see why the connection is aborted.
     */
    public static class GracefulConnectionRefusalException extends IOException {
        public GracefulConnectionRefusalException(String message) {
            super(message);
        }

        public GracefulConnectionRefusalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public MetaNectarAgentProtocol(X509Certificate identity, RSAPrivateKey privateKey, Listener listener) {
        this.identity = identity;
        this.privateKey = privateKey;
        this.listener = listener;
    }

    public String getName() {
        return "Protocol:MetaNectar";
    }

    public void process(Connection connection) throws Exception {
        byte[] sessionId = diffieHellman(connection).generateSecret();

        sendHandshake(connection, sessionId);
        X509Certificate other = receiveHandshake(connection, sessionId);
        connect(connection, other);
    }

    protected abstract KeyAgreement diffieHellman(Connection connection) throws IOException, GeneralSecurityException;

    private void sendHandshake(Connection connection, byte[] sessionId) throws IOException, GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(privateKey);
        sig.update(sessionId);

        // use a map in preparation for possible future extension
        Map<String,Object> requestHeaders = new HashMap<String,Object>();
        requestHeaders.put("Identity", identity);
        requestHeaders.put("Address", listener.getOurURL().toExternalForm());
        requestHeaders.put("Signature",sig.sign());

        LOGGER.fine("Sending " + requestHeaders + " as handshaking headers");

        connection.writeObject(requestHeaders);
    }

    private X509Certificate receiveHandshake(Connection connection, byte[] sessionId) throws IOException, GeneralSecurityException, ClassNotFoundException {
        Map<String, Object> responseHeaders = connection.readObject();
        LOGGER.fine("Got "+responseHeaders+" as handshaking headers");

        X509Certificate server = (X509Certificate)responseHeaders.get("Identity");
        if (server==null)
            throw new IOException("The other end failed to give us its identity");
        // TODO: should we validate certificate? On one hand, we are only using its public key, but on the other hand, that's what you do with certificates...

        URL serverAddress = new URL((String)responseHeaders.get("Address"));

        byte[] signature = (byte[])responseHeaders.get("Signature");
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initVerify(server);
        sig.update(sessionId);
        if (!sig.verify(signature))
            throw new IOException("Signature mismatch. Someone is trying to masquerade?");

        try {
            listener.onConnectingTo(serverAddress, server);
            connection.writeObject(null);   // indicating we accepted the other
        } catch (GracefulConnectionRefusalException e) {
            connection.writeObject(e);
            throw e;
        }

        GracefulConnectionRefusalException refused = connection.readObject();
        if (refused!=null)  throw new GracefulConnectionRefusalException(refused.getMessage(),refused);

        return server;
    }

    protected void connect(Connection connection, X509Certificate other) throws IOException, GeneralSecurityException {
        CombinedCipherOutputStream out = new CombinedCipherOutputStream(connection.out, privateKey, "AES/CBC/PKCS5Padding");
        CombinedCipherInputStream in = new CombinedCipherInputStream(connection.in, (RSAPublicKey) other.getPublicKey(), "AES/CBC/PKCS5Padding");

        final Channel channel = new Channel("outbound-channel", MasterComputer.threadPoolForRemoting,
            new BufferedInputStream(in), new BufferedOutputStream(out));

        listener.onConnectedTo(channel,other);
    }

    /**
     * TODO: this isn't the right place for this method, but I don't know where to put it.
     */
    public static X509Certificate getInstanceIdentityCertificate(InstanceIdentity id, Hudson instance) throws IOException {

        try {
            Date firstDate = new Date();
            Date lastDate = new Date(firstDate.getTime()+ TimeUnit.DAYS.toMillis(365));

            CertificateValidity interval = new CertificateValidity(firstDate, lastDate);

            X500Name subject = new X500Name(instance.getRootUrl(), "", "", "US");
            X509CertInfo info = new X509CertInfo();
            info.set(X509CertInfo.VERSION,new CertificateVersion(CertificateVersion.V3));
            info.set(X509CertInfo.SERIAL_NUMBER,new CertificateSerialNumber(1));
            info.set(X509CertInfo.ALGORITHM_ID,new CertificateAlgorithmId(AlgorithmId.get("SHA1WithRSA")));
            info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(subject));
            info.set(X509CertInfo.KEY, new CertificateX509Key(id.getPublic()));
            info.set(X509CertInfo.VALIDITY, interval);
            info.set(X509CertInfo.ISSUER,   new CertificateIssuerName(subject));

            // sign it
            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(id.getPrivate(), "SHA1withRSA");

            return cert;
        } catch (GeneralSecurityException e) {
            throw new IOException2("Failed to generate a certificate",e);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MetaNectarAgentProtocol.class.getName());
}
