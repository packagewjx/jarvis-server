import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";
import type { BridgeEvent, ChannelSendRequest, JarvisCommandPayload } from "./bridge-types.js";

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

type AgentRunParams = {
  prompt: string;
  sessionId: string;
  thinking?: ThinkingLevel;
  verbose?: VerboseLevel;
};

type OpenClawAgentJson = {
  payloads?: Array<{
    text?: string | null;
    mediaUrl?: string | null;
  }>;
  meta?: {
    stopReason?: string;
  };
};

type ThinkingLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh";
type VerboseLevel = "on" | "off";
type CommandName = "help" | "status" | "new" | "reset" | "think" | "verbose";

type SessionPreferences = {
  thinking?: ThinkingLevel;
  verbose?: VerboseLevel;
};

type CommandRateState = {
  windowStartMs: number;
  count: number;
};

type ResolvedInput =
  | {
      mode: "text";
      prompt: string;
      sessionId: string;
      preferences: SessionPreferences;
    }
  | {
      mode: "command";
      command: {
        name: CommandName;
        args: string[];
        raw: string;
      };
      sessionId: string;
      preferences: SessionPreferences;
    };

export interface AgentRunner {
  run(params: AgentRunParams): Promise<AgentRunResult>;
}

class BridgeUserError extends Error {
  constructor(
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "BridgeUserError";
  }
}

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
  private readonly defaultThinking = parseThinkingLevel(process.env.OPENCLAW_AGENT_THINKING);
  private readonly defaultVerbose = parseVerboseLevel(process.env.OPENCLAW_AGENT_VERBOSE);

  async run(params: AgentRunParams): Promise<AgentRunResult> {
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
    const resolvedThinking = params.thinking ?? this.defaultThinking;
    if (resolvedThinking) {
      args.push("--thinking", resolvedThinking);
    }
    const resolvedVerbose = params.verbose ?? this.defaultVerbose;
    if (resolvedVerbose) {
      args.push("--verbose", resolvedVerbose);
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
  private readonly commandAllowlist = resolveCommandAllowlist();
  private readonly slashAutoDetect = parseBoolean(process.env.OPENCLAW_COMMAND_AUTO_DETECT_SLASH, true);
  private readonly commandRateLimitPerMinute = Number(process.env.OPENCLAW_COMMAND_RATE_LIMIT_PER_MIN ?? "30");
  private readonly commandRateStateByUser = new Map<string, CommandRateState>();
  private readonly sessionGenerationByConversation = new Map<string, number>();
  private readonly sessionPreferencesBySessionId = new Map<string, SessionPreferences>();

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
    let seq = 1;

    try {
      const resolvedInput = this.resolveInput(request);
      if (resolvedInput.mode === "command") {
        this.checkCommandRateLimit(request.userId);
        const commandText = this.handleCommand(request, resolvedInput);
        seq = this.emitText(state, commandText, seq);
        this.emitComplete(state, seq, "command");
        return;
      }

      const result = await this.runner.run({
        prompt: resolvedInput.prompt,
        sessionId: resolvedInput.sessionId,
        thinking: resolvedInput.preferences.thinking,
        verbose: resolvedInput.preferences.verbose
      });

      for (const payload of result.payloads) {
        if (payload.text) {
          seq = this.emitText(state, payload.text, seq);
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

      this.emitComplete(state, seq, result.finishReason);
    } catch (error) {
      const userError = asBridgeUserError(error);
      this.emit(state, {
        type: "message.error",
        requestId: request.requestId,
        conversationId: request.conversationId,
        clientMessageId: request.clientMessageId,
        assistantMessageId: request.assistantMessageId,
        seq,
        code: userError?.code ?? "OPENCLAW_AGENT_FAILED",
        message: userError?.message ?? (error instanceof Error ? error.message : "OpenClaw request failed")
      });
    }
  }

  private emitText(state: RequestState, text: string, seq: number): number {
    for (const delta of splitTextToDeltas(text)) {
      this.emit(state, {
        type: "text.delta",
        requestId: state.request.requestId,
        conversationId: state.request.conversationId,
        clientMessageId: state.request.clientMessageId,
        assistantMessageId: state.request.assistantMessageId,
        seq: seq++,
        cardId: "card_text_main",
        delta
      });
    }
    return seq;
  }

  private emitComplete(state: RequestState, seq: number, finishReason: string): void {
    this.emit(state, {
      type: "message.complete",
      requestId: state.request.requestId,
      conversationId: state.request.conversationId,
      clientMessageId: state.request.clientMessageId,
      assistantMessageId: state.request.assistantMessageId,
      seq,
      finishReason
    });
  }

  private resolveInput(request: ChannelSendRequest): ResolvedInput {
    const sessionId = this.currentSessionId(request.conversationId);
    const preferences = this.sessionPreferencesBySessionId.get(sessionId) ?? {};

    if (request.payload.input_mode === "command") {
      const command = normalizeCommand(
        request.payload.command,
        this.extractSlashCommandFromCards(request),
        this.commandAllowlist
      );
      if (!command) {
        throw new BridgeUserError("INVALID_COMMAND", "input_mode=command requires payload.command or slash command text");
      }
      return { mode: "command", command, sessionId, preferences };
    }

    if (this.slashAutoDetect) {
      const slashCommand = normalizeCommand(undefined, this.extractSlashCommandFromCards(request), this.commandAllowlist);
      if (slashCommand) {
        return { mode: "command", command: slashCommand, sessionId, preferences };
      }
    }

    const prompt = request.payload.cards
      .filter((card) => card.cardType === "text")
      .map((card) => card.text ?? "")
      .join(" ")
      .trim();
    return {
      mode: "text",
      prompt: prompt || "Please respond with a short greeting.",
      sessionId,
      preferences
    };
  }

  private extractSlashCommandFromCards(request: ChannelSendRequest): string | null {
    const textCard = request.payload.cards.find((card) => card.cardType === "text" && (card.text ?? "").trim().length > 0);
    if (!textCard?.text) {
      return null;
    }
    const raw = textCard.text.trim();
    if (!raw.startsWith("/")) {
      return null;
    }
    return raw;
  }

  private handleCommand(
    request: ChannelSendRequest,
    resolved: Extract<ResolvedInput, { mode: "command" }>
  ): string {
    const { name, args, raw } = resolved.command;
    this.auditCommand("start", request, resolved.sessionId, name, raw);
    let responseText = "";
    switch (name) {
      case "help":
        responseText = this.commandHelpText();
        break;
      case "status":
        responseText = this.commandStatusText(resolved.sessionId, resolved.preferences);
        break;
      case "new":
      case "reset":
        responseText = this.commandResetText(request.conversationId);
        break;
      case "think":
        responseText = this.commandThinkText(resolved.sessionId, args);
        break;
      case "verbose":
        responseText = this.commandVerboseText(resolved.sessionId, args);
        break;
      default:
        throw new BridgeUserError("UNSUPPORTED_COMMAND", `unsupported command: ${name}`);
    }
    this.auditCommand("success", request, resolved.sessionId, name, raw);
    return responseText;
  }

  private commandHelpText(): string {
    return [
      "Supported commands:",
      "/status",
      "/new or /reset",
      "/think <off|minimal|low|medium|high|xhigh>",
      "/verbose <on|off>"
    ].join("\n");
  }

  private commandStatusText(sessionId: string, preferences: SessionPreferences): string {
    return [
      "OpenClaw command status:",
      `session_id: ${sessionId}`,
      `thinking: ${preferences.thinking ?? "default"}`,
      `verbose: ${preferences.verbose ?? "default"}`
    ].join("\n");
  }

  private commandResetText(conversationId: string): string {
    const nextSessionId = this.bumpSessionGeneration(conversationId);
    return `Session reset successfully. New session_id: ${nextSessionId}`;
  }

  private commandThinkText(sessionId: string, args: string[]): string {
    const level = parseThinkingLevel(args[0]);
    if (!level) {
      throw new BridgeUserError(
        "INVALID_COMMAND_ARGUMENT",
        "Usage: /think <off|minimal|low|medium|high|xhigh>"
      );
    }
    this.setSessionPreferences(sessionId, { thinking: level });
    return `Thinking level set to ${level} for session ${sessionId}`;
  }

  private commandVerboseText(sessionId: string, args: string[]): string {
    const verbose = parseVerboseLevel(args[0]);
    if (!verbose) {
      throw new BridgeUserError("INVALID_COMMAND_ARGUMENT", "Usage: /verbose <on|off>");
    }
    this.setSessionPreferences(sessionId, { verbose });
    return `Verbose mode set to ${verbose} for session ${sessionId}`;
  }

  private setSessionPreferences(sessionId: string, patch: SessionPreferences): void {
    const prev = this.sessionPreferencesBySessionId.get(sessionId) ?? {};
    this.sessionPreferencesBySessionId.set(sessionId, { ...prev, ...patch });
  }

  private currentSessionId(conversationId: string): string {
    const generation = this.sessionGenerationByConversation.get(conversationId) ?? 0;
    return generation <= 0 ? conversationId : `${conversationId}#${generation}`;
  }

  private bumpSessionGeneration(conversationId: string): string {
    const current = this.sessionGenerationByConversation.get(conversationId) ?? 0;
    const next = current + 1;
    this.sessionGenerationByConversation.set(conversationId, next);
    return `${conversationId}#${next}`;
  }

  private checkCommandRateLimit(userId: string): void {
    const limit = Math.max(1, this.commandRateLimitPerMinute);
    const windowMs = 60_000;
    const now = Date.now();
    const current = this.commandRateStateByUser.get(userId);
    if (!current || now - current.windowStartMs >= windowMs) {
      this.commandRateStateByUser.set(userId, { windowStartMs: now, count: 1 });
      return;
    }
    if (current.count >= limit) {
      throw new BridgeUserError("COMMAND_RATE_LIMITED", "Too many command requests, please retry later");
    }
    current.count += 1;
    this.commandRateStateByUser.set(userId, current);
  }

  private auditCommand(
    stage: "start" | "success",
    request: ChannelSendRequest,
    sessionId: string,
    commandName: string,
    raw: string
  ): void {
    console.info(
      JSON.stringify({
        scope: "jarvis-openclaw-command",
        stage,
        command: commandName,
        userId: request.userId,
        conversationId: request.conversationId,
        sessionId,
        requestId: request.requestId,
        raw
      })
    );
  }
}

function normalizeCommand(
  commandPayload: JarvisCommandPayload | undefined,
  rawSlashText: string | null,
  allowlist: Set<CommandName>
): { name: CommandName; args: string[]; raw: string } | null {
  if (commandPayload) {
    const name = normalizeCommandName(commandPayload.name);
    const args = normalizeCommandArgs(commandPayload.args ?? []);
    if (!allowlist.has(name)) {
      throw new BridgeUserError("UNSUPPORTED_COMMAND", `unsupported command: ${name}`);
    }
    return { name, args, raw: `/${name}${args.length ? ` ${args.join(" ")}` : ""}` };
  }

  if (!rawSlashText) {
    return null;
  }
  const tokens = rawSlashText.trim().split(/\s+/).filter((token) => token.length > 0);
  if (tokens.length === 0 || !tokens[0].startsWith("/")) {
    return null;
  }
  const name = normalizeCommandName(tokens[0].slice(1));
  const args = normalizeCommandArgs(tokens.slice(1));
  if (!allowlist.has(name)) {
    throw new BridgeUserError("UNSUPPORTED_COMMAND", `unsupported command: ${name}`);
  }
  return { name, args, raw: rawSlashText };
}

function normalizeCommandName(rawName: string): CommandName {
  const name = rawName.trim().toLowerCase();
  if (!/^[a-z][a-z0-9_-]{0,31}$/.test(name)) {
    throw new BridgeUserError("INVALID_COMMAND", "invalid command name");
  }
  if (name === "help" || name === "status" || name === "new" || name === "reset" || name === "think" || name === "verbose") {
    return name;
  }
  throw new BridgeUserError("UNSUPPORTED_COMMAND", `unsupported command: ${name}`);
}

function normalizeCommandArgs(rawArgs: string[]): string[] {
  if (rawArgs.length > 8) {
    throw new BridgeUserError("INVALID_COMMAND_ARGUMENT", "too many command arguments");
  }
  return rawArgs.map((arg) => {
    const value = arg.trim();
    if (value.length === 0 || value.length > 64) {
      throw new BridgeUserError("INVALID_COMMAND_ARGUMENT", "invalid command argument length");
    }
    if (!/^[-a-zA-Z0-9_.:@/+=]+$/.test(value)) {
      throw new BridgeUserError("INVALID_COMMAND_ARGUMENT", `unsupported characters in argument: ${arg}`);
    }
    return value;
  });
}

function resolveCommandAllowlist(): Set<CommandName> {
  const raw = process.env.OPENCLAW_COMMAND_ALLOWLIST ?? "help,status,new,reset,think,verbose";
  const allowlist = new Set<CommandName>();
  for (const token of raw.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean)) {
    const normalized = normalizeCommandName(token);
    allowlist.add(normalized);
  }
  if (allowlist.size === 0) {
    throw new Error("OPENCLAW_COMMAND_ALLOWLIST must not be empty");
  }
  return allowlist;
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

function parseThinkingLevel(value: string | undefined): ThinkingLevel | undefined {
  if (!value) {
    return undefined;
  }
  const normalized = value.trim().toLowerCase();
  if (
    normalized === "off" ||
    normalized === "minimal" ||
    normalized === "low" ||
    normalized === "medium" ||
    normalized === "high" ||
    normalized === "xhigh"
  ) {
    return normalized;
  }
  return undefined;
}

function parseVerboseLevel(value: string | undefined): VerboseLevel | undefined {
  if (!value) {
    return undefined;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === "on" || normalized === "off") {
    return normalized;
  }
  return undefined;
}

function asBridgeUserError(error: unknown): BridgeUserError | null {
  if (error instanceof BridgeUserError) {
    return error;
  }
  return null;
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
