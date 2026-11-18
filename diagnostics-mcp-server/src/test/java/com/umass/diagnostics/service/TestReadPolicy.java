package com.umass.diagnostics.service;

import com.umass.diagnostics.config.DiagnosticsResilienceConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

final class TestReadPolicy implements AutoCloseable {
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final IncidentReadPolicy policy;
    TestReadPolicy(String... properties) {
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(DiagnosticsResilienceConfiguration.class, IncidentReadPolicy.class);
        context.refresh();
        policy = context.getBean(IncidentReadPolicy.class);
    }
    @Override public void close() { context.close(); }
}
