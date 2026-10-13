package com.dsh.orchestration.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度组件开关（MA-05）：仅在 dsh.orchestration.enabled=true 时启用
 * worker 池的 @Scheduled 补偿扫描与 await 超时扫描。默认关闭，不产生后台活动。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskSchedulingConfig {
}
