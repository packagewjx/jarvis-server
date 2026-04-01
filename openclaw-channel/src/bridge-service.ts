import { EventEmitter, once } from "node:events";
import type { BridgeEvent, ChannelSendRequest } from "./bridge-types.js";

type RequestState = {
  request: ChannelSendRequest;
  history: BridgeEvent[];
  emitter: EventEmitter;
  completed: boolean;
};

export class BridgeService {
  private readonly requests = new Map<string, RequestState>();

  async submit(request: ChannelSendRequest): Promise<string> {
    const state: RequestState = {
      request,
      history: [],
      emitter: new EventEmitter(),
      completed: false
    };
    this.requests.set(request.requestId, state);
    void this.runDemoPipeline(state);
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

    for (const item of state.history) {
      yield item;
    }

    while (!state.completed) {
      const [event] = (await once(state.emitter, "event")) as [BridgeEvent];
      yield event;
      if (event.type === "message.complete" || event.type === "message.error") {
        state.completed = true;
      }
    }
  }

  private emit(state: RequestState, event: BridgeEvent): void {
    state.history.push(event);
    state.emitter.emit("event", event);
  }

  private async runDemoPipeline(state: RequestState): Promise<void> {
    const { request } = state;
    const sourceText = request.payload.cards
      .filter((card) => card.cardType === "text")
      .map((card) => card.text ?? "")
      .join(" ")
      .trim();

    const response = sourceText || "Hello from the OpenClaw bridge demo";
    const words = response.split(/\s+/).filter(Boolean);
    let seq = 1;

    for (const word of words) {
      await delay(80);
      this.emit(state, {
        type: "text.delta",
        requestId: request.requestId,
        conversationId: request.conversationId,
        clientMessageId: request.clientMessageId,
        assistantMessageId: request.assistantMessageId,
        seq: seq++,
        cardId: "card_text_main",
        delta: `${word} `
      });
    }

    this.emit(state, {
      type: "card.image",
      requestId: request.requestId,
      conversationId: request.conversationId,
      clientMessageId: request.clientMessageId,
      assistantMessageId: request.assistantMessageId,
      seq: seq++,
      cardId: "card_image_preview",
      url: "https://example.com/openclaw-demo.png",
      caption: "Demo image card from the channel plugin"
    });

    this.emit(state, {
      type: "card.audio",
      requestId: request.requestId,
      conversationId: request.conversationId,
      clientMessageId: request.clientMessageId,
      assistantMessageId: request.assistantMessageId,
      seq: seq++,
      cardId: "card_audio_out_1",
      mime: "audio/mpeg",
      durationMs: 320
    });

    for (let index = 0; index < 3; index += 1) {
      await delay(60);
      this.emit(state, {
        type: "audio.chunk",
        requestId: request.requestId,
        conversationId: request.conversationId,
        clientMessageId: request.clientMessageId,
        assistantMessageId: request.assistantMessageId,
        seq: seq++,
        cardId: "card_audio_out_1",
        mime: "audio/mpeg",
        durationMs: 320,
        base64: Buffer.from(`demo-audio-chunk-${index}`).toString("base64")
      });
    }

    await delay(30);
    this.emit(state, {
      type: "message.complete",
      requestId: request.requestId,
      conversationId: request.conversationId,
      clientMessageId: request.clientMessageId,
      assistantMessageId: request.assistantMessageId,
      seq,
      finishReason: "stop"
    });
    state.completed = true;
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
