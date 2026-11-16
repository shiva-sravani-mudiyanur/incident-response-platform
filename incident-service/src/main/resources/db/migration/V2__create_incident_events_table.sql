CREATE TABLE incident_events (
    event_id UUID PRIMARY KEY,
    service_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    message VARCHAR(4000) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_id VARCHAR(128),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
