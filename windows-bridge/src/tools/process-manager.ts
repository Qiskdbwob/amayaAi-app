import { spawn, type ChildProcess } from 'node:child_process';
import { newId } from '../shared/ids';
import { nowMs } from '../shared/time';

export interface TrackedProcess {
  id: string;
  command: string;
  cwd: string;
  startedAt: number;
  process: ChildProcess;
}

/**
 * Registry of running shell processes started by the bridge. Only processes
 * tracked here can be cancelled via `shell.cancel`.
 */
export class ProcessManager {
  private readonly running = new Map<string, TrackedProcess>();

  /** Spawn a command and track it. Returns the process id. */
  spawn(
    command: string,
    cwd: string,
    timeoutMs: number,
    maxOutputBytes: number
  ): Promise<{
    processId: string;
    exitCode: number | null;
    stdout: string;
    stderr: string;
    durationMs: number;
    timedOut: boolean;
    truncated: boolean;
  }> {
    const id = `proc_${newId().slice(0, 8)}`;
    const startedAt = nowMs();

    return new Promise((resolve) => {
      const child = spawn('cmd.exe', ['/d', '/s', '/c', command], {
        cwd,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe']
      });

      const tracked: TrackedProcess = { id, command, cwd, startedAt, process: child };
      this.running.set(id, tracked);

      let stdout = '';
      let stderr = '';
      let truncated = false;
      let timedOut = false;

      child.stdout?.setEncoding('utf-8');
      child.stderr?.setEncoding('utf-8');
      child.stdout?.on('data', (chunk: string) => {
        if (stdout.length + chunk.length > maxOutputBytes) {
          stdout += chunk.slice(0, maxOutputBytes - stdout.length);
          truncated = true;
        } else {
          stdout += chunk;
        }
      });
      child.stderr?.on('data', (chunk: string) => {
        if (stderr.length + chunk.length > maxOutputBytes) {
          stderr += chunk.slice(0, maxOutputBytes - stderr.length);
          truncated = true;
        } else {
          stderr += chunk;
        }
      });

      const timer = setTimeout(() => {
        timedOut = true;
        try { child.kill(); } catch { /* ignore */ }
      }, timeoutMs);

      child.on('exit', (code) => {
        clearTimeout(timer);
        this.running.delete(id);
        resolve({
          processId: id,
          exitCode: code,
          stdout,
          stderr,
          durationMs: nowMs() - startedAt,
          timedOut,
          truncated
        });
      });

      child.on('error', (err) => {
        clearTimeout(timer);
        this.running.delete(id);
        resolve({
          processId: id,
          exitCode: null,
          stdout,
          stderr: stderr + `\n[spawn error] ${err.message}`,
          durationMs: nowMs() - startedAt,
          timedOut: false,
          truncated
        });
      });
    });
  }

  cancel(processId: string): boolean {
    const tracked = this.running.get(processId);
    if (!tracked) return false;
    try { tracked.process.kill(); } catch { /* ignore */ }
    this.running.delete(processId);
    return true;
  }

  activeCount(): number {
    return this.running.size;
  }

  activeIds(): string[] {
    return Array.from(this.running.keys());
  }
}
