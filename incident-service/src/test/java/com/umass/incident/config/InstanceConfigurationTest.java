package com.umass.incident.config;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceConfigurationTest {
    @Test
    void resolvesTheActualOperatingSystemHostname() throws Exception {
        var hostname = new InstanceConfiguration().instanceHostname();
        assertThat(hostname).isNotBlank().isEqualTo(InetAddress.getLocalHost().getHostName());
    }
}
