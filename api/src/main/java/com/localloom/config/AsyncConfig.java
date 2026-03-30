package com.localloom.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

  private static final Logger log = LogManager.getLogger(AsyncConfig.class);

  @Bean(name = "taskExecutor")
  @Override
  public Executor getAsyncExecutor() {
    final var factory = Thread.ofVirtual().name("localloom-async-", 0).factory();
    final var executor = Executors.newThreadPerTaskExecutor(factory);
    log.info("Configured virtual-thread async executor: localloom-async-*");
    return executor;
  }

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (throwable, method, params) ->
        log.error(
            "Uncaught exception in @Async method {}: {}",
            method.getName(),
            throwable.getMessage(),
            throwable);
  }
}
