package com.umass.incident.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorrelationProperties.class)
public class CorrelationConfiguration {
}
