export type JarvisCard = {
  id: string;
  cardType: "text" | "image" | "audio";
  text?: string;
  imageUrl?: string;
  audioUrl?: string;
  audioMime?: string;
  durationMs?: number;
  extra?: unknown;
};

export type JarvisMessageSendPayload = {
  role: string;
  cards: JarvisCard[];
  created_at: number;
};

export type ChannelSendRequest = {
  requestId: string;
  conversationId: string;
  clientMessageId: string;
  assistantMessageId: string;
  traceId: string;
  userId: string;
  payload: JarvisMessageSendPayload;
};

export type ChannelSendAccepted = {
  requestId: string;
};

export type BridgeEvent =
  | {
      type: "text.delta";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      cardId: string;
      delta: string;
    }
  | {
      type: "card.image";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      cardId: string;
      url: string;
      caption?: string;
    }
  | {
      type: "card.audio";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      cardId: string;
      mime: string;
      durationMs: number;
    }
  | {
      type: "audio.chunk";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      cardId: string;
      mime: string;
      durationMs: number;
      base64: string;
    }
  | {
      type: "message.complete";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      finishReason: string;
    }
  | {
      type: "message.error";
      requestId: string;
      conversationId: string;
      clientMessageId: string;
      assistantMessageId: string;
      seq: number;
      code: string;
      message: string;
    };
