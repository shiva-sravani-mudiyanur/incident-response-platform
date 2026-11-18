package com.umass.incident.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Configuration(proxyBeanMethods = false)
public class InstanceConfiguration {
    @Bean
    public String instanceHostname() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostName();
    }
}
