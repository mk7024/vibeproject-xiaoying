package com.muse.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class TaskScheduler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);
    private final ThreadPoolExecutor executor;
    private final TaskRunner runner;
    private final TaskTransitions transitions;

    public TaskScheduler(@Qualifier("taskExecutor") ThreadPoolExecutor executor, TaskRunner runner,
                         TaskTransitions transitions) {
        this.executor = executor;
        this.runner = runner;
        this.transitions = transitions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void created(TaskService.TaskCreated event) {
        try {
            executor.execute(() -> runner.run(event.id()));
        } catch (RejectedExecutionException exception) {
            // AFTER_COMMIT callbacks must use a fresh transaction for any follow-up writes.
            transitions.fail(event.id(), "任务队列已满或服务正在停止，请稍后重新创建任务");
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int recovered = transitions.recoverInterrupted();
        if (recovered > 0) log.info("Marked {} interrupted tasks as failed after restart", recovered);
    }

    @Configuration
    public static class ExecutorConfiguration {
        @Bean(name = "taskExecutor", destroyMethod = "shutdownNow")
        public ThreadPoolExecutor taskExecutor(AppProperties properties) {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(properties.taskQueueCapacity()), runnable -> {
                        Thread thread = new Thread(runnable, "muse-task-worker");
                        thread.setDaemon(false);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }
    }
}
