export type BrowserSubTool =
  | 'browser.new_tab'
  | 'browser.close_tab'
  | 'browser.switch_tab'
  | 'browser.open_url'
  | 'browser.reload'
  | 'browser.go_back'
  | 'browser.go_forward'
  | 'browser.click'
  | 'browser.type_text'
  | 'browser.clear_input'
  | 'browser.scroll'
  | 'browser.get_dom'
  | 'browser.get_visible_text'
  | 'browser.find_element'
  | 'browser.wait_for_element'
  | 'browser.screenshot'
  | 'browser.pause_session'
  | 'browser.resume_session'
  | 'browser.cancel_action'
  | 'browser.get_status'
  | 'browser.analyze_page';

export type BrowserToolStatus = 'success' | 'error' | 'cancelled' | 'paused' | 'timeout';
export type BrowserAgentStatus = 'idle' | 'thinking' | 'browsing' | 'waiting_input' | 'paused' | 'cancelled' | 'error' | 'completed';
export type DomMode = 'interactive_summary' | 'accessibility_tree' | 'visible_text' | 'selector_map' | 'debug_raw_html';

export interface BrowserSessionRef {
  session_id: string;
  browser_id: string;
  active_page_id: string;
}

export interface BrowserSafetyState {
  sensitive_detected: boolean;
  sensitive_type: null | 'username' | 'email_login' | 'password' | 'otp' | 'payment' | 'personal_data' | 'secret' | 'unknown_sensitive';
  requires_user_decision: boolean;
  reason: string | null;
  allowed_next_actions: Array<'fill_manually' | 'allow_once' | 'allow_for_this_site' | 'skip_step' | 'cancel_task' | 'resume_after_user_done'>;
}

export interface BrowserToolError {
  code: 'ELEMENT_NOT_FOUND' | 'TIMEOUT' | 'NETWORK_ERROR' | 'ANDROID_PERMISSION_PROMPT' | 'CANCELLED' | 'BROWSER_ACTION_FAILED';
  message: string;
  recoverable: boolean;
  suggested_action: string;
  details: Record<string, unknown>;
}

export interface BrowserSubToolResponse<T = unknown> {
  id: string;
  parent_call_id: string;
  tool: BrowserSubTool;
  status: BrowserToolStatus;
  timestamp: string;
  duration_ms: number;
  session: BrowserSessionRef;
  request: { params: Record<string, unknown> };
  result: T | null;
  safety: BrowserSafetyState;
  ui: {
    summary: string;
    agent_status: BrowserAgentStatus;
    expandable: boolean;
  };
  error: BrowserToolError | null;
}

export interface BrowserElementSummary {
  element_id: string;
  tag: string;
  role: string;
  type?: string;
  name?: string;
  placeholder?: string;
  text_preview: string;
  selector: string;
  visible: boolean;
  enabled: boolean;
  sensitive: boolean;
  sensitive_type?: BrowserSafetyState['sensitive_type'];
}

export interface BrowserDomResult {
  page: { url: string; title: string };
  dom: {
    mode: DomMode;
    nodes_count: number;
    truncated: boolean;
    interactive_elements: BrowserElementSummary[];
    forms: Array<{
      form_id: string;
      purpose: string;
      fields: string[];
      submit_element_id: string | null;
      sensitive: boolean;
    }>;
    sensitive_fields: number;
    visible_text_preview: string;
  };
}

export interface BrowserParentToolcall {
  id: string;
  tool: 'browser';
  type: 'parent_toolcall';
  status: 'running' | 'paused' | 'cancelled' | 'error' | 'completed';
  summary: string;
  session_id: string;
  browser_id: string;
  active_page_id: string;
  active_url: string;
  started_at: string;
  updated_at: string;
  progress: {
    current_step: number;
    total_steps: number;
    label: string;
  };
  sub_toolcalls: Array<{
    id: string;
    tool: BrowserSubTool;
    status: BrowserToolStatus;
    summary: string;
    duration_ms: number;
    response: BrowserSubToolResponse;
  }>;
  ui: {
    expandable: true;
    show_as_single_chat_tool: true;
    nested_subtools: true;
  };
}
