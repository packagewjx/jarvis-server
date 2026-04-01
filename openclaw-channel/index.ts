import { defineChannelPluginEntry } from "openclaw/plugin-sdk/core";
import { jarvisOpenClawPlugin } from "./src/channel.js";

export default defineChannelPluginEntry({
  id: "jarvis-openclaw",
  name: "Jarvis OpenClaw Bridge",
  description: "OpenClaw channel plugin for the Jarvis Kotlin bridge.",
  plugin: jarvisOpenClawPlugin,
  registerCliMetadata(api) {
    api.registerCli(
      ({ program }) => {
        program
          .command("jarvis-openclaw")
          .description("Manage the Jarvis OpenClaw bridge channel");
      },
      {
        descriptors: [
          {
            name: "jarvis-openclaw",
            description: "Manage the Jarvis OpenClaw bridge channel",
            hasSubcommands: false
          }
        ]
      }
    );
  }
});
