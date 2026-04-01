import { createServer } from "node:https";
import type { IncomingMessage, ServerResponse } from "node:http";
import { readFileSync } from "node:fs";
import { URL } from "node:url";
import { BridgeService } from "./bridge-service.js";
import type { BridgeEvent, ChannelSendAccepted, ChannelSendRequest } from "./bridge-types.js";

const host = process.env.OPENCLAW_CHANNEL_HOST ?? "0.0.0.0";
const port = Number(process.env.OPENCLAW_CHANNEL_PORT ?? "9443");
const authToken = requiredEnv("OPENCLAW_CHANNEL_AUTH_TOKEN");
const keyPath = requiredEnv("OPENCLAW_CHANNEL_TLS_KEY_PATH");
const certPath = requiredEnv("OPENCLAW_CHANNEL_TLS_CERT_PATH");
const bridgeService = new BridgeService();

const server = createServer(
  {
    key: readFileSync(keyPath),
    cert: readFileSync(certPath)
  },
  async (req, res) => {
    try {
      if (!isAuthorized(req, authToken)) {
        json(res, 401, { error: "Unauthorized" });
        return;
      }

      const url = new URL(req.url ?? "/", `https://${req.headers.host ?? "localhost"}`);
      if (req.method === "POST" && url.pathname === "/internal/messages/send") {
        const request = (await readJson(req)) as ChannelSendRequest;
        const requestId = await bridgeService.submit(request);
        json(res, 202, { requestId } satisfies ChannelSendAccepted);
        return;
      }

      if (req.method === "GET" && url.pathname.startsWith("/internal/messages/stream/")) {
        const requestId = url.pathname.split("/").pop();
        if (!requestId) {
          json(res, 400, { error: "Missing request id" });
          return;
        }
        await streamEvents(res, bridgeService, requestId);
        return;
      }

      json(res, 404, { error: "Not found" });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown error";
      json(res, 500, { error: message });
    }
  }
);

server.listen(port, host, () => {
  console.info(`Jarvis OpenClaw bridge listening on https://${host}:${port}`);
});

async function streamEvents(
  res: ServerResponse<IncomingMessage>,
  service: BridgeService,
  requestId: string
): Promise<void> {
  const state = service.getState(requestId);
  if (!state) {
    json(res, 404, { error: "Unknown request id" });
    return;
  }

  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive"
  });

  for await (const event of service.stream(requestId)) {
    writeSse(res, event);
  }
  res.end();
}

function writeSse(res: ServerResponse<IncomingMessage>, event: BridgeEvent): void {
  res.write(`data: ${JSON.stringify(event)}\n\n`);
}

function isAuthorized(req: IncomingMessage, token: string): boolean {
  return req.headers.authorization === `Bearer ${token}`;
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function json(res: ServerResponse<IncomingMessage>, statusCode: number, body: unknown): void {
  res.writeHead(statusCode, {
    "Content-Type": "application/json"
  });
  res.end(JSON.stringify(body));
}

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing environment variable: ${name}`);
  }
  return value;
}
