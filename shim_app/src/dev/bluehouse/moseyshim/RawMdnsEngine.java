package dev.bluehouse.moseybridgeshim;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class RawMdnsEngine {
    interface Listener {
        void onFound(String name, String host, int port);
        void onLost(String name);
    }

    private static final String TAG = "MoseyRawMdns";
    private static final String SERVICE = "_airdrop._tcp.local";
    private static final int MDNS_PORT = 5353;
    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;
    private static final int TYPE_SRV = 33;
    private static final long PEER_TIMEOUT_MS = 45000L;

    private final String serviceName;
    private final String instanceName;
    private final String hostName;
    private final int servicePort;
    private final Listener listener;
    private final Map<String, Peer> peers = new HashMap<>();
    private final Map<String, Host> hosts = new HashMap<>();
    private volatile boolean running;
    private volatile boolean announceRequested;
    private MulticastSocket socket;
    private NetworkInterface networkInterface;
    private Inet6Address group;
    private InetAddress localAddress;
    private Thread thread;

    private static final class Peer {
        String instance;
        String target;
        int port;
        long lastSeen;
        String emittedEndpoint;
    }

    private static final class Host {
        String address;
        long lastSeen;
    }

    RawMdnsEngine(String serviceId, int port, Listener listener) {
        serviceName = sanitizeLabel(serviceId);
        instanceName = serviceName + "." + SERVICE;
        hostName = serviceName.toLowerCase(Locale.US) + ".local";
        servicePort = port;
        this.listener = listener;
    }

    synchronized boolean start() {
        if (running) return true;
        try {
            networkInterface = NetworkInterface.getByName("mosey0");
            localAddress = findLinkLocal(networkInterface);
            if (networkInterface == null || !networkInterface.isUp() || localAddress == null) {
                return false;
            }
            byte[] groupBytes = InetAddress.getByName("ff02::fb").getAddress();
            group = Inet6Address.getByAddress(null, groupBytes, networkInterface);
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MDNS_PORT));
            socket.setNetworkInterface(networkInterface);
            socket.setTimeToLive(255);
            socket.setSoTimeout(1000);
            socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), networkInterface);
            running = true;
            announceRequested = true;
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    runLoop();
                }
            }, "mosey_raw_mdns");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "Raw mDNS started interface=mosey0 address="
                    + scopedAddress(localAddress) + " service=" + instanceName
                    + " port=" + servicePort);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start raw mDNS on mosey0", t);
            stop();
            return false;
        }
    }

    boolean isRunning() {
        return running && socket != null && !socket.isClosed();
    }

    void requestAnnouncement() {
        announceRequested = true;
    }

    synchronized void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.leaveGroup(new InetSocketAddress(group, MDNS_PORT), networkInterface);
            } catch (Throwable ignored) {
            }
            socket.close();
        }
        socket = null;
        Thread oldThread = thread;
        thread = null;
        if (oldThread != null && oldThread != Thread.currentThread()) {
            try {
                oldThread.join(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        for (Peer peer : peers.values()) {
            if (peer.emittedEndpoint != null) listener.onLost(displayName(peer.instance));
        }
        peers.clear();
        hosts.clear();
    }

    private void runLoop() {
        long nextQuery = 0;
        long nextAnnouncement = 0;
        byte[] buffer = new byte[9000];
        while (running) {
            long now = android.os.SystemClock.elapsedRealtime();
            try {
                if (now >= nextQuery) {
                    send(buildQuery());
                    nextQuery = now + 5000L;
                }
                if (announceRequested || now >= nextAnnouncement) {
                    announceRequested = false;
                    send(buildAnnouncement());
                    nextAnnouncement = now + 20000L;
                    Log.d(TAG, "AirDrop mDNS announcement sent");
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() >= 12) {
                    byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                            packet.getOffset() + packet.getLength());
                    boolean queryForUs = parsePacket(data, now);
                    if (queryForUs) send(buildAnnouncement());
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Throwable t) {
                if (running) Log.w(TAG, "Raw mDNS receive/send failure", t);
            }
            expirePeers(android.os.SystemClock.elapsedRealtime());
        }
    }

    private boolean parsePacket(byte[] data, long now) throws Exception {
        int questions = u16(data, 4);
        int answers = u16(data, 6) + u16(data, 8) + u16(data, 10);
        int[] offset = {12};
        boolean queryForUs = false;
        for (int i = 0; i < questions; i++) {
            String name = readName(data, offset);
            int type = u16(data, offset[0]);
            offset[0] += 4;
            if (sameName(name, SERVICE) || sameName(name, instanceName)
                    || sameName(name, hostName) || type == 255) {
                queryForUs = true;
            }
        }
        for (int i = 0; i < answers && offset[0] + 10 <= data.length; i++) {
            String owner = readName(data, offset);
            int type = u16(data, offset[0]);
            long ttl = u32(data, offset[0] + 4);
            int length = u16(data, offset[0] + 8);
            int rdata = offset[0] + 10;
            int end = rdata + length;
            if (end > data.length) break;
            offset[0] = end;
            if (type == TYPE_PTR && sameName(owner, SERVICE)) {
                int[] nameOffset = {rdata};
                String instance = readName(data, nameOffset);
                if (!sameName(instance, instanceName)) {
                    Peer peer = getPeer(instance);
                    peer.instance = instance;
                    peer.lastSeen = ttl == 0 ? 0 : now;
                }
            } else if (type == TYPE_SRV) {
                if (sameName(owner, instanceName)) continue;
                Peer peer = getPeer(owner);
                peer.instance = owner;
                peer.port = u16(data, rdata + 4);
                int[] nameOffset = {rdata + 6};
                peer.target = readName(data, nameOffset);
                peer.lastSeen = ttl == 0 ? 0 : now;
            } else if ((type == TYPE_AAAA && length == 16)
                    || (type == TYPE_A && length == 4)) {
                InetAddress address = InetAddress.getByAddress(
                        Arrays.copyOfRange(data, rdata, end));
                Host host = getHost(owner);
                host.address = scopedAddress(address);
                host.lastSeen = ttl == 0 ? 0 : now;
            } else if (type == TYPE_TXT) {
                if (sameName(owner, instanceName)) continue;
                Peer peer = peers.get(key(owner));
                if (peer != null) peer.lastSeen = ttl == 0 ? 0 : now;
            }
        }
        resolvePeers(now);
        return queryForUs;
    }

    private void resolvePeers(long now) {
        for (Peer peer : peers.values()) {
            if (peer.instance == null || peer.target == null || peer.port <= 0) continue;
            Host host = hosts.get(key(peer.target));
            if (host == null || host.address == null) continue;
            peer.lastSeen = Math.max(peer.lastSeen, host.lastSeen);
            String endpoint = host.address + ":" + peer.port;
            if (!endpoint.equals(peer.emittedEndpoint)) {
                peer.emittedEndpoint = endpoint;
                String name = displayName(peer.instance);
                Log.i(TAG, "AirDrop peer resolved name=" + name + " host="
                        + host.address + " port=" + peer.port);
                listener.onFound(name, host.address, peer.port);
            }
        }
    }

    private Peer getPeer(String name) {
        String mapKey = key(name);
        Peer peer = peers.get(mapKey);
        if (peer == null) {
            peer = new Peer();
            peers.put(mapKey, peer);
        }
        return peer;
    }

    private Host getHost(String name) {
        String mapKey = key(name);
        Host host = hosts.get(mapKey);
        if (host == null) {
            host = new Host();
            hosts.put(mapKey, host);
        }
        return host;
    }

    private void expirePeers(long now) {
        java.util.Iterator<Map.Entry<String, Peer>> iterator = peers.entrySet().iterator();
        while (iterator.hasNext()) {
            Peer peer = iterator.next().getValue();
            if (peer.lastSeen == 0 || now - peer.lastSeen > PEER_TIMEOUT_MS) {
                if (peer.emittedEndpoint != null) {
                    String name = displayName(peer.instance);
                    Log.i(TAG, "AirDrop peer expired name=" + name);
                    listener.onLost(name);
                }
                iterator.remove();
            }
        }
    }

    private byte[] buildQuery() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0); out.writeShort(0); out.writeShort(1);
        out.writeShort(0); out.writeShort(0); out.writeShort(0);
        writeName(out, SERVICE);
        out.writeShort(TYPE_PTR); out.writeShort(1);
        return bytes.toByteArray();
    }

    private byte[] buildAnnouncement() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0); out.writeShort(0x8400); out.writeShort(0);
        out.writeShort(4); out.writeShort(0); out.writeShort(0);

        ByteArrayOutputStream ptr = new ByteArrayOutputStream();
        writeName(new DataOutputStream(ptr), instanceName);
        writeRecord(out, SERVICE, TYPE_PTR, 1, 120, ptr.toByteArray());

        ByteArrayOutputStream srv = new ByteArrayOutputStream();
        DataOutputStream srvOut = new DataOutputStream(srv);
        srvOut.writeShort(0); srvOut.writeShort(0); srvOut.writeShort(servicePort);
        writeName(srvOut, hostName);
        writeRecord(out, instanceName, TYPE_SRV, 0x8001, 120, srv.toByteArray());

        ByteArrayOutputStream txt = new ByteArrayOutputStream();
        writeTxt(txt, "flags=489");
        writeRecord(out, instanceName, TYPE_TXT, 0x8001, 120, txt.toByteArray());

        int addressType = localAddress instanceof Inet6Address ? TYPE_AAAA : TYPE_A;
        writeRecord(out, hostName, addressType, 0x8001, 120, localAddress.getAddress());
        return bytes.toByteArray();
    }

    private void send(byte[] data) throws Exception {
        if (!running || socket == null) return;
        socket.send(new DatagramPacket(data, data.length, group, MDNS_PORT));
    }

    private static void writeRecord(DataOutputStream out, String owner, int type,
            int clazz, int ttl, byte[] data) throws Exception {
        writeName(out, owner);
        out.writeShort(type); out.writeShort(clazz); out.writeInt(ttl);
        out.writeShort(data.length); out.write(data);
    }

    private static void writeTxt(ByteArrayOutputStream out, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write(Math.min(data.length, 255));
        out.write(data, 0, Math.min(data.length, 255));
    }

    private static void writeName(DataOutputStream out, String name) throws Exception {
        for (String label : trimDot(name).split("\\.")) {
            byte[] data = label.getBytes(StandardCharsets.UTF_8);
            if (data.length > 63) throw new IllegalArgumentException("DNS label too long");
            out.writeByte(data.length); out.write(data);
        }
        out.writeByte(0);
    }

    private static String readName(byte[] data, int[] offset) throws Exception {
        StringBuilder name = new StringBuilder();
        int pos = offset[0];
        int resume = -1;
        int jumps = 0;
        while (pos < data.length && jumps++ < 32) {
            int length = data[pos] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                if (pos + 1 >= data.length) throw new IllegalArgumentException("short DNS pointer");
                int pointer = ((length & 0x3f) << 8) | (data[pos + 1] & 0xff);
                if (resume < 0) resume = pos + 2;
                pos = pointer;
                continue;
            }
            pos++;
            if (length == 0) {
                offset[0] = resume >= 0 ? resume : pos;
                return name.toString();
            }
            if (length > 63 || pos + length > data.length) {
                throw new IllegalArgumentException("invalid DNS name");
            }
            if (name.length() > 0) name.append('.');
            name.append(new String(data, pos, length, StandardCharsets.UTF_8));
            pos += length;
        }
        throw new IllegalArgumentException("unterminated DNS name");
    }

    private static InetAddress findLinkLocal(NetworkInterface iface) {
        if (iface == null) return null;
        for (InterfaceAddress address : iface.getInterfaceAddresses()) {
            InetAddress value = address.getAddress();
            if (value instanceof Inet6Address && value.isLinkLocalAddress()) return value;
        }
        for (InterfaceAddress address : iface.getInterfaceAddresses()) {
            if (address.getAddress() != null) return address.getAddress();
        }
        return null;
    }

    private String scopedAddress(InetAddress address) {
        if (address instanceof Inet6Address && address.isLinkLocalAddress()) {
            String raw = address.getHostAddress();
            int percent = raw.indexOf('%');
            return (percent >= 0 ? raw.substring(0, percent) : raw) + "%mosey0";
        }
        return address.getHostAddress();
    }

    private static String sanitizeLabel(String value) {
        String clean = value == null ? "Bada" : value.replaceAll("[^A-Za-z0-9 -]", "-").trim();
        if (clean.isEmpty()) clean = "Bada";
        byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 63) return clean;
        return new String(Arrays.copyOf(bytes, 63), StandardCharsets.UTF_8).trim();
    }

    private static String displayName(String instance) {
        String suffix = "." + SERVICE;
        return instance != null && instance.toLowerCase(Locale.US).endsWith(suffix)
                ? instance.substring(0, instance.length() - suffix.length()) : instance;
    }

    private static String trimDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private static String key(String value) {
        return trimDot(value).toLowerCase(Locale.US);
    }

    private static boolean sameName(String left, String right) {
        return left != null && right != null && key(left).equals(key(right));
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) u16(data, offset) << 16) | u16(data, offset + 2);
    }
}
