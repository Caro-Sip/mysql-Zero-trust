import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class CertificateSetup {

    // Adjusted paths:
    // Server: src/certs/server.p12 (relative to project root)
    // Client: clients/ (relative to project root)
    private static final File SERVER_DIR = new File("src/certs").getAbsoluteFile();
    private static final File CLIENT_DIR = new File("clients").getAbsoluteFile();
    private static final File SERVER_KEYSTORE = new File(SERVER_DIR, "server.p12");

    public static void main(String[] args) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        try {
            // Ensure directories exist
            if (!SERVER_DIR.exists()) SERVER_DIR.mkdirs();
            if (!CLIENT_DIR.exists()) CLIENT_DIR.mkdirs();

            while (true) {
                System.out.println("\n--- Certificate Manager ---");
                System.out.println("Locations:");
                System.out.println(" - Server Key: " + SERVER_KEYSTORE.getPath());
                System.out.println(" - Client Keys: " + CLIENT_DIR.getPath());
                System.out.println("---------------------------");
                System.out.println("1. Generate Server Keystore (Reset/Init)");
                System.out.println("2. Create New Client Certificate");
                System.out.println("3. Exit");
                System.out.print("Choose option: ");
                String choice = reader.readLine();

                if ("1".equals(choice)) {
                    generateServerCert(reader);
                } else if ("2".equals(choice)) {
                    generateClientCert(reader);
                } else if ("3".equals(choice)) {
                    System.out.println("Exiting...");
                    break;
                } else {
                    System.out.println("Invalid option.");
                }

                cleanupBin();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void generateServerCert(BufferedReader reader) throws Exception {
        System.out.println("\n--- Generating Server Certificate ---");
        
        String pass = prompt(reader, "Enter Server Keystore Password", null);
        String cn = prompt(reader, "Enter Common Name (CN) [e.g. localhost]", "localhost");
        String ou = prompt(reader, "Enter Organizational Unit (OU)", "Hospital");
        String o = prompt(reader, "Enter Organization (O)", "Phnom Penh");
        String l = prompt(reader, "Enter City (L)", "Phnom Penh");
        String st = prompt(reader, "Enter State (ST)", "Phnom Penh");
        String c = prompt(reader, "Enter Country Code (C)", "KH");

        // Clean old
        if (SERVER_KEYSTORE.exists()) {
            if (!SERVER_KEYSTORE.delete()) {
                System.out.println("❌ Error: Could not delete existing server keystore. Is the server running?");
                System.out.println("Please stop the server and try again.");
                return;
            }
        }

        String dname = String.format("CN=%s, OU=%s, O=%s, L=%s, ST=%s, C=%s", cn, ou, o, l, st, c);
        
        // Use getAbsolutePath() for keytool
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", 
                   "-storetype", "PKCS12", "-keystore", SERVER_KEYSTORE.getAbsolutePath(), 
                   "-validity", "3650", "-storepass", pass, "-dname", dname);
        
        System.out.println("✅ Server Keystore created at " + SERVER_KEYSTORE.getPath());
        System.out.println("⚠️ IMPORTANT: If you changed the password from 'password', update SimpleWebServer.java!");
    }

    private static void generateClientCert(BufferedReader reader) throws Exception {
        if (!SERVER_KEYSTORE.exists()) {
            System.out.println("❌ Error: Server keystore not found at: " + SERVER_KEYSTORE.getPath());
            System.out.println("Please run Option 1 first.");
            return;
        }

        System.out.println("\n--- Generating Client Certificate ---");
        String filename = prompt(reader, "Enter Client Filename (e.g. doctor)", null);
        // if (filename == null || filename.trim().isEmpty()) return; // prompt handles null now

        String clientPass = prompt(reader, "Enter Password for Client .p12", null);
        String serverPass = prompt(reader, "Enter Server Keystore Password (to import cert)", null);

        // Default DNAME for client as requested (only ask for name/pass)
        String cn = filename; 
        String dname = "CN=" + cn + ", OU=Hospital, O=Hospital, L=City, ST=State, C=US";

        File p12File = new File(CLIENT_DIR, filename + ".p12");
        File cerFile = new File(CLIENT_DIR, filename + ".cer");

        // 1. Generate Client Keypair
        runKeytool("-genkeypair", "-alias", filename, "-keyalg", "RSA", "-keysize", "2048", 
                   "-storetype", "PKCS12", "-keystore", p12File.getAbsolutePath(), 
                   "-validity", "3650", "-storepass", clientPass, "-dname", dname);

        // 2. Export Public Cert
        runKeytool("-exportcert", "-alias", filename, "-keystore", p12File.getAbsolutePath(), 
                   "-storepass", clientPass, "-file", cerFile.getAbsolutePath());

        // 3. Import into Server Truststore
        while (true) {
            try {
                runKeytool("-importcert", "-alias", filename, "-keystore", SERVER_KEYSTORE.getAbsolutePath(), 
                           "-storepass", serverPass, "-file", cerFile.getAbsolutePath(), "-noprompt");
                break;
            } catch (Exception e) {
                System.out.println("❌ Import failed. Likely incorrect Server Keystore password.");
                String retry = prompt(reader, "Try again? (y/n)", "y");
                if (!"y".equalsIgnoreCase(retry)) {
                    System.out.println("⚠️ Warning: Client certificate created but NOT imported to server.");
                    break;
                }
                serverPass = prompt(reader, "Enter Server Keystore Password", null);
            }
        }
        
        // Cleanup cer file
        if (cerFile.exists()) cerFile.delete();

        System.out.println("✅ Client Certificate created: " + p12File.getPath());
        System.out.println("✅ Imported into Server Truststore.");
        System.out.println("👉 SEND THIS FILE TO THE CLIENT: " + p12File.getPath());
    }

    private static String prompt(BufferedReader reader, String message, String defaultValue) throws IOException {
        while (true) {
            if (defaultValue != null) {
                System.out.print(message + " [default: " + defaultValue + "]: ");
            } else {
                System.out.print(message + ": ");
            }
            
            String input = reader.readLine();
            if (input != null && !input.trim().isEmpty()) {
                return input.trim();
            }
            
            if (defaultValue != null) {
                return defaultValue;
            }
            System.out.println("❌ Value is required. Please try again.");
        }
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("keytool");
        for (String arg : args) command.add(arg);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code: " + exitCode);
        }
    }

    private static void cleanupBin() {
        File binCerts = new File("bin/certs");
        File binClients = new File("bin/clients");
        deleteDir(binCerts);
        deleteDir(binClients);
    }

    private static void deleteDir(File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (File f : files) deleteDir(f);
                }
            }
            file.delete();
        }
    }
}
