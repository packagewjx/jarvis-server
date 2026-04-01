import {
  createChannelPluginBase,
  createChatChannelPlugin,
  type OpenClawConfig
} from "openclaw/plugin-sdk/core";
import {
  createScopedDmSecurityResolver,
  createTopLevelChannelConfigAdapter
} from "openclaw/plugin-sdk/channel-config-helpers";

export type ResolvedAccount = {
  accountId: string | null;
  token: string;
  allowFrom: string[];
  dmPolicy?: string;
};

function resolveAccount(cfg: OpenClawConfig, accountId?: string | null): ResolvedAccount {
  const section = (cfg.channels as Record<string, unknown>)?.["jarvis-openclaw"] as
    | Record<string, unknown>
    | undefined;
  const token = section?.token;
  if (typeof token !== "string" || !token) {
    throw new Error("jarvis-openclaw: token is required");
  }

  return {
    accountId: accountId ?? null,
    token,
    allowFrom: Array.isArray(section?.allowFrom)
      ? section.allowFrom.map((entry) => String(entry))
      : [],
    dmPolicy: typeof section?.dmSecurity === "string" ? section.dmSecurity : undefined
  };
}

const config = createTopLevelChannelConfigAdapter<ResolvedAccount>({
  sectionKey: "jarvis-openclaw",
  resolveAccount: (cfg) => resolveAccount(cfg),
  inspectAccount: (cfg) => {
    const section = (cfg.channels as Record<string, unknown>)?.["jarvis-openclaw"] as
      | Record<string, unknown>
      | undefined;
    return {
      enabled: Boolean(section?.token),
      configured: Boolean(section?.token),
      tokenStatus: typeof section?.token === "string" ? "available" : "missing"
    };
  },
  clearBaseFields: ["token", "allowFrom", "dmSecurity"],
  resolveAllowFrom: (account) => account.allowFrom,
  formatAllowFrom: (allowFrom) => allowFrom.map((entry) => String(entry))
});

export const jarvisOpenClawPlugin = createChatChannelPlugin<ResolvedAccount>({
  base: createChannelPluginBase({
    id: "jarvis-openclaw",
    meta: {
      label: "Jarvis OpenClaw Bridge",
      selectionLabel: "Jarvis OpenClaw Bridge",
      detailLabel: "Jarvis OpenClaw Bridge",
      docsPath: "/plugins/sdk-channel-plugins",
      docsLabel: "OpenClaw Channel Plugins",
      blurb: "Connect OpenClaw to the Jarvis Kotlin bridge.",
      aliases: ["jarvis-openclaw"]
    },
    capabilities: {
      chatTypes: ["direct", "group", "thread"],
      reply: true,
      media: true,
      threads: true
    },
    config,
    setup: {
      resolveAccountId: ({ accountId }) => accountId ?? "default",
      applyAccountConfig: ({ cfg, input }) => {
        const channels = (cfg.channels ?? {}) as Record<string, unknown>;
        return {
          ...cfg,
          channels: {
            ...channels,
            "jarvis-openclaw": {
              ...(channels["jarvis-openclaw"] as Record<string, unknown> | undefined),
              token: input.token ?? process.env.OPENCLAW_CHANNEL_TOKEN ?? "",
              allowFrom: input.dmAllowlist ?? [],
              dmSecurity: "allowlist"
            }
          }
        };
      },
      validateInput: ({ input }) =>
        input.token || process.env.OPENCLAW_CHANNEL_TOKEN
          ? null
          : "A channel token is required."
    }
  }) as Parameters<typeof createChatChannelPlugin<ResolvedAccount>>[0]["base"],
  security: {
    resolveDmPolicy: createScopedDmSecurityResolver({
      channelKey: "jarvis-openclaw",
      resolvePolicy: (account) => account.dmPolicy,
      resolveAllowFrom: (account) => account.allowFrom,
      defaultPolicy: "allowlist"
    })
  },
  pairing: {
    text: {
      idLabel: "Jarvis user id",
      message: "Use this code to pair Jarvis with your OpenClaw account:",
      notify: async ({ id, message }) => {
        console.info(`Pairing request for ${id}: ${message}`);
      }
    }
  },
  threading: {
    topLevelReplyToMode: "reply"
  },
  outbound: {
    base: {
      deliveryMode: "direct"
    },
    attachedResults: {
      channel: "jarvis-openclaw",
      sendText: async (params) => {
        return {
          messageId: `${params.to}-${Date.now()}`
        };
      },
      sendMedia: async (params) => {
        return {
          messageId: `${params.to}-${Date.now()}`
        };
      }
    }
  }
});
