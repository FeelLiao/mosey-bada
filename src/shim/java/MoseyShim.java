import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public final class MoseyShim {
    private static final String TAG = "MoseyShim";
    private static final String AIRDROP_SERVICE = "_airdrop._tcp";
    private static final String AIRDROP_UUID = "0000fcf1-0000-1000-8000-00805f9b34fb";
    private static final String EVENT_SOCKET = "/data/local/tmp/mosey-shim-events.sock";
    private static final String KEYSTORE = "/odm/etc/mosey-shim/mosey-shim.p12";
    private static final char[] KEYSTORE_PASSWORD = "mosey".toCharArray();
    private static final int BRIDGE_PORT = 19539;

    private final Context context;
    private final String deviceName;
    private NsdManager nsdManager;
    private SSLServerSocket httpSocket;
    private int httpPort;
    private NsdServiceInfo registeredService;
    private final Set<String> seenServices = new HashSet<>();
    private volatile boolean bridgeRadioStarted;
    private volatile boolean bleAdvertisingStarted;
    private volatile boolean bleScanStarted;

    private MoseyShim(Context context) {
        this.context = context;
        this.deviceName = readDeviceName();
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("mosey_shim");
        Looper.prepare();
        Log.i(TAG, "Mosey shim starting");
        Context context = systemContext();
        MoseyShim shim = new MoseyShim(context);
        shim.startBridgeRadio();
        shim.startHttpsDiscoverServer();
        shim.startNsdDiscovery();
        shim.registerAirDropService();
        shim.startBleRetryLoop();
        Log.i(TAG, "Mosey shim started");
        new CountDownLatch(1).await();
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        if (context == null) {
            throw new IllegalStateException("system context is null");
        }
        return context;
    }

    private String readDeviceName() {
        String model = android.os.Build.MODEL;
        if (model == null || model.length() == 0) {
            model = "Bada";
        }
        return model + " Bada";
    }

    private void startBridgeRadio() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", BRIDGE_PORT), 2000);
            socket.setSoTimeout(3000);
            sendBridgeCommand(socket, 3, "US".getBytes(StandardCharsets.US_ASCII));
            byte[] channels = ByteBuffer.allocate(1 + 3 * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 3)
                    .putInt(149)
                    .putInt(44)
                    .putInt(6)
                    .array();
            sendBridgeCommand(socket, 1, channels);
            bridgeRadioStarted = true;
            Log.i(TAG, "Requested bridge radio start on channels [149,44,6]");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to pre-start bridge radio", t);
        }
    }

    private void startBleRetryLoop() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    if (!bridgeRadioStarted) {
                        startBridgeRadio();
                    }
                    if (!bleAdvertisingStarted) {
                        startBleWakeupAdvertising();
                    }
                    if (!bleScanStarted) {
                        startAppleBleScan();
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "mosey_shim_ble_retry");
        thread.setDaemon(true);
        thread.start();
    }

    private void sendBridgeCommand(Socket socket, int command, byte[] params) throws Exception {
        byte[] payload = new byte[1 + params.length];
        payload[0] = (byte) command;
        System.arraycopy(params, 0, payload, 1, params.length);
        OutputStream out = socket.getOutputStream();
        out.write(1);
        writeLe32(out, payload.length);
        out.write(payload);
        out.flush();
        InputStream in = socket.getInputStream();
        readFully(in, new byte[5]);
        int len = readLe32FromLastHeader();
        if (len > 0) {
            readFully(in, new byte[len]);
        }
    }

    private int lastHeaderLen;

    private void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IllegalStateException("short read");
            }
            off += n;
        }
        if (buf.length == 5) {
            lastHeaderLen = ((buf[1] & 0xff) | ((buf[2] & 0xff) << 8)
                    | ((buf[3] & 0xff) << 16) | ((buf[4] & 0xff) << 24));
        }
    }

    private int readLe32FromLastHeader() {
        return lastHeaderLen;
    }

    private static void writeLe32(OutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private void startHttpsDiscoverServer() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(KEYSTORE)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        httpSocket = (SSLServerSocket) factory.createServerSocket(0);
        httpSocket.setReuseAddress(true);
        httpPort = httpSocket.getLocalPort();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                serveHttpsLoop();
            }
        }, "mosey_shim_https");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "HTTPS /Discover server listening on port " + httpPort);
    }

    private void serveHttpsLoop() {
        while (true) {
            try {
                SSLSocket socket = (SSLSocket) httpSocket.accept();
                final SSLSocket accepted = socket;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        handleHttps(accepted);
                    }
                }, "mosey_shim_https_client");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                Log.w(TAG, "HTTPS accept failed", t);
            }
        }
    }

    private void handleHttps(SSLSocket socket) {
        try (SSLSocket s = socket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            while (true) {
                String line = reader.readLine();
                if (line == null || line.length() == 0) {
                    break;
                }
            }
            String path = "/";
            if (request != null) {
                String[] parts = request.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }
            byte[] body;
            int status;
            if ("/Discover".equals(path)) {
                status = 200;
                body = discoverPlist().getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = new byte[0];
            }
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + " OK\r\n"
                    + "Content-Type: application/x-apple-binary-plist\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "HTTPS client failed", t);
        }
    }

    private String discoverPlist() {
        String caps = Base64.getEncoder().encodeToString("Apple iOS\u0000\u0000\u0001".getBytes(StandardCharsets.UTF_8));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>"
                + "<key>ReceiverComputerName</key><string>" + xml(deviceName) + "</string>"
                + "<key>ReceiverMediaCapabilities</key><data>" + caps + "</data>"
                + "<key>Manufacturer</key><string>Bada</string>"
                + "</dict></plist>\n";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void startNsdDiscovery() {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager unavailable");
            return;
        }
        try {
            nsdManager.discoverServices(AIRDROP_SERVICE, NsdManager.PROTOCOL_DNS_SD,
                    new NsdManager.DiscoveryListener() {
                        @Override public void onDiscoveryStarted(String type) {
                            Log.i(TAG, "NSD discovery started: " + type);
                        }
                        @Override public void onServiceFound(NsdServiceInfo service) {
                            Log.i(TAG, "NSD service found: " + service);
                            resolveService(service);
                        }
                        @Override public void onServiceLost(NsdServiceInfo service) {
                            Log.i(TAG, "NSD service lost: " + service);
                            sendEvent("{\"type\":\"airdrop_lost\",\"name\":\""
                                    + json(service.getServiceName()) + "\"}");
                        }
                        @Override public void onDiscoveryStopped(String type) {
                            Log.i(TAG, "NSD discovery stopped: " + type);
                        }
                        @Override public void onStartDiscoveryFailed(String type, int code) {
                            Log.w(TAG, "NSD discovery start failed: " + code);
                        }
                        @Override public void onStopDiscoveryFailed(String type, int code) {
                            Log.w(TAG, "NSD discovery stop failed: " + code);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start NSD discovery", t);
        }
    }

    private void resolveService(NsdServiceInfo service) {
        String key = service.getServiceName() + "@" + service.getServiceType();
        if (!seenServices.add(key)) {
            return;
        }
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo svc, int code) {
                    Log.w(TAG, "NSD resolve failed: " + code + " " + svc);
                }
                @Override public void onServiceResolved(NsdServiceInfo resolved) {
                    Log.i(TAG, "NSD resolved: " + resolved);
                    List<String> hosts = new ArrayList<>();
                    for (InetAddress address : resolved.getHostAddresses()) {
                        hosts.add(address.getHostAddress());
                    }
                    String host = hosts.isEmpty() ? "" : hosts.get(0);
                    sendEvent("{\"type\":\"airdrop_found\",\"name\":\""
                            + json(resolved.getServiceName())
                            + "\",\"host\":\"" + json(host)
                            + "\",\"port\":" + resolved.getPort()
                            + ",\"service\":\"" + json(resolved.getServiceType()) + "\"}");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to resolve NSD service", t);
        }
    }

    private void registerAirDropService() {
        if (nsdManager == null || httpPort <= 0) {
            return;
        }
        try {
            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName(deviceName);
            service.setServiceType(AIRDROP_SERVICE);
            service.setPort(httpPort);
            service.setAttribute("_dc", "1");
            service.setAttribute("flags", "0x1");
            registeredService = service;
            nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD,
                    new NsdManager.RegistrationListener() {
                        @Override public void onRegistrationFailed(NsdServiceInfo svc, int code) {
                            Log.w(TAG, "AirDrop NSD registration failed: " + code + " " + svc);
                        }
                        @Override public void onServiceRegistered(NsdServiceInfo svc) {
                            Log.i(TAG, "AirDrop NSD registered: " + svc);
                        }
                        @Override public void onServiceUnregistered(NsdServiceInfo svc) {
                            Log.i(TAG, "AirDrop NSD unregistered: " + svc);
                        }
                        @Override public void onUnregistrationFailed(NsdServiceInfo svc, int code) {
                            Log.w(TAG, "AirDrop NSD unregister failed: " + code + " " + svc);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to register AirDrop NSD service", t);
        }
    }

    private void startBleWakeupAdvertising() {
        try {
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null) {
                Log.w(TAG, "Bluetooth adapter unavailable; will retry");
                return;
            }
            BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.w(TAG, "BLE advertiser unavailable");
                return;
            }
            byte[] payload = new byte[20];
            payload[0] = 5;
            payload[1] = 18;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addManufacturerData(76, payload)
                    .addServiceUuid(ParcelUuid.fromString(AIRDROP_UUID))
                    .build();
            advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    bleAdvertisingStarted = true;
                    Log.i(TAG, "BLE wakeup advertising started: " + settingsInEffect);
                }
                @Override public void onStartFailure(int errorCode) {
                    bleAdvertisingStarted = false;
                    Log.w(TAG, "BLE wakeup advertising failed: " + errorCode);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start BLE wakeup advertising", t);
        }
    }

    private void startAppleBleScan() {
        try {
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null) {
                Log.w(TAG, "Bluetooth adapter unavailable for scan; will retry");
                return;
            }
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.w(TAG, "BLE scanner unavailable");
                return;
            }
            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(76, new byte[] { 5 }, new byte[] { (byte) 0xff })
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build();
            scanner.startScan(java.util.Collections.singletonList(filter), settings, new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    ScanRecord record = result.getScanRecord();
                    Log.i(TAG, "Apple BLE wakeup seen: " + result.getDevice());
                    registerAirDropService();
                    sendEvent("{\"type\":\"apple_ble_seen\",\"rssi\":" + result.getRssi() + "}");
                    if (record != null) {
                        List<ParcelUuid> uuids = record.getServiceUuids();
                        if (uuids != null) {
                            Log.i(TAG, "Apple BLE service UUIDs: " + uuids);
                        }
                    }
                }
                @Override public void onScanFailed(int errorCode) {
                    bleScanStarted = false;
                    Log.w(TAG, "Apple BLE scan failed: " + errorCode);
                }
            });
            bleScanStarted = true;
            Log.i(TAG, "Apple BLE scan started");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start Apple BLE scan", t);
        }
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            return manager.getAdapter();
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    private void sendEvent(String json) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 0), 1);
        } catch (Throwable ignored) {
            // Keep java.net classes initialized before android.net.LocalSocket use.
        }
        try {
            android.net.LocalSocket socket = new android.net.LocalSocket();
            socket.connect(new android.net.LocalSocketAddress(
                    EVENT_SOCKET, android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            writeLe32(out, body.length);
            out.write(body);
            out.flush();
            socket.close();
            Log.i(TAG, "Event sent: " + json);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to send shim event", t);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
