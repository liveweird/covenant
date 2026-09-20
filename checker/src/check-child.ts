import { check } from "./check.ts";
import type { CheckRequest } from "./protocol.ts";

process.once("message", (request: CheckRequest) => {
  void check(request).then(
    (response) => process.send?.({ response }),
    (error: unknown) => process.send?.({ error: error instanceof Error ? error.message : String(error) }),
  );
});
