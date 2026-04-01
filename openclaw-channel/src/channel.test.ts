import { describe, expect, it } from "vitest";
import { BridgeService } from "./bridge-service.js";

describe("BridgeService", () => {
  it("streams a completed demo response", async () => {
    const service = new BridgeService();
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
  });
});
