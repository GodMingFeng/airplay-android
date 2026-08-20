package com.github.serezhka.jap2lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers airplay/airtunes service mdns
 */
public class AirPlayBonjour {

    private static final Logger log = LoggerFactory.getLogger(AirPlayBonjour.class);

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;
    /** Twelve upper-case hex digits, this receiver's stand-in for a MAC address. */
    private final String deviceId;

    private JmDNS jmdns;
    private ServiceInfo airPlayService;
    private ServiceInfo airTunesService;

    public AirPlayBonjour(String serverName, String deviceId) {
        this.serverName = serverName;
        this.deviceId = deviceId;
    }

    public void start(int airPlayPort, int airTunesPort) throws Exception {
        // Bound to one address rather than JmmDNS, which follows every interface it can find and
        // registers the service once per interface. It then hears its own record as a conflict and
        // publishes a second "name (2)" entry, so the receiver shows up twice on the sender.
        InetAddress address = primaryAddress();
        jmdns = JmDNS.create(address, "airplay-" + deviceId);
        log.info("mDNS bound to {}", address.getHostAddress());

        airPlayService = ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                serverName, airPlayPort, 0, 0, airPlayMDNSProps());
        jmdns.registerService(airPlayService);
        log.info("{} service is registered on port {}", serverName + AIRPLAY_SERVICE_TYPE, airPlayPort);

        String airTunesServerName = deviceId + "@" + serverName;
        airTunesService = ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps());
        jmdns.registerService(airTunesService);
        log.info("{} service is registered on port {}", airTunesServerName + AIRTUNES_SERVICE_TYPE, airTunesPort);
    }

    public void stop() {
        if (jmdns == null) return;
        try {
            jmdns.unregisterAllServices();
            jmdns.close();
            log.info("mDNS services are unregistered");
        } catch (Exception e) {
            log.warn("Failed to shut mDNS down cleanly", e);
        } finally {
            jmdns = null;
        }
    }

    /** The address senders can actually reach us on: first non-loopback IPv4 of an up interface. */
    private static InetAddress primaryAddress() throws Exception {
        for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!intf.isUp() || intf.isLoopback()) continue;
            for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                if (addr instanceof Inet4Address) return addr;
            }
        }
        throw new IllegalStateException("No usable IPv4 address to advertise on");
    }

    private Map<String, String> airPlayMDNSProps() {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", withColons(deviceId));
        airPlayMDNSProps.put("features", "0x5A7FFFF7,0x1E");
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x4");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV2,1");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        return airPlayMDNSProps;
    }

    /** {@code AABBCCDDEEFF} to {@code AA:BB:CC:DD:EE:FF}, the shape the deviceid record uses. */
    private static String withColons(String deviceId) {
        StringBuilder colonised = new StringBuilder(17);
        for (int i = 0; i + 1 < deviceId.length(); i += 2) {
            if (i > 0) colonised.append(':');
            colonised.append(deviceId, i, i + 2);
        }
        return colonised.toString();
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "0,1,2,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", "0x5A7FFFF7,0x1E");
        airTunesMDNSProps.put("am", "AppleTV2,1");
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("rhd", "5.6.0.0");
        airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x4");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        return airTunesMDNSProps;
    }
}
