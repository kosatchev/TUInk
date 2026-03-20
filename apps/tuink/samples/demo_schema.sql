-- Demo: Relational schema with indexes, constraints, and seed-friendly structure.
BEGIN;

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    full_name       TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'member',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE projects (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES users(id),
    name            TEXT NOT NULL,
    description     TEXT,
    status          TEXT NOT NULL DEFAULT 'active',
    due_date        DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tasks (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    assignee_id     BIGINT REFERENCES users(id),
    title           TEXT NOT NULL,
    details         TEXT,
    priority        SMALLINT NOT NULL DEFAULT 2,
    state           TEXT NOT NULL DEFAULT 'todo',
    estimate_hours  NUMERIC(6,2),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE task_events (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    author_id       BIGINT NOT NULL REFERENCES users(id),
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_projects_owner ON projects(owner_id);
CREATE INDEX idx_tasks_project_state ON tasks(project_id, state);
CREATE INDEX idx_tasks_assignee ON tasks(assignee_id);
CREATE INDEX idx_task_events_task ON task_events(task_id, created_at DESC);

INSERT INTO users (email, full_name, password_hash, role)
VALUES
    ('alice@example.com', 'Alice Ng', 'hash_a', 'admin'),
    ('bob@example.com', 'Bob Stone', 'hash_b', 'member'),
    ('carol@example.com', 'Carol Fox', 'hash_c', 'member');

INSERT INTO projects (owner_id, name, description, status, due_date)
VALUES
    (1, 'Website Refresh', 'Landing page and docs redesign', 'active', CURRENT_DATE + INTERVAL '21 days'),
    (2, 'Billing Cleanup', 'Fix failed invoice retries', 'active', CURRENT_DATE + INTERVAL '14 days'),
    (1, 'Ops Automation', 'Scripts for routine maintenance', 'paused', NULL);

INSERT INTO tasks (project_id, assignee_id, title, details, priority, state, estimate_hours)
VALUES
    (1, 2, 'Create hero mockups', 'Prepare 3 variants', 3, 'in_progress', 8.0),
    (1, 3, 'Audit old assets', 'Find files that can be deleted', 2, 'todo', 4.5),
    (2, 2, 'Investigate retry queue', 'Check dead-letter items', 3, 'todo', 6.0),
    (3, 1, 'Draft maintenance playbook', 'Cover backup and restore', 1, 'done', 5.0);

INSERT INTO task_events (task_id, author_id, event_type, payload)
VALUES
    (1, 2, 'comment', '{"text":"First draft is ready"}'),
    (1, 1, 'state_change', '{"from":"todo","to":"in_progress"}'),
    (4, 1, 'state_change', '{"from":"in_progress","to":"done"}');

CREATE VIEW active_tasks AS
SELECT
    t.id,
    p.name AS project_name,
    u.full_name AS assignee,
    t.title,
    t.priority,
    t.state,
    t.created_at
FROM tasks t
JOIN projects p ON p.id = t.project_id
LEFT JOIN users u ON u.id = t.assignee_id
WHERE t.state <> 'done'
ORDER BY t.priority DESC, t.created_at ASC;

COMMIT;

-- Example reports
SELECT project_name, COUNT(*) AS open_count
FROM active_tasks
GROUP BY project_name
ORDER BY open_count DESC, project_name ASC;

SELECT u.full_name, COUNT(*) AS assigned
FROM tasks t
JOIN users u ON u.id = t.assignee_id
GROUP BY u.full_name
ORDER BY assigned DESC, u.full_name ASC;
