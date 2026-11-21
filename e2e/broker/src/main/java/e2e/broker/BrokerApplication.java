package e2e.broker;

import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class BrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }

    @Bean
    ApplicationRunner startEmbeddedArtemis(@Value("${broker.port:61616}") int port) {
        return args -> {
            Configuration config = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .addAcceptorConfiguration("netty", "tcp://127.0.0.1:" + port);

            EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(config);
            broker.start();
            System.out.println("ARTEMIS_BROKER_STARTED:" + port);
            System.out.flush();

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    broker.stop();
                } catch (Exception ignored) {
                } finally {
                    shutdown.countDown();
                }
            }));
            shutdown.await();
        };
    }
}
