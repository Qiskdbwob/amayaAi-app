import { EventEmitter } from 'node:events';
import type { AgentProvider } from './agent-provider';
import type {
  AgentEventPayload,
  AgentRuntimeInfo
} from '../protocol/bridge-agent';
import { logger } from '../shared/logger';

const SCOPE = 'agent.router';

/**
 * Owns the catalog of [AgentProvider] instances and funnels their events into a
 * single EventEmitter that the WebSocket server subscribes to.
 */
export class AgentRouter extends EventEmitter {
  private readonly providers = new Map<string, AgentProvider>();

  register(provider: AgentProvider): void {
    if (this.providers.has(provider.runtimeId)) {
      logger.warn(SCOPE, `provider ${provider.runtimeId} already registered — replacing`);
      void this.providers.get(provider.runtimeId)?.dispose();
    }
    this.providers.set(provider.runtimeId, provider);
    provider.on('agent_event', (event: AgentEventPayload) => {
      this.emit('agent_event', event);
    });
    provider.on('status_changed', (info: AgentRuntimeInfo) => {
      this.emit('status_changed', info);
    });
  }

  get(runtimeId: string): AgentProvider | null {
    return this.providers.get(runtimeId) ?? null;
  }

  list(): AgentProvider[] {
    return Array.from(this.providers.values());
  }

  async dispose(): Promise<void> {
    const all = Array.from(this.providers.values());
    this.providers.clear();
    await Promise.allSettled(all.map((p) => p.dispose()));
  }
}
