import { defineSetupPluginEntry } from "openclaw/plugin-sdk/core";
import { jarvisOpenClawPlugin } from "./src/channel.js";

export default defineSetupPluginEntry(jarvisOpenClawPlugin);
