package local.digital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) throws Exception {
        // Import is deliberately a separate offline operation: no web listener or remote API calls.
        if (OfflineImport.requested(args)) { OfflineImport.run(args); return; }
        SpringApplication.run(App.class, args);
    }
}
