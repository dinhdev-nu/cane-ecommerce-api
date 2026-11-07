package vn.cane.bootstrap;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduling only in worker mode; jobs are added with their business work packages. */
@Configuration(proxyBeanMethods = false)
@Profile("worker & !api")
@EnableScheduling
public class WorkerConfiguration {}
