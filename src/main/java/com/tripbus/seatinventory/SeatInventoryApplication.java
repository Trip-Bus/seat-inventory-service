package com.tripbus.seatinventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Sin UserDetailsService en memoria: la autenticación es 100% vía JWT emitido por identity.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class SeatInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatInventoryApplication.class, args);
    }

}
