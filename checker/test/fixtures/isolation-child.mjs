import { writeFileSync } from "node:fs";

process.once("message", (request) => {
  if (request.content.startsWith("cpu")) {
    const marker = request.content.slice("cpu:".length);
    if (marker) writeFileSync(marker, String(process.pid));
    while (true) Math.sqrt(2);
  }
  if (request.content === "crash") process.abort();
  process.send({ response: { findings: [], engine: [] } });
});
