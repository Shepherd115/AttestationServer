package app.attestation.server;

import app.attestation.server.AttestationProtocol.DeviceInfo;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonWriter;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.bouncycastle.crypto.generators.SCrypt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

import static app.attestation.server.AttestationProtocol.fingerprintsCustomOS;
import static app.attestation.server.AttestationProtocol.fingerprintsStock;
import static app.attestation.server.AttestationProtocol.fingerprintsStrongBoxCustomOS;
import static app.attestation.server.AttestationProtocol.fingerprintsStrongBoxStock;
import static com.almworks.sqlite4java.SQLiteConstants.SQLITE_CONSTRAINT_UNIQUE;

public class AttestationServer {
    private static final File SAMPLES_DATABASE = new File("samples.db");
    private static final int MAX_SAMPLE_SIZE = 64 * 1024;

    private static final int DEFAULT_VERIFY_INTERVAL = 4 * 60 * 60;
    private static final int MIN_VERIFY_INTERVAL = 60 * 60;
    private static final int MAX_VERIFY_INTERVAL = 7 * 24 * 70 * 60;
    private static final int DEFAULT_ALERT_DELAY = 48 * 60 * 60;
    private static final int MIN_ALERT_DELAY = 32 * 60 * 60;
    private static final int MAX_ALERT_DELAY = 2 * 7 * 24 * 60 * 60;
    private static final int BUSY_TIMEOUT = 10 * 1000;
    private static final int QR_CODE_PIXEL_SIZE = 300;
    private static final long SESSION_LENGTH = 48 * 60 * 60 * 1000;
    private static final int HISTORY_PER_PAGE = 20;

    private static final String DOMAIN = "attestation.app";
    private static final String ORIGIN = "https://" + DOMAIN;

    private static final Logger logger = Logger.getLogger(AttestationServer.class.getName());

    // This should be moved to a table in the database so that it can be modified dynamically
    // without modifying the source code.
    private static final String[] emailBlacklistPatterns = {
        "(contact|security|webmaster)@(attestation.app|grapheneos.org|seamlessupdate.app)"
    };

    private static final Cache<ByteBuffer, Boolean> pendingChallenges = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(1000000)
            .build();

    static void open(final SQLiteConnection conn, final boolean readOnly) throws SQLiteException {
        if (readOnly) {
            conn.openReadonly();
        } else {
            conn.open();
        }
        conn.setBusyTimeout(BUSY_TIMEOUT);
        conn.exec("PRAGMA foreign_keys = ON");
        conn.exec("PRAGMA journal_mode = WAL");
    }

    private static void createAttestationTables(final SQLiteConnection conn) throws SQLiteException {
        conn.exec(
                "CREATE TABLE IF NOT EXISTS Configuration (\n" +
                "key TEXT PRIMARY KEY NOT NULL,\n" +
                "value ANY NOT NULL\n" +
                ") STRICT");

        conn.exec(
                "CREATE TABLE IF NOT EXISTS Accounts (\n" +
                "userId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n" +
                "username TEXT NOT NULL COLLATE NOCASE UNIQUE,\n" +
                "passwordHash BLOB NOT NULL,\n" +
                "passwordSalt BLOB NOT NULL,\n" +
                "subscribeKey BLOB NOT NULL,\n" +
                "creationTime INTEGER NOT NULL,\n" +
                "loginTime INTEGER NOT NULL,\n" +
                "verifyInterval INTEGER NOT NULL,\n" +
                "alertDelay INTEGER NOT NULL\n" +
                ") STRICT");

        conn.exec(
                "CREATE TABLE IF NOT EXISTS EmailAddresses (\n" +
                "userId INTEGER NOT NULL REFERENCES Accounts (userId) ON DELETE CASCADE,\n" +
                "address TEXT NOT NULL,\n" +
                "PRIMARY KEY (userId, address)\n" +
                ") STRICT");

        conn.exec(
                "CREATE TABLE IF NOT EXISTS Sessions (\n" +
                "sessionId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n" +
                "userId INTEGER NOT NULL REFERENCES Accounts (userId) ON DELETE CASCADE,\n" +
                "cookieToken BLOB NOT NULL,\n" +
                "requestToken BLOB NOT NULL,\n" +
                "expiryTime INTEGER NOT NULL\n" +
                ") STRICT");

        conn.exec(
                "CREATE TABLE IF NOT EXISTS Devices (\n" +
                "fingerprint BLOB NOT NULL PRIMARY KEY,\n" +
                "pinnedCertificate0 BLOB NOT NULL,\n" +
                "pinnedCertificate1 BLOB NOT NULL,\n" +
                "pinnedCertificate2 BLOB NOT NULL,\n" +
                "pinnedCertificate3 BLOB NOT NULL,\n" +
                "pinnedVerifiedBootKey BLOB NOT NULL,\n" +
                "verifiedBootHash BLOB,\n" +
                "pinnedOsVersion INTEGER NOT NULL,\n" +
                "pinnedOsPatchLevel INTEGER NOT NULL,\n" +
                "pinnedVendorPatchLevel INTEGER,\n" +
                "pinnedBootPatchLevel INTEGER,\n" +
                "pinnedAppVersion INTEGER NOT NULL,\n" +
                "pinnedSecurityLevel INTEGER NOT NULL,\n" +
                "userProfileSecure INTEGER NOT NULL CHECK (userProfileSecure in (0, 1)),\n" +
                "enrolledBiometrics INTEGER NOT NULL CHECK (enrolledBiometrics in (0, 1)),\n" +
                "accessibility INTEGER NOT NULL CHECK (accessibility in (0, 1)),\n" +
                "deviceAdmin INTEGER NOT NULL CHECK (deviceAdmin in (0, 1, 2)),\n" +
                "adbEnabled INTEGER NOT NULL CHECK (adbEnabled in (0, 1)),\n" +
                "addUsersWhenLocked INTEGER NOT NULL CHECK (addUsersWhenLocked in (0, 1)),\n" +
                "denyNewUsb INTEGER NOT NULL CHECK (denyNewUsb in (0, 1)),\n" +
                "oemUnlockAllowed INTEGER NOT NULL CHECK (oemUnlockAllowed in (0, 1)),\n" +
                "systemUser INTEGER NOT NULL CHECK (systemUser in (0, 1)),\n" +
                "verifiedTimeFirst INTEGER NOT NULL,\n" +
                "verifiedTimeLast INTEGER NOT NULL,\n" +
                "expiredTimeLast INTEGER,\n" +
                "failureTimeLast INTEGER,\n" +
                "userId INTEGER NOT NULL REFERENCES Accounts (userId) ON DELETE CASCADE,\n" +
                "deletionTime INTEGER\n" +
                ") STRICT");

        conn.exec(
                "CREATE TABLE IF NOT EXISTS Attestations (\n" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n" +
                "fingerprint BLOB NOT NULL REFERENCES Devices (fingerprint) ON DELETE CASCADE,\n" +
                "time INTEGER NOT NULL,\n" +
                "strong INTEGER NOT NULL CHECK (strong in (0, 1)),\n" +
                "teeEnforced TEXT NOT NULL,\n" +
                "osEnforced TEXT NOT NULL\n" +
                ") STRICT");
    }

    private static void createAttestationIndices(final SQLiteConnection conn) throws SQLiteException {
        conn.exec("CREATE INDEX IF NOT EXISTS Accounts_loginTime " +
                "ON Accounts (loginTime)");

        conn.exec("CREATE INDEX IF NOT EXISTS Sessions_expiryTime " +
                "ON Sessions (expiryTime)");
        conn.exec("CREATE INDEX IF NOT EXISTS Sessions_userId " +
                "ON Sessions (userId)");

        conn.exec("CREATE INDEX IF NOT EXISTS Devices_userId_verifiedTimeFirst " +
                "ON Devices (userId, verifiedTimeFirst)");
        conn.exec("CREATE INDEX IF NOT EXISTS Devices_userId_verifiedTimeLast_deletionTimeNull " +
                "ON Devices (userId, verifiedTimeLast) WHERE deletionTime IS NULL");
        conn.exec("CREATE INDEX IF NOT EXISTS Devices_deletionTime " +
                "ON Devices (deletionTime) WHERE deletionTime IS NOT NULL");
        conn.exec("CREATE INDEX IF NOT EXISTS Devices_verifiedTimeLast_deletionTimeNull " +
                "ON Devices (verifiedTimeLast) WHERE deletionTime IS NULL");

        conn.exec("CREATE INDEX IF NOT EXISTS Attestations_fingerprint_id " +
                "ON Attestations (fingerprint, id)");
    }

    private static void createSamplesTable(final SQLiteConnection conn) throws SQLiteException {
        conn.exec(
                "CREATE TABLE IF NOT EXISTS Samples (\n" +
                "sample BLOB NOT NULL,\n" +
                "time INTEGER NOT NULL\n" +
                ") STRICT");
    }

    private static int getUserVersion(final SQLiteConnection conn) throws SQLiteException {
        SQLiteStatement pragmaUserVersion = null;
        try {
            pragmaUserVersion = conn.prepare("PRAGMA user_version");
            pragmaUserVersion.step();
            int userVersion = pragmaUserVersion.columnInt(0);
            logger.info("Existing schema version: " + userVersion);
            return userVersion;
        } finally {
            if (pragmaUserVersion != null) {
                pragmaUserVersion.dispose();
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final SQLiteConnection samplesConn = new SQLiteConnection(SAMPLES_DATABASE);
        try {
            open(samplesConn, false);

            final SQLiteStatement selectCreated = samplesConn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='Samples'");
            if (!selectCreated.step()) {
                samplesConn.exec("PRAGMA user_version = 1");
            }
            selectCreated.dispose();

            int userVersion = getUserVersion(samplesConn);

            createSamplesTable(samplesConn);

            // migrate to STRICT table
            if (userVersion == 0) {
                samplesConn.exec("PRAGMA foreign_keys = OFF");
                samplesConn.exec("BEGIN IMMEDIATE TRANSACTION");

                samplesConn.exec("ALTER TABLE Samples RENAME TO OldSamples");

                createSamplesTable(samplesConn);

                samplesConn.exec("INSERT INTO Samples " +
                        "(sample, time) " +
                        "SELECT " +
                        "sample, time " +
                        "FROM OldSamples");

                samplesConn.exec("DROP TABLE OldSamples");

                samplesConn.exec("PRAGMA user_version = 1");
                samplesConn.exec("COMMIT TRANSACTION");
                userVersion = 1;
                samplesConn.exec("PRAGMA foreign_keys = ON");
            }

            logger.info("New schema version: " + userVersion);

            logger.info("Vacuum database");
            samplesConn.exec("VACUUM");
        } finally {
            samplesConn.dispose();
        }

        final SQLiteConnection attestationConn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        try {
            open(attestationConn, false);

            final SQLiteStatement selectCreated = attestationConn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='Configuration'");
            if (!selectCreated.step()) {
                attestationConn.exec("PRAGMA user_version = 6");
            }
            selectCreated.dispose();

            int userVersion = getUserVersion(attestationConn);

            createAttestationTables(attestationConn);
            createAttestationIndices(attestationConn);

            attestationConn.exec("INSERT OR IGNORE INTO Configuration " +
                    "(key, value) VALUES ('backups', 0)");

            if (userVersion < 4) {
                throw new RuntimeException("Database schemas older than version 4 are no longer " +
                        "supported. Use an older AttestationServer revision to upgrade.");
            }

            // migrate to STRICT tables and add NOT NULL to oemUnlockAllowed/systemUser
            if (userVersion < 6) {
                attestationConn.exec("PRAGMA foreign_keys = OFF");
                attestationConn.exec("BEGIN IMMEDIATE TRANSACTION");

                attestationConn.exec("ALTER TABLE Configuration RENAME TO OldConfiguration");
                attestationConn.exec("ALTER TABLE Accounts RENAME TO OldAccounts");
                attestationConn.exec("ALTER TABLE EmailAddresses RENAME TO OldEmailAddresses");
                attestationConn.exec("ALTER TABLE Sessions RENAME TO OldSessions");
                attestationConn.exec("ALTER TABLE Devices RENAME TO OldDevices");
                attestationConn.exec("ALTER TABLE Attestations RENAME TO OldAttestations");

                createAttestationTables(attestationConn);

                attestationConn.exec("INSERT INTO Configuration " +
                        "(key, value) " +
                        "SELECT " +
                        "key, value " +
                        "FROM OldConfiguration");
                attestationConn.exec("INSERT INTO Accounts " +
                        "(userId, username, passwordHash, passwordSalt, subscribeKey, creationTime, loginTime, verifyInterval, alertDelay) " +
                        "SELECT " +
                        "userId, username, passwordHash, passwordSalt, subscribeKey, creationTime, loginTime, verifyInterval, alertDelay " +
                        "FROM OldAccounts");
                attestationConn.exec("INSERT INTO EmailAddresses " +
                        "(userId, address) " +
                        "SELECT " +
                        "userId, address " +
                        "FROM OldEmailAddresses");
                attestationConn.exec("INSERT INTO Sessions " +
                        "(sessionId, userId, cookieToken, requestToken, expiryTime) " +
                        "SELECT " +
                        "sessionId, userId, cookieToken, requestToken, expiryTime " +
                        "FROM OldSessions");
                attestationConn.exec("INSERT INTO Devices " +
                        "(fingerprint, pinnedCertificate0, pinnedCertificate1, pinnedCertificate2, pinnedCertificate3, pinnedVerifiedBootKey, verifiedBootHash, pinnedOsVersion, pinnedOsPatchLevel, pinnedVendorPatchLevel, pinnedBootPatchLevel, pinnedAppVersion, pinnedSecurityLevel, userProfileSecure, enrolledBiometrics, accessibility, deviceAdmin, adbEnabled, addUsersWhenLocked, denyNewUsb, oemUnlockAllowed, systemUser, verifiedTimeFirst, verifiedTimeLast, expiredTimeLast, failureTimeLast, userId, deletionTime)" +
                        "SELECT " +
                        "fingerprint, pinnedCertificate0, pinnedCertificate1, pinnedCertificate2, pinnedCertificate3, pinnedVerifiedBootKey, verifiedBootHash, pinnedOsVersion, pinnedOsPatchLevel, pinnedVendorPatchLevel, pinnedBootPatchLevel, pinnedAppVersion, pinnedSecurityLevel, userProfileSecure, enrolledBiometrics, accessibility, deviceAdmin, adbEnabled, addUsersWhenLocked, denyNewUsb, oemUnlockAllowed, systemUser, verifiedTimeFirst, verifiedTimeLast, expiredTimeLast, failureTimeLast, userId, deletionTime " +
                        "FROM OldDevices");
                attestationConn.exec("INSERT INTO Attestations " +
                        "(id, fingerprint, time, strong, teeEnforced, osEnforced) " +
                        "SELECT " +
                        "id, fingerprint, time, strong, teeEnforced, osEnforced " +
                        "FROM OldAttestations");

                attestationConn.exec("DROP TABLE OldConfiguration");
                attestationConn.exec("DROP TABLE OldAccounts");
                attestationConn.exec("DROP TABLE OldEmailAddresses");
                attestationConn.exec("DROP TABLE OldSessions");
                attestationConn.exec("DROP TABLE OldDevices");
                attestationConn.exec("DROP TABLE OldAttestations");

                createAttestationIndices(attestationConn);

                attestationConn.exec("PRAGMA user_version = 6");
                attestationConn.exec("COMMIT TRANSACTION");
                userVersion = 6;
                attestationConn.exec("PRAGMA foreign_keys = ON");
                logger.info("Migrated to schema version: " + userVersion);
            }

            logger.info("Analyze database");
            attestationConn.exec("ANALYZE");
            logger.info("Vacuum database");
            attestationConn.exec("VACUUM");

            logger.info("Finished database setup");
        } finally {
            attestationConn.dispose();
        }

        Files.createDirectories(Paths.get("backup"));

        new Thread(new AlertDispatcher()).start();
        new Thread(new Maintenance()).start();

        final ThreadPoolExecutor executor = new ThreadPoolExecutor(128, 128, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1024));
        executor.prestartAllCoreThreads();

        System.setProperty("sun.net.httpserver.nodelay", "true");
        final HttpServer server = HttpServer.create(new InetSocketAddress("::1", 8080), 4096);
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/create-account", new CreateAccountHandler());
        server.createContext("/api/change-password", new ChangePasswordHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/logout", new LogoutHandler());
        server.createContext("/api/logout-everywhere", new LogoutEverywhereHandler());
        server.createContext("/api/rotate", new RotateHandler());
        server.createContext("/api/account", new AccountHandler());
        server.createContext("/api/account.png", new AccountQrHandler());
        server.createContext("/api/configuration", new ConfigurationHandler());
        server.createContext("/api/delete-device", new DeleteDeviceHandler());
        server.createContext("/api/devices.json", new DevicesHandler());
        server.createContext("/api/attestation-history.json", new AttestationHistoryHandler());
        server.createContext("/challenge", new ChallengeHandler());
        server.createContext("/verify", new VerifyHandler());
        server.createContext("/submit", new SubmitHandler());
        server.setExecutor(executor);
        server.start();
    }

    private static class StatusHandler implements HttpHandler {
        @Override
        public final void handle(final HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                final byte[] response = "success\n".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                try (final OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                }
            } catch (final Exception e) {
                logger.log(Level.SEVERE, "unhandled error handling request", e);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        }
    }

    private abstract static class PostHandler implements HttpHandler {
        protected abstract void handlePost(final HttpExchange exchange) throws IOException, SQLiteException;

        public void checkOrigin(final HttpExchange exchange) throws GeneralSecurityException {
            final List<String> origin = exchange.getRequestHeaders().get("Origin");
            if (origin != null && !origin.get(0).equals(ORIGIN)) {
                throw new GeneralSecurityException();
            }
            final List<String> fetchMode = exchange.getRequestHeaders().get("Sec-Fetch-Mode");
            if (fetchMode != null && !fetchMode.get(0).equals("same-origin")) {
                throw new GeneralSecurityException();
            }
            final List<String> fetchSite = exchange.getRequestHeaders().get("Sec-Fetch-Site");
            if (fetchSite != null && !fetchSite.get(0).equals("same-origin")) {
                throw new GeneralSecurityException();
            }
        }

        @Override
        public final void handle(final HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                try {
                    checkOrigin(exchange);
                } catch (final GeneralSecurityException e) {
                    logger.log(Level.INFO, "invalid origin headers", e);
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                handlePost(exchange);
            } catch (final Exception e) {
                logger.log(Level.SEVERE, "unhandled error handling request", e);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        }
    }

    private abstract static class AppPostHandler extends PostHandler {
        @Override
        public void checkOrigin(final HttpExchange exchange) {}
    }

    private static final SecureRandom random = new SecureRandom();

    private static byte[] generateRandomToken() {
        final byte[] token = new byte[32];
        random.nextBytes(token);
        return token;
    }

    private static byte[] hash(final byte[] password, final byte[] salt) {
        return SCrypt.generate(password, salt, 32768, 8, 1, 32);
    }

    private static class UsernameUnavailableException extends GeneralSecurityException {
        public UsernameUnavailableException() {}
    }

    private static void validateUsername(final String username) throws GeneralSecurityException {
        if (username.length() > 32 || !username.matches("[a-zA-Z0-9]+")) {
            throw new GeneralSecurityException("invalid username");
        }
    }

    private static void validateUnicode(final String s) throws CharacterCodingException {
        StandardCharsets.UTF_16LE.newEncoder().encode(CharBuffer.wrap(s));
    }

    private static void validatePassword(final String password) throws GeneralSecurityException {
        if (password.length() < 8 || password.length() > 256) {
            throw new GeneralSecurityException("invalid password");
        }

        try {
            validateUnicode(password);
        } catch (final CharacterCodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static void createAccount(final String username, final String password)
            throws GeneralSecurityException, SQLiteException {
        validateUsername(username);
        validatePassword(password);

        final byte[] passwordSalt = generateRandomToken();
        final byte[] passwordHash = hash(password.getBytes(), passwordSalt);
        final byte[] subscribeKey = generateRandomToken();

        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        try {
            open(conn, false);
            final SQLiteStatement insert = conn.prepare("INSERT INTO Accounts " +
                    "(username, passwordHash, passwordSalt, subscribeKey, creationTime, loginTime, verifyInterval, alertDelay) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            insert.bind(1, username);
            insert.bind(2, passwordHash);
            insert.bind(3, passwordSalt);
            insert.bind(4, subscribeKey);
            final long now = System.currentTimeMillis();
            insert.bind(5, now);
            insert.bind(6, now);
            insert.bind(7, DEFAULT_VERIFY_INTERVAL);
            insert.bind(8, DEFAULT_ALERT_DELAY);
            insert.step();
            insert.dispose();
        } catch (final SQLiteException e) {
            if (e.getErrorCode() == SQLITE_CONSTRAINT_UNIQUE) {
                throw new UsernameUnavailableException();
            }
            throw e;
        } finally {
            conn.dispose();
        }
    }

    private static void changePassword(final long userId, final String currentPassword, final String newPassword)
            throws GeneralSecurityException, SQLiteException {
        validatePassword(currentPassword);
        validatePassword(newPassword);

        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        try {
            open(conn, false);

            conn.exec("BEGIN IMMEDIATE TRANSACTION");

            final SQLiteStatement select = conn.prepare("SELECT passwordHash, passwordSalt " +
                    "FROM Accounts WHERE userId = ?");
            select.bind(1, userId);
            select.step();
            final byte[] currentPasswordHash = select.columnBlob(0);
            final byte[] currentPasswordSalt = select.columnBlob(1);
            select.dispose();
            if (!MessageDigest.isEqual(hash(currentPassword.getBytes(), currentPasswordSalt), currentPasswordHash)) {
                throw new GeneralSecurityException("invalid password");
            }

            final byte[] newPasswordSalt = generateRandomToken();
            final byte[] newPasswordHash = hash(newPassword.getBytes(), newPasswordSalt);

            final SQLiteStatement update = conn.prepare("UPDATE Accounts " +
                    "SET passwordHash = ?, passwordSalt = ? WHERE userId = ?");
            update.bind(1, newPasswordHash);
            update.bind(2, newPasswordSalt);
            update.bind(3, userId);
            update.step();
            update.dispose();

            conn.exec("COMMIT TRANSACTION");
        } finally {
            conn.dispose();
        }
    }

    private static class Session {
        final long sessionId;
        final byte[] cookieToken;
        final byte[] requestToken;

        Session(final long sessionId, final byte[] cookieToken, final byte[] requestToken) {
            this.sessionId = sessionId;
            this.cookieToken = cookieToken;
            this.requestToken = requestToken;
        }
    }

    private static Session login(final String username, final String password)
            throws GeneralSecurityException, SQLiteException {
        validatePassword(password);

        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        try {
            open(conn, false);

            conn.exec("BEGIN IMMEDIATE TRANSACTION");

            final SQLiteStatement select = conn.prepare("SELECT userId, passwordHash, " +
                    "passwordSalt FROM Accounts WHERE username = ?");
            select.bind(1, username);
            if (!select.step()) {
                throw new UsernameUnavailableException();
            }
            final long userId = select.columnLong(0);
            final byte[] passwordHash = select.columnBlob(1);
            final byte[] passwordSalt = select.columnBlob(2);
            select.dispose();
            if (!MessageDigest.isEqual(hash(password.getBytes(), passwordSalt), passwordHash)) {
                throw new GeneralSecurityException("invalid password");
            }

            final long now = System.currentTimeMillis();
            final SQLiteStatement delete = conn.prepare("DELETE FROM Sessions WHERE expiryTime < ?");
            delete.bind(1, now);
            delete.step();
            delete.dispose();

            final byte[] cookieToken = generateRandomToken();
            final byte[] requestToken = generateRandomToken();

            final SQLiteStatement insert = conn.prepare("INSERT INTO Sessions " +
                    "(userId, cookieToken, requestToken, expiryTime) VALUES (?, ?, ?, ?)");
            insert.bind(1, userId);
            insert.bind(2, cookieToken);
            insert.bind(3, requestToken);
            insert.bind(4, now + SESSION_LENGTH);
            insert.step();
            insert.dispose();

            final SQLiteStatement updateLoginTime = conn.prepare("UPDATE Accounts SET " +
                    "loginTime = ? WHERE userId = ?");
            updateLoginTime.bind(1, now);
            updateLoginTime.bind(2, userId);
            updateLoginTime.step();
            updateLoginTime.dispose();

            conn.exec("COMMIT TRANSACTION");

            return new Session(conn.getLastInsertId(), cookieToken, requestToken);
        } finally {
            conn.dispose();
        }
    }

    private static class CreateAccountHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final String username;
            final String password;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                username = object.getString("username");
                password = object.getString("password");
            } catch (final ClassCastException | JsonException | NullPointerException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            try {
                createAccount(username, password);
            } catch (final UsernameUnavailableException e) {
                exchange.sendResponseHeaders(409, -1);
                return;
            } catch (final GeneralSecurityException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static class ChangePasswordHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final String requestToken;
            final String currentPassword;
            final String newPassword;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                requestToken = object.getString("requestToken");
                currentPassword = object.getString("currentPassword");
                newPassword = object.getString("newPassword");
            } catch (final ClassCastException | JsonException | NullPointerException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final Account account = verifySession(exchange, false, requestToken.getBytes(StandardCharsets.UTF_8));
            if (account == null) {
                return;
            }

            try {
                changePassword(account.userId, currentPassword, newPassword);
            } catch (final GeneralSecurityException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static class LoginHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final String username;
            final String password;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                username = object.getString("username");
                password = object.getString("password");
            } catch (final ClassCastException | JsonException | NullPointerException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final Session session;
            try {
                session = login(username, password);
            } catch (final UsernameUnavailableException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            } catch (final GeneralSecurityException e) {
                logger.log(Level.INFO, "invalid login information", e);
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            final Base64.Encoder encoder = Base64.getEncoder();
            final byte[] requestToken = encoder.encode(session.requestToken);
            exchange.getResponseHeaders().set("Set-Cookie",
                    String.format("__Host-session=%d|%s; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=%d",
                        session.sessionId, new String(encoder.encode(session.cookieToken)),
                        SESSION_LENGTH / 1000));
            exchange.sendResponseHeaders(200, requestToken.length);
            try (final OutputStream output = exchange.getResponseBody()) {
                output.write(requestToken);
            }
        }
    }

    private static class LogoutHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, true, null);
            if (account == null) {
                return;
            }
            purgeSessionCookie(exchange);
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static class LogoutEverywhereHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, false, null);
            if (account == null) {
                return;
            }
            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, false);

                final SQLiteStatement select = conn.prepare("DELETE FROM Sessions WHERE userId = ?");
                select.bind(1, account.userId);
                select.step();
                select.dispose();
            } finally {
                conn.dispose();
            }
            purgeSessionCookie(exchange);
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static class RotateHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, false, null);
            if (account == null) {
                return;
            }
            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, false);

                final byte[] subscribeKey = generateRandomToken();

                final SQLiteStatement select = conn.prepare("UPDATE Accounts SET " +
                        "subscribeKey = ? WHERE userId = ?");
                select.bind(1, subscribeKey);
                select.bind(2, account.userId);
                select.step();
                select.dispose();
            } finally {
                conn.dispose();
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static String getCookie(final HttpExchange exchange, final String key) {
        final List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (final String cookieHeader : cookieHeaders) {
            final String[] cookies = cookieHeader.split(";");
            for (final String cookie : cookies) {
                final String[] keyValue = cookie.trim().split("=", 2);
                if (keyValue.length == 2) {
                    if (keyValue[0].equals(key)) {
                        return keyValue[1];
                    }
                }
            }
        }
        return null;
    }

    private static void purgeSessionCookie(final HttpExchange exchange) {
        exchange.getResponseHeaders().set("Set-Cookie",
                "__Host-session=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0");
    }

    private static class Account {
        final long userId;
        final String username;
        final byte[] subscribeKey;
        final int verifyInterval;
        final int alertDelay;

        Account(final long userId, final String username, final byte[] subscribeKey,
                final int verifyInterval, final int alertDelay) {
            this.userId = userId;
            this.username = username;
            this.subscribeKey = subscribeKey;
            this.verifyInterval = verifyInterval;
            this.alertDelay = alertDelay;
        }
    }

    private static Account verifySession(final HttpExchange exchange, final boolean end, byte[] requestTokenEncoded)
            throws IOException, SQLiteException {
        final String cookie = getCookie(exchange, "__Host-session");
        if (cookie == null) {
            exchange.sendResponseHeaders(403, -1);
            return null;
        }
        final String[] session = cookie.split("\\|", 2);
        if (session.length != 2) {
            purgeSessionCookie(exchange);
            exchange.sendResponseHeaders(403, -1);
            return null;
        }
        final long sessionId = Long.parseLong(session[0]);
        final byte[] cookieToken = Base64.getDecoder().decode(session[1]);

        if (requestTokenEncoded == null) {
            requestTokenEncoded = new byte[session[1].length()];
            final DataInputStream input = new DataInputStream(exchange.getRequestBody());
            try {
                input.readFully(requestTokenEncoded);
            } catch (final EOFException e) {
                purgeSessionCookie(exchange);
                exchange.sendResponseHeaders(403, -1);
                return null;
            }
        }
        final byte[] requestToken = Base64.getDecoder().decode(requestTokenEncoded);

        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        try {
            open(conn, !end);

            final SQLiteStatement select = conn.prepare("SELECT cookieToken, requestToken, " +
                    "expiryTime, username, subscribeKey, Accounts.userId, verifyInterval, alertDelay " +
                    "FROM Sessions " +
                    "INNER JOIN Accounts on Accounts.userId = Sessions.userId " +
                    "WHERE sessionId = ?");
            select.bind(1, sessionId);
            if (!select.step() || !MessageDigest.isEqual(cookieToken, select.columnBlob(0)) ||
                    !MessageDigest.isEqual(requestToken, select.columnBlob(1))) {
                purgeSessionCookie(exchange);
                exchange.sendResponseHeaders(403, -1);
                return null;
            }

            if (select.columnLong(2) < System.currentTimeMillis()) {
                purgeSessionCookie(exchange);
                exchange.sendResponseHeaders(403, -1);
                return null;
            }

            if (end) {
                final SQLiteStatement delete = conn.prepare("DELETE FROM Sessions " +
                        "WHERE sessionId = ?");
                delete.bind(1, sessionId);
                delete.step();
                delete.dispose();
            }

            return new Account(select.columnLong(5), select.columnString(3), select.columnBlob(4),
                    select.columnInt(6), select.columnInt(7));
        } finally {
            conn.dispose();
        }
    }

    private static class AccountHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, false, null);
            if (account == null) {
                return;
            }
            final JsonObjectBuilder accountJson = Json.createObjectBuilder();
            accountJson.add("username", account.username);
            accountJson.add("verifyInterval", account.verifyInterval);
            accountJson.add("alertDelay", account.alertDelay);

            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, true);
                final SQLiteStatement select = conn.prepare("SELECT address FROM EmailAddresses " +
                        "WHERE userId = ?");
                select.bind(1, account.userId);
                if (select.step()) {
                    accountJson.add("email", select.columnString(0));
                }
                select.dispose();
            } finally {
                conn.dispose();
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (final OutputStream output = exchange.getResponseBody();
                    final JsonWriter writer = Json.createWriter(output)) {
                writer.write(accountJson.build());
            }
        }
    }

    private static void writeQrCode(final byte[] contents, final OutputStream output) throws IOException {
        try {
            final QRCodeWriter writer = new QRCodeWriter();
            final Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1);
            final BitMatrix result = writer.encode(new String(contents, StandardCharsets.ISO_8859_1),
                    BarcodeFormat.QR_CODE, QR_CODE_PIXEL_SIZE, QR_CODE_PIXEL_SIZE, hints);
            MatrixToImageWriter.writeToStream(result, "png", output);
        } catch (WriterException e) {
            throw new RuntimeException(e);
        }
    }

    private static class AccountQrHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, false, null);
            if (account == null) {
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            try (final OutputStream output = exchange.getResponseBody()) {
                final String contents = DOMAIN + " " +
                    account.userId + " " +
                    BaseEncoding.base64().encode(account.subscribeKey) + " " +
                    account.verifyInterval;
                writeQrCode(contents.getBytes(), output);
            }
        }
    }

    private static class ConfigurationHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final int verifyInterval;
            final int alertDelay;
            final String email;
            final String requestToken;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                requestToken = object.getString("requestToken");
                verifyInterval = object.getInt("verifyInterval");
                alertDelay = object.getInt("alertDelay");
                email = object.getString("email").trim();
            } catch (final ClassCastException | JsonException | NullPointerException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final Account account = verifySession(exchange, false, requestToken.getBytes(StandardCharsets.UTF_8));
            if (account == null) {
                return;
            }

            if (verifyInterval < MIN_VERIFY_INTERVAL || verifyInterval > MAX_VERIFY_INTERVAL) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            if (alertDelay < MIN_ALERT_DELAY || alertDelay > MAX_ALERT_DELAY || alertDelay <= verifyInterval) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            if (!email.isEmpty()) {
                try {
                    new InternetAddress(email).validate();
                    for (final String emailBlacklistPattern : emailBlacklistPatterns) {
                        if (email.matches(emailBlacklistPattern)) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                    }
                } catch (final AddressException e) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }

            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, false);

                conn.exec("BEGIN IMMEDIATE TRANSACTION");

                final SQLiteStatement update = conn.prepare("UPDATE Accounts SET " +
                        "verifyInterval = ?, alertDelay = ? WHERE userId = ?");
                update.bind(1, verifyInterval);
                update.bind(2, alertDelay);
                update.bind(3, account.userId);
                update.step();
                update.dispose();

                final SQLiteStatement delete = conn.prepare("DELETE FROM EmailAddresses " +
                        "WHERE userId = ?");
                delete.bind(1, account.userId);
                delete.step();
                delete.dispose();

                if (!email.isEmpty()) {
                    final SQLiteStatement insert = conn.prepare("INSERT INTO EmailAddresses " +
                            "(userId, address) VALUES (?, ?)");
                    insert.bind(1, account.userId);
                    insert.bind(2, email);
                    insert.step();
                    insert.dispose();
                }

                conn.exec("COMMIT TRANSACTION");
            } finally {
                conn.dispose();
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static class DeleteDeviceHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final String requestToken;
            final String fingerprint;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                requestToken = object.getString("requestToken");
                fingerprint = object.getString("fingerprint");
            } catch (final ClassCastException | JsonException | NullPointerException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final Account account = verifySession(exchange, false, requestToken.getBytes(StandardCharsets.UTF_8));
            if (account == null) {
                return;
            }

            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, false);

                final SQLiteStatement update = conn.prepare("UPDATE Devices SET " +
                        "deletionTime = ? WHERE userId = ? AND fingerprint = ?");
                update.bind(1, System.currentTimeMillis());
                update.bind(2, account.userId);
                update.bind(3, BaseEncoding.base16().decode(fingerprint));
                update.step();
                update.dispose();

                if (conn.getChanges() == 0) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            } finally {
                conn.dispose();
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static String convertToPem(final byte[] derEncoded) {
        return "-----BEGIN CERTIFICATE-----\n" +
                new String(Base64.getMimeEncoder(64, "\n".getBytes()).encode(derEncoded)) +
                "\n-----END CERTIFICATE-----";
    }

    private static void writeDevicesJson(final HttpExchange exchange, final long userId)
            throws IOException, SQLiteException {
        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        final JsonArrayBuilder devices = Json.createArrayBuilder();
        try {
            open(conn, true);

            final SQLiteStatement select = conn.prepare("SELECT fingerprint, " +
                    "pinnedCertificate0, pinnedCertificate1, pinnedCertificate2, " +
                    "pinnedCertificate3, hex(pinnedVerifiedBootKey), " +
                    "(SELECT hex(verifiedBootHash) where verifiedBootHash IS NOT NULL), " +
                    "pinnedOsVersion, pinnedOsPatchLevel, pinnedVendorPatchLevel, " +
                    "pinnedBootPatchLevel, pinnedAppVersion, pinnedSecurityLevel, " +
                    "userProfileSecure, enrolledBiometrics, accessibility, deviceAdmin, " +
                    "adbEnabled, addUsersWhenLocked, denyNewUsb, oemUnlockAllowed, " +
                    "systemUser, verifiedTimeFirst, verifiedTimeLast " +
                    "FROM Devices WHERE userId is ? AND deletionTime IS NULL " +
                    "ORDER BY verifiedTimeFirst");
            select.bind(1, userId);
            while (select.step()) {
                final JsonObjectBuilder device = Json.createObjectBuilder();
                final byte[] fingerprint = select.columnBlob(0);
                device.add("fingerprint", BaseEncoding.base16().encode(fingerprint));
                device.add("pinnedCertificate0", convertToPem(select.columnBlob(1)));
                device.add("pinnedCertificate1", convertToPem(select.columnBlob(2)));
                device.add("pinnedCertificate2", convertToPem(select.columnBlob(3)));
                device.add("pinnedCertificate3", convertToPem(select.columnBlob(4)));
                final String verifiedBootKey = select.columnString(5);
                device.add("verifiedBootKey", verifiedBootKey);
                DeviceInfo info;
                final int pinnedSecurityLevel = select.columnInt(12);
                if (pinnedSecurityLevel == AttestationProtocol.SECURITY_LEVEL_STRONGBOX) {
                    info = fingerprintsStrongBoxCustomOS.get(verifiedBootKey);
                    if (info == null) {
                        info = fingerprintsStrongBoxStock.get(verifiedBootKey);
                        if (info == null) {
                            throw new RuntimeException("invalid fingerprint");
                        }
                    }
                } else {
                    info = fingerprintsCustomOS.get(verifiedBootKey);
                    if (info == null) {
                        info = fingerprintsStock.get(verifiedBootKey);
                        if (info == null) {
                            throw new RuntimeException("invalid fingerprint");
                        }
                    }
                }
                device.add("osName", info.osName);
                device.add("name", info.name);
                if (!select.columnNull(6)) {
                    device.add("verifiedBootHash", select.columnString(6));
                }
                device.add("pinnedOsVersion", select.columnInt(7));
                device.add("pinnedOsPatchLevel", select.columnInt(8));
                if (!select.columnNull(9)) {
                    device.add("pinnedVendorPatchLevel", select.columnInt(9));
                }
                if (!select.columnNull(10)) {
                    device.add("pinnedBootPatchLevel", select.columnInt(10));
                }
                device.add("pinnedAppVersion", select.columnInt(11));
                device.add("pinnedSecurityLevel", pinnedSecurityLevel);
                device.add("userProfileSecure", select.columnInt(13));
                device.add("enrolledBiometrics", select.columnInt(14));
                device.add("accessibility", select.columnInt(15));
                device.add("deviceAdmin", select.columnInt(16));
                device.add("adbEnabled", select.columnInt(17));
                device.add("addUsersWhenLocked", select.columnInt(18));
                device.add("denyNewUsb", select.columnInt(19));
                device.add("oemUnlockAllowed", select.columnInt(20));
                device.add("systemUser", select.columnInt(21));
                device.add("verifiedTimeFirst", select.columnLong(22));
                device.add("verifiedTimeLast", select.columnLong(23));
                final SQLiteStatement devicesAttestationsLatestSelect = conn.prepare(
                        "SELECT MIN(id), MAX(id) FROM Attestations WHERE fingerprint = ?");
                devicesAttestationsLatestSelect.bind(1, fingerprint);
                if (devicesAttestationsLatestSelect.step()) {
                    device.add("minId", devicesAttestationsLatestSelect.columnLong(0));
                    device.add("maxId", devicesAttestationsLatestSelect.columnLong(1));
                }
                devicesAttestationsLatestSelect.dispose();
                devices.add(device);
            }
            select.dispose();
        } finally {
            conn.dispose();
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try (final OutputStream output = exchange.getResponseBody();
                final JsonWriter writer = Json.createWriter(output)) {
            writer.write(devices.build());
        }
    }

    private static class DevicesHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final Account account = verifySession(exchange, false, null);
            if (account == null) {
                return;
            }
            writeDevicesJson(exchange, account.userId);
        }
    }

    private static class AttestationHistoryHandler extends PostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            String fingerprint;
            try (final JsonReader reader = Json.createReader(exchange.getRequestBody())) {
                final JsonObject object = reader.readObject();
                fingerprint = object.getString("fingerprint");
                long offsetId = object.getJsonNumber("offsetId").longValue();
                String requestToken = object.getString("requestToken");
                final Account account = verifySession(exchange, false, requestToken.getBytes(StandardCharsets.UTF_8));
                if (account == null) {
                    return;
                }
                writeAttestationHistoryJson(exchange, fingerprint, account, offsetId);
            } catch (final ClassCastException | JsonException | NullPointerException | NumberFormatException | GeneralSecurityException e) {
                logger.log(Level.INFO, "invalid request", e);
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }

    private static void writeAttestationHistoryJson(final HttpExchange exchange, final String deviceFingerprint,
            final Account userAccount, final long offsetId)
            throws IOException, SQLiteException, GeneralSecurityException {
        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        final JsonArrayBuilder attestations = Json.createArrayBuilder();

        final byte[] fingerprint = BaseEncoding.base16().decode(deviceFingerprint);
        SQLiteStatement history;
        try {
            open(conn, true);
            history = conn.prepare("SELECT time, strong, teeEnforced, " +
                "osEnforced, id FROM Attestations INNER JOIN Devices ON " +
                "Attestations.fingerprint = Devices.fingerprint " +
                "WHERE Devices.fingerprint = ? AND userid = ? " +
                "AND Attestations.id <= ? ORDER BY id DESC LIMIT " + HISTORY_PER_PAGE);
            history.bind(1, fingerprint);
            history.bind(2, userAccount.userId);
            history.bind(3, offsetId);
            int rowCount = 0;
            while (history.step()) {
                attestations.add(Json.createObjectBuilder()
                    .add("time", history.columnLong(0))
                    .add("strong", history.columnInt(1) != 0)
                    .add("teeEnforced", history.columnString(2))
                    .add("osEnforced", history.columnString(3))
                    .add("id", history.columnInt(4)));
                rowCount += 1;
            }
            history.dispose();
            if (rowCount == 0) {
                throw new GeneralSecurityException("found no attestation history for userId: " + userAccount.userId +
                        ", fingerprint: " + deviceFingerprint);
            }
        } finally {
            conn.dispose();
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try (final OutputStream output = exchange.getResponseBody();
                final JsonWriter writer = Json.createWriter(output)) {
            writer.write(attestations.build());
        }
    }

    private static class ChallengeHandler extends AppPostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException {
            final byte[] challenge = AttestationProtocol.getChallenge();
            pendingChallenges.put(ByteBuffer.wrap(challenge), true);

            final byte[] challengeMessage =
                    Bytes.concat(new byte[]{AttestationProtocol.PROTOCOL_VERSION},
                            new byte[AttestationProtocol.CHALLENGE_LENGTH], challenge);

            exchange.sendResponseHeaders(200, challengeMessage.length);
            try (final OutputStream output = exchange.getResponseBody()) {
                output.write(challengeMessage);
            }
        }
    }

    private static class VerifyHandler extends AppPostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final List<String> authorization = exchange.getRequestHeaders().get("Authorization");
            if (authorization == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            final String[] tokens = authorization.get(0).split(" ");
            if (!tokens[0].equals("Auditor") || tokens.length < 2 || tokens.length > 3) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            final long userId = Long.parseLong(tokens[1]);
            final String subscribeKey = tokens.length == 3 ? tokens[2] : null;

            final byte[] currentSubscribeKey;
            final int verifyInterval;
            final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
            try {
                open(conn, true);

                final SQLiteStatement select = conn.prepare("SELECT subscribeKey, verifyInterval " +
                        "FROM Accounts WHERE userId = ?");
                select.bind(1, userId);
                if (!select.step()) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                currentSubscribeKey = select.columnBlob(0);
                verifyInterval = select.columnInt(1);
                select.dispose();
            } finally {
                conn.dispose();
            }

            if (subscribeKey != null && !MessageDigest.isEqual(BaseEncoding.base64().decode(subscribeKey),
                    currentSubscribeKey)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final InputStream input = exchange.getRequestBody();

            final ByteArrayOutputStream attestation = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                attestation.write(buffer, 0, read);

                if (attestation.size() > AttestationProtocol.MAX_MESSAGE_SIZE) {
                    final byte[] response = "Attestation too large".getBytes();
                    exchange.sendResponseHeaders(400, response.length);
                    try (final OutputStream output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                    return;
                }
            }

            final byte[] attestationResult = attestation.toByteArray();

            try {
                AttestationProtocol.verifySerialized(attestationResult, pendingChallenges, userId, subscribeKey == null);
            } catch (final BufferUnderflowException | NegativeArraySizeException | DataFormatException | GeneralSecurityException | IOException e) {
                logger.log(Level.INFO, "invalid request", e);
                final byte[] response = "Error\n".getBytes();
                exchange.sendResponseHeaders(400, response.length);
                try (final OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                }
                return;
            }

            final byte[] result = (BaseEncoding.base64().encode(currentSubscribeKey) + " " +
                    verifyInterval).getBytes();
            exchange.sendResponseHeaders(200, result.length);
            try (final OutputStream output = exchange.getResponseBody()) {
                output.write(result);
            }
        }
    }

    private static class SubmitHandler extends AppPostHandler {
        @Override
        public void handlePost(final HttpExchange exchange) throws IOException, SQLiteException {
            final InputStream input = exchange.getRequestBody();

            final ByteArrayOutputStream sample = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                if (sample.size() + read > MAX_SAMPLE_SIZE) {
                    logger.warning("sample submission beyond size limit");
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }

                sample.write(buffer, 0, read);
            }

            if (sample.size() == 0) {
                logger.warning("empty sample submission");
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final SQLiteConnection conn = new SQLiteConnection(SAMPLES_DATABASE);
            try {
                open(conn, false);
                final SQLiteStatement insert = conn.prepare("INSERT INTO Samples " +
                       "(sample, time) VALUES (?, ?)");
                insert.bind(1, sample.toByteArray());
                insert.bind(2, System.currentTimeMillis());
                insert.step();
                insert.dispose();
            } finally {
                conn.dispose();
            }

            exchange.sendResponseHeaders(200, -1);
        }
    }
}
