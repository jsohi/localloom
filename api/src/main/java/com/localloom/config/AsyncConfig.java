package com.localloom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Enables Spring {@code @Async} processing and configures a virtual-thread
 * executor as the default task executor.
 *
 * <p>Virtual threads (Project Loom, available since Java 21) are ideal for the
 * import pipeline because each pipeline run blocks on I/O (HTTP downloads,
 * ffmpeg/yt-dlp subprocesses, sidecar HTTP calls) while consuming negligible
 * OS thread resources.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Creates a virtual-thread-per-task executor exposed as the default
     * {@code @Async} executor.
     *
     * <p>The thread factory assigns a descriptive name prefix so virtual threads
     * appear in stack traces as {@code localloom-async-N}.
     */
    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        CustomizableThreadFactory factory = new CustomizableThreadFactory("localloom-async-");
        factory.setVirtual(true);
        Executor executor = Executors.newThreadPerTaskExecutor(factory);
        log.info("Configured virtual-thread async executor: localloom-async-*");
        return executor;
    }

    /**
     * Logs unhandled exceptions thrown from {@code @Async} methods.
     * The exception is not propagated because the caller does not hold a
     * reference to the future when using void-return async methods.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Uncaught exception in @Async method {}: {}",
                        method.getName(), throwable.getMessage(), throwable);
    }
}
