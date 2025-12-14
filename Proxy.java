import java.net.*;
import java.io.*;
import java.util.*;

public class Proxy {

    static Map<String, String> serverProtocol = new HashMap<>();
    static Map<String, String> keyLocation = new HashMap<>();
    static List<String> knownProxies = new ArrayList<>();

    static final int MAX_HOPS = 10;

    // =========================
    // SERVER & PROXY DISCOVERY
    // =========================
    public static void discoverServerKeys(List<String> serverNodes) {
        for (String node : serverNodes) {
            try {
                String[] parts = node.split(":");
                String address = parts[0];
                int port = Integer.parseInt(parts[1]);

                // Server discovery
                String response = tryTCP(address, port, "GET NAMES");
                if (response != null && response.startsWith("OK")) {
                    serverProtocol.put(node, "TCP");
                    extractKeys(node, response);
                    continue;
                }

                response = tryUDP(address, port, "GET NAMES");
                if (response != null && response.startsWith("OK")) {
                    serverProtocol.put(node, "UDP");
                    extractKeys(node, response);
                }

                // Proxy discovery (TCP ve UDP)
                response = tryTCP(address, port, "PROXY HELLO");
                if ("OK PROXY".equals(response) && !knownProxies.contains(node)) {
                    knownProxies.add(node);
                } else {
                    response = tryUDP(address, port, "PROXY HELLO");
                    if ("OK PROXY".equals(response) && !knownProxies.contains(node)) {
                        knownProxies.add(node);
                    }
                }

            } catch (Exception ignored) {}
        }
    }

    private static void extractKeys(String node, String response) {
        String[] parts = response.split(" ");
        int count = Integer.parseInt(parts[1]);
        for (int i = 0; i < count; i++) {
            keyLocation.put(parts[2 + i], node);
        }
    }

    // =========================
    // CLIENT/PROXY → SERVER/PROXY FORWARDING
    // =========================
    public static String forwardCommand(String command) {
        return forwardCommand(command, MAX_HOPS, new HashSet<>());
    }

    public static String forwardCommand(String command, int hopsLeft, Set<String> visitedProxies) {
        if (hopsLeft <= 0) return "NA";

        try {
            if (command.equals("GET NAMES")) {
                StringBuilder sb = new StringBuilder("OK ");
                sb.append(keyLocation.size());
                for (String key : keyLocation.keySet()) {
                    sb.append(" ").append(key);
                }
                return sb.toString();
            }

            String[] parts = command.split(" ");
            if (parts.length < 1) return "NA";

            if ((parts[0].equals("GET") && parts[1].equals("VALUE")) || parts[0].equals("SET")) {
                String key = parts[0].equals("GET") ? parts[2] : parts[1];

                if (keyLocation.containsKey(key)) {
                    String node = keyLocation.get(key);
                    String[] addr = node.split(":");
                    String resp;
                    if ("TCP".equals(serverProtocol.get(node))) {
                        resp = tryTCP(addr[0], Integer.parseInt(addr[1]), command);
                    } else {
                        resp = tryUDP(addr[0], Integer.parseInt(addr[1]), command);
                    }
                    if (resp == null) return "SERVER DOES NOT SUPPORT COMMAND";
                    return resp;
                } else {
                    for (String proxy : knownProxies) {
                        if (visitedProxies.contains(proxy)) continue; // cycle kontrolü
                        visitedProxies.add(proxy);

                        String[] addr = proxy.split(":");
                        String resp = tryTCP(addr[0], Integer.parseInt(addr[1]),
                                "PROXY FORWARD " + command + " " + (hopsLeft - 1));

                        visitedProxies.remove(proxy);
                        if (resp != null && !resp.equals("NA")) return resp;
                    }
                    return "NA";
                }
            }

            if (parts[0].equals("QUIT")) {
                for (String node : serverProtocol.keySet()) {
                    String[] addr = node.split(":");
                    if ("TCP".equals(serverProtocol.get(node)))
                        tryTCP(addr[0], Integer.parseInt(addr[1]), "QUIT");
                    else
                        tryUDP(addr[0], Integer.parseInt(addr[1]), "QUIT");
                }
                System.exit(0);
            }

        } catch (Exception e) {
            return "NA";
        }

        return "NA";
    }

    // =========================
    // TCP & UDP Utility
    // =========================
    private static String tryTCP(String address, int port, String command) {
        try (Socket socket = new Socket(address, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println(command.trim());
            return in.readLine();

        } catch (Exception e) {
            return null;
        }
    }

    private static String tryUDP(String address, int port, String command) {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(5000);

            byte[] data = (command + "\n").getBytes();
            DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getByName(address), port);
            ds.send(p);

            byte[] buf = new byte[512];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            ds.receive(r);

            return new String(r.getData(), 0, r.getLength()).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // =========================
    // TCP Listener & Handler
    // =========================
    static class TCPListener extends Thread {
        private int port;
        TCPListener(int port) { this.port = port; }

        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("TCP Listener started on port " + port);
                while (true) {
                    Socket client = serverSocket.accept();
                    new TCPHandler(client).start();
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    static class TCPHandler extends Thread {
        private Socket socket;
        TCPHandler(Socket socket) { this.socket = socket; }

        public void run() {
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                String command = in.readLine().trim();
                System.out.println("TCP Client command: " + command);

                if (ProxyCommandHandler.handleIfProxyCommand(command, out)) {
                    socket.close();
                    return;
                }

                if (command.startsWith("PROXY FORWARD "))
                    command = command.substring(14);

                String response = Proxy.forwardCommand(command);
                if (response != null) out.println(response);

                socket.close();
            } catch (IOException e) {
                System.err.println("TCP Handler error: " + e);
            }
        }
    }

    // =========================
    // UDP Listener
    // =========================
    static class UDPListener extends Thread {
        private int port;
        UDPListener(int port) { this.port = port; }

        public void run() {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                System.out.println("UDP Listener started on port " + port);

                while (true) {
                    byte[] buffer = new byte[512];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String command = new String(packet.getData(), 0, packet.getLength()).trim();
                    System.out.println("UDP Client command: " + command);

                    if (command.startsWith("PROXY FORWARD "))
                        command = command.substring(14);

                    String response = Proxy.forwardCommand(command);
                    if (response != null) {
                        DatagramPacket reply = new DatagramPacket(
                                response.getBytes(), response.length(),
                                packet.getAddress(), packet.getPort());
                        socket.send(reply);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // =========================
    // PROXY COMMAND HANDLER
    // =========================
    static class ProxyCommandHandler {
        public static boolean handleIfProxyCommand(String command, PrintWriter out) {
            if (command.equals("PROXY HELLO")) {
                out.println("OK PROXY");
                return true;
            }
            return false;
        }
    }

    // =========================
    // MAIN
    // =========================
    public static void main(String[] args) {
        int port = 0;
        List<String> serverNodes = new ArrayList<>();

        for (int i = 0; i < args.length;) {
            switch (args[i]) {
                case "-port":
                    port = Integer.parseInt(args[i + 1]);
                    i += 2;
                    break;
                case "-server":
                    serverNodes.add(args[i + 1] + ":" + args[i + 2]);
                    i += 3;
                    break;
                default:
                    i++;
            }
        }

        if (port == 0 || serverNodes.isEmpty()) {
            System.err.println("Usage: java Proxy -port <port> -server <address> <port> ...");
            System.exit(1);
        }

        System.out.println("Proxy starting at port: " + port);

        new TCPListener(port).start();
        new UDPListener(port).start();

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        discoverServerKeys(serverNodes);
    }
}
