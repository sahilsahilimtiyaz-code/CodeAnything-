import { startBridge } from "./server.js";

const port = Number(process.argv[2] ?? process.env.INTEL_PORT ?? 18789);
const host = process.env.INTEL_HOST ?? "127.0.0.1";

await startBridge({ host, port });
