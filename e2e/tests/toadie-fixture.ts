import { createServer, type Server } from "node:http";
import { randomUUID } from "node:crypto";

// A test-owned HTTP upstream: the browser talks only to Covenant, whose real GraphQL client
// reads this fixture through Docker's host gateway. No live Toadie architecture is modified.
export async function startToadieFixture() {
  const key = `e2e-${randomUUID()}`;
  let fail = false;
  let revisionNumber = 1;
  let renameAfterServicePage: string | null = null;
  let continuousRevisionChanges = false;
  const requests: { blueprint: string | null; page: number; revision: string }[] = [];
  const blueprints = [
    { id: "1", identifier: "api", relations: {} },
    { id: "2", identifier: "service", relations: {
      provides_apis: { target: "api", many: true }, consumes_apis: { target: "api", many: true },
      system: { target: "system", many: false },
    } },
    { id: "3", identifier: "system", relations: {} },
    { id: "4", identifier: "_team", relations: {} },
  ];
  const entities = [
    { id: "1", blueprint: "api", identifier: "orders", title: "Orders API", relations: {} },
    { id: "2", blueprint: "api", identifier: "events", title: "Order events", relations: {} },
    { id: "3", blueprint: "service", identifier: "checkout", title: "Checkout service", team: ["retail"],
      relations: { provides_apis: ["orders", "events"], system: "commerce" } },
    { id: "4", blueprint: "service", identifier: "storefront", title: "Storefront website", team: "retail",
      relations: { consumes_apis: ["orders", "events"], system: "commerce" } },
    { id: "5", blueprint: "system", identifier: "commerce", title: "Commerce system", relations: {} },
    { id: "6", blueprint: "_team", identifier: "retail", title: "Retail team", relations: {} },
  ].map((entity) => ({ team: null, updatedAt: 1, ...entity }));
  const server: Server = createServer(async (req, res) => {
    if (req.url !== "/integration/graphql" || req.method !== "POST" || req.headers.authorization !== `Bearer ${key}`) {
      res.writeHead(401).end();
      return;
    }
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const { variables } = JSON.parse(Buffer.concat(chunks).toString()) as {
      variables: { blueprint?: string; page: number; pageSize: number };
    };
    if (fail) {
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ data: null, errors: [{ message: "Unavailable" }] }));
      return;
    }
    const { blueprint, page, pageSize } = variables;
    const rows = blueprint ? entities.filter((entity) => entity.blueprint === blueprint) : blueprints;
    const items = rows.slice((page - 1) * pageSize, page * pageSize);
    const revision = String(revisionNumber);
    requests.push({ blueprint: blueprint ?? null, page, revision });
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
      data: { [blueprint ? "entities" : "blueprints"]: { items, page, pageSize, total: rows.length, revision } },
    }));
    if (renameAfterServicePage && blueprint === "service" && page === 1) {
      const service = entities.find((entity) => entity.blueprint === "service" && entity.identifier === "storefront");
      if (!service) throw new Error("Fixture storefront service is missing");
      service.title = renameAfterServicePage;
      renameAfterServicePage = null;
      revisionNumber += 1;
    } else if (continuousRevisionChanges) {
      revisionNumber += 1;
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "0.0.0.0", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Fixture did not bind a TCP port");
  return {
    key,
    baseUrl: `http://${process.env.E2E_UPSTREAM_HOST ?? "host.docker.internal"}:${address.port}`,
    browserUrl: `http://localhost:${address.port}`,
    setFailure: () => { fail = true; },
    renameServiceMidScan: (title: string) => {
      revisionNumber += 1;
      renameAfterServicePage = title;
    },
    setContinuousRevisionChanges: () => {
      revisionNumber += 1;
      continuousRevisionChanges = true;
    },
    clearRequests: () => { requests.length = 0; },
    requests: () => [...requests],
    close: () => new Promise<void>((resolve, reject) => {
      server.closeAllConnections();
      server.close((error) => error ? reject(error) : resolve());
    }),
  };
}
