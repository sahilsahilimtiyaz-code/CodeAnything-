import type { Skill } from "../types.js";

/** Lightweight web fetch via global fetch. No browser automation on-device. */
export function webBrowsingSkill(): Skill {
  return {
    name: "web-browsing",
    description: "Fetch a URL and return text (truncated).",
    tools: [
      {
        name: "web_fetch",
        description: "GET a URL and return its text content (max ~8000 chars).",
        parameters: {
          type: "object",
          properties: { url: { type: "string" }, maxChars: { type: "number" } },
          required: ["url"],
        },
        riskLevel: "low",
        async execute(args, ctx) {
          const url = String(args.url);
          if (!/^https?:\/\//.test(url)) return "Error: only http(s) URLs are allowed.";
          const max = Math.min(Number((args as any).maxChars ?? 8000), 32_000);
          const res = await fetch(url, { signal: ctx.signal, headers: { "User-Agent": "CodingAgentMobile/0.1" } });
          if (!res.ok) return `Error: HTTP ${res.status} for ${url}`;
          const text = await res.text();
          return text.slice(0, max);
        },
      },
    ],
  };
}
