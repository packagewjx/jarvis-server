import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";
import type { BridgeEvent, ChannelSendRequest } from "./bridge-types.js";

type RequestState = {
  request: ChannelSendRequest;
  history: BridgeEvent[];
  emitter: EventEmitter;
  completed: boolean;
  version: number;
};

type AgentPayload = {
  text: string | null;
  mediaUrl: string | null;
};

type AgentRunResult = {
  payloads: AgentPayload[];
  finishReason: string;
};

export interface AgentRunner {
  run(params: { prompt: string; sessionId: string }): Promise<AgentRunResult>;
}

type OpenClawAgentJson = {
  payloads?: Array<{
    text?: string | null;
    mediaUrl?: string | null;
  }>;
  meta?: {
    stopReason?: string;
  };
};

export class OpenClawAgentRunner implements AgentRunner {
  private readonly command = process.env.OPENCLAW_AGENT_COMMAND ?? "npx";
  private readonly commandArgsPrefix =
    process.env.OPENCLAW_AGENT_COMMAND === undefined
      ? [process.env.OPENCLAW_AGENT_BIN ?? "openclaw"]
      : [];
  private readonly workdir = process.env.OPENCLAW_AGENT_WORKDIR ?? process.cwd();
  private readonly timeoutMs = Number(process.env.OPENCLAW_AGENT_TIMEOUT_MS ?? "180000");
  private readonly localMode = parseBoolean(process.env.OPENCLAW_AGENT_LOCAL, true);
  private readonly agentId = process.env.OPENCLAW_AGENT_ID ?? "main";
  private readonly thinking = process.env.OPENCLAW_AGENT_THINKING;

  async run(params: { prompt: string; sessionId: string }): Promise<AgentRunResult> {
    const args = [
      ...this.commandArgsPrefix,
      "agent",
      "--json",
      "--session-id",
      params.sessionId,
      "--message",
      params.prompt,
      "--agent",
      this.agentId
    ];
    if (this.localMode) {
      args.push("--local");
    }
    if (this.thinking) {
      args.push("--thinking", this.thinking);
    }

    const { code, stdout, stderr, timedOut } = await runProcess({
      command: this.command,
      args,
      cwd: this.workdir,
      timeoutMs: this.timeoutMs
    });

    if (timedOut) {
      throw new Error(`openclaw agent timed out after ${this.timeoutMs}ms`);
    }
    if (code !== 0) {
      const detail = (stderr || stdout || "").trim();
      throw new Error(`openclaw agent failed (exit=${code}): ${detail}`);
    }

    let data: OpenClawAgentJson;
    try {
      data = parseOpenClawJson(stdout, stderr);
    } catch (error) {
      const message = error instanceof Error ? error.message : "invalid JSON";
      const output = stdout.trim() || stderr.trim();
      throw new Error(`openclaw agent returned non-JSON output: ${message}; output=${output.slice(0, 400)}`);
    }

    const payloads = (data.payloads ?? []).map((item) => ({
      text: item.text ?? null,
      mediaUrl: item.mediaUrl ?? null
    }));
    return {
      payloads,
      finishReason: data.meta?.stopReason ?? "stop"
    };
  }
}

export class BridgeService {
  private readonly requests = new Map<string, RequestState>();
  constructor(private readonly runner: AgentRunner = new OpenClawAgentRunner()) {}

  async submit(request: ChannelSendRequest): Promise<string> {
    const state: RequestState = {
      request,
      history: [],
      emitter: new EventEmitter(),
      completed: false,
      version: 0
    };
    this.requests.set(request.requestId, state);
    void this.runOpenClawPipeline(state);
    return request.requestId;
  }

  getState(requestId: string): RequestState | undefined {
    return this.requests.get(requestId);
  }

  async *stream(requestId: string): AsyncGenerator<BridgeEvent> {
    const state = this.requests.get(requestId);
    if (!state) {
      throw new Error(`Unknown request id: ${requestId}`);
    }
    let historyIndex = 0;
    let seenVersion = state.version;

    while (true) {
      while (historyIndex < state.history.length) {
        const event = state.history[historyIndex++];
        yield event;
        if (event.type === "message.complete" || event.type === "message.error") {
          state.completed = true;
        }
      }

      if (state.completed) {
        return;
      }

      await waitForStateChange(state, seenVersion);
      seenVersion = state.version;
    }
  }

  private emit(state: RequestState, event: BridgeEvent): void {
    state.history.push(event);
    state.version += 1;
    state.emitter.emit("event");
  }

  private async runOpenClawPipeline(state: RequestState): Promise<void> {
    const { request } = state;
    const prompt = request.payload.cards
      .filter((card) => card.cardType === "text")
      .map((card) => card.text ?? "")
      .join(" ")
      .trim();
    const effectivePrompt = prompt || "Please respond with a short greeting.";
    let seq = 1;

    try {
      const result = await this.runner.run({
        prompt: effectivePrompt,
        sessionId: request.conversationId
      });

      for (const payload of result.payloads) {
        if (payload.text) {
          for (const delta of splitTextToDeltas(payload.text)) {
            this.emit(state, {
              type: "text.delta",
              requestId: request.requestId,
              conversationId: request.conversationId,
              clientMessageId: request.clientMessageId,
              assistantMessageId: request.assistantMessageId,
              seq: seq++,
              cardId: "card_text_main",
              delta
            });
          }
        }
        if (payload.mediaUrl) {
          this.emit(state, {
            type: "card.image",
            requestId: request.requestId,
            conversationId: request.conversationId,
            clientMessageId: request.clientMessageId,
            assistantMessageId: request.assistantMessageId,
            seq: seq++,
            cardId: `card_image_${seq}`,
            url: payload.mediaUrl
          });
        }
      }

      this.emit(state, {
        type: "message.complete",
        requestId: request.requestId,
        conversationId: request.conversationId,
        clientMessageId: request.clientMessageId,
        assistantMessageId: request.assistantMessageId,
        seq,
        finishReason: result.finishReason
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "OpenClaw request failed";
      this.emit(state, {
        type: "message.error",
        requestId: request.requestId,
        conversationId: request.conversationId,
        clientMessageId: request.clientMessageId,
        assistantMessageId: request.assistantMessageId,
        seq,
        code: "OPENCLAW_AGENT_FAILED",
        message
      });
    }
  }
}

function splitTextToDeltas(text: string): string[] {
  const parts = text.split(/(\s+)/).filter((part) => part.length > 0);
  return parts.length > 0 ? parts : [text];
}

function parseBoolean(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined) {
    return fallback;
  }
  return value.toLowerCase() === "true";
}

async function waitForStateChange(state: RequestState, seenVersion: number): Promise<void> {
  await new Promise<void>((resolve) => {
    const onEvent = () => {
      state.emitter.off("event", onEvent);
      resolve();
    };
    state.emitter.on("event", onEvent);
    if (state.version > seenVersion || state.completed) {
      state.emitter.off("event", onEvent);
      resolve();
    }
  });
}

async function runProcess(params: {
  command: string;
  args: string[];
  cwd: string;
  timeoutMs: number;
}): Promise<{ code: number; stdout: string; stderr: string; timedOut: boolean }> {
  return await new Promise((resolve, reject) => {
    const child = spawn(params.command, params.args, {
      cwd: params.cwd,
      env: process.env
    });

    let stdout = "";
    let stderr = "";
    let settled = false;
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 1000).unref();
    }, params.timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", (error) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve({
        code: code ?? 1,
        stdout: stdout.trim(),
        stderr: stderr.trim(),
        timedOut
      });
    });
  });
}

function parseOpenClawJson(stdout: string, stderr: string): OpenClawAgentJson {
  const stdoutTrimmed = stdout.trim();
  if (stdoutTrimmed.length > 0) {
    return JSON.parse(stdoutTrimmed) as OpenClawAgentJson;
  }

  const stderrTrimmed = stderr.trim();
  if (stderrTrimmed.length === 0) {
    throw new Error("empty stdout/stderr");
  }

  const directStart = stderrTrimmed.indexOf("{");
  if (directStart >= 0) {
    const maybeJson = stderrTrimmed.slice(directStart);
    try {
      return JSON.parse(maybeJson) as OpenClawAgentJson;
    } catch {
      // Fall through to backward scan.
    }
  }

  for (let index = stderrTrimmed.lastIndexOf("{"); index >= 0; index = stderrTrimmed.lastIndexOf("{", index - 1)) {
    const candidate = stderrTrimmed.slice(index);
    try {
      return JSON.parse(candidate) as OpenClawAgentJson;
    } catch {
      // keep scanning
    }
  }

  throw new Error("unable to locate JSON object");
}
