import { describe, expect, it } from "vitest";
import { BridgeService } from "./bridge-service.js";

describe("BridgeService", () => {
  it("streams a completed response from the configured runner", async () => {
    const calls: Array<{ sessionId: string; thinking?: string; verbose?: string }> = [];
    const service = new BridgeService({
      async run(params) {
        calls.push({
          sessionId: params.sessionId,
          thinking: params.thinking,
          verbose: params.verbose
        });
        return {
          payloads: [{ text: "hello from real runner", mediaUrl: null }],
          finishReason: "stop"
        };
      }
    });
    const requestId = "req_test";
    await service.submit({
      requestId,
      conversationId: "conv_001",
      clientMessageId: "local_001",
      assistantMessageId: "msg_srv_001",
      traceId: "trace_001",
      userId: "user_001",
      payload: {
        role: "user",
        created_at: Date.now(),
        cards: [{ id: "card_text_1", cardType: "text", text: "hello world" }]
      }
    });

    const events = [];
    for await (const event of service.stream(requestId)) {
      events.push(event);
      if (event.type === "message.complete") {
        break;
      }
    }

    expect(events.some((event) => event.type === "text.delta")).toBe(true);
    expect(events.at(-1)?.type).toBe("message.complete");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.sessionId).toBe("conv_001");
  });

  it("handles slash commands with status output", async () => {
    const service = new BridgeService({
      async run() {
        throw new Error("runner should not be called for /status");
      }
    });

    const requestId = "req_command_status";
    await service.submit({
      requestId,
      conversationId: "conv_status",
      clientMessageId: "local_status",
      assistantMessageId: "msg_srv_status",
      traceId: "trace_status",
      userId: "user_001",
      payload: {
        role: "user",
        created_at: Date.now(),
        cards: [{ id: "card_text_1", cardType: "text", text: "/status" }]
      }
    });

    const deltas: string[] = [];
    let completed = false;
    for await (const event of service.stream(requestId)) {
      if (event.type === "text.delta") {
        deltas.push(event.delta);
      }
      if (event.type === "message.complete") {
        completed = true;
        break;
      }
    }

    expect(completed).toBe(true);
    expect(deltas.join("")).toContain("OpenClaw command status:");
  });

  it("resets session and applies think level to subsequent text turn", async () => {
    const sessions: string[] = [];
    const thinkings: Array<string | undefined> = [];
    const service = new BridgeService({
      async run(params) {
        sessions.push(params.sessionId);
        thinkings.push(params.thinking);
        return {
          payloads: [{ text: `reply:${params.sessionId}`, mediaUrl: null }],
          finishReason: "stop"
        };
      }
    });

    await service.submit({
      requestId: "req_reset",
      conversationId: "conv_reset",
      clientMessageId: "local_reset",
      assistantMessageId: "msg_srv_reset",
      traceId: "trace_reset",
      userId: "user_001",
      payload: {
        role: "user",
        created_at: Date.now(),
        cards: [{ id: "card_text_1", cardType: "text", text: "/reset" }]
      }
    });
    for await (const event of service.stream("req_reset")) {
      if (event.type === "message.complete") {
        break;
      }
    }

    await service.submit({
      requestId: "req_think",
      conversationId: "conv_reset",
      clientMessageId: "local_think",
      assistantMessageId: "msg_srv_think",
      traceId: "trace_think",
      userId: "user_001",
      payload: {
        role: "user",
        created_at: Date.now(),
        cards: [{ id: "card_text_1", cardType: "text", text: "/think high" }]
      }
    });
    for await (const event of service.stream("req_think")) {
      if (event.type === "message.complete") {
        break;
      }
    }

    await service.submit({
      requestId: "req_real",
      conversationId: "conv_reset",
      clientMessageId: "local_real",
      assistantMessageId: "msg_srv_real",
      traceId: "trace_real",
      userId: "user_001",
      payload: {
        role: "user",
        created_at: Date.now(),
        cards: [{ id: "card_text_1", cardType: "text", text: "hello after reset" }]
      }
    });
    for await (const event of service.stream("req_real")) {
      if (event.type === "message.complete") {
        break;
      }
    }

    expect(sessions).toEqual(["conv_reset#1"]);
    expect(thinkings).toEqual(["high"]);
  });
});
