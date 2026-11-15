CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    root_service VARCHAR(100) NOT NULL CHECK (btrim(root_service) <> ''),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    error_count INTEGER NOT NULL CHECK (error_count >= 0)
);

CREATE INDEX idx_incidents_status ON incidents (status);
