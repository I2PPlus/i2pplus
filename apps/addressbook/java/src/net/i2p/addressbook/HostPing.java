package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Simple command-line ping tool
 * Provides I2PIng-compatible functionality with direct I2PSocketManager usage
 */
public class HostPing {

    private final I2PAppContext _context;
    private final Log _log;
    private final NamingService _namingService;

    // Default settings matching I2PIng
    private static final int DEFAULT_COUNT = 10;
    private static final int DEFAULT_TIMEOUT = 8000;
    private static final String DEFAULT_LEASESET_TYPE = "6,4";

    private int _count = DEFAULT_COUNT;
    private int _timeout = DEFAULT_TIMEOUT;
    private String _leaseSetType = DEFAULT_LEASESET_TYPE;
    private boolean _consecutiveMode = false;
    private boolean _debug = false;

    public HostPing() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(HostPing.class);
        _namingService = _context.namingService();
    }

    /**
     * Main ping method for a single destination
     */
    public int pingDestination(String destination) {
        System.out.println(" • Opening tunnel...");

        if (_consecutiveMode) {
            return pingConsecutive(destination);
        } else {
            return pingMultiple(destination);
        }
    }

    /**
     * Multiple ping mode like I2PIng's default behavior
     */
    private int pingMultiple(String destination) {
        List<Long> responseTimes = new ArrayList<>();
        int successCount = 0;

        for (int i = 1; i <= _count; i++) {
            System.out.printf("%d:  ", i);

            long responseTime = pingSingle(destination);

            if (responseTime > 0) {
                responseTimes.add(responseTime);
                successCount++;
                System.out.printf("%-6dms\n", responseTime);
            } else {
                System.out.println("------");
            }

            // Small delay between pings if not the last one
            if (i < _count) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Print summary matching I2PIng format
        printSummary(destination, successCount, _count, responseTimes);
        return successCount > 0 ? 0 : 1;
    }

    /**
     * Consecutive mode - requires 5 consecutive successes
     */
    private int pingConsecutive(String destination) {
        int consecutiveSuccesses = 0;
        int attempts = 0;
        final int requiredConsecutive = 5;

        while (consecutiveSuccesses < requiredConsecutive && attempts < _count) {
            attempts++;
            System.out.printf("%d:  ", attempts);

            long responseTime = pingSingle(destination);

            if (responseTime > 0) {
                consecutiveSuccesses++;
                System.out.printf("%-6dms\n", responseTime);
            } else {
                consecutiveSuccesses = 0;
                System.out.println("------");
            }

            if (consecutiveSuccesses < requiredConsecutive && attempts < _count) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (consecutiveSuccesses >= requiredConsecutive) {
            System.out.printf(" ✔ + (%d consecutive successes) ‣ %s\n", requiredConsecutive, destination);
            return 0;
        } else {
            System.out.printf(" ✖ Only %d/%d consecutive successes ‣ %s\n", consecutiveSuccesses, requiredConsecutive, destination);
            return 1;
        }
    }

    /**
     * Single ping attempt using I2PSocketManager
     */
    private long pingSingle(String hostname) {
        I2PSocketManager socketManager = null;
        try {
            // Get destination
            Destination destination = _namingService.lookup(hostname);
            if (destination == null) {
                if (_debug) {
                    System.out.println("DEBUG: Could not resolve destination: " + hostname);
                }
                return -1;
            }

            // Create socket manager for this ping
            long tunnelBuildStart = System.currentTimeMillis();
            Properties options = new Properties();
            options.setProperty("i2cp.host", "127.0.0.1");
            options.setProperty("inbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("outbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("inbound.shouldTest", "false");
            options.setProperty("outbound.shouldTest", "false");
            options.setProperty("inbound.quantity", "1");
            options.setProperty("outbound.quantity", "1");
            options.setProperty("inbound.backupQuantity", "0");
            options.setProperty("outbound.backupQuantity", "0");
            options.setProperty("i2cp.leaseSetType", "3");
            options.setProperty("i2cp.leaseSetEncType", _leaseSetType);
            options.setProperty("i2cp.dontPublishLeaseSet", "true");

            socketManager = I2PSocketManagerFactory.createManager(options);

            if (socketManager == null) {
                if (_debug) {
                    System.out.println("DEBUG: Failed to create SocketManager");
                }
                return -1;
            }

            long tunnelBuildTime = System.currentTimeMillis() - tunnelBuildStart;
            if (_debug) {
                System.out.println("DEBUG: SocketManager ready in " + tunnelBuildTime + "ms");
            }

            // Use I2PSocketManager ping method like I2Ping does
            long pingStart = System.currentTimeMillis();
            boolean reachable = socketManager.ping(destination, 0, 0, _timeout);
            long pingTime = System.currentTimeMillis() - pingStart;

            if (reachable) {
                return pingTime;
            } else {
                return -1;
            }

        } catch (Exception e) {
            if (_debug) {
                System.out.println("DEBUG: Ping error: " + e.getMessage());
            }
            return -1;
        } finally {
            // Clean up the socket manager
            if (socketManager != null) {
                try {
                    socketManager.destroySocketManager();
                } catch (Exception e) {
                    if (_debug) {
                        System.out.println("DEBUG: Error destroying SocketManager: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Print summary in I2PIng format
     */
    private void printSummary(String destination, int successCount, int totalCount, List<Long> responseTimes) {
        if (successCount > 0 && !responseTimes.isEmpty()) {
            long total = 0;
            long min = Long.MAX_VALUE;
            long max = 0;

            for (long time : responseTimes) {
                total += time;
                if (time < min) min = time;
                if (time > max) max = time;
            }

            long avg = total / responseTimes.size();
            System.out.printf("Results for %s: %d / %d pongs received, average response %dms\n",
                destination, successCount, totalCount, avg);
        } else {
            System.out.printf("Results for %s: 0 / %d pongs received\n", destination, totalCount);
        }
    }

    /**
     * Ping multiple destinations from file
     */
    public int pingFromFile(String filename) {
        List<String> destinations = readDestinationsFromFile(filename);
        if (destinations.isEmpty()) {
            System.out.println(" ✖ No valid destinations found in file");
            return 1;
        }

        System.out.printf(" • Found %d destinations, initiating ping...\n", destinations.size());
        System.out.println(" • Trying to open tunnel(s)");

        int successCount = 0;
        for (String destination : destinations) {
            System.out.printf("Testing %s: ", destination);
            long responseTime = pingSingle(destination);
            if (responseTime > 0) {
                successCount++;
                System.out.printf(" ✔ %dms\n", responseTime);
            } else {
                System.out.println(" ✖ timeout");
            }
        }

        System.out.printf("\nBatch results: %d / %d destinations reachable\n",
            successCount, destinations.size());

        return successCount > 0 ? 0 : 1;
    }

    /**
     * Read destinations from file
     */
    private List<String> readDestinationsFromFile(String filename) {
        List<String> destinations = new ArrayList<>();
        File file = new File(filename);

        if (!file.exists()) {
            System.out.printf(" ✖ File %s not found\n", filename);
            return destinations;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String destination = null;

                    // Handle multiple formats:
                    // 1. hostname.i2p=b64 - extract hostname
                    // 2. hostname.i2p - use as-is
                    // 3. xxx.b32.i2p - use as-is
                    // 4. full B64 destination - use as-is

                    if (line.contains("=")) {
                        // Format: hostname.i2p=b64 - extract hostname
                        String[] parts = line.split("=", 2);
                        if (parts.length >= 1) {
                            destination = parts[0].trim();
                        }
                    } else {
                        // Use the line as-is (hostname.i2p, b32.i2p, or B64)
                        destination = line;
                    }

                    if (isValidDestination(destination)) {
                        destinations.add(destination);
                        if (_debug) {
                            System.out.printf(" • Added destination: %s (from line: %s)\n", destination, line);
                        }
                    } else {
                        if (_debug) {
                            System.out.printf(" • Skipping invalid destination: %s (from line: %s)\n", destination, line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.printf(" ✖ Error reading file %s: %s\n", filename, e.getMessage());
        }

        return destinations;
    }

    /**
     * Validate destination format (B64, B32, or .i2p)
     */
    private boolean isValidDestination(String dest) {
        if (dest == null || dest.isEmpty()) {
            return false;
        }

        // B64 format (full destinations - can vary in length, usually 500+ chars)
        // Accept any long string without dots that looks like B64
        if (dest.length() >= 400 && dest.length() <= 800 && !dest.contains(".")) {
            // Basic check for Base64 characters only
            return dest.matches("^[A-Za-z0-9~\\-]+$");
        }

        // B32 format (52 characters + .b32.i2p)
        if (dest.endsWith(".b32.i2p") && dest.length() >= 50 && dest.length() <= 70) {
            String b32Part = dest.substring(0, dest.length() - 8); // Remove .b32.i2p
            return b32Part.matches("^[a-z2-7]+$"); // B32 uses a-z2-7
        }

        // .i2p hostname (can contain letters, numbers, hyphens)
        if (dest.endsWith(".i2p") && !dest.equals(".i2p")) {
            String hostname = dest.substring(0, dest.length() - 4); // Remove .i2p
            return hostname.length() >= 1 && hostname.matches("^[a-zA-Z0-9\\-]+$");
        }

        return false;
    }

    /**
     * Command line interface
     */
    public static void main(String[] args) {
        HostPing pingTool = new HostPing();
        List<String> destinations = new ArrayList<>();
        String listFile = null;
        boolean showHelp = false;
        int exitCode = 0;

        try {
            // Parse arguments
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (arg.equals("-n") && i + 1 < args.length) {
                    pingTool._count = Integer.parseInt(args[++i]);
                } else if (arg.equals("-t") && i + 1 < args.length) {
                    pingTool._timeout = Integer.parseInt(args[++i]);
                } else if (arg.equals("-ls") && i + 1 < args.length) {
                    pingTool._leaseSetType = args[++i];
                } else if (arg.equals("-c")) {
                    pingTool._consecutiveMode = true;
                } else if (arg.equals("-d")) {
                    pingTool._debug = true;
                } else if (arg.equals("-h")) {
                    listFile = "./hosts.txt";
                } else if (arg.equals("-l") && i + 1 < args.length) {
                    listFile = args[++i];
                } else if (arg.equals("--help")) {
                    showHelp = true;
                } else if (!arg.startsWith("-")) {
                    destinations.add(arg);
                }
            }

            if (showHelp || args.length == 0) {
                printHelp();
                return;
            }

            // Execute ping operations
            if (listFile != null) {
                if (!destinations.isEmpty()) {
                    System.out.println(" ✖ Cannot specify both file and destination arguments");
                    exitCode = 1;
                } else {
                    exitCode = pingTool.pingFromFile(listFile);
                }
            } else if (!destinations.isEmpty()) {
                for (String destination : destinations) {
                    if (!pingTool.isValidDestination(destination)) {
                        System.out.printf(" ✖ Invalid destination format: %s\n", destination);
                        exitCode = 1;
                        continue;
                    }

                    System.out.printf(" ➤ Initiating ping of %s\n", destination);
                    int result = pingTool.pingDestination(destination);
                    if (result != 0) {
                        exitCode = result;
                    }

                    System.out.println(); // Add spacing between multiple destinations
                }
            } else {
                System.out.println(" ✖ No destination specified");
                printHelp();
                exitCode = 1;
            }

        } catch (NumberFormatException e) {
            System.out.println(" ✖ Invalid number format in arguments");
            exitCode = 1;
        } catch (Exception e) {
            System.out.printf(" ✖ Error: %s\n", e.getMessage());
            if (pingTool._debug) {
                e.printStackTrace();
            }
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    /**
     * Print help message
     */
    private static void printHelp() {
        System.out.println("HostPing - I2P destination ping tool using HostChecker");
        System.out.println();
        System.out.println("Usage: java net.i2p.addressbook.HostPing [options] destination");
        System.out.println("       java net.i2p.addressbook.HostPing [options] -h");
        System.out.println("       java net.i2p.addressbook.HostPing [options] -l <file>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -n <count>      Number of pings to send (default: 10)");
        System.out.println("  -t <timeout>    Timeout in milliseconds (default: 8000)");
        System.out.println("  -ls <type>      LeaseSet encryption types (default: 6,4)");
        System.out.println("  -c              Consecutive mode (require 5 consecutive successes)");
        System.out.println("  -d              Enable debug output");
        System.out.println("  -h              Ping all hosts in ./hosts.txt");
        System.out.println("  -l <file>       Ping all hosts in specified file");
        System.out.println("  --help          Show this help message");
        System.out.println();
        System.out.println("Destination formats:");
        System.out.println("  B64 destination (full Base64 string)");
        System.out.println("  B32 address (xxx.b32.i2p)");
        System.out.println("  .i2p hostname");
        System.out.println();
        System.out.println("LeaseSet types:");
        System.out.println("  0     - ElGamal only");
        System.out.println("  4     - ECIES only");
        System.out.println("  5     - ML-KEM-512 only");
        System.out.println("  6     - ML-KEM-768 only");
        System.out.println("  7     - ML-KEM-1024 only");
        System.out.println("  0,4   - ElGamal and ECIES");
        System.out.println("  5,4   - ML-KEM-512 and ECIES");
        System.out.println("  6,4   - ML-KEM-768 and ECIES (default)");
        System.out.println("  7,4   - ML-KEM-1024 and ECIES");
        System.out.println();
        System.out.println("Hosts.txt formats supported:");
        System.out.println("  hostname.i2p");
        System.out.println("  hostname.i2p=b64...");
        System.out.println("  xxx.b32.i2p");
        System.out.println("  full B64 destination");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java net.i2p.addressbook.HostPing notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -n 5 -t 5000 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 0 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 0,4 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 4 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 5 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 6 notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -ls 7,4 -c notbob.i2p");
        System.out.println("  java net.i2p.addressbook.HostPing -h");
        System.out.println("  java net.i2p.addressbook.HostPing -l myhosts.txt");
    }
}