package com.tenantos.registrar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables the async + scheduling machinery behind background tenant workspace provisioning.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

  /**
   * Deliberately small and bounded. Provisioning is I/O against the EKS control plane, and a burst
   * of registrations shouldn't turn into a burst of concurrent API calls against it. CallerRuns
   * rejection means an overflow slows the submitting thread down instead of dropping the kick -
   * losing one is harmless anyway (the job row is durable and the poller retries), but backpressure
   * beats silent discard.
   */
  @Bean
  public ThreadPoolTaskExecutor workspaceProvisioningExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("workspace-prov-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }
}
