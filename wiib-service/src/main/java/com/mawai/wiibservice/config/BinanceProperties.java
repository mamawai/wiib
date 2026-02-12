package com.mawai.wiibservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {
    private String wsUrl;
    private String restBaseUrl;
    private List<String> symbols;
    private long fallbackPollInterval;
}
