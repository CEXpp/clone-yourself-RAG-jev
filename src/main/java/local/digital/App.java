package local.digital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) throws Exception {
        // Import is deliberately a separate offline operation: no web listener or remote API calls.
        if (OfflineImport.requested(args)) { OfflineImport.run(args); return; }
        if (EvalRunner.requested(args)) {
            var app = new SpringApplication(App.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(args)) {
                var runner = context.getBean(EvalRunner.class);
                runner.runEval(args);
            }
            return;
        }
        SpringApplication.run(App.class, args);
    }
}
