// Thin console logger. Never log raw payloads or tokens — callers should pass
// pre-redacted summaries (tool/id/seq only) when logging envelope activity.

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

function ts(): string {
  return new Date().toISOString();
}

function emit(level: LogLevel, scope: string, msg: string, extra?: unknown): void {
  const line = `[${ts()}] [${level.toUpperCase()}] [${scope}] ${msg}`;
  if (extra !== undefined) {
    const printable =
      typeof extra === 'string' ? extra : JSON.stringify(extra);
    if (level === 'error') console.error(line, printable);
    else if (level === 'warn') console.warn(line, printable);
    else console.log(line, printable);
    return;
  }
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
}

export const logger = {
  debug: (scope: string, msg: string, extra?: unknown) => emit('debug', scope, msg, extra),
  info: (scope: string, msg: string, extra?: unknown) => emit('info', scope, msg, extra),
  warn: (scope: string, msg: string, extra?: unknown) => emit('warn', scope, msg, extra),
  error: (scope: string, msg: string, extra?: unknown) => emit('error', scope, msg, extra)
};
