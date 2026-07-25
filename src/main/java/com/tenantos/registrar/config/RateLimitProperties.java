package com.tenantos.registrar.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

  private Map<String, RateLimiterConfig> config = new HashMap<>();

  public Map<String, RateLimiterConfig> getConfig() {
    return config;
  }

  public void setConfig(Map<String, RateLimiterConfig> config) {
    this.config = config;
  }

  public record RateLimiterConfig(String path, int maxRequests, int windowSeconds, boolean enabled) {}
}
