-- V4__approvals.sql
-- User-approval queue for USER_CONFIRM_REQUIRED tool calls (P2-14 #39, ULTRAPLAN section 7.2 item 14 /
-- section 9.1.b DP-9; CE Guardrails). One row per parked tool invocation: the engine's ToolExecutor
-- writes a 'pending' row when a confirm-required tool clears the belt + RBAC gates, blocks the turn's
-- virtual thread on it, and resolves it to 'approved'/'rejected'/'timed_out' on the owner's decision (or a
-- timeout). The row is DURABLE so a pending approval survives a process restart: the dashboard still shows
-- it, and approving an orphaned row re-dispatches the turn from user_message (R1; the exact-resume R2 is a
-- tracked follow-up). The terminal audit of the resolved call still rides tool_invocations (status TEXT,
-- no migration) — this table owns only the approval lifecycle. Forward-only; Hibernate never owns it.

CREATE TABLE tool_approvals (
  id              TEXT PRIMARY KEY,          -- UUID string, supplied by ApprovalService
  session_id      TEXT NOT NULL,             -- channelId:nativeUserId (the turn's session)
  agent_id        TEXT NOT NULL,
  tool_name       TEXT NOT NULL,
  arguments       TEXT NOT NULL,             -- the tool-call arguments JSON (audited verbatim)
  user_message    TEXT,                      -- the original turn prompt, for R1 re-dispatch after restart
  status          TEXT NOT NULL,             -- pending | approved | rejected | timed_out
  decision_reason TEXT,                      -- optional operator reason / 'timeout' / 'non_interactive'
  created_at      INTEGER NOT NULL,
  resolved_at     INTEGER                    -- set when status leaves 'pending', else null
);
CREATE INDEX idx_approvals_status  ON tool_approvals(status, created_at);
CREATE INDEX idx_approvals_session ON tool_approvals(session_id, created_at);
